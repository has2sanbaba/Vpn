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
	dataDir := flag.String("data-dir", "", "Directory for persistent method_data.json & nodes_cache.json")
	mode := flag.String("mode", string(ModeAntiDPI), "Initial routing mode")
	upstream := flag.String("upstream", "", "Optional upstream proxy host:port")
	customURI := flag.String("custom-uri", "", "Optional custom vless/trojan/ss/socks5 URI")
	dohServer := flag.String("doh", "", "Optional custom DoH endpoint")
	fbConfig := flag.String("fbconfig", "", "Optional path to GreatFire fb.config")
	fragSize := flag.Int("frag-size", 24, "TLS ClientHello fragment size in bytes")
	fragDelay := flag.Int("frag-delay", 3, "Inter-fragment delay in milliseconds")
	flag.Parse()

	doh := NewDoHResolver()
	initialCfg := RuntimeConfig{
		Mode:         TunnelMode(*mode),
		UpstreamAddr: *upstream,
		CustomURI:    *customURI,
		DoHServer:    *dohServer,
		FragEnabled:  true,
		FragSize:     *fragSize,
		FragDelayMs:  *fragDelay,
		FbConfigPath: *fbConfig,
		ProfileName:  "GreatFire Instant Smart Bypass",
	}

	router := NewUpstreamRouter(doh, initialCfg)
	pool := NewNodePool(doh, *dataDir)
	if *customURI != "" {
		_ = pool.SetCustomURI(*customURI)
	}
	pool.StartBackground()

	racer := NewConnectionRacer(router, pool, *dataDir)
	gw := NewGatewayServer(router, doh, pool, racer)

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
