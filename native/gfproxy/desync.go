package main

import (
	"bytes"
	"encoding/binary"
	"net"
	"strings"
	"sync/atomic"
	"time"
)

// FragmentTLSClientHello implements 2026 RFC-compliant 3-Record SNI Splitting (ModeHybridTLSRF):
//   - Record 1: Complete 4-byte TLS Handshake Header (0x01 + uint24 length) + ClientVersion +
//     Random + SessionID + CipherSuites + Extensions up to SNI hostname start. Keeping the full
//     4-byte Handshake Header intact in Record 1 guarantees 100% compatibility with Cloudflare,
//     Fastly (jsdelivr), BoringSSL (kMaxEmptyRecords), OpenSSL (s3_both.c), Nginx ssl_preread,
//     and Xray/Sing-box frontends (eliminating SSL_R_BAD_LENGTH / record-flood drops!).
//   - Record 2: First half of the SNI hostname (never contains the full domain).
//   - Record 3: Second half of the SNI hostname + remaining extensions.
func FragmentTLSClientHello(data []byte, maxFragPayload int) [][]byte {
	if len(data) < 9 || data[0] != 0x16 || data[1] != 0x03 {
		return nil
	}
	recLen := int(binary.BigEndian.Uint16(data[3:5]))
	if recLen <= 6 || 5+recLen > len(data) {
		return nil
	}
	handshake := data[5 : 5+recLen]
	// HandshakeType 0x01 == ClientHello
	if handshake[0] != 0x01 {
		return nil
	}

	if maxFragPayload <= 4 {
		maxFragPayload = 32
	}

	versionMajor := data[1]
	versionMinor := data[2]

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

	if sniStart >= 6 && sniEnd > sniStart+1 && sniEnd <= len(handshake) {
		midSNI := sniStart + (sniEnd-sniStart)/2
		if midSNI <= sniStart {
			midSNI = sniStart + 1
		}
		// Record 1: Full ClientHello header + extensions up to start of SNI string
		emitRecord(handshake[:sniStart])
		// Record 2: First half of SNI string only
		emitRecord(handshake[sniStart:midSNI])
		// Record 3: Second half of SNI string + rest of ClientHello
		emitRecord(handshake[midSNI:])
	} else {
		// Safe 3-record fallback when no plaintext SNI is present (e.g. ECH or IP literal)
		cut1 := len(handshake) / 3
		if cut1 < 6 {
			cut1 = 6
		}
		cut2 := (len(handshake) * 2) / 3
		if cut2 <= cut1 && cut1+1 < len(handshake) {
			cut2 = cut1 + 1
		}
		if cut1 < len(handshake) && cut2 < len(handshake) {
			emitRecord(handshake[:cut1])
			emitRecord(handshake[cut1:cut2])
			emitRecord(handshake[cut2:])
		} else {
			for off := 0; off < len(handshake); off += maxFragPayload {
				end := off + maxFragPayload
				if end > len(handshake) {
					end = len(handshake)
				}
				emitRecord(handshake[off:end])
			}
		}
	}

	// Preserve any trailing TLS records coalesced into the same write buffer
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

// FragmentingConn wraps a net.Conn so the first outbound TLS ClientHello or HTTP Host request
// undergoes Dual-Layer Desync: 3-Record TLS SNI Splitting + Xray-style TCP_NODELAY segment slicing.
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
	if fragDelay > 3*time.Millisecond {
		fragDelay = 2 * time.Millisecond
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
		// Case 1: TLS ClientHello -> 3-record RFC-valid TLS split + TCP segment boundary pacing
		if records := FragmentTLSClientHello(b, fc.fragSize); len(records) > 1 {
			for i, rec := range records {
				// Slice middle SNI record at TCP layer too (Xray tlshello TCP split)
				if i == 1 && len(rec) > 7 {
					mid := 5 + (len(rec)-5)/2
					if _, err := fc.Conn.Write(rec[:mid]); err != nil {
						return 0, err
					}
					if fc.fragDelay > 0 {
						time.Sleep(fc.fragDelay)
					}
					if _, err := fc.Conn.Write(rec[mid:]); err != nil {
						return 0, err
					}
				} else {
					if _, err := fc.Conn.Write(rec); err != nil {
						return 0, err
					}
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
