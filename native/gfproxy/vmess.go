package main

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"hash"
	"hash/crc32"
	"hash/fnv"
	"io"
	"net"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/sha3"
)

const (
	vmessMagicUUIDSuffix = "c48619fe-8f02-49e0-b9e9-edf763e17e21"

	kdfSaltVMessAEADKDF              = "VMess AEAD KDF"
	kdfSaltAuthIDEncryptionKey       = "AES Auth ID Encryption"
	kdfSaltVMessHeaderPayloadLenKey  = "VMess Header AEAD Key_Length"
	kdfSaltVMessHeaderPayloadLenIV   = "VMess Header AEAD Nonce_Length"
	kdfSaltVMessHeaderPayloadKey     = "VMess Header AEAD Key"
	kdfSaltVMessHeaderPayloadIV      = "VMess Header AEAD Nonce"
	kdfSaltAEADRespHeaderLenKey      = "AEAD Resp Header Len Key"
	kdfSaltAEADRespHeaderLenIV       = "AEAD Resp Header Len IV"
	kdfSaltAEADRespHeaderPayloadKey  = "AEAD Resp Header Key"
	kdfSaltAEADRespHeaderPayloadIV   = "AEAD Resp Header IV"

	vmessSecAES128GCM        byte = 3
	vmessSecChaCha20Poly1305 byte = 4

	vmessOptChunkStream  byte = 0x01
	vmessOptChunkMasking byte = 0x04

	vmessMaxChunkPayload = 8192
)

type vmessHashCreator struct {
	parent *vmessHashCreator
	value  []byte
}

func (h *vmessHashCreator) Create() hash.Hash {
	if h.parent == nil {
		return hmac.New(sha256.New, h.value)
	}
	return hmac.New(h.parent.Create, h.value)
}

func vmessKDF(key []byte, path ...string) []byte {
	creator := &vmessHashCreator{value: []byte(kdfSaltVMessAEADKDF)}
	for _, p := range path {
		creator = &vmessHashCreator{parent: creator, value: []byte(p)}
	}
	mac := creator.Create()
	mac.Write(key)
	return mac.Sum(nil)
}

func vmessKDF16(key []byte, path ...string) []byte {
	return vmessKDF(key, path...)[:16]
}

func vmessCmdKey(uuidBytes [16]byte) [16]byte {
	h := md5.New()
	h.Write(uuidBytes[:])
	h.Write([]byte(vmessMagicUUIDSuffix))
	var out [16]byte
	copy(out[:], h.Sum(nil))
	return out
}

func createVMessAuthID(cmdKey [16]byte, ts int64) ([16]byte, error) {
	var plain [16]byte
	binary.BigEndian.PutUint64(plain[0:8], uint64(ts))
	if _, err := io.ReadFull(rand.Reader, plain[8:12]); err != nil {
		return [16]byte{}, err
	}
	crc := crc32.ChecksumIEEE(plain[0:12])
	binary.BigEndian.PutUint32(plain[12:16], crc)

	blk, err := aes.NewCipher(vmessKDF16(cmdKey[:], kdfSaltAuthIDEncryptionKey))
	if err != nil {
		return [16]byte{}, err
	}
	var out [16]byte
	blk.Encrypt(out[:], plain[:])
	return out, nil
}

func sealVMessAEADHeader(cmdKey [16]byte, headerPlain []byte) ([]byte, error) {
	authID, err := createVMessAuthID(cmdKey, time.Now().Unix())
	if err != nil {
		return nil, err
	}
	var connNonce [8]byte
	if _, err := io.ReadFull(rand.Reader, connNonce[:]); err != nil {
		return nil, err
	}

	var lenBytes [2]byte
	binary.BigEndian.PutUint16(lenBytes[:], uint16(len(headerPlain)))

	lenKey := vmessKDF16(cmdKey[:], kdfSaltVMessHeaderPayloadLenKey, string(authID[:]), string(connNonce[:]))
	lenIV := vmessKDF(cmdKey[:], kdfSaltVMessHeaderPayloadLenIV, string(authID[:]), string(connNonce[:]))[:12]
	lenBlk, err := aes.NewCipher(lenKey)
	if err != nil {
		return nil, err
	}
	lenGCM, err := cipher.NewGCM(lenBlk)
	if err != nil {
		return nil, err
	}
	encLen := lenGCM.Seal(nil, lenIV, lenBytes[:], authID[:])

	hdrKey := vmessKDF16(cmdKey[:], kdfSaltVMessHeaderPayloadKey, string(authID[:]), string(connNonce[:]))
	hdrIV := vmessKDF(cmdKey[:], kdfSaltVMessHeaderPayloadIV, string(authID[:]), string(connNonce[:]))[:12]
	hdrBlk, err := aes.NewCipher(hdrKey)
	if err != nil {
		return nil, err
	}
	hdrGCM, err := cipher.NewGCM(hdrBlk)
	if err != nil {
		return nil, err
	}
	encHdr := hdrGCM.Seal(nil, hdrIV, headerPlain, authID[:])

	var out bytes.Buffer
	out.Grow(16 + len(encLen) + 8 + len(encHdr))
	out.Write(authID[:])
	out.Write(encLen)
	out.Write(connNonce[:])
	out.Write(encHdr)
	return out.Bytes(), nil
}

func deriveVMessChaChaKey(key16 []byte) []byte {
	out := make([]byte, 32)
	h1 := md5.Sum(key16)
	copy(out[0:16], h1[:])
	h2 := md5.Sum(out[0:16])
	copy(out[16:32], h2[:])
	return out
}

type vmessShakeMask struct {
	shake sha3.ShakeHash
	buf   [2]byte
}

func newVMessShakeMask(iv []byte) *vmessShakeMask {
	sh := sha3.NewShake128()
	_, _ = sh.Write(iv)
	return &vmessShakeMask{shake: sh}
}

func (m *vmessShakeMask) NextMask() uint16 {
	_, _ = m.shake.Read(m.buf[:])
	return binary.BigEndian.Uint16(m.buf[:])
}

type vmessConn struct {
	net.Conn
	sealedReqHdr []byte
	respAuthByte byte

	txAEAD  cipher.AEAD
	txIV    [16]byte
	txCount uint16
	txMask  *vmessShakeMask
	wrote   bool
	writeMu sync.Mutex

	rxAEAD    cipher.AEAD
	rxKey     [16]byte
	rxIV      [16]byte
	rxCount   uint16
	rxMask    *vmessShakeMask
	readHdrOk bool
	rxPending []byte
	readMu    sync.Mutex
}

func dialVMess(ctx context.Context, doh *DoHResolver, node OutboundNode, targetHost, targetPort string) (net.Conn, error) {
	uuidBytes, ok := parseUUIDBytes(node.UUID)
	if !ok {
		return nil, errors.New("invalid VMess UUID")
	}
	portNum, err := strconv.Atoi(targetPort)
	if err != nil || portNum <= 0 || portNum > 65535 {
		return nil, errors.New("invalid target port")
	}

	carrier, err := dialNodeCarrier(ctx, doh, node)
	if err != nil {
		return nil, err
	}

	var reqKey [16]byte
	var reqIV [16]byte
	var randSeed [33]byte
	if _, err := io.ReadFull(rand.Reader, randSeed[:]); err != nil {
		_ = carrier.Close()
		return nil, err
	}
	copy(reqKey[:], randSeed[0:16])
	copy(reqIV[:], randSeed[16:32])
	respAuth := randSeed[32]

	secByte := vmessSecAES128GCM
	if strings.Contains(strings.ToLower(node.Cipher), "chacha20") {
		secByte = vmessSecChaCha20Poly1305
	}

	padLen := int(randSeed[0] & 0x07) // 0..7 random header padding
	optByte := vmessOptChunkStream | vmessOptChunkMasking

	var hdr bytes.Buffer
	hdr.WriteByte(0x01) // VMess v1
	hdr.Write(reqIV[:])
	hdr.Write(reqKey[:])
	hdr.WriteByte(respAuth)
	hdr.WriteByte(optByte)
	hdr.WriteByte(byte(padLen<<4) | secByte)
	hdr.WriteByte(0x00) // reserved
	hdr.WriteByte(0x01) // TCP command

	// VMess Port-Then-Address format
	hdr.WriteByte(byte(portNum >> 8))
	hdr.WriteByte(byte(portNum & 0xFF))
	if ip := net.ParseIP(targetHost); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			hdr.WriteByte(0x01)
			hdr.Write(ip4)
		} else {
			hdr.WriteByte(0x03)
			hdr.Write(ip.To16())
		}
	} else {
		if len(targetHost) > 255 {
			_ = carrier.Close()
			return nil, errors.New("target hostname too long")
		}
		hdr.WriteByte(0x02)
		hdr.WriteByte(byte(len(targetHost)))
		hdr.WriteString(targetHost)
	}

	if padLen > 0 {
		pad := make([]byte, padLen)
		_, _ = io.ReadFull(rand.Reader, pad)
		hdr.Write(pad)
	}

	fnvHash := fnv.New32a()
	_, _ = fnvHash.Write(hdr.Bytes())
	var sumBuf [4]byte
	binary.BigEndian.PutUint32(sumBuf[:], fnvHash.Sum32())
	hdr.Write(sumBuf[:])

	cmdKey := vmessCmdKey(uuidBytes)
	sealedHdr, err := sealVMessAEADHeader(cmdKey, hdr.Bytes())
	if err != nil {
		_ = carrier.Close()
		return nil, err
	}

	respKeySum := sha256.Sum256(reqKey[:])
	respIVSum := sha256.Sum256(reqIV[:])
	var respKey, respIV [16]byte
	copy(respKey[:], respKeySum[:16])
	copy(respIV[:], respIVSum[:16])

	txCipher, err := buildVMessBodyAEAD(secByte, reqKey[:])
	if err != nil {
		_ = carrier.Close()
		return nil, err
	}
	rxCipher, err := buildVMessBodyAEAD(secByte, respKey[:])
	if err != nil {
		_ = carrier.Close()
		return nil, err
	}

	return &vmessConn{
		Conn:         carrier,
		sealedReqHdr: sealedHdr,
		respAuthByte: respAuth,
		txAEAD:       txCipher,
		txIV:         reqIV,
		txMask:       newVMessShakeMask(reqIV[:]),
		rxAEAD:       rxCipher,
		rxKey:        respKey,
		rxIV:         respIV,
		rxMask:       newVMessShakeMask(respIV[:]),
	}, nil
}

func buildVMessBodyAEAD(secByte byte, key16 []byte) (cipher.AEAD, error) {
	if secByte == vmessSecChaCha20Poly1305 {
		return chacha20poly1305.New(deriveVMessChaChaKey(key16))
	}
	blk, err := aes.NewCipher(key16)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(blk)
}

func makeVMessChunkNonce(iv [16]byte, count uint16) [12]byte {
	var nonce [12]byte
	copy(nonce[:], iv[:12])
	binary.BigEndian.PutUint16(nonce[0:2], count)
	return nonce
}

func (c *vmessConn) Write(b []byte) (int, error) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()

	var out bytes.Buffer
	if !c.wrote {
		c.wrote = true
		out.Write(c.sealedReqHdr)
	}

	for off := 0; off < len(b); off += vmessMaxChunkPayload {
		end := off + vmessMaxChunkPayload
		if end > len(b) {
			end = len(b)
		}
		chunk := b[off:end]
		nonce := makeVMessChunkNonce(c.txIV, c.txCount)
		c.txCount++

		sealed := c.txAEAD.Seal(nil, nonce[:], chunk, nil)
		maskedLen := uint16(len(sealed)) ^ c.txMask.NextMask()
		var sizeBytes [2]byte
		binary.BigEndian.PutUint16(sizeBytes[:], maskedLen)
		out.Write(sizeBytes[:])
		out.Write(sealed)
	}

	if _, err := c.Conn.Write(out.Bytes()); err != nil {
		return 0, err
	}
	return len(b), nil
}

func (c *vmessConn) Read(b []byte) (int, error) {
	c.readMu.Lock()
	defer c.readMu.Unlock()

	if !c.readHdrOk {
		if err := c.readAndVerifyResponseHeader(); err != nil {
			return 0, err
		}
		c.readHdrOk = true
	}

	for len(c.rxPending) == 0 {
		var sizeBytes [2]byte
		if _, err := io.ReadFull(c.Conn, sizeBytes[:]); err != nil {
			return 0, err
		}
		chunkSize := int(binary.BigEndian.Uint16(sizeBytes[:]) ^ c.rxMask.NextMask())
		tagOverhead := c.rxAEAD.Overhead()
		if chunkSize < tagOverhead || chunkSize > 65535 {
			return 0, errors.New("invalid VMess AEAD chunk length")
		}

		encChunk := make([]byte, chunkSize)
		if _, err := io.ReadFull(c.Conn, encChunk); err != nil {
			return 0, err
		}

		nonce := makeVMessChunkNonce(c.rxIV, c.rxCount)
		c.rxCount++
		plain, err := c.rxAEAD.Open(nil, nonce[:], encChunk, nil)
		if err != nil {
			return 0, err
		}
		if len(plain) == 0 {
			return 0, io.EOF
		}
		c.rxPending = plain
	}

	n := copy(b, c.rxPending)
	c.rxPending = c.rxPending[n:]
	return n, nil
}

func (c *vmessConn) readAndVerifyResponseHeader() error {
	lenKey := vmessKDF16(c.rxKey[:], kdfSaltAEADRespHeaderLenKey)
	lenIV := vmessKDF(c.rxIV[:], kdfSaltAEADRespHeaderLenIV)[:12]
	lenBlk, err := aes.NewCipher(lenKey)
	if err != nil {
		return err
	}
	lenGCM, err := cipher.NewGCM(lenBlk)
	if err != nil {
		return err
	}

	var encLen [18]byte
	if _, err := io.ReadFull(c.Conn, encLen[:]); err != nil {
		return err
	}
	decLenBytes, err := lenGCM.Open(nil, lenIV, encLen[:], nil)
	if err != nil {
		return err
	}
	hdrLen := int(binary.BigEndian.Uint16(decLenBytes))
	if hdrLen < 4 || hdrLen > 2048 {
		return errors.New("unexpected VMess response header size")
	}

	hdrKey := vmessKDF16(c.rxKey[:], kdfSaltAEADRespHeaderPayloadKey)
	hdrIV := vmessKDF(c.rxIV[:], kdfSaltAEADRespHeaderPayloadIV)[:12]
	hdrBlk, err := aes.NewCipher(hdrKey)
	if err != nil {
		return err
	}
	hdrGCM, err := cipher.NewGCM(hdrBlk)
	if err != nil {
		return err
	}

	encHdr := make([]byte, hdrLen+16)
	if _, err := io.ReadFull(c.Conn, encHdr); err != nil {
		return err
	}
	decHdr, err := hdrGCM.Open(nil, hdrIV, encHdr, nil)
	if err != nil {
		return err
	}
	if len(decHdr) < 4 || decHdr[0] != c.respAuthByte {
		return errors.New("VMess response auth byte mismatch")
	}
	return nil
}

// ============================================================================
// Flexible VMess Share Link Parser (V2RayN JSON + Standard URI + Shadowrocket)
// ============================================================================

func parseVMessLink(raw string) (OutboundNode, bool) {
	body := strings.TrimSpace(raw[len("vmess://"):])
	if body == "" {
		return OutboundNode{}, false
	}

	// Dialect 1: Standard V2RayN Base64-JSON ("vmess://eyJ2IjoiMiIs...")
	mainPart, rawQuery, urlTag := splitTagAndQuery(body)
	if !strings.Contains(mainPart, "@") {
		if decoded, ok := decodeFlexibleBase64(mainPart); ok {
			decTrim := strings.TrimSpace(decoded)
			if strings.HasPrefix(decTrim, "{") {
				return parseVMessJSON(raw, decTrim, urlTag)
			}
			// Dialect 2: Shadowrocket Base64 ("cipher:uuid@host:port") + optional ?remarks=&obfs=websocket&path=&tls=1
			if strings.Contains(decTrim, "@") {
				return parseVMessShadowrocket(raw, decTrim, rawQuery, urlTag)
			}
		}
	}

	// Dialect 3: Xray / V2Fly URL format ("vmess://uuid@host:port?type=ws&security=tls&sni=...#tag")
	at := strings.LastIndexByte(mainPart, '@')
	if at <= 0 {
		return OutboundNode{}, false
	}
	userPart := strings.TrimSpace(mainPart[:at])
	cipherName := "aes-128-gcm"
	uuidStr := userPart
	if colon := strings.IndexByte(userPart, ':'); colon > 0 {
		cipherName = strings.ToLower(strings.TrimSpace(userPart[:colon]))
		uuidStr = strings.TrimSpace(userPart[colon+1:])
	}
	if _, ok := parseUUIDBytes(uuidStr); !ok {
		return OutboundNode{}, false
	}
	host, port, ok := splitHostPortClean(mainPart[at+1:])
	if !ok {
		return OutboundNode{}, false
	}

	q, _ := url.ParseQuery(rawQuery)
	netType := strings.ToLower(strings.TrimSpace(q.Get("type")))
	if netType == "" || netType == "raw" {
		netType = "tcp"
	}
	if netType != "tcp" && netType != "ws" {
		return OutboundNode{}, false
	}
	sec := strings.ToLower(strings.TrimSpace(q.Get("security")))
	if sec == "reality" {
		return OutboundNode{}, false
	}
	useTLS := sec == "tls" || sec == "ssl" || q.Get("tls") == "1"
	sni := firstNonEmpty(strings.TrimSpace(q.Get("sni")), strings.TrimSpace(q.Get("peer")), strings.TrimSpace(q.Get("host")))
	if sni == "" && net.ParseIP(host) == nil {
		sni = host
	}
	wsHost := firstNonEmpty(strings.TrimSpace(q.Get("host")), sni, host)
	wsPath := firstNonEmpty(q.Get("path"), "/")
	if enc := strings.ToLower(strings.TrimSpace(q.Get("encryption"))); strings.Contains(enc, "chacha20") {
		cipherName = "chacha20-poly1305"
	}
	insecure := q.Get("allowInsecure") == "1" || strings.EqualFold(q.Get("allowInsecure"), "true")
	if urlTag == "" {
		urlTag = fmt.Sprintf("VMess-%s:%s", host, port)
	}
	return OutboundNode{
		ID:            fmt.Sprintf("vmess:%s:%s:%s:%s:%s", host, port, uuidStr, netType, sni),
		Name:          urlTag,
		RawURI:        raw,
		Protocol:      ProtoVMess,
		Host:          host,
		Port:          port,
		UUID:          uuidStr,
		Cipher:        cipherName,
		Transport:     netType,
		TLS:           useTLS,
		SNI:           sni,
		WSHost:        wsHost,
		WSPath:        wsPath,
		AllowInsecure: insecure,
	}, true
}

func parseVMessJSON(raw, jsonText, fallbackTag string) (OutboundNode, bool) {
	var m map[string]any
	if err := json.Unmarshal([]byte(jsonText), &m); err != nil {
		return OutboundNode{}, false
	}
	str := func(keys ...string) string {
		for _, k := range keys {
			if v, ok := m[k]; ok && v != nil {
				switch tv := v.(type) {
				case string:
					if s := strings.TrimSpace(tv); s != "" {
						return s
					}
				case float64:
					return strconv.Itoa(int(tv))
				case json.Number:
					return tv.String()
				}
			}
		}
		return ""
	}

	host := str("add", "address", "host_addr")
	port := extractLeadingDigits(str("port"))
	uuidStr := str("id", "uuid")
	if host == "" || !validPort(port) {
		return OutboundNode{}, false
	}
	if _, ok := parseUUIDBytes(uuidStr); !ok {
		return OutboundNode{}, false
	}

	netType := strings.ToLower(str("net", "network"))
	if netType == "" || netType == "raw" {
		netType = "tcp"
	}
	// Reject HTTP header-obfs over raw TCP ("type": "http") unless ws
	hdrType := strings.ToLower(str("type"))
	if netType == "tcp" && hdrType != "" && hdrType != "none" {
		return OutboundNode{}, false
	}
	if netType != "tcp" && netType != "ws" {
		return OutboundNode{}, false
	}

	tlsField := strings.ToLower(str("tls", "security"))
	if tlsField == "reality" {
		return OutboundNode{}, false
	}
	useTLS := tlsField == "tls" || tlsField == "ssl" || tlsField == "1" || tlsField == "true"

	wsHost := str("host")
	if strings.Contains(wsHost, ",") {
		wsHost = strings.TrimSpace(strings.Split(wsHost, ",")[0])
	}
	sni := firstNonEmpty(str("sni", "peer"), wsHost)
	if sni == "" && net.ParseIP(host) == nil {
		sni = host
	}
	if wsHost == "" {
		wsHost = firstNonEmpty(sni, host)
	}
	wsPath := firstNonEmpty(str("path"), "/")

	scy := strings.ToLower(str("scy", "security_cipher", "cipher"))
	cipherName := "aes-128-gcm"
	if strings.Contains(scy, "chacha20") {
		cipherName = "chacha20-poly1305"
	}

	insecureStr := strings.ToLower(str("allowInsecure", "insecure", "skip-cert-verify"))
	insecure := insecureStr == "1" || insecureStr == "true"

	tag := firstNonEmpty(fallbackTag, str("ps", "remarks", "name"), fmt.Sprintf("VMess-%s:%s", host, port))
	return OutboundNode{
		ID:            fmt.Sprintf("vmess:%s:%s:%s:%s:%s", host, port, uuidStr, netType, sni),
		Name:          tag,
		RawURI:        raw,
		Protocol:      ProtoVMess,
		Host:          host,
		Port:          port,
		UUID:          uuidStr,
		Cipher:        cipherName,
		Transport:     netType,
		TLS:           useTLS,
		SNI:           sni,
		WSHost:        wsHost,
		WSPath:        wsPath,
		AllowInsecure: insecure,
	}, true
}

func parseVMessShadowrocket(raw, decodedMain, rawQuery, urlTag string) (OutboundNode, bool) {
	at := strings.LastIndexByte(decodedMain, '@')
	if at <= 0 {
		return OutboundNode{}, false
	}
	left := decodedMain[:at]
	host, port, ok := splitHostPortClean(decodedMain[at+1:])
	if !ok {
		return OutboundNode{}, false
	}
	uuidStr := left
	cipherName := "aes-128-gcm"
	if colon := strings.IndexByte(left, ':'); colon > 0 {
		if strings.Contains(strings.ToLower(left[:colon]), "chacha20") {
			cipherName = "chacha20-poly1305"
		}
		uuidStr = left[colon+1:]
	}
	if _, ok := parseUUIDBytes(uuidStr); !ok {
		return OutboundNode{}, false
	}
	q, _ := url.ParseQuery(rawQuery)
	obfs := strings.ToLower(q.Get("obfs"))
	netType := "tcp"
	if obfs == "websocket" || obfs == "ws" {
		netType = "ws"
	} else if obfs != "" && obfs != "none" {
		return OutboundNode{}, false
	}
	useTLS := q.Get("tls") == "1" || strings.EqualFold(q.Get("tls"), "true")
	sni := firstNonEmpty(strings.TrimSpace(q.Get("peer")), strings.TrimSpace(q.Get("sni")), strings.TrimSpace(q.Get("obfsParam")))
	if sni == "" && net.ParseIP(host) == nil {
		sni = host
	}
	wsHost := firstNonEmpty(strings.TrimSpace(q.Get("obfsParam")), sni, host)
	wsPath := firstNonEmpty(q.Get("path"), "/")
	tag := firstNonEmpty(urlTag, strings.TrimSpace(q.Get("remarks")), fmt.Sprintf("VMess-%s:%s", host, port))
	return OutboundNode{
		ID:            fmt.Sprintf("vmess:%s:%s:%s:%s:%s", host, port, uuidStr, netType, sni),
		Name:          tag,
		RawURI:        raw,
		Protocol:      ProtoVMess,
		Host:          host,
		Port:          port,
		UUID:          uuidStr,
		Cipher:        cipherName,
		Transport:     netType,
		TLS:           useTLS,
		SNI:           sni,
		WSHost:        wsHost,
		WSPath:        wsPath,
		AllowInsecure: q.Get("allowInsecure") == "1",
	}, true
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}
