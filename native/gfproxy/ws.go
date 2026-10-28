package main

import (
	"bufio"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

const wsGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

var edParamRegex = regexp.MustCompile(`(?i)([?&])ed=\d+(&?)`)

// NormalizeWSPath decodes percent-encoded WebSocket paths (e.g. "%2Fpath%3Fed%3D2560")
// and strips Xray/Cloudflare Worker "?ed=2048" / "?ed=2560" client hints so upstream
// servers never reject the Upgrade request with HTTP 400 / 404.
func NormalizeWSPath(rawPath string) string {
	p := strings.TrimSpace(rawPath)
	if p == "" {
		return "/"
	}
	if strings.HasPrefix(p, "%2F") || strings.HasPrefix(p, "%2f") || strings.Contains(p, "%3F") || strings.Contains(p, "%3f") {
		if unescaped, err := url.QueryUnescape(p); err == nil && unescaped != "" {
			p = unescaped
		}
	}
	// Strip ?ed=N or &ed=N cleanly
	cleaned := edParamRegex.ReplaceAllStringFunc(p, func(m string) string {
		if strings.HasPrefix(m, "?") && strings.HasSuffix(m, "&") {
			return "?"
		}
		if strings.HasPrefix(m, "&") && strings.HasSuffix(m, "&") {
			return "&"
		}
		return ""
	})
	cleaned = strings.TrimRight(cleaned, "?&")
	if cleaned == "" {
		return "/"
	}
	if !strings.HasPrefix(cleaned, "/") {
		cleaned = "/" + cleaned
	}
	return cleaned
}

// wsConn implements net.Conn over an RFC 6455 binary WebSocket connection.
type wsConn struct {
	net.Conn
	br      *bufio.Reader
	readMu  sync.Mutex
	writeMu sync.Mutex
	readBuf []byte
}

func dialWebSocketStream(underConn net.Conn, hostHeader, rawPath string) (net.Conn, error) {
	cleanPath := NormalizeWSPath(rawPath)

	var keyBytes [16]byte
	if _, err := rand.Read(keyBytes[:]); err != nil {
		return nil, err
	}
	wsKey := base64.StdEncoding.EncodeToString(keyBytes[:])

	var reqBuilder strings.Builder
	reqBuilder.WriteString("GET " + cleanPath + " HTTP/1.1\r\n")
	reqBuilder.WriteString("Host: " + hostHeader + "\r\n")
	reqBuilder.WriteString("Upgrade: websocket\r\n")
	reqBuilder.WriteString("Connection: Upgrade\r\n")
	reqBuilder.WriteString("Sec-WebSocket-Key: " + wsKey + "\r\n")
	reqBuilder.WriteString("Sec-WebSocket-Version: 13\r\n")
	reqBuilder.WriteString("User-Agent: Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36\r\n")
	reqBuilder.WriteString("\r\n")

	_ = underConn.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := underConn.Write([]byte(reqBuilder.String())); err != nil {
		return nil, err
	}

	br := bufio.NewReaderSize(underConn, 16384)
	resp, err := http.ReadResponse(br, &http.Request{Method: http.MethodGet})
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		return nil, fmt.Errorf("websocket upgrade failed: HTTP %d", resp.StatusCode)
	}

	sum := sha1.Sum([]byte(wsKey + wsGUID))
	expectedAccept := base64.StdEncoding.EncodeToString(sum[:])
	if got := strings.TrimSpace(resp.Header.Get("Sec-WebSocket-Accept")); got != "" && got != expectedAccept {
		return nil, errors.New("websocket Sec-WebSocket-Accept mismatch")
	}

	_ = underConn.SetDeadline(time.Time{})
	return &wsConn{
		Conn: underConn,
		br:   br,
	}, nil
}

func (w *wsConn) Read(b []byte) (int, error) {
	w.readMu.Lock()
	defer w.readMu.Unlock()

	for len(w.readBuf) == 0 {
		payload, opcode, err := w.readFrame()
		if err != nil {
			return 0, err
		}
		switch opcode {
		case 0x01, 0x02, 0x00: // text, binary, continuation
			if len(payload) > 0 {
				w.readBuf = payload
			}
		case 0x08: // close
			return 0, io.EOF
		case 0x09: // ping -> reply pong
			_ = w.writeControlFrame(0x0A, payload)
		case 0x0A: // pong -> ignore
		}
	}

	n := copy(b, w.readBuf)
	w.readBuf = w.readBuf[n:]
	return n, nil
}

func (w *wsConn) readFrame() ([]byte, byte, error) {
	var hdr [2]byte
	if _, err := io.ReadFull(w.br, hdr[:]); err != nil {
		return nil, 0, err
	}
	opcode := hdr[0] & 0x0F
	masked := (hdr[1] & 0x80) != 0
	length := uint64(hdr[1] & 0x7F)

	switch length {
	case 126:
		var ext [2]byte
		if _, err := io.ReadFull(w.br, ext[:]); err != nil {
			return nil, 0, err
		}
		length = uint64(binary.BigEndian.Uint16(ext[:]))
	case 127:
		var ext [8]byte
		if _, err := io.ReadFull(w.br, ext[:]); err != nil {
			return nil, 0, err
		}
		length = binary.BigEndian.Uint64(ext[:])
	}

	if length > 4*1024*1024 {
		return nil, 0, errors.New("websocket frame too large")
	}

	var maskKey [4]byte
	if masked {
		if _, err := io.ReadFull(w.br, maskKey[:]); err != nil {
			return nil, 0, err
		}
	}

	payload := make([]byte, int(length))
	if length > 0 {
		if _, err := io.ReadFull(w.br, payload); err != nil {
			return nil, 0, err
		}
		if masked {
			for i := range payload {
				payload[i] ^= maskKey[i&3]
			}
		}
	}
	return payload, opcode, nil
}

func (w *wsConn) Write(b []byte) (int, error) {
	if len(b) == 0 {
		return 0, nil
	}
	w.writeMu.Lock()
	defer w.writeMu.Unlock()

	total := 0
	const maxChunk = 32768
	for off := 0; off < len(b); off += maxChunk {
		end := off + maxChunk
		if end > len(b) {
			end = len(b)
		}
		chunk := b[off:end]
		if err := w.writeMaskedFrameLocked(0x02, chunk); err != nil {
			return total, err
		}
		total += len(chunk)
	}
	return total, nil
}

func (w *wsConn) writeControlFrame(opcode byte, payload []byte) error {
	if len(payload) > 125 {
		payload = payload[:125]
	}
	w.writeMu.Lock()
	defer w.writeMu.Unlock()
	return w.writeMaskedFrameLocked(opcode, payload)
}

func (w *wsConn) writeMaskedFrameLocked(opcode byte, payload []byte) error {
	var maskKey [4]byte
	_, _ = rand.Read(maskKey[:])

	plen := len(payload)
	frame := make([]byte, 0, 14+plen)
	frame = append(frame, 0x80|(opcode&0x0F)) // FIN=1

	switch {
	case plen <= 125:
		frame = append(frame, 0x80|byte(plen))
	case plen <= 65535:
		frame = append(frame, 0x80|126, byte(plen>>8), byte(plen))
	default:
		frame = append(frame, 0x80|127,
			0, 0, 0, 0,
			byte(plen>>24), byte(plen>>16), byte(plen>>8), byte(plen))
	}

	frame = append(frame, maskKey[:]...)
	start := len(frame)
	frame = append(frame, payload...)
	for i := 0; i < plen; i++ {
		frame[start+i] ^= maskKey[i&3]
	}

	_, err := w.Conn.Write(frame)
	return err
}
