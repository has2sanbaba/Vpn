package main

import (
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	listenAddr := flag.String("listen", "127.0.0.1:18080", "Local unified HTTP/SOCKS5 proxy listen address")
	controlAddr := flag.String("control", "127.0.0.1:18081", "Local HTTP control & telemetry listen address")
	token := flag.String("token", "", "Shared secret token for control API (X-GF-Token)")
	mode := flag.String("mode", string(ModeAntiDPI), "Initial routing mode: anti_dpi, socks5, http_proxy, envoy_http, blocked, direct")
	upstream := flag.String("upstream", "", "Optional upstream proxy host:port")
	dohServer := flag.String("doh", "", "Optional custom DoH endpoint")
	fbConfig := flag.String("fbconfig", "", "Optional path to GreatFire fb.config")
	fragSize := flag.Int("frag-size", 24, "TLS ClientHello fragment size in bytes")
	fragDelay := flag.Int("frag-delay", 4, "Inter-fragment delay in milliseconds")
	flag.Parse()

	doh := NewDoHResolver()
	initialCfg := RuntimeConfig{
		Mode:         TunnelMode(*mode),
		UpstreamAddr: *upstream,
		DoHServer:    *dohServer,
		FragEnabled:  true,
		FragSize:     *fragSize,
		FragDelayMs:  *fragDelay,
		FbConfigPath: *fbConfig,
		ProfileName:  "GreatFire Smart Anti-DPI",
	}

	router := NewUpstreamRouter(doh, initialCfg)
	gw := NewGatewayServer(router, doh)

	if *controlAddr != "" {
		ctrlMux := NewControlMux(gw, router, doh, *token)
		go func() {
			log.Printf("[gfproxy] Control API listening on %s", *controlAddr)
			if err := http.ListenAndServe(*controlAddr, ctrlMux); err != nil {
				log.Printf("[gfproxy] Control server stopped: %v", err)
			}
		}()
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		log.Printf("[gfproxy] Shutting down...")
		_ = gw.Close()
		os.Exit(0)
	}()

	if err := gw.ListenAndServe(*listenAddr); err != nil {
		log.Fatalf("[gfproxy] Gateway fatal error: %v", err)
	}
}
