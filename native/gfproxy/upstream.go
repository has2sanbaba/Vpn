package main

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

// TunnelMode controls how the GreatFire unified local proxy routes outbound traffic.
type TunnelMode string

const (
	ModeAntiDPI   TunnelMode = "anti_dpi"   // FreeBrowser DoH + TLS Record/TCP Fragmentation + CDN Fronting
	ModeSOCKS5    TunnelMode = "socks5"     // Upstream SOCKS5 (IEnvoyProxy V2Ray/XRay/Hysteria2/Snowflake/PTs, Shadowsocks, custom SOCKS5)
	ModeHTTPProxy TunnelMode = "http_proxy" // Upstream HTTP CONNECT proxy (IEnvoyProxy MASQUE, custom HTTP proxy)
	ModeEnvoyHTTP TunnelMode = "envoy_http" // GreatFire Envoy HTTPS header-rewrite proxy (Url-Orig / Host-Orig) + Anti-DPI tunnel fallback
	ModeBlocked   TunnelMode = "blocked"    // Strict Kill-Switch: block all traffic when VPN is disconnected
	ModeDirect    TunnelMode = "direct"     // Explicit unproxied mode (only if user disables protection)
)

// RuntimeConfig holds hot-swappable gateway routing settings.
type RuntimeConfig struct {
	Mode          TunnelMode `json:"mode"`
	UpstreamAddr  string     `json:"upstream_addr"`  // e.g. 127.0.0.1:47000 or host:port
	UpstreamUser  string     `json:"upstream_user"`  // optional SOCKS5/HTTP auth username
	UpstreamPass  string     `json:"upstream_pass"`  // optional SOCKS5/HTTP auth password
	EnvoyURL      string     `json:"envoy_url"`      // e.g. https://allowed.example.com/path/
	EnvoyAddress  string     `json:"envoy_address"`  // optional hardcoded IP for Envoy host
	EnvoyHostHdr  string     `json:"envoy_host_hdr"` // optional Host header override for Envoy
	EnvoySalt     string     `json:"envoy_salt"`     // cache-busting salt
	DoHServer     string     `json:"doh_server"`     // optional custom DoH server
	FragEnabled   bool       `json:"frag_enabled"`   // TLS ClientHello & HTTP fragmentation
	FragSize      int        `json:"frag_size"`      // TLS record split size in bytes
	FragDelayMs   int        `json:"frag_delay_ms"`  // inter-fragment delay in milliseconds
	FbConfigPath  string     `json:"fb_config_path"` // optional GreatFire fb.config CDN fronting file
	ProfileName   string     `json:"profile_name"`   // human-readable active profile label
}

type CDNFrontNode struct {
	FrontDomain string
	IP          string
}

type UpstreamRouter struct {
	mu       sync.RWMutex
	cfg      RuntimeConfig
	doh      *DoHResolver
	cdnNodes []CDNFrontNode
}

func NewUpstreamRouter(doh *DoHResolver, initial RuntimeConfig) *UpstreamRouter {
	r := &UpstreamRouter{
		doh: doh,
	}
	r.UpdateConfig(initial)
	return r
}

func (r *UpstreamRouter) GetConfig() RuntimeConfig {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.cfg
}

func (r *UpstreamRouter) UpdateConfig(cfg RuntimeConfig) {
	if cfg.Mode == "" {
		cfg.Mode = ModeAntiDPI
	}
	if cfg.FragSize <= 0 {
		cfg.FragSize = 24
	}
	if cfg.FragDelayMs < 0 {
		cfg.FragDelayMs = 4
	}
	if cfg.EnvoySalt == "" {
		cfg.EnvoySalt = "gf-envoy-browser"
	}

	r.doh.Configure(cfg.DoHServer, cfg.FragEnabled, cfg.FragSize, time.Duration(cfg.FragDelayMs)*time.Millisecond)

	var nodes []CDNFrontNode
	if cfg.FbConfigPath != "" {
		nodes = loadFbConfigNodes(cfg.FbConfigPath)
	}

	r.mu.Lock()
	r.cfg = cfg
	r.cdnNodes = nodes
	r.mu.Unlock()
}

// loadFbConfigNodes parses GreatFire FreeBrowser's fb.config CDN-CIDRS entries.
func loadFbConfigNodes(path string) []CDNFrontNode {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var out []CDNFrontNode
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.Split(line, "\t")
		var clean []string
		for _, p := range parts {
			if strings.TrimSpace(p) != "" {
				clean = append(clean, strings.TrimSpace(p))
			}
		}
		if len(clean) >= 3 && clean[0] == "CDN-CIDRS" {
			domain := clean[1]
			if domain == "yourdomain.com" {
				continue // skip placeholder template entry
			}
			for _, ipItem := range strings.Split(clean[2], ",") {
				ipItem = strings.TrimSpace(ipItem)
				if ip := net.ParseIP(ipItem); ip != nil {
					out = append(out, CDNFrontNode{FrontDomain: domain, IP: ip.String()})
				}
			}
		}
	}
	return out
}

var ErrKillSwitchBlocked = errors.New("connection blocked by GreatFire VPN Kill-Switch")

// DialTarget establishes an outbound stream to targetHost:targetPort using the active GreatFire mode.
func (r *UpstreamRouter) DialTarget(ctx context.Context, targetHost, targetPort string) (net.Conn, error) {
	cfg := r.GetConfig()

	switch cfg.Mode {
	case ModeBlocked:
		return nil, ErrKillSwitchBlocked

	case ModeSOCKS5:
		if cfg.UpstreamAddr == "" {
			return nil, errors.New("SOCKS5 mode active but upstream_addr is empty")
		}
		return dialViaSOCKS5(ctx, cfg.UpstreamAddr, cfg.UpstreamUser, cfg.UpstreamPass, targetHost, targetPort)

	case ModeHTTPProxy:
		if cfg.UpstreamAddr == "" {
			return nil, errors.New("HTTP proxy mode active but upstream_addr is empty")
		}
		return dialViaHTTPConnect(ctx, cfg.UpstreamAddr, cfg.UpstreamUser, cfg.UpstreamPass, targetHost, targetPort)

	case ModeDirect:
		var d net.Dialer
		d.Timeout = 12 * time.Second
		return d.DialContext(ctx, "tcp", net.JoinHostPort(targetHost, targetPort))

	case ModeEnvoyHTTP, ModeAntiDPI:
		fallthrough
	default:
		return r.dialAntiDPI(ctx, cfg, targetHost, targetPort)
	}
}

func (r *UpstreamRouter) dialAntiDPI(ctx context.Context, cfg RuntimeConfig, targetHost, targetPort string) (net.Conn, error) {
	ips, err := r.doh.Resolve(ctx, targetHost)
	if err != nil || len(ips) == 0 {
		return nil, fmt.Errorf("DoH resolution failed for %s: %w", targetHost, err)
	}

	var lastErr error
	maxTry := len(ips)
	if maxTry > 3 {
		maxTry = 3
	}
	for i := 0; i < maxTry; i++ {
		var d net.Dialer
		d.Timeout = 10 * time.Second
		conn, dialErr := d.DialContext(ctx, "tcp", net.JoinHostPort(ips[i], targetPort))
		if dialErr == nil {
			if cfg.FragEnabled {
				return NewFragmentingConn(conn, cfg.FragSize, time.Duration(cfg.FragDelayMs)*time.Millisecond), nil
			}
			return conn, nil
		}
		lastErr = dialErr
	}
	return nil, fmt.Errorf("failed dialing clean IPs for %s: %w", targetHost, lastErr)
}

// ForwardPlainHTTP handles non-CONNECT HTTP proxy requests, including GreatFire Envoy URL rewriting
// (Url-Orig / Host-Orig headers) and FreeBrowser CDN Domain Fronting when configured.
func (r *UpstreamRouter) ForwardPlainHTTP(ctx context.Context, req *http.Request) (*http.Response, error) {
	cfg := r.GetConfig()
	if cfg.Mode == ModeBlocked {
		return nil, ErrKillSwitchBlocked
	}

	// GreatFire Envoy HTTP/HTTPS header-rewrite protocol (Url-Orig + Host-Orig + SHA-256 digest)
	if cfg.Mode == ModeEnvoyHTTP && cfg.EnvoyURL != "" {
		return r.roundTripEnvoyRewrite(ctx, cfg, req)
	}

	// FreeBrowser CDN Domain Fronting if fb.config nodes exist
	r.mu.RLock()
	nodes := r.cdnNodes
	r.mu.RUnlock()
	if cfg.Mode == ModeAntiDPI && len(nodes) > 0 {
		if resp, err := r.roundTripDomainFronting(ctx, nodes[0], req); err == nil {
			return resp, nil
		}
	}

	tr := &http.Transport{
		DialContext: func(dialCtx context.Context, _, addr string) (net.Conn, error) {
			h, p, err := net.SplitHostPort(addr)
			if err != nil {
				h = addr
				p = "80"
			}
			return r.DialTarget(dialCtx, h, p)
		},
		ResponseHeaderTimeout: 20 * time.Second,
	}
	defer tr.CloseIdleConnections()

	outReq := req.Clone(ctx)
	outReq.RequestURI = ""
	removeHopByHopHeaders(outReq.Header)
	return tr.RoundTrip(outReq)
}

func (r *UpstreamRouter) roundTripEnvoyRewrite(ctx context.Context, cfg RuntimeConfig, orig *http.Request) (*http.Response, error) {
	targetURL := orig.URL.String()
	origHost := orig.URL.Hostname()
	if origHost == "" {
		origHost = orig.Host
	}

	sum := sha256.Sum256([]byte(targetURL + cfg.EnvoySalt))
	digest := url.QueryEscape(hex.EncodeToString(sum[:]))
	sep := "?"
	if strings.Contains(cfg.EnvoyURL, "?") {
		sep = "&"
	}
	rewrittenURL := cfg.EnvoyURL + sep + "digest=" + digest

	parsedEnvoy, err := url.Parse(rewrittenURL)
	if err != nil {
		return nil, err
	}

	outReq, err := http.NewRequestWithContext(ctx, orig.Method, rewrittenURL, orig.Body)
	if err != nil {
		return nil, err
	}
	for k, vals := range orig.Header {
		for _, v := range vals {
			outReq.Header.Add(k, v)
		}
	}
	removeHopByHopHeaders(outReq.Header)
	outReq.Header.Set("Url-Orig", targetURL)
	outReq.Header.Set("Host-Orig", origHost)
	if cfg.EnvoyHostHdr != "" {
		outReq.Host = cfg.EnvoyHostHdr
	}

	tr := &http.Transport{
		ForceAttemptHTTP2: true,
		DialTLSContext: func(dialCtx context.Context, _, addr string) (net.Conn, error) {
			host, port, splitErr := net.SplitHostPort(addr)
			if splitErr != nil {
				host = parsedEnvoy.Hostname()
				port = "443"
			}
			connectHost := host
			if cfg.EnvoyAddress != "" {
				connectHost = cfg.EnvoyAddress
			}
			rawConn, dialErr := r.dialAntiDPI(dialCtx, cfg, connectHost, port)
			if dialErr != nil {
				return nil, dialErr
			}
			tlsConn := tls.Client(rawConn, &tls.Config{
				ServerName: host,
				RootCAs:    AndroidRootCertPool(),
				NextProtos: []string{"h2", "http/1.1"},
				MinVersion: tls.VersionTLS12,
			})
			if hsErr := tlsConn.HandshakeContext(dialCtx); hsErr != nil {
				_ = rawConn.Close()
				return nil, hsErr
			}
			return tlsConn, nil
		},
	}
	defer tr.CloseIdleConnections()
	return tr.RoundTrip(outReq)
}

func (r *UpstreamRouter) roundTripDomainFronting(ctx context.Context, node CDNFrontNode, orig *http.Request) (*http.Response, error) {
	frontURL := fmt.Sprintf("https://%s/df/gf", node.FrontDomain)
	outReq, err := http.NewRequestWithContext(ctx, orig.Method, frontURL, orig.Body)
	if err != nil {
		return nil, err
	}
	outReq.Host = node.FrontDomain
	outReq.Header.Set("X-Original-URL", orig.URL.String())
	tr := &http.Transport{
		DialTLSContext: func(dialCtx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			d.Timeout = 10 * time.Second
			raw, err := d.DialContext(dialCtx, "tcp", net.JoinHostPort(node.IP, "443"))
			if err != nil {
				return nil, err
			}
			// Domain fronting connects to CDN edge with empty/benign SNI per GreatFire FreeBrowser design
			tlsConn := tls.Client(raw, &tls.Config{
				ServerName: "",
				RootCAs:    AndroidRootCertPool(),
				MinVersion: tls.VersionTLS12,
			})
			if err := tlsConn.HandshakeContext(dialCtx); err != nil {
				_ = raw.Close()
				return nil, err
			}
			return tlsConn, nil
		},
	}
	defer tr.CloseIdleConnections()
	return tr.RoundTrip(outReq)
}

// dialViaSOCKS5 connects to an upstream SOCKS5 server (RFC 1928 + RFC 1929 username/password auth)
// passing the domain name directly so DNS resolution happens remotely on the tunnel endpoint (zero DNS leak!).
func dialViaSOCKS5(ctx context.Context, proxyAddr, username, password, targetHost, targetPort string) (net.Conn, error) {
	var d net.Dialer
	d.Timeout = 15 * time.Second
	conn, err := d.DialContext(ctx, "tcp", proxyAddr)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))

	if username != "" {
		_, err = conn.Write([]byte{0x05, 0x02, 0x00, 0x02})
	} else {
		_, err = conn.Write([]byte{0x05, 0x01, 0x00})
	}
	if err != nil {
		_ = conn.Close()
		return nil, err
	}

	var methodResp [2]byte
	if _, err := io.ReadFull(conn, methodResp[:]); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if methodResp[0] != 0x05 {
		_ = conn.Close()
		return nil, errors.New("invalid SOCKS5 version from upstream")
	}
	if methodResp[1] == 0x02 {
		uBytes := []byte(username)
		pBytes := []byte(password)
		if len(uBytes) > 255 || len(pBytes) > 255 {
			_ = conn.Close()
			return nil, errors.New("SOCKS5 credentials too long")
		}
		authBuf := make([]byte, 0, 3+len(uBytes)+len(pBytes))
		authBuf = append(authBuf, 0x01, byte(len(uBytes)))
		authBuf = append(authBuf, uBytes...)
		authBuf = append(authBuf, byte(len(pBytes)))
		authBuf = append(authBuf, pBytes...)
		if _, err := conn.Write(authBuf); err != nil {
			_ = conn.Close()
			return nil, err
		}
		var authResp [2]byte
		if _, err := io.ReadFull(conn, authResp[:]); err != nil || authResp[1] != 0x00 {
			_ = conn.Close()
			return nil, errors.New("SOCKS5 authentication failed")
		}
	} else if methodResp[1] != 0x00 {
		_ = conn.Close()
		return nil, fmt.Errorf("SOCKS5 unsupported auth method 0x%02x", methodResp[1])
	}

	portNum, err := net.LookupPort("tcp", targetPort)
	if err != nil {
		_ = conn.Close()
		return nil, err
	}

	req := []byte{0x05, 0x01, 0x00}
	if ip := net.ParseIP(targetHost); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			req = append(req, 0x01)
			req = append(req, ip4...)
		} else {
			req = append(req, 0x04)
			req = append(req, ip.To16()...)
		}
	} else {
		if len(targetHost) > 255 {
			_ = conn.Close()
			return nil, errors.New("hostname too long for SOCKS5")
		}
		req = append(req, 0x03, byte(len(targetHost)))
		req = append(req, []byte(targetHost)...)
	}
	req = append(req, byte(portNum>>8), byte(portNum&0xFF))

	if _, err := conn.Write(req); err != nil {
		_ = conn.Close()
		return nil, err
	}

	var hdr [4]byte
	if _, err := io.ReadFull(conn, hdr[:]); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if hdr[1] != 0x00 {
		_ = conn.Close()
		return nil, fmt.Errorf("SOCKS5 connect rejected (code 0x%02x)", hdr[1])
	}

	var skip int
	switch hdr[3] {
	case 0x01:
		skip = 4 + 2
	case 0x04:
		skip = 16 + 2
	case 0x03:
		var l [1]byte
		if _, err := io.ReadFull(conn, l[:]); err != nil {
			_ = conn.Close()
			return nil, err
		}
		skip = int(l[0]) + 2
	default:
		_ = conn.Close()
		return nil, errors.New("unknown SOCKS5 address type")
	}
	if _, err := io.CopyN(io.Discard, conn, int64(skip)); err != nil {
		_ = conn.Close()
		return nil, err
	}

	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

// dialViaHTTPConnect connects through an upstream HTTP CONNECT proxy (such as MASQUE or corporate/remote HTTP proxy).
func dialViaHTTPConnect(ctx context.Context, proxyAddr, username, password, targetHost, targetPort string) (net.Conn, error) {
	var d net.Dialer
	d.Timeout = 12 * time.Second
	conn, err := d.DialContext(ctx, "tcp", proxyAddr)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))

	dest := net.JoinHostPort(targetHost, targetPort)
	var sb strings.Builder
	sb.WriteString("CONNECT " + dest + " HTTP/1.1\r\n")
	sb.WriteString("Host: " + dest + "\r\n")
	if username != "" {
		cred := base64.StdEncoding.EncodeToString([]byte(username + ":" + password))
		sb.WriteString("Proxy-Authorization: Basic " + cred + "\r\n")
	}
	sb.WriteString("\r\n")

	if _, err := conn.Write([]byte(sb.String())); err != nil {
		_ = conn.Close()
		return nil, err
	}

	br := bufio.NewReader(conn)
	resp, err := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		_ = conn.Close()
		return nil, fmt.Errorf("upstream HTTP CONNECT returned %d", resp.StatusCode)
	}

	_ = conn.SetDeadline(time.Time{})
	if br.Buffered() > 0 {
		return &bufferedConn{Conn: conn, r: io.MultiReader(br, conn)}, nil
	}
	return conn, nil
}

type bufferedConn struct {
	net.Conn
	r io.Reader
}

func (bc *bufferedConn) Read(p []byte) (int, error) {
	return bc.r.Read(p)
}

func removeHopByHopHeaders(h http.Header) {
	for _, k := range []string{
		"Proxy-Connection",
		"Proxy-Authenticate",
		"Proxy-Authorization",
		"Connection",
		"Keep-Alive",
		"TE",
		"Trailer",
		"Transfer-Encoding",
		"Upgrade",
	} {
		h.Del(k)
	}
}
