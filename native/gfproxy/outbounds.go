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
	ProtoVMess       NodeProtocol = "vmess"
	ProtoTrojan      NodeProtocol = "trojan"
	ProtoShadowsocks NodeProtocol = "ss"
	ProtoSOCKS5      NodeProtocol = "socks5"
	ProtoHTTP        NodeProtocol = "http"
)

type OutboundNode struct {
	ID               string       `json:"id"`
	Name             string       `json:"name"`
	Source           string       `json:"source,omitempty"`
	RawURI           string       `json:"raw_uri"`
	Protocol         NodeProtocol `json:"protocol"`
	Host             string       `json:"host"`
	Port             string       `json:"port"`
	UUID             string       `json:"uuid,omitempty"`
	Password         string       `json:"password,omitempty"`
	Username         string       `json:"username,omitempty"`
	Cipher           string       `json:"cipher,omitempty"`
	Transport        string       `json:"transport"` // "tcp", "ws", "xhttp", "grpc", "httpupgrade", "h2"
	TLS              bool         `json:"tls"`
	Security         string       `json:"security,omitempty"` // "tls", "reality", "none"
	SNI              string       `json:"sni,omitempty"`
	WSHost           string       `json:"ws_host,omitempty"`
	WSPath           string       `json:"ws_path,omitempty"`
	Mode             string       `json:"mode,omitempty"`
	ServiceName      string       `json:"service_name,omitempty"`
	Fingerprint      string       `json:"fingerprint,omitempty"`
	ALPN             string       `json:"alpn,omitempty"`
	Flow             string       `json:"flow,omitempty"`
	RealityPublicKey string       `json:"reality_pbk,omitempty"`
	RealityShortID   string       `json:"reality_sid,omitempty"`
	AllowInsecure    bool         `json:"allow_insecure,omitempty"`
	AvgRTTMs         int64        `json:"avg_rtt_ms,omitempty"`
}

var cleanCloudflareEdgeIPs = []string{
	"104.18.2.161",
	"172.66.40.212",
	"162.159.135.42",
	"104.16.132.229",
	"104.17.147.22",
	"172.64.155.141",
	"108.162.198.44",
	"198.41.209.102",
	"104.19.144.33",
	"172.67.74.226",
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

// ParseShareLinks parses multi-line, noisy Telegram text, or base64-encoded subscription text into OutboundNodes.
func ParseShareLinks(rawText string) []OutboundNode {
	return ParseShareLinksWithSource(rawText, "")
}

func ParseShareLinksWithSource(rawText string, sourceName string) []OutboundNode {
	trimmed := strings.TrimSpace(SanitizeShareLinkInput(rawText))
	if trimmed == "" {
		return nil
	}

	// Strip optional #profile-title header lines before attempting Base64 decode if no scheme is present
	if !containsProxyScheme(trimmed) && !strings.Contains(trimmed, "proxies:") {
		var nonCommentLines []string
		for _, l := range strings.Split(trimmed, "\n") {
			lt := strings.TrimSpace(l)
			if lt != "" && !strings.HasPrefix(lt, "#") && !strings.HasPrefix(lt, "//") {
				nonCommentLines = append(nonCommentLines, lt)
			}
		}
		joined := strings.Join(nonCommentLines, "")
		if decoded, ok := decodeFlexibleBase64(joined); ok && (containsProxyScheme(decoded) || strings.Contains(decoded, "proxies:")) {
			trimmed = SanitizeShareLinkInput(decoded)
		} else if decoded, ok := decodeFlexibleBase64(trimmed); ok && (containsProxyScheme(decoded) || strings.Contains(decoded, "proxies:")) {
			trimmed = SanitizeShareLinkInput(decoded)
		}
	}

	candidates := extractCandidateURITokens(trimmed)
	var out []OutboundNode
	seen := make(map[string]bool)
	for _, token := range candidates {
		node, ok := ParseSingleShareLink(token)
		if !ok {
			continue
		}
		if node.Source == "" {
			node.Source = inferSourceFromRemarkOrDefault(node.Name, sourceName)
		}
		if !seen[node.ID] {
			seen[node.ID] = true
			out = append(out, node)
		}
	}

	if strings.Contains(trimmed, "proxies:") || len(out) == 0 {
		for _, cNode := range ParseClashProxies(trimmed, sourceName) {
			if !seen[cNode.ID] {
				seen[cNode.ID] = true
				out = append(out, cNode)
			}
		}
	}
	return out
}

func containsProxyScheme(s string) bool {
	low := strings.ToLower(s)
	for _, p := range []string{"vless://", "vmess://", "trojan://", "ss://", "socks5://", "socks5h://", "socks://"} {
		if strings.Contains(low, p) {
			return true
		}
	}
	return false
}

var supportedProxyPrefixes = []string{
	"vless://",
	"vmess://",
	"trojan://",
	"ss://",
	"socks5://",
	"socks5h://",
	"socks://",
	"http://",
}

// extractCandidateURITokens cleanly extracts 1..N proxy URIs even when surrounded by Telegram
// captions, markdown backticks, quotes, or multiple configs on a single line.
func extractCandidateURITokens(text string) []string {
	text = strings.ReplaceAll(text, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	text = strings.ReplaceAll(text, "`", "\n")
	text = strings.ReplaceAll(text, "\"", "\n")
	text = strings.ReplaceAll(text, "'", "\n")
	text = strings.ReplaceAll(text, "<", "\n")
	text = strings.ReplaceAll(text, ">", "\n")

	var out []string
	for _, rawLine := range strings.Split(text, "\n") {
		line := strings.TrimSpace(rawLine)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "//") {
			continue
		}
		lower := strings.ToLower(line)

		// Find all scheme occurrences inside this line
		var positions []int
		for i := 0; i < len(lower); i++ {
			for _, pref := range supportedProxyPrefixes {
				if strings.HasPrefix(lower[i:], pref) {
					// Avoid matching "vless://" inside "vmess://" etc., and avoid matching "ss://" inside "vless://" or "vmess://"
					if pref == "ss://" && i > 0 {
						prev := lower[i-1]
						if prev >= 'a' && prev <= 'z' {
							continue
						}
					}
					positions = append(positions, i)
					i += len(pref) - 1
					break
				}
			}
		}

		if len(positions) == 0 {
			// Also support bare host:port SOCKS5 shorthand when user enters e.g. 127.0.0.1:10808
			if h, p, ok := splitHostPortClean(line); ok && !strings.Contains(line, "/") {
				out = append(out, "socks5://"+net.JoinHostPort(h, p))
			}
			continue
		}

		for idx, start := range positions {
			end := len(line)
			if idx+1 < len(positions) {
				end = positions[idx+1]
			}
			seg := strings.TrimSpace(line[start:end])
			// Strip trailing spaces from URI part before '#', while keeping spaces inside '#remark' if last on line
			if hashPos := strings.IndexByte(seg, '#'); hashPos > 0 {
				uriHead := seg[:hashPos]
				if sp := strings.IndexAny(uriHead, " \t"); sp > 0 {
					seg = uriHead[:sp]
				}
			} else if sp := strings.IndexAny(seg, " \t"); sp > 0 {
				seg = seg[:sp]
			}
			if seg != "" {
				out = append(out, seg)
			}
		}
	}
	return out
}

func inferSourceFromRemarkOrDefault(remark, fallback string) string {
	r := strings.TrimSpace(remark)
	if strings.HasPrefix(r, "[") {
		if closeIdx := strings.IndexByte(r, ']'); closeIdx > 1 && closeIdx <= 20 {
			return strings.TrimSpace(r[1:closeIdx])
		}
	}
	if fallback != "" {
		return fallback
	}
	return "Global"
}

// DiagnoseUnsupportedShareLink provides a clear Persian reason when a user pastes an unsupported link type.
func DiagnoseUnsupportedShareLink(raw string) string {
	low := strings.ToLower(strings.TrimSpace(SanitizeShareLinkInput(raw)))
	switch {
	case strings.Contains(low, "hysteria2://") || strings.Contains(low, "hy2://") || strings.Contains(low, "tuic://") || strings.Contains(low, "wireguard://") || strings.Contains(low, "wg://"):
		return "پروتکل UDP (Hysteria2/TUIC/WireGuard) پشتیبانی نمی‌شود؛ از VLESS (شامل XHTTP/Reality/WS/gRPC/TCP)، VMess، Trojan، SS، SOCKS5 یا لینک اشتراک استفاده کنید."
	default:
		return "فرمت کانفیگ معتبر نیست. لینک‌های مجاز: vless:// (شامل xhttp/reality/ws/grpc/tcp), vmess://, trojan://, ss://, socks5:// یا لینک اشتراک https://..."
	}
}

func ParseSingleShareLink(uri string) (OutboundNode, bool) {
	uri = strings.TrimSpace(SanitizeShareLinkInput(uri))
	lower := strings.ToLower(uri)
	switch {
	case strings.HasPrefix(lower, "vless://"):
		return parseVLESSLink(uri)
	case strings.HasPrefix(lower, "vmess://"):
		return parseVMessLink(uri)
	case strings.HasPrefix(lower, "trojan://"):
		return parseTrojanLink(uri)
	case strings.HasPrefix(lower, "ss://"):
		return parseShadowsocksLink(uri)
	case strings.HasPrefix(lower, "socks5://"), strings.HasPrefix(lower, "socks5h://"), strings.HasPrefix(lower, "socks://"):
		return parseSOCKSOrHTTPLink(uri, ProtoSOCKS5)
	case strings.HasPrefix(lower, "http://"):
		return parseSOCKSOrHTTPLink(uri, ProtoHTTP)
	default:
		if h, p, ok := splitHostPortClean(uri); ok && !strings.Contains(uri, "/") {
			return parseSOCKSOrHTTPLink("socks5://"+net.JoinHostPort(h, p), ProtoSOCKS5)
		}
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
	if uUnesc, uErr := url.QueryUnescape(uuidStr); uErr == nil && uUnesc != "" {
		uuidStr = strings.TrimSpace(uUnesc)
	}
	if _, ok := parseUUIDBytes(uuidStr); !ok {
		return OutboundNode{}, false
	}
	host, port, ok := splitHostPortClean(mainPart[at+1:])
	if !ok {
		return OutboundNode{}, false
	}

	q, _ := url.ParseQuery(rawQuery)
	rawType := q.Get("type")
	if rawType == "" {
		rawType = q.Get("net")
	}
	netType, ok := normalizeTransportName(rawType)
	if !ok {
		return OutboundNode{}, false
	}

	// Reject experimental custom post-quantum VLESS inner encryption, accept standard "none" / ""
	if enc := strings.ToLower(strings.TrimSpace(q.Get("encryption"))); enc != "" && enc != "none" && enc != "zero" {
		return OutboundNode{}, false
	}

	sec := strings.ToLower(strings.TrimSpace(q.Get("security")))
	pbk := strings.TrimSpace(q.Get("pbk"))
	sid := strings.TrimSpace(q.Get("sid"))
	if sec == "reality" {
		if _, pOk := decodeRealityPublicKey(pbk); !pOk {
			return OutboundNode{}, false
		}
		if _, sOk := decodeRealityShortID(sid); !sOk {
			return OutboundNode{}, false
		}
	}

	flow := strings.ToLower(strings.TrimSpace(q.Get("flow")))
	if flow == "none" {
		flow = ""
	}
	if flow != "" && flow != "xtls-rprx-vision" && flow != "xtls-rprx-vision-udp443" {
		return OutboundNode{}, false
	}
	if flow == "xtls-rprx-vision-udp443" {
		flow = "xtls-rprx-vision"
	}

	useTLS := sec == "tls" || sec == "ssl" || sec == "reality" ||
		port == "443" || port == "2053" || port == "2083" || port == "2087" || port == "2096" || port == "8443"
	if sec == "none" {
		useTLS = false
	}
	if sec == "" {
		if useTLS {
			sec = "tls"
		} else {
			sec = "none"
		}
	}

	sni := strings.TrimSpace(q.Get("sni"))
	if sni == "" {
		sni = strings.TrimSpace(q.Get("peer"))
	}
	if sni == "" {
		sni = strings.TrimSpace(q.Get("serverName"))
	}
	wsHost := strings.TrimSpace(q.Get("host"))
	if wsHost == "" {
		wsHost = strings.TrimSpace(q.Get("authority"))
	}
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

	mode := strings.ToLower(strings.TrimSpace(q.Get("mode")))
	serviceName := strings.TrimSpace(q.Get("serviceName"))
	fp := strings.TrimSpace(q.Get("fp"))
	alpn := strings.TrimSpace(q.Get("alpn"))

	insecure := q.Get("allowInsecure") == "1" || strings.EqualFold(q.Get("allowInsecure"), "true") ||
		q.Get("insecure") == "1" || strings.EqualFold(q.Get("insecure"), "true")

	if tag == "" {
		tag = fmt.Sprintf("VLESS-%s:%s", host, port)
	}
	id := fmt.Sprintf("vless:%s:%s:%s:%s:%s:%s", host, port, uuidStr, netType, sni, wsPath)
	return OutboundNode{
		ID:               id,
		Name:             tag,
		RawURI:           raw,
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
		Mode:             mode,
		ServiceName:      serviceName,
		Fingerprint:      fp,
		ALPN:             alpn,
		Flow:             flow,
		RealityPublicKey: pbk,
		RealityShortID:   sid,
		AllowInsecure:    insecure,
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
	rawType := q.Get("type")
	if rawType == "" {
		rawType = q.Get("net")
	}
	netType, ok := normalizeTransportName(rawType)
	if !ok {
		return OutboundNode{}, false
	}
	sec := strings.ToLower(strings.TrimSpace(q.Get("security")))
	pbk := strings.TrimSpace(q.Get("pbk"))
	sid := strings.TrimSpace(q.Get("sid"))
	if sec == "reality" {
		if _, pOk := decodeRealityPublicKey(pbk); !pOk {
			return OutboundNode{}, false
		}
		if _, sOk := decodeRealityShortID(sid); !sOk {
			return OutboundNode{}, false
		}
	}
	useTLS := sec != "none"
	if sec == "" {
		sec = "tls"
	}

	sni := strings.TrimSpace(q.Get("sni"))
	if sni == "" {
		sni = strings.TrimSpace(q.Get("peer"))
	}
	wsHost := strings.TrimSpace(q.Get("host"))
	if wsHost == "" {
		wsHost = strings.TrimSpace(q.Get("authority"))
	}
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
	mode := strings.ToLower(strings.TrimSpace(q.Get("mode")))
	serviceName := strings.TrimSpace(q.Get("serviceName"))
	fp := strings.TrimSpace(q.Get("fp"))
	alpn := strings.TrimSpace(q.Get("alpn"))
	insecure := q.Get("allowInsecure") == "1" || strings.EqualFold(q.Get("allowInsecure"), "true") ||
		q.Get("insecure") == "1" || strings.EqualFold(q.Get("insecure"), "true") || (sni == "" && sec != "reality")

	if tag == "" {
		tag = fmt.Sprintf("Trojan-%s:%s", host, port)
	}
	id := fmt.Sprintf("trojan:%s:%s:%s:%s:%s", host, port, password, netType, wsPath)
	return OutboundNode{
		ID:               id,
		Name:             tag,
		RawURI:           raw,
		Protocol:         ProtoTrojan,
		Host:             host,
		Port:             port,
		Password:         password,
		Transport:        netType,
		TLS:              useTLS,
		Security:         sec,
		SNI:              sni,
		WSHost:           wsHost,
		WSPath:           wsPath,
		Mode:             mode,
		ServiceName:      serviceName,
		Fingerprint:      fp,
		ALPN:             alpn,
		RealityPublicKey: pbk,
		RealityShortID:   sid,
		AllowInsecure:    insecure,
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
	case ProtoVMess:
		return dialVMess(ctx, doh, node, targetHost, targetPort)
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
	switch node.Transport {
	case "xhttp", "splithttp":
		return dialXHTTPStream(ctx, doh, node)
	case "grpc", "gun":
		return dialGRPCTunStream(ctx, doh, node)
	case "h2", "http", "http2":
		return dialH2Stream(ctx, doh, node)
	}

	rawConn, err := dialRawNodeTCP(ctx, doh, node)
	if err != nil {
		return nil, err
	}

	stream := rawConn
	if node.Security == "reality" {
		realityStream, _, _, rErr := handshakeRealityTLS(ctx, rawConn, node)
		if rErr != nil {
			_ = rawConn.Close()
			return nil, rErr
		}
		stream = realityStream
	} else if node.TLS {
		// Fragment outer TLS ClientHello so ISP DPI cannot inspect or block the proxy server's SNI!
		fragConn := NewFragmentingConn(rawConn, 20, 2*time.Millisecond)
		sni := node.SNI
		if sni == "" && net.ParseIP(node.Host) == nil {
			sni = node.Host
		}
		// Negotiate http/1.1 on TCP/WS/HTTPUpgrade outer TLS carrier so raw TCP VLESS/Trojan/VMess streams
		// and HTTP/1.1 WebSocket/HTTPUpgrade upgrades never get misrouted to HTTP/2 web fallbacks.
		tlsStream, _, tErr := handshakeTLSWithALPN(ctx, fragConn, sni, node.Fingerprint, []string{"http/1.1"}, node.AllowInsecure || sni == "")
		if tErr != nil {
			_ = rawConn.Close()
			return nil, tErr
		}
		stream = tlsStream
	}

	switch node.Transport {
	case "ws":
		wsHost := firstNonEmpty(node.WSHost, node.SNI, node.Host)
		wsStream, wErr := dialWebSocketStream(stream, wsHost, node.WSPath)
		if wErr != nil {
			_ = stream.Close()
			return nil, wErr
		}
		stream = wsStream
	case "httpupgrade":
		upHost := firstNonEmpty(node.WSHost, node.SNI, node.Host)
		upStream, uErr := dialHTTPUpgradeStream(stream, upHost, node.WSPath)
		if uErr != nil {
			_ = stream.Close()
			return nil, uErr
		}
		stream = upStream
	}

	if node.Protocol == ProtoVLESS && node.Flow == "xtls-rprx-vision" {
		if uuidBytes, ok := parseUUIDBytes(node.UUID); ok {
			stream = newXTLSVisionConn(stream, rawConn, uuidBytes)
		}
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

	// Build VLESS v0 header: [ver=0x00][uuid 16B][addons][cmd=0x01][port 2B][addrType][addr]
	var hdr bytes.Buffer
	hdr.WriteByte(0x00)
	hdr.Write(uuidBytes[:])
	if node.Flow == "xtls-rprx-vision" {
		// Protobuf Addons{Flow: "xtls-rprx-vision"} => len 18 (0x12), tag 0x0A, len 0x10, "xtls-rprx-vision"
		hdr.WriteByte(0x12)
		hdr.WriteByte(0x0A)
		hdr.WriteByte(0x10)
		hdr.WriteString("xtls-rprx-vision")
	} else {
		hdr.WriteByte(0x00) // addons length = 0
	}
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
