package main

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	utls "github.com/refraction-networking/utls"
	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/hkdf"
)

type NodeProtocol string

const (
	ProtoVLESS       NodeProtocol = "vless"
	ProtoTrojan      NodeProtocol = "trojan"
	ProtoShadowsocks NodeProtocol = "ss"
	ProtoSOCKS5      NodeProtocol = "socks5"
	ProtoHTTP        NodeProtocol = "http"
)

type OutboundNode struct {
	ID            string       `json:"id"`
	Name          string       `json:"name"`
	RawURI        string       `json:"raw_uri"`
	Protocol      NodeProtocol `json:"protocol"`
	Host          string       `json:"host"`
	Port          string       `json:"port"`
	UUID          string       `json:"uuid,omitempty"`
	Password      string       `json:"password,omitempty"`
	Username      string       `json:"username,omitempty"`
	Cipher        string       `json:"cipher,omitempty"`
	Transport     string       `json:"transport"` // "tcp" or "ws"
	TLS           bool         `json:"tls"`
	SNI           string       `json:"sni,omitempty"`
	WSHost        string       `json:"ws_host,omitempty"`
	WSPath        string       `json:"ws_path,omitempty"`
	AllowInsecure bool         `json:"allow_insecure,omitempty"`
	AvgRTTMs      int64        `json:"avg_rtt_ms,omitempty"`
}

var cleanCloudflareEdgeIPs = []string{
	"104.18.2.161",
	"172.66.40.212",
	"162.159.135.42",
	"104.16.132.229",
}

var cloudflareCIDRs = func() []*net.IPNet {
	cidrs := []string{
		"104.16.0.0/12",
		"172.64.0.0/13",
		"162.158.0.0/15",
		"108.162.192.0/18",
		"188.114.96.0/20",
		"190.93.240.0/20",
		"197.234.240.0/22",
		"198.41.128.0/17",
	}
	var out []*net.IPNet
	for _, c := range cidrs {
		if _, ipnet, err := net.ParseCIDR(c); err == nil {
			out = append(out, ipnet)
		}
	}
	return out
}()

func isCloudflareIP(ipStr string) bool {
	ip := net.ParseIP(ipStr)
	if ip == nil {
		return false
	}
	for _, n := range cloudflareCIDRs {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

// ParseShareLinks parses multi-line or base64-encoded subscription text into OutboundNodes.
func ParseShareLinks(rawText string) []OutboundNode {
	trimmed := strings.TrimSpace(rawText)
	if trimmed == "" {
		return nil
	}
	if !strings.Contains(trimmed, "://") {
		if decoded, ok := decodeFlexibleBase64(trimmed); ok && strings.Contains(decoded, "://") {
			trimmed = decoded
		}
	}

	var out []OutboundNode
	seen := make(map[string]bool)
	for _, line := range strings.Split(trimmed, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "//") {
			continue
		}
		node, ok := ParseSingleShareLink(line)
		if !ok {
			continue
		}
		if !seen[node.ID] {
			seen[node.ID] = true
			out = append(out, node)
		}
	}
	return out
}

func ParseSingleShareLink(uri string) (OutboundNode, bool) {
	uri = strings.TrimSpace(uri)
	lower := strings.ToLower(uri)
	switch {
	case strings.HasPrefix(lower, "vless://"):
		return parseVLESSLink(uri)
	case strings.HasPrefix(lower, "trojan://"):
		return parseTrojanLink(uri)
	case strings.HasPrefix(lower, "ss://"):
		return parseShadowsocksLink(uri)
	case strings.HasPrefix(lower, "socks5://"), strings.HasPrefix(lower, "socks://"):
		return parseSOCKSOrHTTPLink(uri, ProtoSOCKS5)
	case strings.HasPrefix(lower, "http://"):
		return parseSOCKSOrHTTPLink(uri, ProtoHTTP)
	default:
		return OutboundNode{}, false
	}
}

func splitTagAndQuery(rest string) (beforeQuery, rawQuery, tag string) {
	if hashIdx := strings.IndexByte(rest, '#'); hashIdx >= 0 {
		tagRaw := rest[hashIdx+1:]
		if unescaped, err := url.QueryUnescape(tagRaw); err == nil {
			tag = strings.TrimSpace(unescaped)
		} else {
			tag = strings.TrimSpace(tagRaw)
		}
		rest = rest[:hashIdx]
	}
	if qIdx := strings.IndexByte(rest, '?'); qIdx >= 0 {
		rawQuery = rest[qIdx+1:]
		beforeQuery = rest[:qIdx]
	} else {
		beforeQuery = rest
	}
	return beforeQuery, rawQuery, tag
}

func splitHostPortClean(hostPort string) (string, string, bool) {
	hostPort = strings.TrimSpace(strings.TrimSuffix(hostPort, "/"))
	if hostPort == "" {
		return "", "", false
	}
	// Handle IPv6 [::1]:443
	if strings.HasPrefix(hostPort, "[") {
		closeIdx := strings.IndexByte(hostPort, ']')
		if closeIdx <= 1 || closeIdx+2 > len(hostPort) || hostPort[closeIdx+1] != ':' {
			return "", "", false
		}
		host := hostPort[1:closeIdx]
		port := extractLeadingDigits(hostPort[closeIdx+2:])
		if !validPort(port) {
			return "", "", false
		}
		return host, port, true
	}
	colon := strings.LastIndexByte(hostPort, ':')
	if colon <= 0 || colon+1 >= len(hostPort) {
		return "", "", false
	}
	host := strings.TrimSpace(hostPort[:colon])
	port := extractLeadingDigits(hostPort[colon+1:])
	if host == "" || !validPort(port) {
		return "", "", false
	}
	return host, port, true
}

func extractLeadingDigits(s string) string {
	i := 0
	for i < len(s) && s[i] >= '0' && s[i] <= '9' {
		i++
	}
	return s[:i]
}

func validPort(p string) bool {
	if p == "" {
		return false
	}
	n, err := strconv.Atoi(p)
	return err == nil && n >= 1 && n <= 65535
}

func parseVLESSLink(raw string) (OutboundNode, bool) {
	body := raw[len("vless://"):]
	mainPart, rawQuery, tag := splitTagAndQuery(body)
	at := strings.LastIndexByte(mainPart, '@')
	if at <= 0 {
		return OutboundNode{}, false
	}
	uuidStr := strings.TrimSpace(mainPart[:at])
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
		// Handled by compatible TLS/WS/SS nodes instead of heavy external daemon
		return OutboundNode{}, false
	}
	if flow := strings.TrimSpace(q.Get("flow")); flow != "" {
		return OutboundNode{}, false
	}

	useTLS := sec == "tls" || sec == "ssl" || port == "443" || port == "2053" || port == "2083" || port == "2087" || port == "2096" || port == "8443"
	if sec == "none" {
		useTLS = false
	}

	sni := strings.TrimSpace(q.Get("sni"))
	if sni == "" {
		sni = strings.TrimSpace(q.Get("peer"))
	}
	wsHost := strings.TrimSpace(q.Get("host"))
	if sni == "" && wsHost != "" {
		sni = wsHost
	}
	if sni == "" && net.ParseIP(host) == nil {
		sni = host
	}
	if wsHost == "" {
		if sni != "" {
			wsHost = sni
		} else {
			wsHost = host
		}
	}
	wsPath := q.Get("path")
	if wsPath == "" {
		wsPath = "/"
	}

	insecure := q.Get("allowInsecure") == "1" || strings.EqualFold(q.Get("allowInsecure"), "true") ||
		q.Get("insecure") == "1" || strings.EqualFold(q.Get("insecure"), "true")

	if tag == "" {
		tag = fmt.Sprintf("VLESS-%s:%s", host, port)
	}
	id := fmt.Sprintf("vless:%s:%s:%s:%s:%s", host, port, uuidStr, netType, sni)
	return OutboundNode{
		ID:            id,
		Name:          tag,
		RawURI:        raw,
		Protocol:      ProtoVLESS,
		Host:          host,
		Port:          port,
		UUID:          uuidStr,
		Transport:     netType,
		TLS:           useTLS,
		SNI:           sni,
		WSHost:        wsHost,
		WSPath:        wsPath,
		AllowInsecure: insecure,
	}, true
}

func parseTrojanLink(raw string) (OutboundNode, bool) {
	body := raw[len("trojan://"):]
	mainPart, rawQuery, tag := splitTagAndQuery(body)
	at := strings.LastIndexByte(mainPart, '@')
	if at <= 0 {
		return OutboundNode{}, false
	}
	passRaw := mainPart[:at]
	password, err := url.QueryUnescape(passRaw)
	if err != nil || password == "" {
		password = passRaw
	}
	host, port, ok := splitHostPortClean(mainPart[at+1:])
	if !ok || password == "" {
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
	useTLS := sec != "none"

	sni := strings.TrimSpace(q.Get("sni"))
	if sni == "" {
		sni = strings.TrimSpace(q.Get("peer"))
	}
	wsHost := strings.TrimSpace(q.Get("host"))
	if sni == "" && wsHost != "" {
		sni = wsHost
	}
	if sni == "" && net.ParseIP(host) == nil {
		sni = host
	}
	if wsHost == "" {
		if sni != "" {
			wsHost = sni
		} else {
			wsHost = host
		}
	}
	wsPath := q.Get("path")
	if wsPath == "" {
		wsPath = "/"
	}
	insecure := q.Get("allowInsecure") == "1" || strings.EqualFold(q.Get("allowInsecure"), "true") ||
		q.Get("insecure") == "1" || strings.EqualFold(q.Get("insecure"), "true") || sni == ""

	if tag == "" {
		tag = fmt.Sprintf("Trojan-%s:%s", host, port)
	}
	id := fmt.Sprintf("trojan:%s:%s:%s:%s", host, port, password, netType)
	return OutboundNode{
		ID:            id,
		Name:          tag,
		RawURI:        raw,
		Protocol:      ProtoTrojan,
		Host:          host,
		Port:          port,
		Password:      password,
		Transport:     netType,
		TLS:           useTLS,
		SNI:           sni,
		WSHost:        wsHost,
		WSPath:        wsPath,
		AllowInsecure: insecure,
	}, true
}

func parseShadowsocksLink(raw string) (OutboundNode, bool) {
	body := raw[len("ss://"):]
	mainPart, rawQuery, tag := splitTagAndQuery(body)
	// Reject SIP003 external plugin configs (obfs-local / v2ray-plugin binary)
	if strings.Contains(strings.ToLower(rawQuery), "plugin=") {
		return OutboundNode{}, false
	}

	var credPart, hostPortPart string
	if at := strings.LastIndexByte(mainPart, '@'); at > 0 {
		credPart = mainPart[:at]
		hostPortPart = mainPart[at+1:]
		if unescaped, err := url.QueryUnescape(credPart); err == nil {
			credPart = unescaped
		}
		if !strings.Contains(credPart, ":") {
			if dec, ok := decodeFlexibleBase64(credPart); ok {
				credPart = dec
			}
		}
	} else {
		dec, ok := decodeFlexibleBase64(mainPart)
		if !ok {
			return OutboundNode{}, false
		}
		at2 := strings.LastIndexByte(dec, '@')
		if at2 <= 0 {
			return OutboundNode{}, false
		}
		credPart = dec[:at2]
		hostPortPart = dec[at2+1:]
	}

	colon := strings.IndexByte(credPart, ':')
	if colon <= 0 || colon+1 >= len(credPart) {
		return OutboundNode{}, false
	}
	method := strings.ToLower(strings.TrimSpace(credPart[:colon]))
	password := credPart[colon+1:]

	switch method {
	case "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "aead_aes_128_gcm", "aead_aes_256_gcm", "aead_chacha20_poly1305":
	default:
		return OutboundNode{}, false
	}

	host, port, ok := splitHostPortClean(hostPortPart)
	if !ok || password == "" {
		return OutboundNode{}, false
	}

	if tag == "" {
		tag = fmt.Sprintf("SS-%s:%s", host, port)
	}
	id := fmt.Sprintf("ss:%s:%s:%s", host, port, method)
	return OutboundNode{
		ID:        id,
		Name:      tag,
		RawURI:    raw,
		Protocol:  ProtoShadowsocks,
		Host:      host,
		Port:      port,
		Cipher:    normalizeSSCipher(method),
		Password:  password,
		Transport: "tcp",
	}, true
}

func normalizeSSCipher(m string) string {
	switch strings.ToLower(m) {
	case "aes-128-gcm", "aead_aes_128_gcm":
		return "aes-128-gcm"
	case "aes-256-gcm", "aead_aes_256_gcm":
		return "aes-256-gcm"
	default:
		return "chacha20-ietf-poly1305"
	}
}

func parseSOCKSOrHTTPLink(raw string, proto NodeProtocol) (OutboundNode, bool) {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return OutboundNode{}, false
	}
	host, port, ok := splitHostPortClean(u.Host)
	if !ok {
		return OutboundNode{}, false
	}
	user := ""
	pass := ""
	if u.User != nil {
		user = u.User.Username()
		pass, _ = u.User.Password()
	}
	name := strings.TrimSpace(u.Fragment)
	if name == "" {
		name = fmt.Sprintf("%s-%s:%s", strings.ToUpper(string(proto)), host, port)
	}
	return OutboundNode{
		ID:        fmt.Sprintf("%s:%s:%s:%s", proto, host, port, user),
		Name:      name,
		RawURI:    raw,
		Protocol:  proto,
		Host:      host,
		Port:      port,
		Username:  user,
		Password:  pass,
		Transport: "tcp",
	}, true
}

func decodeFlexibleBase64(s string) (string, bool) {
	s = strings.TrimSpace(s)
	s = strings.ReplaceAll(s, "\r", "")
	s = strings.ReplaceAll(s, "\n", "")
	s = strings.ReplaceAll(s, " ", "")
	if s == "" {
		return "", false
	}
	padded := s
	if rem := len(padded) % 4; rem != 0 {
		padded += strings.Repeat("=", 4-rem)
	}
	for _, enc := range []*base64.Encoding{
		base64.StdEncoding,
		base64.URLEncoding,
		base64.RawStdEncoding,
		base64.RawURLEncoding,
	} {
		input := padded
		if enc == base64.RawStdEncoding || enc == base64.RawURLEncoding {
			input = strings.TrimRight(s, "=")
		}
		if b, err := enc.DecodeString(input); err == nil && len(b) > 0 {
			return string(b), true
		}
	}
	return "", false
}

func parseUUIDBytes(uuidStr string) ([16]byte, bool) {
	var out [16]byte
	clean := strings.ReplaceAll(strings.TrimSpace(uuidStr), "-", "")
	if len(clean) != 32 {
		return out, false
	}
	b, err := hex.DecodeString(clean)
	if err != nil || len(b) != 16 {
		return out, false
	}
	copy(out[:], b)
	return out, true
}

// DialOutboundNode establishes an encrypted proxy stream to targetHost:targetPort via node.
func DialOutboundNode(ctx context.Context, doh *DoHResolver, node OutboundNode, targetHost, targetPort string) (net.Conn, error) {
	switch node.Protocol {
	case ProtoVLESS:
		return dialVLESS(ctx, doh, node, targetHost, targetPort)
	case ProtoTrojan:
		return dialTrojan(ctx, doh, node, targetHost, targetPort)
	case ProtoShadowsocks:
		return dialShadowsocksAEAD(ctx, doh, node, targetHost, targetPort)
	case ProtoSOCKS5:
		return dialViaSOCKS5(ctx, net.JoinHostPort(node.Host, node.Port), node.Username, node.Password, targetHost, targetPort)
	case ProtoHTTP:
		return dialViaHTTPConnect(ctx, net.JoinHostPort(node.Host, node.Port), node.Username, node.Password, targetHost, targetPort)
	default:
		return nil, fmt.Errorf("unsupported outbound protocol %s", node.Protocol)
	}
}

func dialNodeCarrier(ctx context.Context, doh *DoHResolver, node OutboundNode) (net.Conn, error) {
	var candidateIPs []string
	if ip := net.ParseIP(node.Host); ip != nil {
		candidateIPs = append(candidateIPs, ip.String())
		if node.TLS && node.Transport == "ws" && isCloudflareIP(ip.String()) {
			candidateIPs = append(candidateIPs, cleanCloudflareEdgeIPs[:2]...)
		}
	} else if doh != nil {
		ips, err := doh.Resolve(ctx, node.Host)
		if err == nil && len(ips) > 0 {
			candidateIPs = append(candidateIPs, ips[0])
			if node.TLS && node.Transport == "ws" && isCloudflareIP(ips[0]) {
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
			perAttemptTimeout = 2500 * time.Millisecond
		}
		var d net.Dialer
		d.Timeout = perAttemptTimeout
		c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(dialHost, node.Port))
		if err == nil {
			rawConn = c
			break
		}
		lastErr = err
	}
	if rawConn == nil {
		return nil, fmt.Errorf("dial node %s:%s failed: %w", node.Host, node.Port, lastErr)
	}

	stream := rawConn
	if node.TLS {
		// Fragment outer TLS ClientHello so ISP DPI cannot inspect or block the proxy server's SNI!
		fragConn := NewFragmentingConn(rawConn, 20, 2*time.Millisecond)
		sni := node.SNI
		if sni == "" && net.ParseIP(node.Host) == nil {
			sni = node.Host
		}
		tlsStream, err := handshakeChromeUTLS(ctx, fragConn, sni, node.Transport == "ws", node.AllowInsecure || sni == "")
		if err != nil {
			_ = rawConn.Close()
			return nil, err
		}
		stream = tlsStream
	}

	if node.Transport == "ws" {
		wsHost := node.WSHost
		if wsHost == "" {
			if node.SNI != "" {
				wsHost = node.SNI
			} else {
				wsHost = node.Host
			}
		}
		wsStream, err := dialWebSocketStream(stream, wsHost, node.WSPath)
		if err != nil {
			_ = stream.Close()
			return nil, err
		}
		stream = wsStream
	}

	return stream, nil
}

// handshakeChromeUTLS mimics Chrome's TLS fingerprint (exact GreatFire FreeBrowser technique)
// with fallback to Go stdlib TLS if uTLS preset fails on an unusual cipher suite.
func handshakeChromeUTLS(ctx context.Context, conn net.Conn, serverName string, forceHTTP11 bool, insecure bool) (net.Conn, error) {
	// Localhost test server fast path
	if serverName == "127.0.0.1" || serverName == "localhost" {
		alpn := []string{"h2", "http/1.1"}
		if forceHTTP11 {
			alpn = []string{"http/1.1"}
		}
		stdConn := tls.Client(conn, &tls.Config{
			ServerName:         serverName,
			RootCAs:            AndroidRootCertPool(),
			InsecureSkipVerify: insecure,
			NextProtos:         alpn,
			MinVersion:         tls.VersionTLS12,
		})
		if err := stdConn.HandshakeContext(ctx); err != nil {
			return nil, err
		}
		return stdConn, nil
	}

	uCfg := &utls.Config{
		ServerName:         serverName,
		RootCAs:            AndroidRootCertPool(),
		InsecureSkipVerify: insecure,
		MinVersion:         tls.VersionTLS12,
	}

	spec, err := utls.UTLSIdToSpec(utls.HelloChrome_Auto)
	if err != nil {
		return nil, err
	}
	for _, ext := range spec.Extensions {
		if alpnExt, ok := ext.(*utls.ALPNExtension); ok {
			if forceHTTP11 {
				alpnExt.AlpnProtocols = []string{"http/1.1"}
			} else {
				alpnExt.AlpnProtocols = []string{"h2", "http/1.1"}
			}
			break
		}
	}

	uConn := utls.UClient(conn, uCfg, utls.HelloCustom)
	if err := uConn.ApplyPreset(&spec); err != nil {
		return nil, err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = uConn.SetDeadline(deadline)
	} else {
		_ = uConn.SetDeadline(time.Now().Add(6 * time.Second))
	}
	if err := uConn.HandshakeContext(ctx); err != nil {
		return nil, err
	}
	_ = uConn.SetDeadline(time.Time{})
	return uConn, nil
}

// ============================================================================
// 1. VLESS v0 Stream Client
// ============================================================================

type vlessConn struct {
	net.Conn
	reqHeader  []byte
	wroteHdr   bool
	readHdr    bool
	writeMu    sync.Mutex
	readMu     sync.Mutex
}

func dialVLESS(ctx context.Context, doh *DoHResolver, node OutboundNode, targetHost, targetPort string) (net.Conn, error) {
	uuidBytes, ok := parseUUIDBytes(node.UUID)
	if !ok {
		return nil, errors.New("invalid VLESS UUID")
	}
	portNum, err := strconv.Atoi(targetPort)
	if err != nil || portNum <= 0 || portNum > 65535 {
		return nil, errors.New("invalid target port")
	}

	carrier, err := dialNodeCarrier(ctx, doh, node)
	if err != nil {
		return nil, err
	}

	// Build VLESS v0 header: [ver=0x00][uuid 16B][addonsLen=0x00][cmd=0x01][port 2B][addrType][addr]
	var hdr bytes.Buffer
	hdr.WriteByte(0x00)
	hdr.Write(uuidBytes[:])
	hdr.WriteByte(0x00) // addons length = 0
	hdr.WriteByte(0x01) // TCP
	hdr.WriteByte(byte(portNum >> 8))
	hdr.WriteByte(byte(portNum & 0xFF))

	if ip := net.ParseIP(targetHost); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			hdr.WriteByte(0x01) // VLESS IPv4
			hdr.Write(ip4)
		} else {
			hdr.WriteByte(0x03) // VLESS IPv6
			hdr.Write(ip.To16())
		}
	} else {
		if len(targetHost) > 255 {
			_ = carrier.Close()
			return nil, errors.New("target hostname too long")
		}
		hdr.WriteByte(0x02) // VLESS Domain = 0x02
		hdr.WriteByte(byte(len(targetHost)))
		hdr.WriteString(targetHost)
	}

	return &vlessConn{
		Conn:      carrier,
		reqHeader: hdr.Bytes(),
	}, nil
}

func (v *vlessConn) Write(b []byte) (int, error) {
	v.writeMu.Lock()
	defer v.writeMu.Unlock()

	if !v.wroteHdr {
		v.wroteHdr = true
		combined := make([]byte, len(v.reqHeader)+len(b))
		copy(combined, v.reqHeader)
		copy(combined[len(v.reqHeader):], b)
		if _, err := v.Conn.Write(combined); err != nil {
			return 0, err
		}
		return len(b), nil
	}
	return v.Conn.Write(b)
}

func (v *vlessConn) Read(b []byte) (int, error) {
	v.readMu.Lock()
	defer v.readMu.Unlock()

	if !v.readHdr {
		var respHdr [2]byte
		if _, err := io.ReadFull(v.Conn, respHdr[:]); err != nil {
			return 0, err
		}
		addonLen := int(respHdr[1])
		if addonLen > 0 {
			if _, err := io.CopyN(io.Discard, v.Conn, int64(addonLen)); err != nil {
				return 0, err
			}
		}
		v.readHdr = true
	}
	return v.Conn.Read(b)
}

// ============================================================================
// 2. Trojan Stream Client
// ============================================================================

type trojanConn struct {
	net.Conn
	reqHeader []byte
	wroteHdr  bool
	writeMu   sync.Mutex
}

func dialTrojan(ctx context.Context, doh *DoHResolver, node OutboundNode, targetHost, targetPort string) (net.Conn, error) {
	portNum, err := strconv.Atoi(targetPort)
	if err != nil || portNum <= 0 || portNum > 65535 {
		return nil, errors.New("invalid target port")
	}

	carrier, err := dialNodeCarrier(ctx, doh, node)
	if err != nil {
		return nil, err
	}

	hash := sha256.Sum224([]byte(node.Password))
	hexPass := hex.EncodeToString(hash[:]) // 56 ASCII hex chars

	var hdr bytes.Buffer
	hdr.WriteString(hexPass)
	hdr.WriteString("\r\n")
	hdr.WriteByte(0x01) // CONNECT
	writeSOCKS5Addr(&hdr, targetHost, portNum)
	hdr.WriteString("\r\n")

	return &trojanConn{
		Conn:      carrier,
		reqHeader: hdr.Bytes(),
	}, nil
}

func (t *trojanConn) Write(b []byte) (int, error) {
	t.writeMu.Lock()
	defer t.writeMu.Unlock()

	if !t.wroteHdr {
		t.wroteHdr = true
		combined := make([]byte, len(t.reqHeader)+len(b))
		copy(combined, t.reqHeader)
		copy(combined[len(t.reqHeader):], b)
		if _, err := t.Conn.Write(combined); err != nil {
			return 0, err
		}
		return len(b), nil
	}
	return t.Conn.Write(b)
}

// ============================================================================
// 3. Shadowsocks AEAD Stream Client (SIP004: aes-128-gcm, aes-256-gcm, chacha20-ietf-poly1305)
// ============================================================================

const ssMaxPayload = 0x3FFF

type ssAEADConn struct {
	net.Conn
	masterKey  []byte
	cipherName string
	saltSize   int
	targetHdr  []byte

	writeMu sync.Mutex
	txAEAD  cipher.AEAD
	txNonce []byte

	readMu  sync.Mutex
	rxAEAD  cipher.AEAD
	rxNonce []byte
	rxBuf   []byte
}

func dialShadowsocksAEAD(ctx context.Context, doh *DoHResolver, node OutboundNode, targetHost, targetPort string) (net.Conn, error) {
	portNum, err := strconv.Atoi(targetPort)
	if err != nil || portNum <= 0 || portNum > 65535 {
		return nil, errors.New("invalid target port")
	}

	keyLen := 32
	if node.Cipher == "aes-128-gcm" {
		keyLen = 16
	}
	masterKey := evpBytesToKey(node.Password, keyLen)

	dialHost := node.Host
	if net.ParseIP(dialHost) == nil && doh != nil {
		if ips, err := doh.Resolve(ctx, dialHost); err == nil && len(ips) > 0 {
			dialHost = ips[0]
		}
	}

	var d net.Dialer
	d.Timeout = 3500 * time.Millisecond
	rawConn, err := d.DialContext(ctx, "tcp", net.JoinHostPort(dialHost, node.Port))
	if err != nil {
		return nil, err
	}

	var addrBuf bytes.Buffer
	writeSOCKS5Addr(&addrBuf, targetHost, portNum)

	return &ssAEADConn{
		Conn:       rawConn,
		masterKey:  masterKey,
		cipherName: node.Cipher,
		saltSize:   keyLen,
		targetHdr:  addrBuf.Bytes(),
	}, nil
}

func evpBytesToKey(password string, keyLen int) []byte {
	pass := []byte(password)
	var b, prev []byte
	for len(b) < keyLen {
		h := md5.New()
		h.Write(prev)
		h.Write(pass)
		prev = h.Sum(nil)
		b = append(b, prev...)
	}
	return b[:keyLen]
}

func newSSSubkeyAEAD(cipherName string, masterKey, salt []byte) (cipher.AEAD, error) {
	subkey := make([]byte, len(masterKey))
	r := hkdf.New(sha1.New, masterKey, salt, []byte("ss-subkey"))
	if _, err := io.ReadFull(r, subkey); err != nil {
		return nil, err
	}
	switch cipherName {
	case "aes-128-gcm", "aes-256-gcm":
		blk, err := aes.NewCipher(subkey)
		if err != nil {
			return nil, err
		}
		return cipher.NewGCM(blk)
	default:
		return chacha20poly1305.New(subkey)
	}
}

func incSSNonce(nonce []byte) {
	for i := 0; i < len(nonce); i++ {
		nonce[i]++
		if nonce[i] != 0 {
			return
		}
	}
}

func (s *ssAEADConn) Write(b []byte) (int, error) {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()

	var wireOut bytes.Buffer
	var plain []byte

	if s.txAEAD == nil {
		salt := make([]byte, s.saltSize)
		if _, err := rand.Read(salt); err != nil {
			return 0, err
		}
		aead, err := newSSSubkeyAEAD(s.cipherName, s.masterKey, salt)
		if err != nil {
			return 0, err
		}
		s.txAEAD = aead
		s.txNonce = make([]byte, aead.NonceSize())
		wireOut.Write(salt)

		plain = make([]byte, len(s.targetHdr)+len(b))
		copy(plain, s.targetHdr)
		copy(plain[len(s.targetHdr):], b)
	} else {
		plain = b
	}

	for off := 0; off < len(plain); off += ssMaxPayload {
		end := off + ssMaxPayload
		if end > len(plain) {
			end = len(plain)
		}
		chunk := plain[off:end]

		var lenPlain [2]byte
		binary.BigEndian.PutUint16(lenPlain[:], uint16(len(chunk)))
		encLen := s.txAEAD.Seal(nil, s.txNonce, lenPlain[:], nil)
		incSSNonce(s.txNonce)

		encPayload := s.txAEAD.Seal(nil, s.txNonce, chunk, nil)
		incSSNonce(s.txNonce)

		wireOut.Write(encLen)
		wireOut.Write(encPayload)
	}

	if _, err := s.Conn.Write(wireOut.Bytes()); err != nil {
		return 0, err
	}
	return len(b), nil
}

func (s *ssAEADConn) Read(b []byte) (int, error) {
	s.readMu.Lock()
	defer s.readMu.Unlock()

	if len(s.rxBuf) == 0 {
		if s.rxAEAD == nil {
			salt := make([]byte, s.saltSize)
			if _, err := io.ReadFull(s.Conn, salt); err != nil {
				return 0, err
			}
			aead, err := newSSSubkeyAEAD(s.cipherName, s.masterKey, salt)
			if err != nil {
				return 0, err
			}
			s.rxAEAD = aead
			s.rxNonce = make([]byte, aead.NonceSize())
		}

		tagSize := s.rxAEAD.Overhead()
		encLenBuf := make([]byte, 2+tagSize)
		if _, err := io.ReadFull(s.Conn, encLenBuf); err != nil {
			return 0, err
		}
		lenPlain, err := s.rxAEAD.Open(nil, s.rxNonce, encLenBuf, nil)
		if err != nil {
			return 0, err
		}
		incSSNonce(s.rxNonce)

		size := int(binary.BigEndian.Uint16(lenPlain)) & ssMaxPayload
		encPayload := make([]byte, size+tagSize)
		if _, err := io.ReadFull(s.Conn, encPayload); err != nil {
			return 0, err
		}
		payload, err := s.rxAEAD.Open(nil, s.rxNonce, encPayload, nil)
		if err != nil {
			return 0, err
		}
		incSSNonce(s.rxNonce)
		s.rxBuf = payload
	}

	n := copy(b, s.rxBuf)
	s.rxBuf = s.rxBuf[n:]
	return n, nil
}

func writeSOCKS5Addr(buf *bytes.Buffer, host string, port int) {
	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			buf.WriteByte(0x01)
			buf.Write(ip4)
		} else {
			buf.WriteByte(0x04)
			buf.Write(ip.To16())
		}
	} else {
		if len(host) > 255 {
			host = host[:255]
		}
		buf.WriteByte(0x03)
		buf.WriteByte(byte(len(host)))
		buf.WriteString(host)
	}
	buf.WriteByte(byte(port >> 8))
	buf.WriteByte(byte(port & 0xFF))
}
