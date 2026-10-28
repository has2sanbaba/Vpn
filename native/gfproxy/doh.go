package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// DoHEndpoint represents a bootstrap-free DNS-over-HTTPS server reachable directly by IP
// so ISP UDP/53 DNS poisoning can never hijack bootstrap resolution.
type DoHEndpoint struct {
	Name       string
	IP         string
	Port       string
	ServerName string
	Path       string
}

var defaultDoHEndpoints = []DoHEndpoint{
	{Name: "Cloudflare-1", IP: "1.1.1.1", Port: "443", ServerName: "cloudflare-dns.com", Path: "/dns-query"},
	{Name: "Cloudflare-2", IP: "1.0.0.1", Port: "443", ServerName: "cloudflare-dns.com", Path: "/dns-query"},
	{Name: "Google-1", IP: "8.8.8.8", Port: "443", ServerName: "dns.google", Path: "/dns-query"},
	{Name: "Quad9", IP: "9.9.9.9", Port: "443", ServerName: "dns.quad9.net", Path: "/dns-query"},
	{Name: "AdGuard", IP: "94.140.14.14", Port: "443", ServerName: "dns.adguard-dns.com", Path: "/dns-query"},
}

type dnsCacheItem struct {
	ips       []string
	expiresAt time.Time
}

// DoHResolver resolves hostnames over encrypted DNS-over-HTTPS (RFC 8484 wireformat),
// using TLS ClientHello record fragmentation on the DoH handshake itself and filtering
// out ISP-poisoned censorship IPs (inspired by GreatFire FreeBrowser fbproxy).
type DoHResolver struct {
	mu          sync.RWMutex
	cache       map[string]dnsCacheItem
	customDoH   string
	fragEnabled bool
	fragSize    int
	fragDelay   time.Duration

	Queries   atomic.Uint64
	CacheHits atomic.Uint64
}

func NewDoHResolver() *DoHResolver {
	return &DoHResolver{
		cache:       make(map[string]dnsCacheItem),
		fragEnabled: true,
		fragSize:    24,
		fragDelay:   4 * time.Millisecond,
	}
}

func (r *DoHResolver) Configure(customDoH string, fragEnabled bool, fragSize int, fragDelay time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.customDoH = strings.TrimSpace(customDoH)
	r.fragEnabled = fragEnabled
	if fragSize > 0 {
		r.fragSize = fragSize
	}
	if fragDelay >= 0 {
		r.fragDelay = fragDelay
	}
}

// Resolve returns clean IPv4/IPv6 addresses for host. If host is already an IP literal, it returns it directly.
func (r *DoHResolver) Resolve(ctx context.Context, host string) ([]string, error) {
	host = strings.TrimSpace(strings.TrimSuffix(host, "."))
	if host == "" {
		return nil, errors.New("empty host")
	}
	if ip := net.ParseIP(host); ip != nil {
		return []string{ip.String()}, nil
	}
	if strings.EqualFold(host, "localhost") {
		return []string{"127.0.0.1"}, nil
	}

	key := strings.ToLower(host)
	r.mu.RLock()
	cached, ok := r.cache[key]
	fragEnabled := r.fragEnabled
	fragSize := r.fragSize
	fragDelay := r.fragDelay
	customDoH := r.customDoH
	r.mu.RUnlock()

	if ok && time.Now().Before(cached.expiresAt) && len(cached.ips) > 0 {
		r.CacheHits.Add(1)
		return cached.ips, nil
	}

	r.Queries.Add(1)

	endpoints := make([]DoHEndpoint, 0, len(defaultDoHEndpoints)+1)
	if customDoH != "" {
		if ep, ok := parseCustomDoH(customDoH); ok {
			endpoints = append(endpoints, ep)
		}
	}
	endpoints = append(endpoints, defaultDoHEndpoints...)

	type dohResult struct {
		ips []string
		ttl uint32
		err error
	}

	queryCtx, cancel := context.WithTimeout(ctx, 7*time.Second)
	defer cancel()

	// Race top 3 DoH servers concurrently for lowest latency (FreeBrowser method)
	limit := 3
	if len(endpoints) < limit {
		limit = len(endpoints)
	}
	resCh := make(chan dohResult, limit)
	for i := 0; i < limit; i++ {
		ep := endpoints[i]
		go func(endpoint DoHEndpoint) {
			ips, ttl, err := queryDoHWireformat(queryCtx, endpoint, key, 1, fragEnabled, fragSize, fragDelay) // QTYPE A = 1
			if err != nil || len(ips) == 0 {
				// Try AAAA (28) if A gave nothing
				ips6, ttl6, err6 := queryDoHWireformat(queryCtx, endpoint, key, 28, fragEnabled, fragSize, fragDelay)
				if err6 == nil && len(ips6) > 0 {
					resCh <- dohResult{ips: ips6, ttl: ttl6}
					return
				}
			}
			resCh <- dohResult{ips: ips, ttl: ttl, err: err}
		}(ep)
	}

	var lastErr error
	for i := 0; i < limit; i++ {
		select {
		case res := <-resCh:
			if res.err == nil && len(res.ips) > 0 {
				ttl := time.Duration(res.ttl) * time.Second
				if ttl < 60*time.Second {
					ttl = 60 * time.Second
				}
				if ttl > 30*time.Minute {
					ttl = 30 * time.Minute
				}
				r.mu.Lock()
				r.cache[key] = dnsCacheItem{
					ips:       res.ips,
					expiresAt: time.Now().Add(ttl),
				}
				r.mu.Unlock()
				return res.ips, nil
			}
			if res.err != nil {
				lastErr = res.err
			}
		case <-queryCtx.Done():
			lastErr = queryCtx.Err()
		}
	}

	// Secondary fallback: remaining DoH endpoints sequentially
	for i := limit; i < len(endpoints); i++ {
		ips, ttl, err := queryDoHWireformat(ctx, endpoints[i], key, 1, fragEnabled, fragSize, fragDelay)
		if err == nil && len(ips) > 0 {
			dur := time.Duration(ttl) * time.Second
			if dur < 60*time.Second {
				dur = 60 * time.Second
			}
			r.mu.Lock()
			r.cache[key] = dnsCacheItem{ips: ips, expiresAt: time.Now().Add(dur)}
			r.mu.Unlock()
			return ips, nil
		}
		lastErr = err
	}

	if lastErr == nil {
		lastErr = fmt.Errorf("no clean DNS records found for %s", host)
	}
	return nil, lastErr
}

func parseCustomDoH(raw string) (DoHEndpoint, bool) {
	raw = strings.TrimPrefix(raw, "https://")
	raw = strings.TrimPrefix(raw, "doh://")
	slash := strings.IndexByte(raw, '/')
	hostPort := raw
	path := "/dns-query"
	if slash >= 0 {
		hostPort = raw[:slash]
		path = raw[slash:]
	}
	host, port, err := net.SplitHostPort(hostPort)
	if err != nil {
		host = hostPort
		port = "443"
	}
	if ip := net.ParseIP(host); ip != nil {
		return DoHEndpoint{
			Name:       "Custom-IP",
			IP:         ip.String(),
			Port:       port,
			ServerName: ip.String(),
			Path:       path,
		}, true
	}
	if strings.EqualFold(host, "cloudflare-dns.com") || strings.EqualFold(host, "one.one.one.one") {
		return DoHEndpoint{Name: "Custom-CF", IP: "1.1.1.1", Port: port, ServerName: host, Path: path}, true
	}
	if strings.EqualFold(host, "dns.google") {
		return DoHEndpoint{Name: "Custom-Google", IP: "8.8.8.8", Port: port, ServerName: host, Path: path}, true
	}
	if strings.EqualFold(host, "dns.quad9.net") {
		return DoHEndpoint{Name: "Custom-Quad9", IP: "9.9.9.9", Port: port, ServerName: host, Path: path}, true
	}
	return DoHEndpoint{}, false
}

func queryDoHWireformat(
	ctx context.Context,
	ep DoHEndpoint,
	domain string,
	qtype uint16,
	fragEnabled bool,
	fragSize int,
	fragDelay time.Duration,
) ([]string, uint32, error) {
	wireReq, txID, err := buildDNSQuery(domain, qtype)
	if err != nil {
		return nil, 0, err
	}

	transport := &http.Transport{
		ForceAttemptHTTP2: true,
		DialTLSContext: func(dialCtx context.Context, network, _ string) (net.Conn, error) {
			var d net.Dialer
			d.Timeout = 5 * time.Second
			rawConn, err := d.DialContext(dialCtx, "tcp", net.JoinHostPort(ep.IP, ep.Port))
			if err != nil {
				return nil, err
			}
			var wrapped net.Conn = rawConn
			if fragEnabled {
				wrapped = NewFragmentingConn(rawConn, fragSize, fragDelay)
			}
			tlsCfg := &tls.Config{
				ServerName: ep.ServerName,
				NextProtos: []string{"h2", "http/1.1"},
				MinVersion: tls.VersionTLS12,
			}
			tlsConn := tls.Client(wrapped, tlsCfg)
			if err := tlsConn.HandshakeContext(dialCtx); err != nil {
				_ = rawConn.Close()
				return nil, err
			}
			return tlsConn, nil
		},
	}
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		Timeout:   6 * time.Second,
	}

	urlStr := fmt.Sprintf("https://%s%s", ep.ServerName, ep.Path)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, urlStr, bytes.NewReader(wireReq))
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	req.Header.Set("User-Agent", "Mozilla/5.0 Chrome/128.0.0.0")

	resp, err := client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, 0, fmt.Errorf("DoH %s status %d", ep.Name, resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	if err != nil {
		return nil, 0, err
	}

	return parseDNSResponse(body, txID)
}

// buildDNSQuery constructs a standard RFC 1035 DNS query message.
func buildDNSQuery(domain string, qtype uint16) ([]byte, uint16, error) {
	var idBytes [2]byte
	if _, err := rand.Read(idBytes[:]); err != nil {
		idBytes = [2]byte{0x12, 0x34}
	}
	txID := binary.BigEndian.Uint16(idBytes[:])

	var buf bytes.Buffer
	// Header: ID, Flags (RD=1 -> 0x0100), QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
	_ = binary.Write(&buf, binary.BigEndian, txID)
	_ = binary.Write(&buf, binary.BigEndian, uint16(0x0100))
	_ = binary.Write(&buf, binary.BigEndian, uint16(1))
	_ = binary.Write(&buf, binary.BigEndian, uint16(0))
	_ = binary.Write(&buf, binary.BigEndian, uint16(0))
	_ = binary.Write(&buf, binary.BigEndian, uint16(0))

	labels := strings.Split(strings.TrimSuffix(domain, "."), ".")
	for _, label := range labels {
		if len(label) == 0 || len(label) > 63 {
			return nil, 0, fmt.Errorf("invalid DNS label %q", label)
		}
		buf.WriteByte(byte(len(label)))
		buf.WriteString(label)
	}
	buf.WriteByte(0x00) // root label

	_ = binary.Write(&buf, binary.BigEndian, qtype)     // QTYPE
	_ = binary.Write(&buf, binary.BigEndian, uint16(1)) // QCLASS IN
	return buf.Bytes(), txID, nil
}

// parseDNSResponse parses A and AAAA answers from a DNS wireformat response and filters poisoned IPs.
func parseDNSResponse(msg []byte, expectedID uint16) ([]string, uint32, error) {
	if len(msg) < 12 {
		return nil, 0, errors.New("short DNS message")
	}
	id := binary.BigEndian.Uint16(msg[0:2])
	if expectedID != 0 && id != expectedID {
		return nil, 0, errors.New("DNS transaction ID mismatch")
	}
	flags := binary.BigEndian.Uint16(msg[2:4])
	rcode := flags & 0x000F
	if rcode != 0 {
		return nil, 0, fmt.Errorf("DNS rcode %d", rcode)
	}
	qdCount := int(binary.BigEndian.Uint16(msg[4:6]))
	anCount := int(binary.BigEndian.Uint16(msg[6:8]))

	offset := 12
	for i := 0; i < qdCount; i++ {
		next, err := skipDNSName(msg, offset)
		if err != nil {
			return nil, 0, err
		}
		offset = next + 4 // QTYPE + QCLASS
		if offset > len(msg) {
			return nil, 0, errors.New("truncated DNS question")
		}
	}

	var ips []string
	var minTTL uint32 = 300
	for i := 0; i < anCount; i++ {
		next, err := skipDNSName(msg, offset)
		if err != nil {
			return nil, 0, err
		}
		offset = next
		if offset+10 > len(msg) {
			return nil, 0, errors.New("truncated DNS answer header")
		}
		rrType := binary.BigEndian.Uint16(msg[offset : offset+2])
		ttl := binary.BigEndian.Uint32(msg[offset+4 : offset+8])
		rdLen := int(binary.BigEndian.Uint16(msg[offset+8 : offset+10]))
		offset += 10
		if offset+rdLen > len(msg) {
			return nil, 0, errors.New("truncated DNS rdata")
		}
		rdata := msg[offset : offset+rdLen]
		offset += rdLen

		if rrType == 1 && rdLen == 4 {
			ip := net.IP(rdata)
			if !IsPoisonedOrBogonIP(ip) {
				ips = append(ips, ip.String())
				if ttl > 0 && ttl < minTTL {
					minTTL = ttl
				}
			}
		} else if rrType == 28 && rdLen == 16 {
			ip := net.IP(rdata)
			if !IsPoisonedOrBogonIP(ip) {
				ips = append(ips, ip.String())
				if ttl > 0 && ttl < minTTL {
					minTTL = ttl
				}
			}
		}
	}
	return ips, minTTL, nil
}

func skipDNSName(msg []byte, offset int) (int, error) {
	jumps := 0
	for {
		if offset >= len(msg) {
			return 0, errors.New("name out of bounds")
		}
		b := msg[offset]
		if b == 0 {
			return offset + 1, nil
		}
		if (b & 0xC0) == 0xC0 {
			if offset+1 >= len(msg) {
				return 0, errors.New("compressed pointer out of bounds")
			}
			return offset + 2, nil
		}
		if (b & 0xC0) != 0 {
			return 0, errors.New("invalid DNS label compression")
		}
		offset += 1 + int(b)
		jumps++
		if jumps > 128 {
			return 0, errors.New("DNS label loop")
		}
	}
}

// IsPoisonedOrBogonIP detects ISP censorship sinkhole IPs (such as 10.10.34.34-36 in Iran,
// 0.0.0.0, loopback, link-local, private RFC1918) returned for public internet domains.
func IsPoisonedOrBogonIP(ip net.IP) bool {
	if ip == nil || ip.IsUnspecified() || ip.IsLoopback() || ip.IsMulticast() || ip.IsLinkLocalUnicast() {
		return true
	}
	if ip4 := ip.To4(); ip4 != nil {
		// Iranian DCI censorship block page IPs: 10.10.34.34, 10.10.34.35, 10.10.34.36 (and all RFC1918)
		if ip4[0] == 10 {
			return true
		}
		if ip4[0] == 172 && ip4[1] >= 16 && ip4[1] <= 31 {
			return true
		}
		if ip4[0] == 192 && ip4[1] == 168 {
			return true
		}
		if ip4[0] == 127 || ip4[0] == 0 {
			return true
		}
	} else if ip.IsPrivate() {
		return true
	}
	return false
}
