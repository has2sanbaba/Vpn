package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

// GatewayServer accepts BOTH HTTP/HTTPS CONNECT and SOCKS5 connections on a single localhost port,
// auto-detecting the protocol from the first byte (0x05 -> SOCKS5, ASCII -> HTTP/CONNECT).
type GatewayServer struct {
	router   *UpstreamRouter
	doh      *DoHResolver
	listener net.Listener

	BytesUp           atomic.Uint64
	BytesDown         atomic.Uint64
	TotalConnections  atomic.Uint64
	ActiveConnections atomic.Int64
	BlockedRequests   atomic.Uint64
	StartedAt         time.Time
}

func NewGatewayServer(router *UpstreamRouter, doh *DoHResolver) *GatewayServer {
	return &GatewayServer{
		router:    router,
		doh:       doh,
		StartedAt: time.Now(),
	}
}

func (g *GatewayServer) ListenAndServe(listenAddr string) error {
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return err
	}
	g.listener = ln
	log.Printf("[gfproxy] Unified HTTP/SOCKS5 gateway listening on %s", ln.Addr().String())

	for {
		conn, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			continue
		}
		go g.handleClient(conn)
	}
}

func (g *GatewayServer) Addr() string {
	if g.listener == nil {
		return ""
	}
	return g.listener.Addr().String()
}

func (g *GatewayServer) Close() error {
	if g.listener != nil {
		return g.listener.Close()
	}
	return nil
}

func (g *GatewayServer) handleClient(client net.Conn) {
	g.TotalConnections.Add(1)
	g.ActiveConnections.Add(1)
	defer func() {
		g.ActiveConnections.Add(-1)
		_ = client.Close()
	}()

	_ = client.SetReadDeadline(time.Now().Add(20 * time.Second))
	br := bufio.NewReaderSize(client, 8192)
	first, err := br.Peek(1)
	if err != nil {
		return
	}
	_ = client.SetReadDeadline(time.Time{})

	if first[0] == 0x05 {
		g.handleSOCKS5(client, br)
	} else {
		g.handleHTTP(client, br)
	}
}

func (g *GatewayServer) handleSOCKS5(client net.Conn, br *bufio.Reader) {
	// Greeting: VER, NMETHODS, METHODS...
	hdr, err := br.Peek(2)
	if err != nil {
		return
	}
	nMethods := int(hdr[1])
	greeting := make([]byte, 2+nMethods)
	if _, err := io.ReadFull(br, greeting); err != nil {
		return
	}
	// Reply: no authentication required on localhost
	if _, err := client.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// Request: VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT
	var reqHdr [4]byte
	if _, err := io.ReadFull(br, reqHdr[:]); err != nil {
		return
	}
	if reqHdr[0] != 0x05 || reqHdr[1] != 0x01 {
		_, _ = client.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var host string
	switch reqHdr[3] {
	case 0x01:
		var ip4 [4]byte
		if _, err := io.ReadFull(br, ip4[:]); err != nil {
			return
		}
		host = net.IP(ip4[:]).String()
	case 0x03:
		l, err := br.ReadByte()
		if err != nil {
			return
		}
		dom := make([]byte, int(l))
		if _, err := io.ReadFull(br, dom); err != nil {
			return
		}
		host = string(dom)
	case 0x04:
		var ip6 [16]byte
		if _, err := io.ReadFull(br, ip6[:]); err != nil {
			return
		}
		host = net.IP(ip6[:]).String()
	default:
		_, _ = client.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var portBytes [2]byte
	if _, err := io.ReadFull(br, portBytes[:]); err != nil {
		return
	}
	port := strconv.Itoa(int(binary.BigEndian.Uint16(portBytes[:])))

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	remote, err := g.router.DialTarget(ctx, host, port)
	cancel()
	if err != nil {
		if errors.Is(err, ErrKillSwitchBlocked) {
			g.BlockedRequests.Add(1)
			_, _ = client.Write([]byte{0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		} else {
			_, _ = client.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		}
		return
	}
	defer remote.Close()

	if _, err := client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	g.pipeBidirectional(client, br, remote)
}

func (g *GatewayServer) handleHTTP(client net.Conn, br *bufio.Reader) {
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}

	if strings.EqualFold(req.Method, http.MethodConnect) {
		host, port, splitErr := net.SplitHostPort(req.Host)
		if splitErr != nil {
			host = req.Host
			port = "443"
		}

		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		remote, dialErr := g.router.DialTarget(ctx, host, port)
		cancel()
		if dialErr != nil {
			if errors.Is(dialErr, ErrKillSwitchBlocked) {
				g.BlockedRequests.Add(1)
				_, _ = io.WriteString(client, "HTTP/1.1 503 VPN Kill-Switch Active\r\nContent-Length: 0\r\n\r\n")
			} else {
				_, _ = io.WriteString(client, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
			}
			return
		}
		defer remote.Close()

		if _, err := io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			return
		}
		g.pipeBidirectional(client, br, remote)
		return
	}

	// Plain HTTP proxy request (e.g., GET http://example.com/path HTTP/1.1)
	if req.URL.Scheme == "" {
		req.URL.Scheme = "http"
	}
	if req.URL.Host == "" {
		req.URL.Host = req.Host
	}

	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	resp, err := g.router.ForwardPlainHTTP(ctx, req)
	if err != nil {
		if errors.Is(err, ErrKillSwitchBlocked) {
			g.BlockedRequests.Add(1)
			_, _ = io.WriteString(client, "HTTP/1.1 503 VPN Kill-Switch Active\r\nContent-Type: text/plain\r\n\r\nBlocked by Kill-Switch")
		} else {
			msg := fmt.Sprintf("GreatFire Proxy Error: %v", err)
			_, _ = fmt.Fprintf(client, "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\n\r\n%s", len(msg), msg)
		}
		return
	}
	defer resp.Body.Close()

	countingBody := &countingReader{r: resp.Body, counter: &g.BytesDown}
	resp.Body = io.NopCloser(countingBody)
	_ = resp.Write(client)
}

func (g *GatewayServer) pipeBidirectional(client net.Conn, clientReader io.Reader, remote net.Conn) {
	done := make(chan struct{}, 2)

	// Client -> Remote (Upload)
	go func() {
		n, _ := io.Copy(remote, clientReader)
		if n > 0 {
			g.BytesUp.Add(uint64(n))
		}
		if tc, ok := remote.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		} else if fc, ok := remote.(*FragmentingConn); ok {
			if inner, ok2 := fc.Conn.(*net.TCPConn); ok2 {
				_ = inner.CloseWrite()
			}
		}
		done <- struct{}{}
	}()

	// Remote -> Client (Download)
	go func() {
		n, _ := io.Copy(client, remote)
		if n > 0 {
			g.BytesDown.Add(uint64(n))
		}
		if tc, ok := client.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		}
		done <- struct{}{}
	}()

	<-done
	<-done
}

type countingReader struct {
	r       io.Reader
	counter *atomic.Uint64
}

func (cr *countingReader) Read(p []byte) (int, error) {
	n, err := cr.r.Read(p)
	if n > 0 {
		cr.counter.Add(uint64(n))
	}
	return n, err
}

// StatusResponse is returned by the authenticated localhost control API for live UI telemetry.
type StatusResponse struct {
	OK                bool          `json:"ok"`
	GatewayAddr       string        `json:"gateway_addr"`
	UptimeSec         int64         `json:"uptime_sec"`
	BytesUp           uint64        `json:"bytes_up"`
	BytesDown         uint64        `json:"bytes_down"`
	TotalConnections  uint64        `json:"total_connections"`
	ActiveConnections int64         `json:"active_connections"`
	BlockedRequests   uint64        `json:"blocked_requests"`
	DoHQueries        uint64        `json:"doh_queries"`
	DoHCacheHits      uint64        `json:"doh_cache_hits"`
	Config            RuntimeConfig `json:"config"`
}

type ProbeResponse struct {
	OK        bool   `json:"ok"`
	Status    int    `json:"status"`
	LatencyMs int64  `json:"latency_ms"`
	Mode      string `json:"mode"`
	Error     string `json:"error,omitempty"`
}

func NewControlMux(gw *GatewayServer, router *UpstreamRouter, doh *DoHResolver, token string) http.Handler {
	mux := http.NewServeMux()

	authorized := func(w http.ResponseWriter, r *http.Request) bool {
		if token != "" && r.Header.Get("X-GF-Token") != token {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return false
		}
		return true
	}

	mux.HandleFunc("/status", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		resp := StatusResponse{
			OK:                true,
			GatewayAddr:       gw.Addr(),
			UptimeSec:         int64(time.Since(gw.StartedAt).Seconds()),
			BytesUp:           gw.BytesUp.Load(),
			BytesDown:         gw.BytesDown.Load(),
			TotalConnections:  gw.TotalConnections.Load(),
			ActiveConnections: gw.ActiveConnections.Load(),
			BlockedRequests:   gw.BlockedRequests.Load(),
			DoHQueries:        doh.Queries.Load(),
			DoHCacheHits:      doh.CacheHits.Load(),
			Config:            router.GetConfig(),
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	})

	mux.HandleFunc("/config", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var next RuntimeConfig
		if err := json.NewDecoder(io.LimitReader(r.Body, 65536)).Decode(&next); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		router.UpdateConfig(next)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "config": router.GetConfig()})
	})

	mux.HandleFunc("/probe", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		target := r.URL.Query().Get("url")
		if target == "" {
			target = "https://www.google.com/generate_204"
		}
		u, err := url.Parse(target)
		if err != nil || (u.Scheme != "https" && u.Scheme != "http") {
			w.WriteHeader(http.StatusBadRequest)
			_ = json.NewEncoder(w).Encode(ProbeResponse{OK: false, Error: "invalid probe url"})
			return
		}

		start := time.Now()
		ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
		defer cancel()

		cfg := router.GetConfig()
		tr := &http.Transport{
			ForceAttemptHTTP2: true,
			DialContext: func(dialCtx context.Context, _, addr string) (net.Conn, error) {
				h, p, splitErr := net.SplitHostPort(addr)
				if splitErr != nil {
					h = u.Hostname()
					p = "80"
				}
				return router.DialTarget(dialCtx, h, p)
			},
			DialTLSContext: func(dialCtx context.Context, _, addr string) (net.Conn, error) {
				h, p, splitErr := net.SplitHostPort(addr)
				if splitErr != nil {
					h = u.Hostname()
					p = "443"
				}
				raw, dialErr := router.DialTarget(dialCtx, h, p)
				if dialErr != nil {
					return nil, dialErr
				}
				tlsConn := tls.Client(raw, &tls.Config{
					ServerName: h,
					NextProtos: []string{"h2", "http/1.1"},
					MinVersion: tls.VersionTLS12,
				})
				if hsErr := tlsConn.HandshakeContext(dialCtx); hsErr != nil {
					_ = raw.Close()
					return nil, hsErr
				}
				return tlsConn, nil
			},
		}
		defer tr.CloseIdleConnections()

		client := &http.Client{Transport: tr, Timeout: 12 * time.Second}
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
		req.Header.Set("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")

		var resp *http.Response
		if cfg.Mode == ModeEnvoyHTTP && cfg.EnvoyURL != "" {
			resp, err = router.ForwardPlainHTTP(ctx, req)
		} else {
			resp, err = client.Do(req)
		}

		elapsed := time.Since(start).Milliseconds()
		w.Header().Set("Content-Type", "application/json")
		if err != nil {
			_ = json.NewEncoder(w).Encode(ProbeResponse{
				OK:        false,
				LatencyMs: elapsed,
				Mode:      string(cfg.Mode),
				Error:     err.Error(),
			})
			return
		}
		_ = resp.Body.Close()
		_ = json.NewEncoder(w).Encode(ProbeResponse{
			OK:        resp.StatusCode >= 200 && resp.StatusCode < 400,
			Status:    resp.StatusCode,
			LatencyMs: elapsed,
			Mode:      string(cfg.Mode),
		})
	})

	return mux
}
