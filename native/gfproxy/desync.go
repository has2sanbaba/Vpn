package main

import (
	"bytes"
	"encoding/binary"
	"net"
	"strings"
	"sync/atomic"
	"time"
)

// FragmentTLSClientHello inspects an outbound payload. If it starts with a TLS Handshake
// ClientHello record (0x16 0x03 ... 0x01), it splits the single TLS record into multiple
// smaller valid TLS Handshake records (RFC 5246 / RFC 8446 record-layer fragmentation),
// placing a split boundary directly inside the SNI (server_name) extension if present.
//
// Stateful DPI middleboxes that inspect a single TLS record for banned SNI hostnames fail
// to match the fragmented SNI, while all compliant TLS servers reassemble the handshake
// records transparently with 100% authentic end-to-end TLS certificates (no MITM CA!).
func FragmentTLSClientHello(data []byte, maxFragPayload int) [][]byte {
	if len(data) < 9 || data[0] != 0x16 || data[1] != 0x03 {
		return nil
	}
	recLen := int(binary.BigEndian.Uint16(data[3:5]))
	if recLen <= 4 || 5+recLen > len(data) {
		return nil
	}
	handshake := data[5 : 5+recLen]
	// HandshakeType 0x01 == ClientHello
	if handshake[0] != 0x01 {
		return nil
	}

	if maxFragPayload <= 2 {
		maxFragPayload = 24
	}

	versionMajor := data[1]
	versionMinor := data[2]

	// Locate SNI hostname offset inside handshake slice if present
	sniStart, sniEnd := findSNIBoundsInHandshake(handshake)

	var chunks [][]byte
	emitRecord := func(part []byte) {
		if len(part) == 0 {
			return
		}
		rec := make([]byte, 5+len(part))
		rec[0] = 0x16
		rec[1] = versionMajor
		rec[2] = versionMinor
		binary.BigEndian.PutUint16(rec[3:5], uint16(len(part)))
		copy(rec[5:], part)
		chunks = append(chunks, rec)
	}

	if sniStart > 0 && sniEnd > sniStart+2 && sniEnd <= len(handshake) {
		// Split 1: first 2 bytes of ClientHello
		firstCut := 2
		if firstCut > sniStart {
			firstCut = sniStart
		}
		emitRecord(handshake[:firstCut])

		// Split 2: up to the middle of the SNI hostname
		midSNI := sniStart + (sniEnd-sniStart)/2
		for off := firstCut; off < midSNI; off += maxFragPayload {
			end := off + maxFragPayload
			if end > midSNI {
				end = midSNI
			}
			emitRecord(handshake[off:end])
		}

		// Split 3: second half of SNI into tiny pieces (3 bytes) to defeat deep SNI matchers
		for off := midSNI; off < sniEnd; off += 3 {
			end := off + 3
			if end > sniEnd {
				end = sniEnd
			}
			emitRecord(handshake[off:end])
		}

		// Split 4: remainder of ClientHello
		for off := sniEnd; off < len(handshake); off += maxFragPayload * 2 {
			end := off + maxFragPayload*2
			if end > len(handshake) {
				end = len(handshake)
			}
			emitRecord(handshake[off:end])
		}
	} else {
		// Generic multi-record split when no SNI or ECH is used
		for off := 0; off < len(handshake); off += maxFragPayload {
			end := off + maxFragPayload
			if end > len(handshake) {
				end = len(handshake)
			}
			emitRecord(handshake[off:end])
		}
	}

	// Append any trailing TLS records that came in the same write call
	if 5+recLen < len(data) {
		tail := make([]byte, len(data)-(5+recLen))
		copy(tail, data[5+recLen:])
		chunks = append(chunks, tail)
	}

	return chunks
}

// findSNIBoundsInHandshake returns [start, end) byte offsets of the SNI host_name string
// inside a TLS ClientHello handshake message (starting at HandshakeType=0x01).
func findSNIBoundsInHandshake(hs []byte) (int, int) {
	// Handshake header: 1 byte type + 3 bytes length + 2 bytes client_version + 32 bytes random = 38
	if len(hs) < 42 || hs[0] != 0x01 {
		return -1, -1
	}
	pos := 38
	// Session ID
	sidLen := int(hs[pos])
	pos += 1 + sidLen
	if pos+2 > len(hs) {
		return -1, -1
	}
	// Cipher Suites
	csLen := int(binary.BigEndian.Uint16(hs[pos : pos+2]))
	pos += 2 + csLen
	if pos+1 > len(hs) {
		return -1, -1
	}
	// Compression Methods
	compLen := int(hs[pos])
	pos += 1 + compLen
	if pos+2 > len(hs) {
		return -1, -1
	}
	// Extensions total length
	extTotalLen := int(binary.BigEndian.Uint16(hs[pos : pos+2]))
	pos += 2
	endExts := pos + extTotalLen
	if endExts > len(hs) {
		endExts = len(hs)
	}

	for pos+4 <= endExts {
		extType := binary.BigEndian.Uint16(hs[pos : pos+2])
		extLen := int(binary.BigEndian.Uint16(hs[pos+2 : pos+4]))
		pos += 4
		if pos+extLen > endExts {
			break
		}
		if extType == 0x0000 && extLen >= 5 {
			// ServerNameList length (2 bytes), NameType (1 byte == 0x00), HostName length (2 bytes)
			nameType := hs[pos+2]
			nameLen := int(binary.BigEndian.Uint16(hs[pos+3 : pos+5]))
			nameStart := pos + 5
			nameEnd := nameStart + nameLen
			if nameType == 0x00 && nameLen > 0 && nameEnd <= pos+extLen {
				return nameStart, nameEnd
			}
		}
		pos += extLen
	}
	return -1, -1
}

// FragmentingConn wraps a net.Conn so the very first outbound TLS ClientHello or HTTP Host
// request is fragmented at the TLS record & TCP segment layers with TCP_NODELAY enabled.
type FragmentingConn struct {
	net.Conn
	firstWrite atomic.Bool
	fragSize   int
	fragDelay  time.Duration
}

func NewFragmentingConn(conn net.Conn, fragSize int, fragDelay time.Duration) *FragmentingConn {
	if tcpConn, ok := conn.(*net.TCPConn); ok {
		_ = tcpConn.SetNoDelay(true)
	}
	fc := &FragmentingConn{
		Conn:      conn,
		fragSize:  fragSize,
		fragDelay: fragDelay,
	}
	fc.firstWrite.Store(true)
	return fc
}

func (fc *FragmentingConn) Write(b []byte) (int, error) {
	if fc.firstWrite.CompareAndSwap(true, false) {
		// Case 1: TLS ClientHello -> Multi-record TLS fragmentation + TCP segment flush
		if records := FragmentTLSClientHello(b, fc.fragSize); len(records) > 1 {
			for i, rec := range records {
				if _, err := fc.Conn.Write(rec); err != nil {
					return 0, err
				}
				if i < len(records)-1 && fc.fragDelay > 0 {
					time.Sleep(fc.fragDelay)
				}
			}
			return len(b), nil
		}

		// Case 2: Plain HTTP request -> Split inside "Host: " header & mix header casing
		if looksLikePlainHTTP(b) {
			chunks := splitHTTPRequestAtHost(b)
			if len(chunks) > 1 {
				for i, c := range chunks {
					if _, err := fc.Conn.Write(c); err != nil {
						return 0, err
					}
					if i == 0 && fc.fragDelay > 0 {
						time.Sleep(fc.fragDelay)
					}
				}
				return len(b), nil
			}
		}
	}
	return fc.Conn.Write(b)
}

func looksLikePlainHTTP(b []byte) bool {
	if len(b) < 16 {
		return false
	}
	prefixes := []string{"GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH "}
	head := string(b[:8])
	for _, p := range prefixes {
		if strings.HasPrefix(head, p) {
			return true
		}
	}
	return false
}

func splitHTTPRequestAtHost(b []byte) [][]byte {
	lower := bytes.ToLower(b)
	idx := bytes.Index(lower, []byte("\r\nhost:"))
	if idx < 0 {
		return nil
	}
	// Mix case of Host header ("hOsT:") to bypass naive DPI signatures while staying 100% HTTP/1.1 valid
	patched := make([]byte, len(b))
	copy(patched, b)
	copy(patched[idx+2:idx+7], []byte("hOsT:"))

	valStart := idx + 7
	for valStart < len(patched) && patched[valStart] == ' ' {
		valStart++
	}
	splitAt := valStart + 2
	if splitAt >= len(patched) {
		return nil
	}
	return [][]byte{patched[:splitAt], patched[splitAt:]}
}
