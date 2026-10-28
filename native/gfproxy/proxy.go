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
	"sync"
	"sync/atomic"
	"time"
)

// GatewayServer accepts BOTH HTTP/HTTPS CONNECT and SOCKS5 connections on a single localhost port,
// racing Direct Anti-DPI + Clean-IP DoH + Verified Upstream Tunnels per connection with 0ms startup wait.
type GatewayServer struct {
	router   *UpstreamRouter
	doh      *DoHResolver
	pool     *NodePool
	racer    *ConnectionRacer
	listener net.Listener

	BytesUp           atomic.Uint64
	BytesDown         atomic.Uint64
	TotalConnections  atomic.Uint64
	ActiveConnections atomic.Int64
	BlockedRequests   atomic.Uint64
	StartedAt         time.Time
}

func NewGatewayServer(router *UpstreamRouter, doh *DoHResolver, pool *NodePool, racer *ConnectionRacer) *GatewayServer {
	if pool == nil {
		pool = NewNodePool(doh, "")
	}
	if racer == nil {
		racer = NewConnectionRacer(router, pool, "")
	}
	return &GatewayServer{
		router:    router,
		doh:       doh,
		pool:      pool,
		racer:     racer,
		StartedAt: time.Now(),
	}
}

func (g *GatewayServer) ListenAndServe(listenAddr string) error {
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return err
	}
	g.listener = ln
	log.Printf("[gfproxy] Instant FreeBrowser/Envoy gateway listening on %s", ln.Addr().String())

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
	if g.pool != nil {
		g.pool.Close()
	}
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
	br := bufio.NewReaderSize(client, 16384)
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

// readInitialClientPayload reads the client's initial burst (such as a full TLS 1.3 ClientHello,
// including multi-packet post-quantum ClientHellos > 1460 bytes) so ConnectionRacer can race legs safely.
func readInitialClientPayload(client net.Conn, br *bufio.Reader) ([]byte, error) {
	_ = client.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
	defer func() { _ = client.SetReadDeadline(time.Time{}) }()

	var tmp [8192]byte
	n, err := br.Read(tmp[:])
	if n == 0 {
		var netErr net.Error
		if errors.As(err, &netErr) && netErr.Timeout() {
			return nil, nil // Server-speaks-first protocol
		}
		return nil, err
	}

	buf := make([]byte, n)
	copy(buf, tmp[:n])

	// Ensure full TLS Handshake ClientHello record is read if split across TCP segments
	if buf[0] == 0x16 && (len(buf) < 2 || buf[1] == 0x03) {
		_ = client.SetReadDeadline(time.Now().Add(1500 * time.Millisecond))
		for len(buf) < 5 {
			var b [1]byte
			if _, rErr := io.ReadFull(br, b[:]); rErr != nil {
				return buf, nil
			}
			buf = append(buf, b[0])
		}
		recLen := int(binary.BigEndian.Uint16(buf[3:5]))
		want := 5 + recLen
		if recLen > 0 && recLen <= 32768 && len(buf) < want {
			rem := make([]byte, want-len(buf))
			if _, rErr := io.ReadFull(br, rem); rErr == nil {
				buf = append(buf, rem...)
			}
		}
	}

	// Drain any immediately buffered trailing bytes in bufio.Reader
	if extra := br.Buffered(); extra > 0 && extra <= 16384 {
		tail := make([]byte, extra)
		if m, _ := br.Read(tail); m > 0 {
			buf = append(buf, tail[:m]...)
		}
	}

	return buf, nil
}

func (g *GatewayServer) handleSOCKS5(client net.Conn, br *bufio.Reader) {
	hdr, err := br.Peek(2)
	if err != nil {
		return
	}
	nMethods := int(hdr[1])
	greeting := make([]byte, 2+nMethods)
	if _, err := io.ReadFull(br, greeting); err != nil {
		return
	}
	if _, err := client.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

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

	cfg := g.router.GetConfig()
	if cfg.Mode == ModeBlocked {
		g.BlockedRequests.Add(1)
		_, _ = client.Write([]byte{0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	if cfg.Mode == ModeSOCKS5 || cfg.Mode == ModeHTTPProxy || cfg.Mode == ModeDirect {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		remote, dialErr := g.router.DialTarget(ctx, host, port)
		cancel()
		if dialErr != nil {
			_, _ = client.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}
		defer remote.Close()
		if _, err := client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
			return
		}
		g.pipeBidirectional(client, br, remote)
		return
	}

	// Instant acknowledge + parallel ClientHello/ServerHello racing
	if _, err := client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	initialPayload, err := readInitialClientPayload(client, br)
	if err != nil {
		return
	}
	if len(initialPayload) > 0 {
		g.BytesUp.Add(uint64(len(initialPayload)))
	}

	ctx, cancel := context.WithTimeout(context.Background(), 18*time.Second)
	remote, dialErr := g.racer.ConnectAndVerify(ctx, host, port, initialPayload)
	cancel()
	if dialErr != nil {
		return
	}
	defer remote.Close()
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

		cfg := g.router.GetConfig()
		if cfg.Mode == ModeBlocked {
			g.BlockedRequests.Add(1)
			_, _ = io.WriteString(client, "HTTP/1.1 503 Blocked\r\nContent-Length: 0\r\n\r\n")
			return
		}
		if cfg.Mode == ModeSOCKS5 || cfg.Mode == ModeHTTPProxy || cfg.Mode == ModeDirect {
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			remote, dialErr := g.router.DialTarget(ctx, host, port)
			cancel()
			if dialErr != nil {
				_, _ = io.WriteString(client, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
				return
			}
			defer remote.Close()
			if _, err := io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
				return
			}
			g.pipeBidirectional(client, br, remote)
			return
		}

		// Instant 0ms HTTP CONNECT acknowledgement -> capture TLS ClientHello -> parallel race!
		if _, err := io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			return
		}
		initialPayload, readErr := readInitialClientPayload(client, br)
		if readErr != nil {
			return
		}
		if len(initialPayload) > 0 {
			g.BytesUp.Add(uint64(len(initialPayload)))
		}

		ctx, cancel := context.WithTimeout(context.Background(), 18*time.Second)
		remote, dialErr := g.racer.ConnectAndVerify(ctx, host, port, initialPayload)
		cancel()
		if dialErr != nil {
			return
		}
		defer remote.Close()
		g.pipeBidirectional(client, br, remote)
		return
	}

	// Plain HTTP proxy request (e.g., GET http://example.com/path HTTP/1.1)
	if g.router.GetConfig().Mode == ModeBlocked {
		g.BlockedRequests.Add(1)
		_, _ = io.WriteString(client, "HTTP/1.1 503 Blocked\r\nContent-Length: 0\r\n\r\n")
		return
	}
	if req.URL.Scheme == "" {
		req.URL.Scheme = "http"
	}
	if req.URL.Host == "" {
		req.URL.Host = req.Host
	}

	ctx, cancel := context.WithTimeout(context.Background(), 16*time.Second)
	defer cancel()

	resp, err := g.forwardSmartHTTP(ctx, req)
	if err != nil {
		msg := fmt.Sprintf("Proxy Error: %v", err)
		_, _ = fmt.Fprintf(client, "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\n\r\n%s", len(msg), msg)
		return
	}
	defer resp.Body.Close()

	countingBody := &countingReader{r: resp.Body, counter: &g.BytesDown}
	resp.Body = io.NopCloser(countingBody)
	_ = resp.Write(client)
}

func (g *GatewayServer) forwardSmartHTTP(ctx context.Context, req *http.Request) (*http.Response, error) {
	host := req.URL.Hostname()
	if !isKnownFilteredHost(host) {
		resp, err := g.router.ForwardPlainHTTP(ctx, req)
		if err == nil && resp.StatusCode != 451 && resp.StatusCode != 403 {
			return resp, nil
		}
		if resp != nil {
			_ = resp.Body.Close()
		}
	}

	// Fallback / filtered host: route plain HTTP through fastest verified tunnel node
	nodes := g.pool.PickNodes(ctx, 1, 1500*time.Millisecond)
	if len(nodes) > 0 {
		tr := &http.Transport{
			DialContext: func(dialCtx context.Context, _, addr string) (net.Conn, error) {
				h, p, sErr := net.SplitHostPort(addr)
				if sErr != nil {
					h = addr
					p = "80"
				}
				return DialOutboundNode(dialCtx, g.doh, nodes[0], h, p)
			},
			ResponseHeaderTimeout: 10 * time.Second,
		}
		defer tr.CloseIdleConnections()
		outReq := req.Clone(ctx)
		outReq.RequestURI = ""
		removeHopByHopHeaders(outReq.Header)
		if resp, err := tr.RoundTrip(outReq); err == nil {
			return resp, nil
		}
	}

	return g.router.ForwardPlainHTTP(ctx, req)
}

func (g *GatewayServer) pipeBidirectional(client net.Conn, clientReader io.Reader, remote net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)

	// Client -> Remote (Upload)
	go func() {
		defer wg.Done()
		n, _ := io.Copy(remote, clientReader)
		if n > 0 {
			g.BytesUp.Add(uint64(n))
		}
		closeHalfWrite(remote)
	}()

	// Remote -> Client (Download)
	go func() {
		defer wg.Done()
		n, _ := io.Copy(client, remote)
		if n > 0 {
			g.BytesDown.Add(uint64(n))
		}
		closeHalfWrite(client)
	}()

	wg.Wait()
}

func closeHalfWrite(c net.Conn) {
	switch v := c.(type) {
	case *net.TCPConn:
		_ = v.CloseWrite()
	case *FragmentingConn:
		closeHalfWrite(v.Conn)
	case *prefixedConn:
		closeHalfWrite(v.Conn)
	default:
		_ = c.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
	}
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
	ReadyNodes        int           `json:"ready_nodes"`
	BestNode          string        `json:"best_node,omitempty"`
	BestProto         string        `json:"best_proto,omitempty"`
	BestRTTMs         int64         `json:"best_rtt_ms,omitempty"`
	BestSpeedKbps     int64         `json:"best_speed_kbps,omitempty"`
	DirectWins        uint64        `json:"direct_wins"`
	TunnelWins        uint64        `json:"tunnel_wins"`
	Config            RuntimeConfig `json:"config"`
}

type ProbeResponse struct {
	OK        bool   `json:"ok"`
	Status    int    `json:"status"`
	LatencyMs int64  `json:"latency_ms"`
	SpeedKbps int64  `json:"speed_kbps,omitempty"`
	Mode      string `json:"mode"`
	BestNode  string `json:"best_node,omitempty"`
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
		bestName, bestProto, bestRTT, bestSpeed, readyCnt := gw.pool.BestNodeSummary()
		cfg := router.GetConfig()
		cfg.CustomURI = gw.pool.GetCustomURI()
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
			ReadyNodes:        readyCnt,
			BestNode:          bestName,
			BestProto:         bestProto,
			BestRTTMs:         bestRTT,
			BestSpeedKbps:     bestSpeed,
			DirectWins:        gw.racer.DirectWins.Load(),
			TunnelWins:        gw.racer.TunnelWins.Load(),
			Config:            cfg,
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	})

	mux.HandleFunc("/nodes", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"ok":    true,
			"nodes": gw.pool.ListReadyNodes(),
		})
	})

	mux.HandleFunc("/select-node", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var reqBody struct {
			ID string `json:"id"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&reqBody); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		res, ok := gw.pool.SelectNodeByID(reqBody.ID)
		if ok {
			gw.racer.ResetMethodCache()
		}
		w.Header().Set("Content-Type", "application/json")
		if !ok {
			w.WriteHeader(http.StatusNotFound)
		}
		_ = json.NewEncoder(w).Encode(res)
	})

	mux.HandleFunc("/switch-best", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 6*time.Second)
		defer cancel()
		res := gw.pool.SwitchToBestDifferentNode(ctx)
		if res.OK {
			gw.racer.ResetMethodCache()
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(res)
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
		if err := gw.pool.SetCustomURI(next.CustomURI); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		router.UpdateConfig(next)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "config": router.GetConfig()})
	})

	mux.HandleFunc("/refresh", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		gw.pool.RefreshFromFeedsAsync()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
	})

	mux.HandleFunc("/probe", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(w, r) {
			return
		}
		target := r.URL.Query().Get("url")
		if target == "" {
			target = "https://www.youtube.com/generate_204"
		}
		timeoutMs, _ := strconv.Atoi(r.URL.Query().Get("timeout_ms"))
		if timeoutMs < 2000 || timeoutMs > 20000 {
			timeoutMs = 8000
		}

		u, err := url.Parse(target)
		if err != nil || (u.Scheme != "https" && u.Scheme != "http") {
			w.WriteHeader(http.StatusBadRequest)
			_ = json.NewEncoder(w).Encode(ProbeResponse{OK: false, Error: "invalid probe url"})
			return
		}

		start := time.Now()
		timeoutDur := time.Duration(timeoutMs) * time.Millisecond
		ctx, cancel := context.WithTimeout(r.Context(), timeoutDur)
		defer cancel()

		proxyURL, _ := url.Parse("http://" + gw.Addr())
		tr := &http.Transport{
			Proxy: http.ProxyURL(proxyURL),
			TLSClientConfig: &tls.Config{
				RootCAs:    AndroidRootCertPool(),
				MinVersion: tls.VersionTLS12,
			},
		}
		defer tr.CloseIdleConnections()

		client := &http.Client{Transport: tr, Timeout: timeoutDur}
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
		req.Header.Set("User-Agent", "Mozilla/5.0 Chrome/130.0.0.0 Mobile Safari/537.36")

		resp, err := client.Do(req)
		elapsed := time.Since(start).Milliseconds()
		bestName, _, _, bestSpeed, _ := gw.pool.BestNodeSummary()

		w.Header().Set("Content-Type", "application/json")
		if err != nil {
			_ = json.NewEncoder(w).Encode(ProbeResponse{
				OK:        false,
				LatencyMs: elapsed,
				SpeedKbps: bestSpeed,
				Mode:      string(router.GetConfig().Mode),
				BestNode:  bestName,
				Error:     err.Error(),
			})
			return
		}
		_ = resp.Body.Close()
		_ = json.NewEncoder(w).Encode(ProbeResponse{
			OK:        resp.StatusCode >= 200 && resp.StatusCode < 400,
			Status:    resp.StatusCode,
			LatencyMs: elapsed,
			SpeedKbps: bestSpeed,
			Mode:      string(router.GetConfig().Mode),
			BestNode:  bestName,
		})
	})

	return mux
}
