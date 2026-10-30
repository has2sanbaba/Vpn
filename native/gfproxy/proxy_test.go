package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"hash/crc32"
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

	bestName, _, _, bestRTT, bestSpeed, cnt, _ := pool.BestNodeSummary()
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
	nowBest, _, _, _, _, _, _ := pool.BestNodeSummary()
	if nowBest != "Fast-DE" {
		t.Fatalf("expected manual select to activate Fast-DE, got %s", nowBest)
	}
}

func TestVMessAEADParseAndEndToEndRoundTrip(t *testing.T) {
	uuidStr := "27848739-7e62-4138-9fd3-098a63964b6b"
	uuidBytes, ok := parseUUIDBytes(uuidStr)
	if !ok {
		t.Fatal("invalid uuid")
	}
	cmdKey := vmessCmdKey(uuidBytes)

	// Start minimal Xray-compatible VMess AEAD server
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	go func() {
		conn, aErr := ln.Accept()
		if aErr != nil {
			return
		}
		defer conn.Close()

		var authID [16]byte
		if _, err := io.ReadFull(conn, authID[:]); err != nil {
			return
		}
		// Verify AuthID CRC32
		authBlk, _ := aes.NewCipher(vmessKDF16(cmdKey[:], kdfSaltAuthIDEncryptionKey))
		var decAuth [16]byte
		authBlk.Decrypt(decAuth[:], authID[:])
		if binary.BigEndian.Uint32(decAuth[12:16]) != crc32.ChecksumIEEE(decAuth[0:12]) {
			return
		}

		var encHdrLen [18]byte
		var connNonce [8]byte
		if _, err := io.ReadFull(conn, encHdrLen[:]); err != nil {
			return
		}
		if _, err := io.ReadFull(conn, connNonce[:]); err != nil {
			return
		}

		lenBlk, _ := aes.NewCipher(vmessKDF16(cmdKey[:], kdfSaltVMessHeaderPayloadLenKey, string(authID[:]), string(connNonce[:])))
		lenGCM, _ := cipher.NewGCM(lenBlk)
		lenIV := vmessKDF(cmdKey[:], kdfSaltVMessHeaderPayloadLenIV, string(authID[:]), string(connNonce[:]))[:12]
		decLenB, err := lenGCM.Open(nil, lenIV, encHdrLen[:], authID[:])
		if err != nil {
			return
		}
		hdrLen := int(binary.BigEndian.Uint16(decLenB))

		encHdr := make([]byte, hdrLen+16)
		if _, err := io.ReadFull(conn, encHdr); err != nil {
			return
		}
		hdrBlk, _ := aes.NewCipher(vmessKDF16(cmdKey[:], kdfSaltVMessHeaderPayloadKey, string(authID[:]), string(connNonce[:])))
		hdrGCM, _ := cipher.NewGCM(hdrBlk)
		hdrIV := vmessKDF(cmdKey[:], kdfSaltVMessHeaderPayloadIV, string(authID[:]), string(connNonce[:]))[:12]
		plainHdr, err := hdrGCM.Open(nil, hdrIV, encHdr, authID[:])
		if err != nil || len(plainHdr) < 42 {
			return
		}

		var reqIV, reqKey [16]byte
		copy(reqIV[:], plainHdr[1:17])
		copy(reqKey[:], plainHdr[17:33])
		respAuth := plainHdr[33]

		// Read client's first SHAKE128-masked AES-128-GCM body chunk
		rxMask := newVMessShakeMask(reqIV[:])
		var szBuf [2]byte
		if _, err := io.ReadFull(conn, szBuf[:]); err != nil {
			return
		}
		cLen := int(binary.BigEndian.Uint16(szBuf[:]) ^ rxMask.NextMask())
		encBody := make([]byte, cLen)
		if _, err := io.ReadFull(conn, encBody); err != nil {
			return
		}
		bodyBlk, _ := aes.NewCipher(reqKey[:])
		bodyGCM, _ := cipher.NewGCM(bodyBlk)
		nonce0 := makeVMessChunkNonce(reqIV, 0)
		msg, err := bodyGCM.Open(nil, nonce0[:], encBody, nil)
		if err != nil || string(msg) != "PING_VMESS_AEAD" {
			return
		}

		// Send AEAD response header + encrypted response chunk
		respKeySum := sha256.Sum256(reqKey[:])
		respIVSum := sha256.Sum256(reqIV[:])
		var respKey, respIV [16]byte
		copy(respKey[:], respKeySum[:16])
		copy(respIV[:], respIVSum[:16])

		respHdrPlain := []byte{respAuth, 0x00, 0x00, 0x00}
		rLenBlk, _ := aes.NewCipher(vmessKDF16(respKey[:], kdfSaltAEADRespHeaderLenKey))
		rLenGCM, _ := cipher.NewGCM(rLenBlk)
		rLenIV := vmessKDF(respIV[:], kdfSaltAEADRespHeaderLenIV)[:12]
		var rLenRaw [2]byte
		binary.BigEndian.PutUint16(rLenRaw[:], uint16(len(respHdrPlain)))
		_, _ = conn.Write(rLenGCM.Seal(nil, rLenIV, rLenRaw[:], nil))

		rHdrBlk, _ := aes.NewCipher(vmessKDF16(respKey[:], kdfSaltAEADRespHeaderPayloadKey))
		rHdrGCM, _ := cipher.NewGCM(rHdrBlk)
		rHdrIV := vmessKDF(respIV[:], kdfSaltAEADRespHeaderPayloadIV)[:12]
		_, _ = conn.Write(rHdrGCM.Seal(nil, rHdrIV, respHdrPlain, nil))

		txMask := newVMessShakeMask(respIV[:])
		txBlk, _ := aes.NewCipher(respKey[:])
		txGCM, _ := cipher.NewGCM(txBlk)
		rNonce0 := makeVMessChunkNonce(respIV, 0)
		sealedPayload := txGCM.Seal(nil, rNonce0[:], []byte("VMESS_AEAD_PONG_OK"), nil)
		var outSz [2]byte
		binary.BigEndian.PutUint16(outSz[:], uint16(len(sealedPayload))^txMask.NextMask())
		_, _ = conn.Write(outSz[:])
		_, _ = conn.Write(sealedPayload)
	}()

	host, port, _ := net.SplitHostPort(ln.Addr().String())
	vmessJSON, _ := json.Marshal(map[string]any{
		"v":    "2",
		"ps":   "Test-VMess-Node",
		"add":  host,
		"port": port,
		"id":   uuidStr,
		"aid":  "0",
		"scy":  "aes-128-gcm",
		"net":  "tcp",
		"type": "none",
		"tls":  "",
	})
	vmessURI := "vmess://" + base64.StdEncoding.EncodeToString(vmessJSON)
	node, ok := ParseSingleShareLink(vmessURI)
	if !ok || node.Protocol != ProtoVMess {
		t.Fatalf("failed parsing vmess link: %+v", node)
	}

	c, err := DialOutboundNode(context.Background(), nil, node, "www.youtube.com", "443")
	if err != nil {
		t.Fatalf("DialOutboundNode VMess: %v", err)
	}
	defer c.Close()
	if _, err := c.Write([]byte("PING_VMESS_AEAD")); err != nil {
		t.Fatalf("VMess Write: %v", err)
	}
	reply, err := io.ReadAll(c)
	if err != nil {
		t.Fatalf("VMess Read: %v", err)
	}
	if string(reply) != "VMESS_AEAD_PONG_OK" {
		t.Fatalf("got %q, want VMESS_AEAD_PONG_OK", string(reply))
	}
}

func TestMultiSourceSeedsAndCustomConfigManagement(t *testing.T) {
	seeds := ParseShareLinksWithSource(embeddedSeedNodes, "MultiSeed")
	if len(seeds) < 60 {
		t.Fatalf("expected >= 60 embedded multi-source seeds, got %d", len(seeds))
	}
	sources := make(map[string]int)
	protos := make(map[NodeProtocol]int)
	for _, s := range seeds {
		sources[s.Source]++
		protos[s.Protocol]++
	}
	if len(sources) < 6 {
		t.Fatalf("expected >= 6 independent seed sources, got %d: %v", len(sources), sources)
	}
	if protos[ProtoVMess] == 0 || protos[ProtoVLESS] == 0 || protos[ProtoShadowsocks] == 0 {
		t.Fatalf("expected multi-protocol diversity including VMess, VLESS, SS: %v", protos)
	}

	// Noisy Telegram text extraction + /custom/add + /custom/list + /custom/delete
	doh := NewDoHResolver()
	router := NewUpstreamRouter(doh, RuntimeConfig{Mode: ModeAntiDPI})
	pool := NewNodePool(doh, t.TempDir())
	pool.mu.Lock()
	pool.probeHook = func(node OutboundNode) (int64, int64, bool) {
		return 88, 22000, true
	}
	pool.mu.Unlock()

	racer := NewConnectionRacer(router, pool, "")
	gw := NewGatewayServer(router, doh, pool, racer)
	mux := NewControlMux(gw, router, doh, "tok")

	noisyTelegramText := "🔥 کانفیگ پرسرعت جدید امشب:\n`vless://b2f2f9f2-1864-48ea-839b-22815faff1f1@104.18.2.161:443?security=tls&type=ws&sni=sub.example.com&path=%2Fws#MyCustomFast` \nکانال ما @test"
	bodyBytes, _ := json.Marshal(map[string]string{"input": noisyTelegramText})
	reqAdd := httptest.NewRequest(http.MethodPost, "/custom/add", bytes.NewReader(bodyBytes))
	reqAdd.Header.Set("X-GF-Token", "tok")
	recAdd := httptest.NewRecorder()
	mux.ServeHTTP(recAdd, reqAdd)
	if recAdd.Code != http.StatusOK {
		t.Fatalf("expected 200 OK from /custom/add, got %d: %s", recAdd.Code, recAdd.Body.String())
	}
	var addRes CustomAddResult
	_ = json.Unmarshal(recAdd.Body.Bytes(), &addRes)
	if !addRes.OK || addRes.AddedCount != 1 || addRes.WorkingCount != 1 || addRes.BestNode != "MyCustomFast" {
		t.Fatalf("unexpected CustomAddResult: %+v", addRes)
	}

	customList, _ := pool.ListCustomConfigs()
	if len(customList) != 1 || !customList[0].Active {
		t.Fatalf("expected 1 active custom config, got %+v", customList)
	}

	delBody, _ := json.Marshal(map[string]any{"id": customList[0].ID})
	reqDel := httptest.NewRequest(http.MethodPost, "/custom/delete", bytes.NewReader(delBody))
	reqDel.Header.Set("X-GF-Token", "tok")
	recDel := httptest.NewRecorder()
	mux.ServeHTTP(recDel, reqDel)
	if recDel.Code != http.StatusOK {
		t.Fatalf("expected 200 OK from /custom/delete")
	}
	afterDel, _ := pool.ListCustomConfigs()
	if len(afterDel) != 0 {
		t.Fatalf("expected custom config deleted, got %d", len(afterDel))
	}
}

func TestInMemoryJNIControlHandlerAndUTF8Safety(t *testing.T) {
	doh := NewDoHResolver()
	router := NewUpstreamRouter(doh, RuntimeConfig{Mode: ModeAntiDPI})
	pool := NewNodePool(doh, t.TempDir())
	pool.mu.Lock()
	pool.probeHook = func(node OutboundNode) (int64, int64, bool) {
		return 64, 31000, true
	}
	pool.mu.Unlock()

	racer := NewConnectionRacer(router, pool, "")
	gw := NewGatewayServer(router, doh, pool, racer)

	// 1. Nil handler returns 503 cleanly without panicking
	codeNil, rawNil := InvokeControlHandlerInMemory(nil, http.MethodGet, "/status", "")
	if codeNil != http.StatusServiceUnavailable || !strings.Contains(rawNil, "engine not started") {
		t.Fatalf("expected 503 for nil handler, got %d %s", codeNil, rawNil)
	}

	// 2. Tokenless in-memory JNI mux (matches jni_bridge.go NewControlMux(..., ""))
	mux := NewControlMux(gw, router, doh, "")

	// 3. Add custom node with 4-byte UTF-8 emojis (verifies CESU-8-free byte marshaling payload)
	payload, _ := json.Marshal(map[string]string{
		"input": "vless://b2f2f9f2-1864-48ea-839b-22815faff1f1@104.18.2.161:443?security=tls&type=ws&sni=sub.example.com&path=%2Fws#🚀آلمان-پرسرعت⚡",
	})
	codeAdd, rawAdd := InvokeControlHandlerInMemory(mux, http.MethodPost, "/custom/add", string(payload))
	if codeAdd != http.StatusOK {
		t.Fatalf("expected 200 OK from in-memory /custom/add, got %d: %s", codeAdd, rawAdd)
	}
	var addRes CustomAddResult
	if err := json.Unmarshal([]byte(rawAdd), &addRes); err != nil {
		t.Fatalf("unmarshal addRes: %v (%s)", err, rawAdd)
	}
	if !addRes.OK || addRes.WorkingCount != 1 || !strings.Contains(addRes.BestNode, "🚀آلمان-پرسرعت⚡") {
		t.Fatalf("unexpected JNI in-memory addRes: %+v", addRes)
	}

	// 4. Status check via in-memory JNI control plane
	codeStatus, rawStatus := InvokeControlHandlerInMemory(mux, http.MethodGet, "/status", "")
	if codeStatus != http.StatusOK {
		t.Fatalf("expected 200 OK from in-memory /status, got %d: %s", codeStatus, rawStatus)
	}
	var st map[string]any
	if err := json.Unmarshal([]byte(rawStatus), &st); err != nil {
		t.Fatalf("unmarshal status: %v", err)
	}
	if st["ok"] != true || st["best_node"] != "🚀آلمان-پرسرعت⚡" {
		t.Fatalf("expected ok=true and emoji best_node, got %+v", st)
	}
}


