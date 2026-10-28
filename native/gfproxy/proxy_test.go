package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
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

	// Verify Iranian censorship sinkhole IPs & RFC1918 are rejected
	for _, bad := range []string{"10.10.34.34", "10.10.34.35", "10.10.34.36", "127.0.0.1", "0.0.0.0", "192.168.1.1"} {
		if !IsPoisonedOrBogonIP(net.ParseIP(bad)) {
			t.Errorf("expected %s to be detected as poisoned/bogon", bad)
		}
	}
	for _, good := range []string{"142.250.185.206", "1.1.1.1", "104.16.132.229"} {
		if IsPoisonedOrBogonIP(net.ParseIP(good)) {
			t.Errorf("expected %s to be accepted as clean public IP", good)
		}
	}
}

func TestFragmentTLSClientHelloEndToEndHandshake(t *testing.T) {
	// Spin up a real Go TLS 1.3 HTTPS server and verify that FragmentingConn splits ClientHello
	// across multiple TLS records AND completes a real cryptographic TLS handshake cleanly!
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
	gw := NewGatewayServer(router, doh)

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

	// 1. Test HTTP proxy mode through unified port
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

	// 2. Test SOCKS5 mode on the EXACT SAME port!
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

	// 3. Test Strict Kill-Switch (ModeBlocked) prevents any leak!
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
	// Synthesize a minimal ClientHello containing SNI "wikipedia.org"
	sni := "wikipedia.org"
	var ext bytes.Buffer
	// Extension type 0x0000 (server_name)
	_ = binary.Write(&ext, binary.BigEndian, uint16(0x0000))
	listLen := 3 + len(sni)
	_ = binary.Write(&ext, binary.BigEndian, uint16(2+listLen))
	_ = binary.Write(&ext, binary.BigEndian, uint16(listLen))
	ext.WriteByte(0x00)
	_ = binary.Write(&ext, binary.BigEndian, uint16(len(sni)))
	ext.WriteString(sni)

	var body bytes.Buffer
	body.Write([]byte{0x03, 0x03})       // client_version TLS 1.2
	body.Write(make([]byte, 32))         // random
	body.WriteByte(0x00)                 // session_id len 0
	body.Write([]byte{0x00, 0x02, 0x13, 0x01}) // cipher suite TLS_AES_128_GCM_SHA256
	body.Write([]byte{0x01, 0x00})       // compression methods
	_ = binary.Write(&body, binary.BigEndian, uint16(ext.Len()))
	body.Write(ext.Bytes())

	var hs bytes.Buffer
	hs.WriteByte(0x01) // ClientHello
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

	// Reassemble payload from emitted TLS records and ensure 100% byte-identical handshake!
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
	tmpl := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: dnsName},
		DNSNames:     []string{dnsName},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
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
