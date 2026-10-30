package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha512"
	gotls "crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	mrand "math/rand"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	utls "github.com/refraction-networking/utls"
	"golang.org/x/crypto/hkdf"
	"golang.org/x/net/http2"
)

var (
	markdownLinkInURIRegex = regexp.MustCompile(`\[([^\[\]]+)\]\((?:https?://|mailto:)?[^()\s]+\)`)
	mailtoSchemeRegex      = regexp.MustCompile(`(?i)<mailto:([^>\s]+)>`)
)

// SanitizeShareLinkInput cleans common copy-paste artifacts from HTML, Telegram, Markdown,
// and forum posts (such as "&amp;", "[host:443](http://host:443)", "<mailto:uuid@host:443>")
// without altering valid percent-encoded paths or multi-'#' fragments.
func SanitizeShareLinkInput(raw string) string {
	if raw == "" {
		return ""
	}
	s := strings.ReplaceAll(raw, "\u200b", "")
	s = strings.ReplaceAll(s, "\u200c", "")
	s = strings.ReplaceAll(s, "\ufeff", "")
	s = strings.ReplaceAll(s, "&amp;", "&")
	s = strings.ReplaceAll(s, "&#38;", "&")
	s = mailtoSchemeRegex.ReplaceAllString(s, "$1")
	if strings.Contains(s, "](") {
		s = markdownLinkInURIRegex.ReplaceAllString(s, "$1")
	}
	return s
}

func normalizeTransportName(raw string) (string, bool) {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "", "tcp", "raw":
		return "tcp", true
	case "ws", "websocket":
		return "ws", true
	case "xhttp", "splithttp":
		return "xhttp", true
	case "grpc", "gun":
		return "grpc", true
	case "httpupgrade", "http-upgrade":
		return "httpupgrade", true
	case "h2", "http", "http2":
		return "h2", true
	default:
		return "", false
	}
}

func resolveUTLSClientHelloID(fp string) utls.ClientHelloID {
	switch strings.ToLower(strings.TrimSpace(fp)) {
	case "firefox":
		return utls.HelloFirefox_Auto
	case "safari", "ios":
		return utls.HelloIOS_Auto
	case "edge":
		return utls.HelloEdge_Auto
	case "android":
		return utls.HelloAndroid_11_OkHttp
	case "360":
		return utls.Hello360_Auto
	case "qq":
		return utls.HelloQQ_Auto
	case "random", "randomized":
		return utls.HelloRandomizedALPN
	default:
		return utls.HelloChrome_Auto
	}
}

func parseALPNList(raw string, defaultProtos []string) []string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return defaultProtos
	}
	if unesc, err := url.QueryUnescape(trimmed); err == nil && unesc != "" {
		trimmed = unesc
	}
	parts := strings.Split(trimmed, ",")
	var out []string
	for _, p := range parts {
		v := strings.TrimSpace(p)
		if v == "h2" || v == "http/1.1" {
			out = append(out, v)
		}
	}
	if len(out) == 0 {
		return defaultProtos
	}
	return out
}

func dialRawNodeTCP(ctx context.Context, doh *DoHResolver, node OutboundNode) (net.Conn, error) {
	var candidateIPs []string
	isCFTransport := node.TLS && (node.Transport == "ws" || node.Transport == "xhttp" || node.Transport == "httpupgrade" || node.Transport == "grpc")
	if ip := net.ParseIP(node.Host); ip != nil {
		candidateIPs = append(candidateIPs, ip.String())
		if isCFTransport && isCloudflareIP(ip.String()) {
			candidateIPs = append(candidateIPs, cleanCloudflareEdgeIPs[:2]...)
		}
	} else if doh != nil {
		ips, err := doh.Resolve(ctx, node.Host)
		if err == nil && len(ips) > 0 {
			candidateIPs = append(candidateIPs, ips[0])
			if isCFTransport && isCloudflareIP(ips[0]) {
				candidateIPs = append(candidateIPs, cleanCloudflareEdgeIPs[0])
			}
		}
	}
	if len(candidateIPs) == 0 {
		candidateIPs = []string{node.Host}
	}

	var rawConn net.Conn
	var lastErr error
	for i, dialHost := range candidateIPs {
		perAttemptTimeout := 3200 * time.Millisecond
		if i > 0 {
			perAttemptTimeout = 2400 * time.Millisecond
		}
		var d net.Dialer
		d.Timeout = perAttemptTimeout
		c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(dialHost, node.Port))
		if err == nil {
			if tcpConn, ok := c.(*net.TCPConn); ok {
				_ = tcpConn.SetNoDelay(true)
				_ = tcpConn.SetKeepAlive(true)
				_ = tcpConn.SetKeepAlivePeriod(30 * time.Second)
			}
			rawConn = c
			break
		}
		lastErr = err
	}
	if rawConn == nil {
		return nil, fmt.Errorf("dial node %s:%s failed: %w", node.Host, node.Port, lastErr)
	}
	return rawConn, nil
}

func handshakeTLSWithALPN(
	ctx context.Context,
	conn net.Conn,
	serverName string,
	fingerprint string,
	alpnProtos []string,
	insecure bool,
) (net.Conn, string, error) {
	if len(alpnProtos) == 0 {
		alpnProtos = []string{"h2", "http/1.1"}
	}

	if serverName == "127.0.0.1" || serverName == "localhost" {
		stdConn := gotls.Client(conn, &gotls.Config{
			ServerName:         serverName,
			RootCAs:            AndroidRootCertPool(),
			InsecureSkipVerify: insecure,
			NextProtos:         alpnProtos,
			MinVersion:         gotls.VersionTLS12,
		})
		if err := stdConn.HandshakeContext(ctx); err != nil {
			return nil, "", err
		}
		return stdConn, stdConn.ConnectionState().NegotiatedProtocol, nil
	}

	uCfg := &utls.Config{
		ServerName:         serverName,
		RootCAs:            AndroidRootCertPool(),
		InsecureSkipVerify: insecure,
		MinVersion:         gotls.VersionTLS12,
	}

	helloID := resolveUTLSClientHelloID(fingerprint)
	spec, err := utls.UTLSIdToSpec(helloID)
	if err != nil {
		spec, err = utls.UTLSIdToSpec(utls.HelloChrome_Auto)
		if err != nil {
			return nil, "", err
		}
	}
	replacedALPN := false
	for _, ext := range spec.Extensions {
		if alpnExt, ok := ext.(*utls.ALPNExtension); ok {
			alpnExt.AlpnProtocols = alpnProtos
			replacedALPN = true
			break
		}
	}
	if !replacedALPN {
		spec.Extensions = append(spec.Extensions, &utls.ALPNExtension{AlpnProtocols: alpnProtos})
	}

	uConn := utls.UClient(conn, uCfg, utls.HelloCustom)
	if err := uConn.ApplyPreset(&spec); err != nil {
		return nil, "", err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = uConn.SetDeadline(deadline)
	} else {
		_ = uConn.SetDeadline(time.Now().Add(6 * time.Second))
	}
	if err := uConn.HandshakeContext(ctx); err != nil {
		return nil, "", err
	}
	_ = uConn.SetDeadline(time.Time{})
	return uConn, uConn.ConnectionState().NegotiatedProtocol, nil
}

// ============================================================================
// Xray REALITY TLS 1.3 Handshake (X25519 + HKDF-SHA256 + AES-256-GCM + Ed25519)
// ============================================================================

func decodeRealityPublicKey(pbk string) ([]byte, bool) {
	b, ok := decodeFlexibleBase64(strings.TrimSpace(pbk))
	if !ok || len(b) != 32 {
		return nil, false
	}
	return []byte(b), true
}

func decodeRealityShortID(sid string) ([]byte, bool) {
	clean := strings.TrimSpace(sid)
	var out [8]byte
	if clean == "" {
		return out[:], true
	}
	if len(clean)%2 == 1 {
		clean = "0" + clean
	}
	if len(clean) > 16 {
		return nil, false
	}
	b, err := hex.DecodeString(clean)
	if err != nil || len(b) > 8 {
		return nil, false
	}
	copy(out[:], b)
	return out[:], true
}

func handshakeRealityTLS(ctx context.Context, rawConn net.Conn, node OutboundNode) (net.Conn, net.Conn, string, error) {
	pubBytes, ok := decodeRealityPublicKey(node.RealityPublicKey)
	if !ok {
		return nil, nil, "", errors.New("invalid REALITY pbk")
	}
	shortID, ok := decodeRealityShortID(node.RealityShortID)
	if !ok {
		return nil, nil, "", errors.New("invalid REALITY sid")
	}
	serverPubKey, err := ecdh.X25519().NewPublicKey(pubBytes)
	if err != nil {
		return nil, nil, "", err
	}

	sni := node.SNI
	if sni == "" {
		sni = node.Host
	}

	var authKey []byte
	var certVerified atomic.Bool

	uCfg := &utls.Config{
		ServerName:             sni,
		InsecureSkipVerify:     true,
		SessionTicketsDisabled: true,
		VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return errors.New("REALITY: empty server certificate")
			}
			cert, pErr := x509.ParseCertificate(rawCerts[0])
			if pErr != nil {
				return pErr
			}
			if pub, isEd := cert.PublicKey.(ed25519.PublicKey); isEd && len(authKey) == 32 {
				mac := hmac.New(sha512.New, authKey)
				mac.Write(pub)
				if hmac.Equal(mac.Sum(nil), cert.Signature) {
					certVerified.Store(true)
					return nil
				}
			}
			return errors.New("REALITY: server certificate HMAC mismatch (untrusted or active probe)")
		},
	}

	// Chrome 120 guarantees standard X25519 KeyShare in State13.EcdheKey across utls v1.6.x
	uConn := utls.UClient(rawConn, uCfg, utls.HelloChrome_120)
	if err := uConn.BuildHandshakeState(); err != nil {
		return nil, nil, "", err
	}

	hello := uConn.HandshakeState.Hello
	ecdheKey := uConn.HandshakeState.State13.EcdheKey
	if hello == nil || ecdheKey == nil || len(hello.Random) < 32 {
		return nil, nil, "", errors.New("REALITY: missing TLS 1.3 X25519 state")
	}

	// Zero SessionId (32 bytes) and marshal so hello.Raw[39:71] holds 32 zero bytes for AAD
	hello.SessionId = make([]byte, 32)
	if err := uConn.MarshalClientHello(); err != nil {
		return nil, nil, "", err
	}
	if len(hello.Raw) < 71 {
		return nil, nil, "", errors.New("REALITY: malformed ClientHello raw buffer")
	}
	copy(hello.Raw[39:71], hello.SessionId)

	sharedSecret, err := ecdheKey.ECDH(serverPubKey)
	if err != nil {
		return nil, nil, "", err
	}

	authKey = make([]byte, 32)
	kdfReader := hkdf.New(sha256.New, sharedSecret, hello.Random[:20], []byte("REALITY"))
	if _, err := io.ReadFull(kdfReader, authKey); err != nil {
		return nil, nil, "", err
	}

	var plain [16]byte
	plain[0] = 1
	plain[1] = 8
	plain[2] = 24
	plain[3] = 0
	binary.BigEndian.PutUint32(plain[4:8], uint32(time.Now().Unix()))
	copy(plain[8:16], shortID)

	blk, err := aes.NewCipher(authKey)
	if err != nil {
		return nil, nil, "", err
	}
	aead, err := cipher.NewGCM(blk)
	if err != nil {
		return nil, nil, "", err
	}

	sealed := aead.Seal(nil, hello.Random[20:32], plain[:], hello.Raw)
	if len(sealed) != 32 {
		return nil, nil, "", errors.New("REALITY: unexpected sealed session id size")
	}
	copy(hello.SessionId, sealed)
	copy(hello.Raw[39:71], sealed)

	if deadline, ok := ctx.Deadline(); ok {
		_ = uConn.SetDeadline(deadline)
	} else {
		_ = uConn.SetDeadline(time.Now().Add(6 * time.Second))
	}
	if err := uConn.HandshakeContext(ctx); err != nil {
		return nil, nil, "", err
	}
	if !certVerified.Load() {
		_ = uConn.Close()
		return nil, nil, "", errors.New("REALITY: handshake completed without verified certificate")
	}
	_ = uConn.SetDeadline(time.Time{})
	return uConn, rawConn, uConn.ConnectionState().NegotiatedProtocol, nil
}

// ============================================================================
// XTLS Vision Stream Wrapper (flow=xtls-rprx-vision)
// ============================================================================

type xtlsVisionConn struct {
	net.Conn
	rawConn net.Conn

	writeMu       sync.Mutex
	wroteUserUUID bool
	userUUID      [16]byte
	padPackets    int

	readMu           sync.Mutex
	inVisionRead     bool
	checkedFirstRead bool
	directRead       bool
	remCmd           int
	curCmd           byte
	remContent       int
	remPadding       int
	pendingPlain     []byte
}

func newXTLSVisionConn(tlsConn net.Conn, rawConn net.Conn, uuidBytes [16]byte) net.Conn {
	return &xtlsVisionConn{
		Conn:         tlsConn,
		rawConn:      rawConn,
		userUUID:     uuidBytes,
		padPackets:   1,
		inVisionRead: true,
		remCmd:       -1,
	}
}

func (v *xtlsVisionConn) Write(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	v.writeMu.Lock()
	defer v.writeMu.Unlock()

	if v.padPackets <= 0 {
		return v.Conn.Write(p)
	}
	v.padPackets--

	// Wrap initial VLESS header + client payload in a Vision CommandPaddingEnd (0x01) frame
	// so the server cleanly terminates Vision padding and keeps uplink inside outer TLS 1.3.
	contentLen := len(p)
	if contentLen > 32768 {
		// Split oversized initial write cleanly
		n1, err := v.writeVisionFrame(p[:32768], 0x00)
		if err != nil {
			return n1, err
		}
		n2, err := v.writeVisionFrame(p[32768:], 0x01)
		return n1 + n2, err
	}
	return v.writeVisionFrame(p, 0x01)
}

func (v *xtlsVisionConn) writeVisionFrame(payload []byte, cmd byte) (int, error) {
	contentLen := len(payload)
	padLen := 0
	if contentLen < 900 {
		var rb [2]byte
		_, _ = rand.Read(rb[:])
		padLen = 500 + int(binary.BigEndian.Uint16(rb[:])%400) - contentLen
		if padLen < 16 {
			padLen = 32
		}
	} else {
		var rb [1]byte
		_, _ = rand.Read(rb[:])
		padLen = int(rb[0])
	}

	prefixLen := 5
	if !v.wroteUserUUID {
		prefixLen += 16
	}
	frame := make([]byte, 0, prefixLen+contentLen+padLen)
	if !v.wroteUserUUID {
		frame = append(frame, v.userUUID[:]...)
		v.wroteUserUUID = true
	}
	frame = append(frame,
		cmd,
		byte(contentLen>>8), byte(contentLen),
		byte(padLen>>8), byte(padLen),
	)
	frame = append(frame, payload...)
	if padLen > 0 {
		pad := make([]byte, padLen)
		_, _ = rand.Read(pad)
		frame = append(frame, pad...)
	}
	if _, err := v.Conn.Write(frame); err != nil {
		return 0, err
	}
	return len(payload), nil
}

func (v *xtlsVisionConn) Read(b []byte) (int, error) {
	v.readMu.Lock()
	defer v.readMu.Unlock()

	for len(v.pendingPlain) == 0 {
		if !v.inVisionRead {
			if v.directRead && v.rawConn != nil {
				return v.rawConn.Read(b)
			}
			return v.Conn.Read(b)
		}

		var tmp [16384]byte
		n, err := v.Conn.Read(tmp[:])
		if n == 0 {
			return 0, err
		}
		chunk := tmp[:n]

		if !v.checkedFirstRead {
			v.checkedFirstRead = true
			// In VLESS downlink, first 2 bytes are [0x00, 0x00] VLESS response header, followed by 16-byte UserUUID if Vision is active!
			if len(chunk) >= 18 && chunk[0] == 0x00 && chunk[1] == 0x00 && bytes.Equal(chunk[2:18], v.userUUID[:]) {
				v.pendingPlain = append(v.pendingPlain, chunk[:2]...)
				chunk = chunk[18:]
				v.remCmd = 5
			} else if len(chunk) >= 16 && bytes.Equal(chunk[:16], v.userUUID[:]) {
				chunk = chunk[16:]
				v.remCmd = 5
			} else {
				v.inVisionRead = false
				v.pendingPlain = append(v.pendingPlain, chunk...)
				break
			}
		}

		for len(chunk) > 0 {
			if !v.inVisionRead {
				v.pendingPlain = append(v.pendingPlain, chunk...)
				break
			}
			if v.remCmd > 0 {
				bt := chunk[0]
				chunk = chunk[1:]
				switch v.remCmd {
				case 5:
					v.curCmd = bt
				case 4:
					v.remContent = int(bt) << 8
				case 3:
					v.remContent |= int(bt)
				case 2:
					v.remPadding = int(bt) << 8
				case 1:
					v.remPadding |= int(bt)
				}
				v.remCmd--
			} else if v.remContent > 0 {
				take := v.remContent
				if take > len(chunk) {
					take = len(chunk)
				}
				v.pendingPlain = append(v.pendingPlain, chunk[:take]...)
				chunk = chunk[take:]
				v.remContent -= take
			} else if v.remPadding > 0 {
				take := v.remPadding
				if take > len(chunk) {
					take = len(chunk)
				}
				chunk = chunk[take:]
				v.remPadding -= take
			}

			if v.remCmd == 0 && v.remContent == 0 && v.remPadding == 0 {
				if v.curCmd == 0x00 {
					v.remCmd = 5
				} else {
					v.inVisionRead = false
					if v.curCmd == 0x02 {
						v.directRead = true
					}
					if len(chunk) > 0 {
						v.pendingPlain = append(v.pendingPlain, chunk...)
					}
					break
				}
			}
		}
	}

	copied := copy(b, v.pendingPlain)
	v.pendingPlain = v.pendingPlain[copied:]
	return copied, nil
}

// ============================================================================
// Xray HTTPUpgrade Transport
// ============================================================================

type httpUpgradeConn struct {
	net.Conn
	br *bufio.Reader
}

func (bc *httpUpgradeConn) Read(p []byte) (int, error) {
	return bc.br.Read(p)
}

func dialHTTPUpgradeStream(underConn net.Conn, hostHeader, rawPath string) (net.Conn, error) {
	cleanPath := NormalizeWSPath(rawPath)
	reqStr := "GET " + cleanPath + " HTTP/1.1\r\n" +
		"Host: " + hostHeader + "\r\n" +
		"Connection: Upgrade\r\n" +
		"Upgrade: websocket\r\n" +
		"User-Agent: Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36\r\n\r\n"

	_ = underConn.SetDeadline(time.Now().Add(9 * time.Second))
	if _, err := underConn.Write([]byte(reqStr)); err != nil {
		return nil, err
	}

	br := bufio.NewReaderSize(underConn, 16384)
	resp, err := http.ReadResponse(br, &http.Request{Method: http.MethodGet})
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		return nil, fmt.Errorf("httpupgrade failed: HTTP %d", resp.StatusCode)
	}
	_ = underConn.SetDeadline(time.Time{})
	return &httpUpgradeConn{Conn: underConn, br: br}, nil
}

// ============================================================================
// Xray XHTTP / SplitHTTP Transport (mode=auto / packet-up / stream-one / stream-up)
// ============================================================================

func normalizeXHTTPPathAndQuery(rawPath string, withSessionInPath bool) (normPath string, normQuery string) {
	p := strings.TrimSpace(rawPath)
	if strings.Contains(p, "%") {
		if unesc, err := url.QueryUnescape(p); err == nil && unesc != "" {
			p = unesc
		}
	}
	parts := strings.SplitN(p, "?", 2)
	normPath = parts[0]
	if len(parts) > 1 {
		normQuery = parts[1]
	}
	if normPath == "" || normPath[0] != '/' {
		normPath = "/" + normPath
	}
	if withSessionInPath && !strings.HasSuffix(normPath, "/") {
		normPath += "/"
	}
	return normPath, normQuery
}

func generateRandomUUIDv4() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func makeXHTTPPaddingReferer(baseURL url.URL) string {
	padLen := 100 + mrand.Intn(901) // exact Xray default range [100..1000]
	baseURL.RawQuery = "x_padding=" + strings.Repeat("X", padLen)
	return baseURL.String()
}

func buildXHTTPRoundTripper(doh *DoHResolver, node OutboundNode) (http.RoundTripper, func(), error) {
	isReality := node.Security == "reality"
	isTLS := node.TLS || isReality

	if !isTLS {
		tr := &http.Transport{
			DisableKeepAlives: true,
			IdleConnTimeout:   30 * time.Second,
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				return dialRawNodeTCP(ctx, doh, node)
			},
		}
		return tr, tr.CloseIdleConnections, nil
	}

	alpn := parseALPNList(node.ALPN, []string{"h2", "http/1.1"})
	sni := firstNonEmpty(node.SNI, node.WSHost, node.Host)
	insecure := node.AllowInsecure || (sni == "" && !isReality)

	// Probe/dial helper that negotiates TLS/REALITY; if server only speaks HTTP/1.1, fall back cleanly!
	if len(alpn) == 1 && alpn[0] == "http/1.1" {
		tr := &http.Transport{
			DisableKeepAlives: false,
			MaxIdleConns:      8,
			IdleConnTimeout:   30 * time.Second,
			DialTLSContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				raw, err := dialRawNodeTCP(ctx, doh, node)
				if err != nil {
					return nil, err
				}
				if isReality {
					uConn, _, _, rErr := handshakeRealityTLS(ctx, raw, node)
					if rErr != nil {
						_ = raw.Close()
						return nil, rErr
					}
					return uConn, nil
				}
				frag := NewFragmentingConn(raw, 20, 2*time.Millisecond)
				c, _, tErr := handshakeTLSWithALPN(ctx, frag, sni, node.Fingerprint, []string{"http/1.1"}, insecure)
				if tErr != nil {
					_ = raw.Close()
					return nil, tErr
				}
				return c, nil
			},
		}
		return tr, tr.CloseIdleConnections, nil
	}

	// Negotiate first connection so we know whether server selected h2 or http/1.1
	probeCtx, probeCancel := context.WithTimeout(context.Background(), 5500*time.Millisecond)
	firstRaw, err := dialRawNodeTCP(probeCtx, doh, node)
	if err != nil {
		probeCancel()
		return nil, nil, err
	}

	var firstTLS net.Conn
	var negProto string
	if isReality {
		firstTLS, _, negProto, err = handshakeRealityTLS(probeCtx, firstRaw, node)
	} else {
		frag := NewFragmentingConn(firstRaw, 20, 2*time.Millisecond)
		firstTLS, negProto, err = handshakeTLSWithALPN(probeCtx, frag, sni, node.Fingerprint, alpn, insecure)
	}
	probeCancel()
	if err != nil {
		_ = firstRaw.Close()
		return nil, nil, err
	}

	var usedFirst atomic.Bool
	var connsMu sync.Mutex
	activeConns := []net.Conn{firstTLS}
	trackConn := func(c net.Conn) net.Conn {
		connsMu.Lock()
		activeConns = append(activeConns, c)
		connsMu.Unlock()
		return c
	}
	closeAllTracked := func() {
		connsMu.Lock()
		list := append([]net.Conn(nil), activeConns...)
		activeConns = nil
		connsMu.Unlock()
		for _, c := range list {
			if c != nil {
				_ = c.Close()
			}
		}
	}

	takeFirstOrDial := func(ctx context.Context, wantProtos []string) (net.Conn, error) {
		if usedFirst.CompareAndSwap(false, true) {
			return firstTLS, nil
		}
		raw, dErr := dialRawNodeTCP(ctx, doh, node)
		if dErr != nil {
			return nil, dErr
		}
		if isReality {
			c, _, _, rErr := handshakeRealityTLS(ctx, raw, node)
			if rErr != nil {
				_ = raw.Close()
				return nil, rErr
			}
			return trackConn(c), nil
		}
		frag := NewFragmentingConn(raw, 20, 2*time.Millisecond)
		c, _, tErr := handshakeTLSWithALPN(ctx, frag, sni, node.Fingerprint, wantProtos, insecure)
		if tErr != nil {
			_ = raw.Close()
			return nil, tErr
		}
		return trackConn(c), nil
	}

	if negProto == "h2" {
		h2Tr := &http2.Transport{
			ReadIdleTimeout: 30 * time.Second,
			PingTimeout:     10 * time.Second,
			DialTLSContext: func(ctx context.Context, _, _ string, _ *gotls.Config) (net.Conn, error) {
				return takeFirstOrDial(ctx, []string{"h2"})
			},
		}
		cleanup := func() {
			h2Tr.CloseIdleConnections()
			closeAllTracked()
		}
		return h2Tr, cleanup, nil
	}

	// Server negotiated http/1.1
	h1Tr := &http.Transport{
		DisableKeepAlives: false,
		MaxIdleConns:      8,
		IdleConnTimeout:   30 * time.Second,
		DialTLSContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return takeFirstOrDial(ctx, []string{"http/1.1"})
		},
	}
	cleanup := func() {
		h1Tr.CloseIdleConnections()
		closeAllTracked()
	}
	return h1Tr, cleanup, nil
}

type xhttpConn struct {
	ctx        context.Context
	cancel     context.CancelFunc
	cleanup    func()
	downBody   io.ReadCloser
	downReady  chan struct{}
	downErr    error
	upPipeW    *io.PipeWriter
	mode       string
	remoteAddr net.Addr
	localAddr  net.Addr
	deadlineMu sync.Mutex
	readTimer  *time.Timer
	closeOnce  sync.Once
}

func dialXHTTPStream(ctx context.Context, doh *DoHResolver, node OutboundNode) (net.Conn, error) {
	rt, cleanup, err := buildXHTTPRoundTripper(doh, node)
	if err != nil {
		return nil, err
	}

	mode := strings.ToLower(strings.TrimSpace(node.Mode))
	if mode == "" || mode == "auto" {
		if node.Security == "reality" {
			mode = "stream-one"
		} else {
			mode = "packet-up"
		}
	}

	withSessionInPath := mode != "stream-one"
	normPath, normQuery := normalizeXHTTPPathAndQuery(node.WSPath, withSessionInPath)

	scheme := "http"
	if node.TLS || node.Security == "reality" {
		scheme = "https"
	}
	reqHost := firstNonEmpty(node.WSHost, node.SNI, node.Host)

	baseURL := url.URL{
		Scheme:   scheme,
		Host:     reqHost,
		Path:     normPath,
		RawQuery: normQuery,
	}

	connCtx, cancel := context.WithCancel(context.Background())
	httpClient := &http.Client{Transport: rt}
	const browserUA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

	xc := &xhttpConn{
		ctx:        connCtx,
		cancel:     cancel,
		cleanup:    cleanup,
		downReady:  make(chan struct{}),
		mode:       mode,
		remoteAddr: &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 443},
		localAddr:  &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0},
	}

	if mode == "stream-one" {
		pr, pw := io.Pipe()
		xc.upPipeW = pw

		streamURL := baseURL
		// When server negotiated HTTP/1.1 instead of HTTP/2, Go's stdlib http.Transport
		// is half-duplex (waits for req.Body EOF before returning resp). Perform raw
		// full-duplex HTTP/1.1 chunked streaming directly over the TLS connection.
		if h1Tr, isH1 := rt.(*http.Transport); isH1 && h1Tr.DialTLSContext != nil {
			h1Conn, dErr := h1Tr.DialTLSContext(ctx, "tcp", reqHost)
			if dErr != nil {
				cancel()
				cleanup()
				return nil, dErr
			}
			reqURI := streamURL.RequestURI()
			referer := makeXHTTPPaddingReferer(baseURL)
			hdr := "POST " + reqURI + " HTTP/1.1\r\n" +
				"Host: " + reqHost + "\r\n" +
				"User-Agent: " + browserUA + "\r\n" +
				"Referer: " + referer + "\r\n" +
				"Content-Type: application/grpc\r\n" +
				"Transfer-Encoding: chunked\r\n\r\n"
			if _, wErr := io.WriteString(h1Conn, hdr); wErr != nil {
				cancel()
				cleanup()
				return nil, wErr
			}
			go func() {
				defer func() { _ = pr.Close() }()
				buf := make([]byte, 32*1024)
				for {
					n, rErr := pr.Read(buf)
					if n > 0 {
						chunkHdr := strconv.FormatInt(int64(n), 16) + "\r\n"
						if _, err := io.WriteString(h1Conn, chunkHdr); err != nil {
							return
						}
						if _, err := h1Conn.Write(buf[:n]); err != nil {
							return
						}
						if _, err := io.WriteString(h1Conn, "\r\n"); err != nil {
							return
						}
					}
					if rErr != nil {
						_, _ = io.WriteString(h1Conn, "0\r\n\r\n")
						return
					}
				}
			}()
			go func() {
				dummyReq, _ := http.NewRequest(http.MethodPost, streamURL.String(), nil)
				resp, rErr := http.ReadResponse(bufio.NewReader(h1Conn), dummyReq)
				if rErr != nil {
					xc.downErr = rErr
					close(xc.downReady)
					_ = pr.Close()
					return
				}
				if resp.StatusCode != http.StatusOK {
					_ = resp.Body.Close()
					xc.downErr = fmt.Errorf("xhttp stream-one H1 HTTP %d", resp.StatusCode)
					close(xc.downReady)
					_ = pr.Close()
					return
				}
				xc.downBody = resp.Body
				close(xc.downReady)
			}()
			return xc, nil
		}

		req, rErr := http.NewRequestWithContext(connCtx, http.MethodPost, streamURL.String(), pr)
		if rErr != nil {
			cancel()
			cleanup()
			return nil, rErr
		}
		req.Header.Set("User-Agent", browserUA)
		req.Header.Set("Referer", makeXHTTPPaddingReferer(baseURL))
		req.Header.Set("Content-Type", "application/grpc")

		go func() {
			resp, doErr := httpClient.Do(req)
			if doErr != nil {
				xc.downErr = doErr
				close(xc.downReady)
				_ = pr.Close()
				return
			}
			if resp.StatusCode != http.StatusOK {
				_ = resp.Body.Close()
				xc.downErr = fmt.Errorf("xhttp stream-one HTTP %d", resp.StatusCode)
				close(xc.downReady)
				_ = pr.Close()
				return
			}
			xc.downBody = resp.Body
			close(xc.downReady)
		}()
		return xc, nil
	}

	// mode == "packet-up" or "stream-up"
	sessionID := generateRandomUUIDv4()

	downURL := baseURL
	downURL.Path = appendSlashSegment(baseURL.Path, sessionID)
	downReq, err := http.NewRequestWithContext(connCtx, http.MethodGet, downURL.String(), nil)
	if err != nil {
		cancel()
		cleanup()
		return nil, err
	}
	downReq.Header.Set("User-Agent", browserUA)
	downReq.Header.Set("Referer", makeXHTTPPaddingReferer(baseURL))

	go func() {
		resp, doErr := httpClient.Do(downReq)
		if doErr != nil {
			xc.downErr = doErr
			close(xc.downReady)
			return
		}
		if resp.StatusCode != http.StatusOK {
			_ = resp.Body.Close()
			xc.downErr = fmt.Errorf("xhttp stream-down HTTP %d", resp.StatusCode)
			close(xc.downReady)
			return
		}
		xc.downBody = resp.Body
		close(xc.downReady)
	}()

	pr, pw := io.Pipe()
	xc.upPipeW = pw

	if mode == "stream-up" {
		upURL := baseURL
		upURL.Path = appendSlashSegment(baseURL.Path, sessionID)
		upReq, uErr := http.NewRequestWithContext(connCtx, http.MethodPost, upURL.String(), pr)
		if uErr != nil {
			_ = xc.Close()
			return nil, uErr
		}
		upReq.Header.Set("User-Agent", browserUA)
		upReq.Header.Set("Referer", makeXHTTPPaddingReferer(baseURL))
		upReq.Header.Set("Content-Type", "application/grpc")
		go func() {
			resp, doErr := httpClient.Do(upReq)
			if doErr == nil && resp != nil {
				_, _ = io.Copy(io.Discard, resp.Body)
				_ = resp.Body.Close()
			}
			_ = pr.Close()
		}()
		return xc, nil
	}

	// Default CDN-safe packet-up uploader loop (batches writes into sequenced POST /path/<sessionId>/<seq>)
	go func() {
		defer func() { _ = pr.Close() }()
		buf := make([]byte, 256*1024)
		var seq int64
		var lastPost time.Time

		for {
			n, rErr := pr.Read(buf)
			if n > 0 {
				// Coalesce immediately available bytes in pipe if small
				payload := make([]byte, n)
				copy(payload, buf[:n])

				if since := time.Since(lastPost); since < 25*time.Millisecond && seq > 0 {
					time.Sleep(25*time.Millisecond - since)
				}
				lastPost = time.Now()

				seqStr := strconv.FormatInt(seq, 10)
				seq++

				pktURL := baseURL
				pktURL.Path = appendSlashSegment(appendSlashSegment(baseURL.Path, sessionID), seqStr)

				postCtx, postCancel := context.WithTimeout(connCtx, 10*time.Second)
				postReq, pErr := http.NewRequestWithContext(postCtx, http.MethodPost, pktURL.String(), bytes.NewReader(payload))
				if pErr != nil {
					postCancel()
					_ = xc.Close()
					return
				}
				postReq.ContentLength = int64(len(payload))
				postReq.Header.Set("User-Agent", browserUA)
				postReq.Header.Set("Referer", makeXHTTPPaddingReferer(baseURL))

				resp, doErr := httpClient.Do(postReq)
				if resp != nil {
					_, _ = io.Copy(io.Discard, resp.Body)
					_ = resp.Body.Close()
				}
				postCancel()
				if doErr != nil || (resp != nil && resp.StatusCode != http.StatusOK) {
					_ = xc.Close()
					return
				}
			}
			if rErr != nil {
				return
			}
		}
	}()

	// Fail fast if dialing immediately errored before returning
	select {
	case <-xc.downReady:
		if xc.downErr != nil {
			_ = xc.Close()
			return nil, xc.downErr
		}
	case <-ctx.Done():
		_ = xc.Close()
		return nil, ctx.Err()
	case <-time.After(15 * time.Millisecond):
		// Allow initial client VLESS/Trojan payload write to proceed immediately (required for stream-one / CDN servers that flush response headers upon first uplink packet!)
	}
	return xc, nil
}

func appendSlashSegment(basePath, seg string) string {
	if strings.HasSuffix(basePath, "/") {
		return basePath + seg
	}
	return basePath + "/" + seg
}

func (x *xhttpConn) Read(p []byte) (int, error) {
	select {
	case <-x.downReady:
	case <-x.ctx.Done():
		return 0, io.ErrClosedPipe
	}
	if x.downErr != nil {
		return 0, x.downErr
	}
	if x.downBody == nil {
		return 0, io.EOF
	}
	return x.downBody.Read(p)
}

func (x *xhttpConn) Write(p []byte) (int, error) {
	if x.upPipeW == nil {
		return 0, io.ErrClosedPipe
	}
	return x.upPipeW.Write(p)
}

func (x *xhttpConn) Close() error {
	x.closeOnce.Do(func() {
		x.deadlineMu.Lock()
		if x.readTimer != nil {
			x.readTimer.Stop()
		}
		x.deadlineMu.Unlock()

		x.cancel()
		if x.upPipeW != nil {
			_ = x.upPipeW.Close()
		}
		select {
		case <-x.downReady:
			if x.downBody != nil {
				_ = x.downBody.Close()
			}
		default:
		}
		if x.cleanup != nil {
			x.cleanup()
		}
	})
	return nil
}

func (x *xhttpConn) LocalAddr() net.Addr  { return x.localAddr }
func (x *xhttpConn) RemoteAddr() net.Addr { return x.remoteAddr }

func (x *xhttpConn) SetDeadline(t time.Time) error {
	_ = x.SetReadDeadline(t)
	return nil
}

func (x *xhttpConn) SetReadDeadline(t time.Time) error {
	x.deadlineMu.Lock()
	defer x.deadlineMu.Unlock()
	if x.readTimer != nil {
		x.readTimer.Stop()
		x.readTimer = nil
	}
	if !t.IsZero() {
		d := time.Until(t)
		if d <= 0 {
			go func() { _ = x.Close() }()
			return nil
		}
		x.readTimer = time.AfterFunc(d, func() {
			_ = x.Close()
		})
	}
	return nil
}

func (x *xhttpConn) SetWriteDeadline(_ time.Time) error { return nil }

// ============================================================================
// Xray gRPC (GunService / Tun) & HTTP/2 Stream Transports
// ============================================================================

type grpcGunConn struct {
	ctx        context.Context
	cancel     context.CancelFunc
	cleanup    func()
	pipeW      *io.PipeWriter
	respReady  chan struct{}
	respBody   io.ReadCloser
	respErr    error
	readMu     sync.Mutex
	writeMu    sync.Mutex
	pending    []byte
	closeOnce  sync.Once
	deadlineMu sync.Mutex
	readTimer  *time.Timer
}

func encodeProtobufVarint(v uint64) []byte {
	var buf [10]byte
	i := 0
	for v >= 0x80 {
		buf[i] = byte(v) | 0x80
		v >>= 7
		i++
	}
	buf[i] = byte(v)
	return buf[:i+1]
}

func decodeProtobufHunkPayload(msg []byte) ([]byte, error) {
	if len(msg) == 0 {
		return nil, nil
	}
	// Protobuf Hunk: field 1, wire type 2 (bytes) => tag 0x0A + varint length + bytes
	idx := 0
	var out []byte
	for idx < len(msg) {
		tag := msg[idx]
		idx++
		if (tag & 0x07) != 2 {
			return msg, nil
		}
		var length uint64
		var shift uint
		for {
			if idx >= len(msg) {
				return nil, io.ErrUnexpectedEOF
			}
			b := msg[idx]
			idx++
			length |= uint64(b&0x7F) << shift
			if b < 0x80 {
				break
			}
			shift += 7
			if shift >= 64 {
				return nil, errors.New("grpc varint overflow")
			}
		}
		if uint64(len(msg)-idx) < length {
			return nil, io.ErrUnexpectedEOF
		}
		if (tag >> 3) == 1 {
			out = append(out, msg[idx:idx+int(length)]...)
		}
		idx += int(length)
	}
	return out, nil
}

func dialGRPCTunStream(ctx context.Context, doh *DoHResolver, node OutboundNode) (net.Conn, error) {
	isReality := node.Security == "reality"
	sni := firstNonEmpty(node.SNI, node.WSHost, node.Host)
	insecure := node.AllowInsecure || (sni == "" && !isReality)

	h2Tr := &http2.Transport{
		ReadIdleTimeout: 30 * time.Second,
		PingTimeout:     10 * time.Second,
		AllowHTTP:       !node.TLS && !isReality,
	}
	if node.TLS || isReality {
		h2Tr.DialTLSContext = func(dCtx context.Context, _, _ string, _ *gotls.Config) (net.Conn, error) {
			raw, err := dialRawNodeTCP(dCtx, doh, node)
			if err != nil {
				return nil, err
			}
			if isReality {
				c, _, _, rErr := handshakeRealityTLS(dCtx, raw, node)
				if rErr != nil {
					_ = raw.Close()
					return nil, rErr
				}
				return c, nil
			}
			frag := NewFragmentingConn(raw, 20, 2*time.Millisecond)
			c, _, tErr := handshakeTLSWithALPN(dCtx, frag, sni, node.Fingerprint, []string{"h2"}, insecure)
			if tErr != nil {
				_ = raw.Close()
				return nil, tErr
			}
			return c, nil
		}
	} else {
		h2Tr.DialTLSContext = func(dCtx context.Context, _, _ string, _ *gotls.Config) (net.Conn, error) {
			return dialRawNodeTCP(dCtx, doh, node)
		}
	}

	svc := strings.Trim(strings.TrimSpace(firstNonEmpty(node.ServiceName, strings.TrimPrefix(node.WSPath, "/"))), "/")
	if svc == "" {
		svc = "GunService"
	}
	if unesc, err := url.QueryUnescape(svc); err == nil && unesc != "" {
		svc = strings.Trim(unesc, "/")
	}
	grpcPath := "/" + svc + "/Tun"
	if strings.Contains(svc, "/") && strings.HasSuffix(svc, "/Tun") {
		grpcPath = "/" + svc
	}

	scheme := "https"
	if !node.TLS && !isReality {
		scheme = "http"
	}
	reqHost := firstNonEmpty(node.WSHost, sni, node.Host)
	targetURL := scheme + "://" + reqHost + grpcPath

	connCtx, cancel := context.WithCancel(context.Background())
	pr, pw := io.Pipe()

	req, err := http.NewRequestWithContext(connCtx, http.MethodPost, targetURL, pr)
	if err != nil {
		cancel()
		return nil, err
	}
	req.Header.Set("Content-Type", "application/grpc")
	req.Header.Set("TE", "trailers")
	req.Header.Set("User-Agent", "grpc-go/1.64.0")

	gc := &grpcGunConn{
		ctx:       connCtx,
		cancel:    cancel,
		cleanup:   h2Tr.CloseIdleConnections,
		pipeW:     pw,
		respReady: make(chan struct{}),
	}

	client := &http.Client{Transport: h2Tr}
	go func() {
		resp, doErr := client.Do(req)
		if doErr != nil {
			gc.respErr = doErr
			close(gc.respReady)
			_ = pr.Close()
			return
		}
		if resp.StatusCode != http.StatusOK {
			_ = resp.Body.Close()
			gc.respErr = fmt.Errorf("grpc Tun HTTP %d", resp.StatusCode)
			close(gc.respReady)
			_ = pr.Close()
			return
		}
		gc.respBody = resp.Body
		close(gc.respReady)
	}()

	select {
	case <-gc.respReady:
		if gc.respErr != nil {
			_ = gc.Close()
			return nil, gc.respErr
		}
	case <-ctx.Done():
		_ = gc.Close()
		return nil, ctx.Err()
	case <-time.After(15 * time.Millisecond):
	}
	return gc, nil
}

func (g *grpcGunConn) Write(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	g.writeMu.Lock()
	defer g.writeMu.Unlock()

	total := 0
	const maxChunk = 32768
	for off := 0; off < len(p); off += maxChunk {
		end := off + maxChunk
		if end > len(p) {
			end = len(p)
		}
		chunk := p[off:end]
		varintLen := encodeProtobufVarint(uint64(len(chunk)))
		pbLen := 1 + len(varintLen) + len(chunk)

		frame := make([]byte, 5+pbLen)
		frame[0] = 0x00 // uncompressed gRPC flag
		binary.BigEndian.PutUint32(frame[1:5], uint32(pbLen))
		frame[5] = 0x0A // field 1 bytes
		copy(frame[6:], varintLen)
		copy(frame[6+len(varintLen):], chunk)

		if _, err := g.pipeW.Write(frame); err != nil {
			return total, err
		}
		total += len(chunk)
	}
	return total, nil
}

func (g *grpcGunConn) Read(p []byte) (int, error) {
	select {
	case <-g.respReady:
	case <-g.ctx.Done():
		return 0, io.ErrClosedPipe
	}
	if g.respErr != nil {
		return 0, g.respErr
	}
	if g.respBody == nil {
		return 0, io.EOF
	}

	g.readMu.Lock()
	defer g.readMu.Unlock()

	for len(g.pending) == 0 {
		var hdr [5]byte
		if _, err := io.ReadFull(g.respBody, hdr[:]); err != nil {
			return 0, err
		}
		if hdr[0] != 0x00 {
			return 0, errors.New("compressed grpc frame unsupported")
		}
		msgLen := binary.BigEndian.Uint32(hdr[1:5])
		if msgLen > 4*1024*1024 {
			return 0, errors.New("grpc frame exceeds 4MB cap")
		}
		if msgLen == 0 {
			continue
		}
		msg := make([]byte, int(msgLen))
		if _, err := io.ReadFull(g.respBody, msg); err != nil {
			return 0, err
		}
		data, err := decodeProtobufHunkPayload(msg)
		if err != nil {
			return 0, err
		}
		if len(data) > 0 {
			g.pending = data
		}
	}

	n := copy(p, g.pending)
	g.pending = g.pending[n:]
	return n, nil
}

func (g *grpcGunConn) Close() error {
	g.closeOnce.Do(func() {
		g.deadlineMu.Lock()
		if g.readTimer != nil {
			g.readTimer.Stop()
		}
		g.deadlineMu.Unlock()

		g.cancel()
		if g.pipeW != nil {
			_ = g.pipeW.Close()
		}
		select {
		case <-g.respReady:
			if g.respBody != nil {
				_ = g.respBody.Close()
			}
		default:
		}
		if g.cleanup != nil {
			g.cleanup()
		}
	})
	return nil
}

func (g *grpcGunConn) LocalAddr() net.Addr  { return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0} }
func (g *grpcGunConn) RemoteAddr() net.Addr { return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 443} }
func (g *grpcGunConn) SetDeadline(t time.Time) error {
	return g.SetReadDeadline(t)
}
func (g *grpcGunConn) SetReadDeadline(t time.Time) error {
	g.deadlineMu.Lock()
	defer g.deadlineMu.Unlock()
	if g.readTimer != nil {
		g.readTimer.Stop()
		g.readTimer = nil
	}
	if !t.IsZero() {
		d := time.Until(t)
		if d <= 0 {
			go func() { _ = g.Close() }()
			return nil
		}
		g.readTimer = time.AfterFunc(d, func() { _ = g.Close() })
	}
	return nil
}
func (g *grpcGunConn) SetWriteDeadline(_ time.Time) error { return nil }

func dialH2Stream(ctx context.Context, doh *DoHResolver, node OutboundNode) (net.Conn, error) {
	// Xray H2 transport uses a streaming PUT over HTTP/2
	n2 := node
	n2.Mode = "stream-one"
	return dialXHTTPStream(ctx, doh, n2)
}

// ============================================================================
// Clash / Mihomo YAML Proxy Entry Parser (for Clash Subscription URLs)
// ============================================================================

func ParseClashProxies(rawYAML string, defaultSource string) []OutboundNode {
	if !strings.Contains(rawYAML, "proxies:") && !strings.Contains(rawYAML, "server:") {
		return nil
	}
	var out []OutboundNode
	seen := make(map[string]bool)

	var cur map[string]string
	flush := func() {
		if len(cur) == 0 {
			return
		}
		if node, ok := clashMapToOutboundNode(cur, defaultSource); ok && !seen[node.ID] {
			seen[node.ID] = true
			out = append(out, node)
		}
		cur = nil
	}

	for _, rawLine := range strings.Split(strings.ReplaceAll(rawYAML, "\r\n", "\n"), "\n") {
		line := strings.TrimSpace(rawLine)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		// Inline flow style: - {name: "...", type: vless, server: ..., port: 443, ...}
		if strings.HasPrefix(line, "-") && strings.Contains(line, "{") && strings.Contains(line, "}") {
			flush()
			inner := line[strings.IndexByte(line, '{')+1 : strings.LastIndexByte(line, '}')]
			m := make(map[string]string)
			for _, kv := range splitClashInlinePairs(inner) {
				if cIdx := strings.IndexByte(kv, ':'); cIdx > 0 {
					k := strings.ToLower(strings.TrimSpace(kv[:cIdx]))
					v := trimYAMLQuotes(strings.TrimSpace(kv[cIdx+1:]))
					m[k] = v
				}
			}
			if node, ok := clashMapToOutboundNode(m, defaultSource); ok && !seen[node.ID] {
				seen[node.ID] = true
				out = append(out, node)
			}
			continue
		}

		if strings.HasPrefix(line, "- ") {
			flush()
			cur = make(map[string]string)
			line = strings.TrimSpace(line[2:])
		}
		if cur != nil {
			if cIdx := strings.IndexByte(line, ':'); cIdx > 0 {
				k := strings.ToLower(strings.TrimSpace(line[:cIdx]))
				v := trimYAMLQuotes(strings.TrimSpace(line[cIdx+1:]))
				if v != "" && !strings.HasPrefix(v, "{") {
					if _, exists := cur[k]; !exists || k == "path" || k == "host" || k == "public-key" || k == "short-id" || k == "grpc-service-name" {
						cur[k] = v
					}
				}
			}
		}
	}
	flush()
	return out
}

func splitClashInlinePairs(s string) []string {
	var parts []string
	depth := 0
	inQuote := byte(0)
	start := 0
	for i := 0; i < len(s); i++ {
		ch := s[i]
		if inQuote != 0 {
			if ch == inQuote && (i == 0 || s[i-1] != '\\') {
				inQuote = 0
			}
			continue
		}
		switch ch {
		case '"', '\'':
			inQuote = ch
		case '{', '[':
			depth++
		case '}', ']':
			if depth > 0 {
				depth--
			}
		case ',':
			if depth == 0 {
				parts = append(parts, s[start:i])
				start = i + 1
			}
		}
	}
	if start < len(s) {
		parts = append(parts, s[start:])
	}
	return parts
}

func trimYAMLQuotes(s string) string {
	if len(s) >= 2 && ((s[0] == '"' && s[len(s)-1] == '"') || (s[0] == '\'' && s[len(s)-1] == '\'')) {
		return s[1 : len(s)-1]
	}
	return s
}

func clashMapToOutboundNode(m map[string]string, defaultSource string) (OutboundNode, bool) {
	typ := strings.ToLower(m["type"])
	host := m["server"]
	port := extractLeadingDigits(m["port"])
	if host == "" || !validPort(port) {
		return OutboundNode{}, false
	}
	name := firstNonEmpty(m["name"], fmt.Sprintf("%s-%s:%s", strings.ToUpper(typ), host, port))
	netType, ok := normalizeTransportName(firstNonEmpty(m["network"], "tcp"))
	if !ok {
		return OutboundNode{}, false
	}
	useTLS := m["tls"] == "true" || m["tls"] == "1" || port == "443"
	sni := firstNonEmpty(m["servername"], m["sni"], host)
	wsHost := firstNonEmpty(m["host"], sni, host)
	wsPath := firstNonEmpty(m["path"], "/")
	fp := firstNonEmpty(m["client-fingerprint"], "chrome")
	insecure := m["skip-cert-verify"] == "true" || m["skip-cert-verify"] == "1"
	pbk := m["public-key"]
	sid := m["short-id"]
	sec := ""
	if pbk != "" {
		sec = "reality"
		useTLS = true
	} else if useTLS {
		sec = "tls"
	}

	switch typ {
	case "vless":
		uuidStr := m["uuid"]
		if _, uOk := parseUUIDBytes(uuidStr); !uOk {
			return OutboundNode{}, false
		}
		flow := strings.ToLower(m["flow"])
		if flow != "" && flow != "none" && flow != "xtls-rprx-vision" {
			return OutboundNode{}, false
		}
		return OutboundNode{
			ID:               fmt.Sprintf("vless:%s:%s:%s:%s:%s", host, port, uuidStr, netType, sni),
			Name:             name,
			Source:           firstNonEmpty(defaultSource, "Clash"),
			Protocol:         ProtoVLESS,
			Host:             host,
			Port:             port,
			UUID:             uuidStr,
			Transport:        netType,
			TLS:              useTLS,
			Security:         sec,
			SNI:              sni,
			WSHost:           wsHost,
			WSPath:           wsPath,
			ServiceName:      m["grpc-service-name"],
			Fingerprint:      fp,
			Flow:             flow,
			RealityPublicKey: pbk,
			RealityShortID:   sid,
			AllowInsecure:    insecure,
		}, true
	case "trojan":
		pass := m["password"]
		if pass == "" {
			return OutboundNode{}, false
		}
		return OutboundNode{
			ID:               fmt.Sprintf("trojan:%s:%s:%s:%s", host, port, pass, netType),
			Name:             name,
			Source:           firstNonEmpty(defaultSource, "Clash"),
			Protocol:         ProtoTrojan,
			Host:             host,
			Port:             port,
			Password:         pass,
			Transport:        netType,
			TLS:              true,
			Security:         firstNonEmpty(sec, "tls"),
			SNI:              sni,
			WSHost:           wsHost,
			WSPath:           wsPath,
			ServiceName:      m["grpc-service-name"],
			Fingerprint:      fp,
			RealityPublicKey: pbk,
			RealityShortID:   sid,
			AllowInsecure:    insecure,
		}, true
	case "vmess":
		uuidStr := m["uuid"]
		if _, uOk := parseUUIDBytes(uuidStr); !uOk {
			return OutboundNode{}, false
		}
		cipherName := "aes-128-gcm"
		if strings.Contains(strings.ToLower(m["cipher"]), "chacha20") {
			cipherName = "chacha20-poly1305"
		}
		return OutboundNode{
			ID:            fmt.Sprintf("vmess:%s:%s:%s:%s:%s", host, port, uuidStr, netType, sni),
			Name:          name,
			Source:        firstNonEmpty(defaultSource, "Clash"),
			Protocol:      ProtoVMess,
			Host:          host,
			Port:          port,
			UUID:          uuidStr,
			Cipher:        cipherName,
			Transport:     netType,
			TLS:           useTLS,
			Security:      sec,
			SNI:           sni,
			WSHost:        wsHost,
			WSPath:        wsPath,
			ServiceName:   m["grpc-service-name"],
			Fingerprint:   fp,
			AllowInsecure: insecure,
		}, true
	case "ss":
		pass := m["password"]
		ciph := strings.ToLower(m["cipher"])
		switch ciph {
		case "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305":
		default:
			return OutboundNode{}, false
		}
		if pass == "" {
			return OutboundNode{}, false
		}
		return OutboundNode{
			ID:        fmt.Sprintf("ss:%s:%s:%s", host, port, ciph),
			Name:      name,
			Source:    firstNonEmpty(defaultSource, "Clash"),
			Protocol:  ProtoShadowsocks,
			Host:      host,
			Port:      port,
			Cipher:    ciph,
			Password:  pass,
			Transport: "tcp",
		}, true
	}
	return OutboundNode{}, false
}
