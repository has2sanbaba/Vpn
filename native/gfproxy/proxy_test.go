package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha1"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestDNSWireQueryAndPoisonFilter(t *testing.T) {
	wire, txID, err := buildDNSQuery("www.youtube.com", 1)
	if err != nil {
		t.Fatalf("buildDNSQuery failed: %v", err)
	}
	if len(wire) < 20 {
		t.Fatalf("unexpected short query: %d", len(wire))
	}
	if binary.BigEndian.Uint16(wire[0:2]) != txID {
		t.Fatalf("txID mismatch")
	}

	for _, bad := range []string{"10.10.34.34", "10.10.34.35", "10.10.34.36", "100.64.0.1", "198.18.0.1", "127.0.0.1", "0.0.0.0", "192.168.1.1"} {
		if !IsPoisonedOrBogonIP(net.ParseIP(bad)) {
			t.Errorf("expected %s to be detected as poisoned/bogon", bad)
		}
	}
	for _, good := range []string{"142.250.185.206", "1.1.1.1", "104.16.132.229", "8.8.8.8", "9.9.9.11"} {
		if IsPoisonedOrBogonIP(net.ParseIP(good)) {
			t.Errorf("expected %s to be accepted as clean public IP", good)
		}
	}
	if AndroidRootCertPool() == nil {
		t.Fatalf("expected non-nil AndroidRootCertPool")
	}
}

func TestWSPathNormalizationAndShareLinkParsing(t *testing.T) {
	cases := map[string]string{
		"%2F9BozNyvQ8VdRvsYX%3Fed%3D2560":                   "/9BozNyvQ8VdRvsYX",
		"/?ed=2048":                                          "/",
		"/?TELEGRAM--TEST?ed=2560":                           "/?TELEGRAM--TEST",
		"/ws?token=abc&ed=2560&mode=fast":                    "/ws?token=abc&mode=fast",
		"pathWithoutLeadingSlash":                            "/pathWithoutLeadingSlash",
	}
	for in, want := range cases {
		if got := NormalizeWSPath(in); got != want {
			t.Fatalf("NormalizeWSPath(%q) = %q, want %q", in, got, want)
		}
	}

	sample := strings.Join([]string{
		"vless://b2f2f9f2-1864-48ea-839b-22815faff1f1@172.66.44.124:2096?encryption=none&security=tls&sni=example.pages.dev&type=ws&path=%2F9BozNyvQ8VdRvsYX%3Fed%3D2560#CF-Pages",
		"ss://YWVzLTEyOC1nY206c2hhZG93c29ja3M=@37.19.198.244:443junkSuffix#US-SS",
		"trojan://Trojan@Pass123@in.tronsg.com:443?sni=in.tronsg.com&allowInsecure=1&type=tcp#IN-Trojan",
		"socks5://user:pass@127.0.0.1:1080#LocalSOCKS",
	}, "\n")

	nodes := ParseShareLinks(sample)
	if len(nodes) != 4 {
		t.Fatalf("expected 4 parsed nodes, got %d", len(nodes))
	}
	if nodes[0].Protocol != ProtoVLESS || nodes[0].WSHost != "example.pages.dev" {
		t.Fatalf("unexpected VLESS parse: %+v", nodes[0])
	}
	if nodes[1].Protocol != ProtoShadowsocks || nodes[1].Port != "443" || nodes[1].Cipher != "aes-128-gcm" {
		t.Fatalf("unexpected SS parse: %+v", nodes[1])
	}
	if nodes[2].Protocol != ProtoTrojan || nodes[2].Password != "Trojan@Pass123" {
		t.Fatalf("unexpected Trojan parse: %+v", nodes[2])
	}
}

func TestFragmentTLSClientHelloEndToEndHandshake(t *testing.T) {
	cert := generateSelfSignedCert(t, "blocked.example.org")
	ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	})
	if err != nil {
		t.Fatalf("tls.Listen: %v", err)
	}
	defer ln.Close()

	srv := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("GREATFIRE_OK:" + r.TLS.ServerName))
		}),
	}
	go srv.Serve(ln)
	defer srv.Close()

	rawConn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("net.Dial: %v", err)
	}

	fragConn := NewFragmentingConn(rawConn, 16, 1*time.Millisecond)
	tlsClient := tls.Client(fragConn, &tls.Config{
		ServerName:         "blocked.example.org",
		InsecureSkipVerify: true,
	})
	if err := tlsClient.Handshake(); err != nil {
		t.Fatalf("fragmented TLS handshake failed: %v", err)
	}
	defer tlsClient.Close()

	_, _ = io.WriteString(tlsClient, "GET / HTTP/1.1\r\nHost: blocked.example.org\r\nConnection: close\r\n\r\n")
	resp, err := http.ReadResponse(bufio.NewReader(tlsClient), nil)
	if err != nil {
		t.Fatalf("ReadResponse: %v", err)
	}
	body, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if string(body) != "GREATFIRE_OK:blocked.example.org" {
		t.Fatalf("unexpected body: %q", string(body))
	}
}

func TestShadowsocksAEADRoundTrip(t *testing.T) {
	password := "gf-secret-pass"
	cipherName := "chacha20-ietf-poly1305"
	masterKey := evpBytesToKey(password, 32)

	// Start minimal Shadowsocks AEAD echo-HTTP server
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	go func() {
		conn, acceptErr := ln.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()

		// Read client salt + first frame
		rxSalt := make([]byte, 32)
		if _, err := io.ReadFull(conn, rxSalt); err != nil {
			return
		}
		rxAEAD, _ := newSSSubkeyAEAD(cipherName, masterKey, rxSalt)
		rxNonce := make([]byte, rxAEAD.NonceSize())

		encLen := make([]byte, 2+rxAEAD.Overhead())
		if _, err := io.ReadFull(conn, encLen); err != nil {
			return
		}
		decLen, err := rxAEAD.Open(nil, rxNonce, encLen, nil)
		if err != nil {
			return
		}
		incSSNonce(rxNonce)
		plen := int(binary.BigEndian.Uint16(decLen))
		encData := make([]byte, plen+rxAEAD.Overhead())
		if _, err := io.ReadFull(conn, encData); err != nil {
			return
		}
		plain, err := rxAEAD.Open(nil, rxNonce, encData, nil)
		if err != nil || len(plain) < 10 {
			return
		}

		// Reply encrypted HTTP 200
		txSalt := make([]byte, 32)
		_, _ = rand.Read(txSalt)
		txAEAD, _ := newSSSubkeyAEAD(cipherName, masterKey, txSalt)
		txNonce := make([]byte, txAEAD.NonceSize())
		reply := []byte("SS_TUNNEL_OK")
		var lenBytes [2]byte
		binary.BigEndian.PutUint16(lenBytes[:], uint16(len(reply)))
		out := append([]byte{}, txSalt...)
		out = append(out, txAEAD.Seal(nil, txNonce, lenBytes[:], nil)...)
		incSSNonce(txNonce)
		out = append(out, txAEAD.Seal(nil, txNonce, reply, nil)...)
		_, _ = conn.Write(out)
	}()

	host, port, _ := net.SplitHostPort(ln.Addr().String())
	node := OutboundNode{
		ID:       "test-ss",
		Protocol: ProtoShadowsocks,
		Host:     host,
		Port:     port,
		Cipher:   cipherName,
		Password: password,
	}

	c, err := DialOutboundNode(context.Background(), nil, node, "youtube.com", "443")
	if err != nil {
		t.Fatalf("DialOutboundNode SS: %v", err)
	}
	defer c.Close()

	if _, err := c.Write([]byte("HELLO_THROUGH_SS")); err != nil {
		t.Fatalf("SS write: %v", err)
	}
	got, err := io.ReadAll(c)
	if err != nil {
		t.Fatalf("SS read: %v", err)
	}
	if string(got) != "SS_TUNNEL_OK" {
		t.Fatalf("got %q, want SS_TUNNEL_OK", string(got))
	}
}

func TestPerConnectionRacerFailoverDefeatsCensoredBlockpage(t *testing.T) {
	// 1. Real uncensored HTTPS origin (simulating real YouTube behind tunnel)
	realCert := generateSelfSignedCert(t, "www.youtube.com")
	realOriginLn, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
		Certificates: []tls.Certificate{realCert},
		MinVersion:   tls.VersionTLS12,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer realOriginLn.Close()
	realSrv := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("REAL_YOUTUBE_200_OK"))
		}),
	}
	go realSrv.Serve(realOriginLn)
	defer realSrv.Close()

	// 2. Fake ISP-censored Direct IP that injects an HTTP 403 blockpage instead of TLS ServerHello!
	censoredLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer censoredLn.Close()
	go func() {
		for {
			c, err := censoredLn.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				var buf [512]byte
				_, _ = conn.Read(buf[:])
				_, _ = conn.Write([]byte("HTTP/1.1 403 Forbidden - ISP Censorship Blockpage\r\n\r\n"))
			}(c)
		}
	}()

	// 3. Local VLESS over WebSocket+TLS tunnel server forwarding to realOriginLn!
	wsCert := generateSelfSignedCert(t, "127.0.0.1")
	vlessLn, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
		Certificates: []tls.Certificate{wsCert},
		NextProtos:   []string{"http/1.1"},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer vlessLn.Close()

	go func() {
		for {
			c, acceptErr := vlessLn.Accept()
			if acceptErr != nil {
				return
			}
			go serveMockVLESSWebSocket(c, realOriginLn.Addr().String())
		}
	}()

	// Seed DoH cache with the poisoned/blocked Direct IP so Direct leg hits censoredLn
	doh := NewDoHResolver()
	censoredHost, censoredPort, _ := net.SplitHostPort(censoredLn.Addr().String())
	doh.mu.Lock()
	doh.cache["censored-video.test"] = dnsCacheItem{
		ips:       []string{censoredHost},
		expiresAt: time.Now().Add(time.Hour),
	}
	doh.mu.Unlock()

	router := NewUpstreamRouter(doh, RuntimeConfig{
		Mode:        ModeAntiDPI,
		FragEnabled: true,
		FragSize:    20,
		FragDelayMs: 1,
	})
	pool := NewNodePool(doh, "")
	vlessHost, vlessPort, _ := net.SplitHostPort(vlessLn.Addr().String())
	vlessNode, ok := ParseSingleShareLink(
		"vless://b2f2f9f2-1864-48ea-839b-22815faff1f1@" + vlessHost + ":" + vlessPort +
			"?security=tls&sni=127.0.0.1&allowInsecure=1&type=ws&path=%2Fws%3Fed%3D2560#TestVLESS",
	)
	if !ok {
		t.Fatal("failed to parse test VLESS node")
	}
	pool.AddStaticTestNode(vlessNode, 45)

	racer := NewConnectionRacer(router, pool, "")
	gw := NewGatewayServer(router, doh, pool, racer)
	gwLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	gw.listener = gwLn
	go func() {
		for {
			c, aErr := gwLn.Accept()
			if aErr != nil {
				return
			}
			go gw.handleClient(c)
		}
	}()
	defer gw.Close()

	proxyURL, _ := url.Parse("http://" + gw.Addr())
	client := &http.Client{
		Transport: &http.Transport{
			Proxy: http.ProxyURL(proxyURL),
			TLSClientConfig: &tls.Config{
				ServerName:         "www.youtube.com",
				InsecureSkipVerify: true,
			},
		},
		Timeout: 6 * time.Second,
	}

	resp, err := client.Get("https://censored-video.test:" + censoredPort + "/watch")
	if err != nil {
		t.Fatalf("expected racer to defeat ISP 403 blockpage and succeed via VLESS WS tunnel, got err: %v", err)
	}
	body, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if string(body) != "REAL_YOUTUBE_200_OK" {
		t.Fatalf("unexpected body: %q", string(body))
	}
	if racer.TunnelWins.Load() == 0 {
		t.Fatalf("expected TunnelWins > 0")
	}
}

func serveMockVLESSWebSocket(tlsServerConn net.Conn, forwardAddr string) {
	defer tlsServerConn.Close()
	br := bufio.NewReader(tlsServerConn)
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	if req.URL.RequestURI() != "/ws" {
		_, _ = io.WriteString(tlsServerConn, "HTTP/1.1 404 Not Found\r\n\r\n")
		return
	}
	key := req.Header.Get("Sec-WebSocket-Key")
	sum := sha1.Sum([]byte(key + wsGUID))
	accept := base64.StdEncoding.EncodeToString(sum[:])
	_, _ = io.WriteString(tlsServerConn,
		"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n")

	ws := &wsConn{Conn: tlsServerConn, br: br}
	// Read VLESS v0 request header: [0x00 (1B)][16B uuid][0x00 (1B)][0x01 (1B)][2B port] = 21 bytes
	var fixed [21]byte
	if _, err := io.ReadFull(ws, fixed[:]); err != nil {
		return
	}
	var atyp [1]byte
	if _, err := io.ReadFull(ws, atyp[:]); err != nil {
		return
	}
	switch atyp[0] {
	case 0x01:
		_, _ = io.CopyN(io.Discard, ws, 4)
	case 0x02:
		var l [1]byte
		_, _ = io.ReadFull(ws, l[:])
		_, _ = io.CopyN(io.Discard, ws, int64(l[0]))
	case 0x03:
		_, _ = io.CopyN(io.Discard, ws, 16)
	}

	originConn, err := net.Dial("tcp", forwardAddr)
	if err != nil {
		return
	}
	defer originConn.Close()

	// Write VLESS v0 response header [0x00, 0x00] to client via UNMASKED server binary frame
	sendServerWS := func(p []byte) error {
		plen := len(p)
		var f []byte
		f = append(f, 0x82)
		if plen <= 125 {
			f = append(f, byte(plen))
		} else {
			f = append(f, 126, byte(plen>>8), byte(plen))
		}
		f = append(f, p...)
		_, wErr := tlsServerConn.Write(f)
		return wErr
	}
	_ = sendServerWS([]byte{0x00, 0x00})

	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(originConn, ws)
		done <- struct{}{}
	}()
	go func() {
		buf := make([]byte, 16384)
		for {
			n, rErr := originConn.Read(buf)
			if n > 0 {
				if sendServerWS(buf[:n]) != nil {
					break
				}
			}
			if rErr != nil {
				break
			}
		}
		done <- struct{}{}
	}()
	<-done
}

func TestUnifiedGatewayHTTPConnectAndSOCKS5AndKillSwitch(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("TUNNELED_SUCCESS"))
	}))
	defer target.Close()

	doh := NewDoHResolver()
	router := NewUpstreamRouter(doh, RuntimeConfig{
		Mode:        ModeAntiDPI,
		FragEnabled: true,
		FragSize:    20,
		FragDelayMs: 1,
	})
	gw := NewGatewayServer(router, doh, nil, nil)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	gw.listener = ln
	go func() {
		for {
			c, acceptErr := ln.Accept()
			if acceptErr != nil {
				return
			}
			go gw.handleClient(c)
		}
	}()
	defer gw.Close()

	proxyURL, _ := url.Parse("http://" + gw.Addr())
	httpClient := &http.Client{
		Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)},
		Timeout:   5 * time.Second,
	}
	resp, err := httpClient.Get(target.URL)
	if err != nil {
		t.Fatalf("HTTP proxy GET failed: %v", err)
	}
	b, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if string(b) != "TUNNELED_SUCCESS" {
		t.Fatalf("unexpected HTTP proxy body: %q", string(b))
	}

	targetURL, _ := url.Parse(target.URL)
	host, port, _ := net.SplitHostPort(targetURL.Host)
	socksConn, err := dialViaSOCKS5(context.Background(), gw.Addr(), "", "", host, port)
	if err != nil {
		t.Fatalf("SOCKS5 dial failed: %v", err)
	}
	_, _ = io.WriteString(socksConn, "GET / HTTP/1.1\r\nHost: "+targetURL.Host+"\r\nConnection: close\r\n\r\n")
	socksResp, err := http.ReadResponse(bufio.NewReader(socksConn), nil)
	if err != nil {
		t.Fatalf("SOCKS5 HTTP response failed: %v", err)
	}
	sb, _ := io.ReadAll(socksResp.Body)
	_ = socksResp.Body.Close()
	_ = socksConn.Close()
	if string(sb) != "TUNNELED_SUCCESS" {
		t.Fatalf("unexpected SOCKS5 body: %q", string(sb))
	}

	httpClient.CloseIdleConnections()
	router.UpdateConfig(RuntimeConfig{Mode: ModeBlocked})
	respBlocked, err := httpClient.Get(target.URL)
	if err == nil {
		_ = respBlocked.Body.Close()
		if respBlocked.StatusCode != http.StatusServiceUnavailable {
			t.Fatalf("expected 503 when Kill-Switch active, got %d", respBlocked.StatusCode)
		}
	}
	if gw.BlockedRequests.Load() == 0 {
		t.Fatalf("expected BlockedRequests counter > 0")
	}
}

func TestFragmentTLSClientHelloPacketStructure(t *testing.T) {
	sni := "wikipedia.org"
	var ext bytes.Buffer
	_ = binary.Write(&ext, binary.BigEndian, uint16(0x0000))
	listLen := 3 + len(sni)
	_ = binary.Write(&ext, binary.BigEndian, uint16(2+listLen))
	_ = binary.Write(&ext, binary.BigEndian, uint16(listLen))
	ext.WriteByte(0x00)
	_ = binary.Write(&ext, binary.BigEndian, uint16(len(sni)))
	ext.WriteString(sni)

	var body bytes.Buffer
	body.Write([]byte{0x03, 0x03})
	body.Write(make([]byte, 32))
	body.WriteByte(0x00)
	body.Write([]byte{0x00, 0x02, 0x13, 0x01})
	body.Write([]byte{0x01, 0x00})
	_ = binary.Write(&ext, binary.BigEndian, uint16(0)) // no extra ext
	_ = binary.Write(&body, binary.BigEndian, uint16(ext.Len()-2))
	body.Write(ext.Bytes()[:ext.Len()-2])

	var hs bytes.Buffer
	hs.WriteByte(0x01)
	hsLen := body.Len()
	hs.Write([]byte{byte(hsLen >> 16), byte(hsLen >> 8), byte(hsLen)})
	hs.Write(body.Bytes())

	var rec bytes.Buffer
	rec.Write([]byte{0x16, 0x03, 0x01})
	_ = binary.Write(&rec, binary.BigEndian, uint16(hs.Len()))
	rec.Write(hs.Bytes())

	frags := FragmentTLSClientHello(rec.Bytes(), 12)
	if len(frags) < 3 {
		t.Fatalf("expected >= 3 TLS records, got %d", len(frags))
	}

	var reassembled bytes.Buffer
	for i, f := range frags {
		if f[0] != 0x16 || f[1] != 0x03 {
			t.Fatalf("fragment %d invalid TLS header", i)
		}
		if strings.Contains(string(f), sni) {
			t.Fatalf("fragment %d still contains unfragmented full SNI %q!", i, sni)
		}
		l := int(binary.BigEndian.Uint16(f[3:5]))
		reassembled.Write(f[5 : 5+l])
	}
	if !bytes.Equal(reassembled.Bytes(), hs.Bytes()) {
		t.Fatalf("reassembled handshake does not match original ClientHello!")
	}
}

func generateSelfSignedCert(t *testing.T, dnsName string) tls.Certificate {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	ip := net.ParseIP(dnsName)
	tmpl := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: dnsName},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	if ip != nil {
		tmpl.IPAddresses = []net.IP{ip}
	} else {
		tmpl.DNSNames = []string{dnsName}
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{
		Certificate: [][]byte{der},
		PrivateKey:  priv,
	}
}

func TestSwitchToBestDifferentProxyAndSpeedScoring(t *testing.T) {
	doh := NewDoHResolver()
	router := NewUpstreamRouter(doh, RuntimeConfig{Mode: ModeAntiDPI, FragEnabled: true})
	pool := NewNodePool(doh, "")

	n1, _ := ParseSingleShareLink("socks5://127.0.0.1:10801#Fast-DE")
	n2, _ := ParseSingleShareLink("socks5://127.0.0.1:10802#Fast-NL")
	n3, _ := ParseSingleShareLink("socks5://127.0.0.1:10803#Slow-US")

	pool.mu.Lock()
	pool.probeHook = func(node OutboundNode) (int64, int64, bool) {
		switch node.Name {
		case "Fast-DE":
			return 95, 18500, true
		case "Fast-NL":
			return 118, 15200, true
		case "Slow-US":
			return 340, 280, true
		default:
			return 0, 0, false
		}
	}
	pool.mu.Unlock()

	pool.upsertVerifiedNode(n3, 340, 280)
	pool.upsertVerifiedNode(n2, 118, 15200)
	pool.upsertVerifiedNode(n1, 95, 18500)

	bestName, _, bestRTT, bestSpeed, cnt := pool.BestNodeSummary()
	if cnt != 3 || bestName != "Fast-DE" || bestRTT != 95 || bestSpeed != 18500 {
		t.Fatalf("expected initial best Fast-DE (95ms, 18500kbps), got %s (%dms, %dkbps, cnt=%d)", bestName, bestRTT, bestSpeed, cnt)
	}

	racer := NewConnectionRacer(router, pool, "")
	gw := NewGatewayServer(router, doh, pool, racer)
	mux := NewControlMux(gw, router, doh, "secret123")

	// Call POST /switch-best -> must switch AWAY from Fast-DE to the fastest remaining proxy (Fast-NL, NOT Slow-US!)
	req := httptest.NewRequest(http.MethodPost, "/switch-best", nil)
	req.Header.Set("X-GF-Token", "secret123")
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK from /switch-best, got %d: %s", rec.Code, rec.Body.String())
	}
	var sw SwitchBestResult
	if err := json.Unmarshal(rec.Body.Bytes(), &sw); err != nil {
		t.Fatalf("decode SwitchBestResult: %v", err)
	}
	if !sw.OK || sw.SwitchedFrom != "Fast-DE" || sw.BestNode != "Fast-NL" {
		t.Fatalf("expected switch Fast-DE -> Fast-NL, got %+v", sw)
	}

	// Verify /nodes endpoint marks Fast-NL active
	reqNodes := httptest.NewRequest(http.MethodGet, "/nodes", nil)
	reqNodes.Header.Set("X-GF-Token", "secret123")
	recNodes := httptest.NewRecorder()
	mux.ServeHTTP(recNodes, reqNodes)
	if recNodes.Code != http.StatusOK || !strings.Contains(recNodes.Body.String(), `"Fast-NL"`) {
		t.Fatalf("unexpected /nodes output: %s", recNodes.Body.String())
	}

	// Verify /select-node manual selection back to Fast-DE
	selBody := bytes.NewBufferString(`{"id":"` + n1.ID + `"}`)
	reqSel := httptest.NewRequest(http.MethodPost, "/select-node", selBody)
	reqSel.Header.Set("X-GF-Token", "secret123")
	recSel := httptest.NewRecorder()
	mux.ServeHTTP(recSel, reqSel)
	if recSel.Code != http.StatusOK {
		t.Fatalf("expected 200 OK from /select-node, got %d", recSel.Code)
	}
	nowBest, _, _, _, _ := pool.BestNodeSummary()
	if nowBest != "Fast-DE" {
		t.Fatalf("expected manual select to activate Fast-DE, got %s", nowBest)
	}
}

