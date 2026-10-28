package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/publicsuffix"
)

const (
	MethodDirectDPI = "direct_dpi"
	MethodTunnel    = "tunnel"
)

var knownFilteredSuffixes = []string{
	"youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com", "youtu.be", "youtube-nocookie.com",
	"t.me", "telegram.org", "tdesktop.com", "telesco.pe", "telegram.me",
	"x.com", "twitter.com", "twimg.com", "t.co",
	"instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net", "fbsbx.com", "messenger.com",
	"whatsapp.com", "whatsapp.net", "threads.net",
	"bbc.com", "bbc.co.uk", "bbci.co.uk", "voanews.com", "iranintl.com", "radiofarda.com",
	"dw.com", "euronews.com", "rfi.fr", "independentpersian.com", "manoto.news",
	"reddit.com", "redd.it", "redditmedia.com", "redditstatic.com",
	"tiktok.com", "tiktokcdn.com", "musical.ly",
	"soundcloud.com", "sndcdn.com", "spotify.com", "scdn.co",
	"netflix.com", "nflxvideo.net", "nflximg.net", "twitch.tv", "ttvnw.net",
	"openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com",
	"claude.ai", "anthropic.com", "perplexity.ai",
	"discord.com", "discordapp.com", "discord.gg", "discordapp.net",
	"wikipedia.org", "wikimedia.org", "wiktionary.org",
	"medium.com", "quora.com", "archive.org", "vimeo.com", "dailymotion.com",
	"signal.org", "proton.me", "protonmail.com",
	"duckduckgo.com", "ipleak.net",
}

type legSpec struct {
	kind   string // MethodDirectDPI or MethodTunnel
	delay  time.Duration
	node   *OutboundNode
}

type legOutcome struct {
	conn   net.Conn
	kind   string
	nodeID string
	rttMs  int64
	err    error
}

// ConnectionRacer implements GreatFire FreeBrowser (fbproxy) per-domain circumvention racing
// with eTLD+1 method memory (method_data.json) and TLS ServerHello validation.
type ConnectionRacer struct {
	router         *UpstreamRouter
	pool           *NodePool
	methodFilePath string

	mu         sync.RWMutex
	methodData map[string]string
	dirty      atomic.Bool

	DirectWins atomic.Uint64
	TunnelWins atomic.Uint64
}

func NewConnectionRacer(router *UpstreamRouter, pool *NodePool, dataDir string) *ConnectionRacer {
	var methodPath string
	if dataDir != "" {
		_ = os.MkdirAll(dataDir, 0o755)
		methodPath = filepath.Join(dataDir, "method_data.json")
	}
	cr := &ConnectionRacer{
		router:         router,
		pool:           pool,
		methodFilePath: methodPath,
		methodData:     make(map[string]string),
	}
	cr.loadMethodData()
	go cr.persistLoop()
	return cr
}

func (cr *ConnectionRacer) loadMethodData() {
	if cr.methodFilePath == "" {
		return
	}
	b, err := os.ReadFile(cr.methodFilePath)
	if err != nil || len(b) == 0 {
		return
	}
	var loaded map[string]string
	if json.Unmarshal(b, &loaded) == nil && loaded != nil {
		cr.mu.Lock()
		cr.methodData = loaded
		cr.mu.Unlock()
	}
}

func (cr *ConnectionRacer) persistLoop() {
	if cr.methodFilePath == "" {
		return
	}
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		if !cr.dirty.CompareAndSwap(true, false) {
			continue
		}
		cr.mu.RLock()
		b, err := json.Marshal(cr.methodData)
		cr.mu.RUnlock()
		if err == nil {
			_ = os.WriteFile(cr.methodFilePath, b, 0o600)
		}
	}
}

func (cr *ConnectionRacer) recordWinningMethod(eTldPlusOne, kind string) {
	if eTldPlusOne == "" {
		return
	}
	cr.mu.Lock()
	prev := cr.methodData[eTldPlusOne]
	if prev != kind {
		cr.methodData[eTldPlusOne] = kind
		cr.dirty.Store(true)
	}
	cr.mu.Unlock()
}

func (cr *ConnectionRacer) ResetMethodCache() {
	cr.mu.Lock()
	cr.methodData = make(map[string]string)
	cr.dirty.Store(true)
	cr.mu.Unlock()
}

func domainETLDPlusOne(host string) string {
	h := strings.ToLower(strings.TrimSpace(host))
	if h == "" || net.ParseIP(h) != nil {
		return h
	}
	if etld, err := publicsuffix.EffectiveTLDPlusOne(h); err == nil && etld != "" {
		return etld
	}
	return h
}

func isDomesticOrLocalHost(host string) bool {
	h := strings.ToLower(strings.TrimSpace(host))
	if h == "localhost" || strings.HasSuffix(h, ".ir") || strings.HasSuffix(h, ".local") {
		return true
	}
	if ip := net.ParseIP(h); ip != nil {
		return ip.IsLoopback() || ip.IsPrivate()
	}
	return false
}

func isKnownFilteredHost(host string) bool {
	h := strings.ToLower(strings.TrimSpace(host))
	for _, suf := range knownFilteredSuffixes {
		if h == suf || strings.HasSuffix(h, "."+suf) {
			return true
		}
	}
	return false
}

// ConnectAndVerify establishes the fastest working uncensored stream to host:port.
// If initialPayload is non-empty (e.g. TLS ClientHello), it races Direct Anti-DPI Fragmentation
// alongside pre-warmed Upstream Tunnels and returns ONLY the first leg that responds with a
// genuine ServerHello / valid HTTP header (automatically defeating ISP RSTs, timeouts, & blockpages!).
func (cr *ConnectionRacer) ConnectAndVerify(
	ctx context.Context,
	host, port string,
	initialPayload []byte,
) (net.Conn, error) {
	cfg := cr.router.GetConfig()

	// Explicit user override modes (e.g. custom SOCKS5 / HTTP proxy or direct mode)
	if cfg.Mode == ModeDirect || cfg.Mode == ModeSOCKS5 || cfg.Mode == ModeHTTPProxy {
		conn, err := cr.router.DialTarget(ctx, host, port)
		if err != nil {
			return nil, err
		}
		if len(initialPayload) > 0 {
			if _, wErr := conn.Write(initialPayload); wErr != nil {
				_ = conn.Close()
				return nil, wErr
			}
		}
		return conn, nil
	}

	// Custom share-link proxy override in NodePool
	if customURI := cr.pool.GetCustomURI(); customURI != "" && !isDomesticOrLocalHost(host) {
		nodes := cr.pool.PickNodes(ctx, 1, 0)
		if len(nodes) > 0 {
			conn, err := DialOutboundNode(ctx, cr.router.doh, nodes[0], host, port)
			if err == nil {
				if len(initialPayload) > 0 {
					if _, wErr := conn.Write(initialPayload); wErr != nil {
						_ = conn.Close()
						return nil, wErr
					}
				}
				cr.TunnelWins.Add(1)
				return conn, nil
			}
		}
	}

	// Domestic (.ir / localhost) fast-path: Direct without foreign proxy latency
	if isDomesticOrLocalHost(host) {
		conn, err := cr.router.dialAntiDPI(ctx, cfg, host, port)
		if err != nil {
			var d net.Dialer
			d.Timeout = 8 * time.Second
			conn, err = d.DialContext(ctx, "tcp", net.JoinHostPort(host, port))
			if err != nil {
				return nil, err
			}
		}
		if len(initialPayload) > 0 {
			if _, wErr := conn.Write(initialPayload); wErr != nil {
				_ = conn.Close()
				return nil, wErr
			}
		}
		return conn, nil
	}

	etld := domainETLDPlusOne(host)
	knownBlocked := isKnownFilteredHost(host)

	cr.mu.RLock()
	savedMethod := cr.methodData[etld]
	cr.mu.RUnlock()

	preferTunnel := knownBlocked || (savedMethod == MethodTunnel && rand.Float64() < 0.92)

	// Fetch up to 2 distinct verified fast upstream nodes
	coldWait := time.Duration(0)
	if preferTunnel && cr.pool.ReadyCount() == 0 {
		// On very first millisecond of cold app launch, wait up to 2.2s for the fastest of 20 workers
		coldWait = 2200 * time.Millisecond
	}
	nodes := cr.pool.PickNodes(ctx, 2, coldWait)

	// If no initial payload was sent by client (server-speaks-first), dial best single leg cleanly
	if len(initialPayload) == 0 {
		if preferTunnel && len(nodes) > 0 {
			if c, err := DialOutboundNode(ctx, cr.router.doh, nodes[0], host, port); err == nil {
				cr.TunnelWins.Add(1)
				return c, nil
			}
		}
		return cr.router.dialAntiDPI(ctx, cfg, host, port)
	}

	// Build staggered parallel Happy-Eyeballs circumvention plan (GreatFire FreeBrowser pattern)
	var legs []legSpec
	if preferTunnel && len(nodes) > 0 {
		n0 := nodes[0]
		legs = append(legs, legSpec{kind: MethodTunnel, delay: 0, node: &n0})
		legs = append(legs, legSpec{kind: MethodDirectDPI, delay: 160 * time.Millisecond})
		if len(nodes) > 1 {
			n1 := nodes[1]
			legs = append(legs, legSpec{kind: MethodTunnel, delay: 650 * time.Millisecond, node: &n1})
		}
	} else {
		legs = append(legs, legSpec{kind: MethodDirectDPI, delay: 0})
		if len(nodes) > 0 {
			n0 := nodes[0]
			legs = append(legs, legSpec{kind: MethodTunnel, delay: 300 * time.Millisecond, node: &n0})
		}
		if len(nodes) > 1 {
			n1 := nodes[1]
			legs = append(legs, legSpec{kind: MethodTunnel, delay: 950 * time.Millisecond, node: &n1})
		}
	}

	raceCtx, cancelRace := context.WithCancel(ctx)
	defer cancelRace()

	resCh := make(chan legOutcome, len(legs))
	for _, leg := range legs {
		go func(sp legSpec) {
			if sp.delay > 0 {
				timer := time.NewTimer(sp.delay)
				select {
				case <-timer.C:
				case <-raceCtx.Done():
					timer.Stop()
					resCh <- legOutcome{err: raceCtx.Err()}
					return
				}
			}
			conn, rttMs, err := cr.runSingleLeg(raceCtx, cfg, sp, host, port, initialPayload)
			nodeID := ""
			if sp.node != nil {
				nodeID = sp.node.ID
			}
			resCh <- legOutcome{
				conn:   conn,
				kind:   sp.kind,
				nodeID: nodeID,
				rttMs:  rttMs,
				err:    err,
			}
		}(leg)
	}

	var lastErr error
	var winner *legOutcome
	for i := 0; i < len(legs); i++ {
		out := <-resCh
		if out.err == nil && out.conn != nil {
			if winner == nil {
				w := out
				winner = &w
				cancelRace() // Immediately cancel slower in-flight legs!
				break
			} else {
				_ = out.conn.Close()
			}
		} else {
			if out.kind == MethodTunnel && out.nodeID != "" && !errors.Is(out.err, context.Canceled) {
				cr.pool.RecordFailure(out.nodeID)
			}
			if out.err != nil && !errors.Is(out.err, context.Canceled) {
				lastErr = out.err
			}
		}
	}

	if winner != nil {
		// Drain remaining goroutines in background so any late socket is closed cleanly without leaking FDs
		remaining := len(legs) - 1
		if remaining > 0 {
			go func(rem int) {
				for j := 0; j < rem; j++ {
					late := <-resCh
					if late.conn != nil {
						_ = late.conn.Close()
					}
				}
			}(remaining)
		}

		if winner.kind == MethodTunnel {
			cr.TunnelWins.Add(1)
			if winner.nodeID != "" {
				cr.pool.RecordSuccess(winner.nodeID, winner.rttMs)
			}
		} else {
			cr.DirectWins.Add(1)
		}
		cr.recordWinningMethod(etld, winner.kind)
		return winner.conn, nil
	}

	if lastErr == nil {
		lastErr = fmt.Errorf("all circumvention methods failed for %s:%s", host, port)
	}
	return nil, lastErr
}

func (cr *ConnectionRacer) runSingleLeg(
	ctx context.Context,
	cfg RuntimeConfig,
	sp legSpec,
	host, port string,
	initialPayload []byte,
) (net.Conn, int64, error) {
	start := time.Now()

	var conn net.Conn
	var err error
	if sp.kind == MethodTunnel && sp.node != nil {
		conn, err = DialOutboundNode(ctx, cr.router.doh, *sp.node, host, port)
	} else {
		conn, err = cr.router.dialAntiDPI(ctx, cfg, host, port)
	}
	if err != nil {
		return nil, 0, err
	}

	isTLS := len(initialPayload) >= 5 && initialPayload[0] == 0x16 && initialPayload[1] == 0x03
	isHTTP := !isTLS && looksLikePlainHTTP(initialPayload)

	legDeadline := time.Now().Add(7 * time.Second)
	if sp.kind == MethodDirectDPI {
		legDeadline = time.Now().Add(4500 * time.Millisecond)
	}
	_ = conn.SetDeadline(legDeadline)

	// Ensure context cancellation closes blocked Read/Write immediately
	stopDone := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.SetDeadline(time.Now())
		case <-stopDone:
		}
	}()

	if _, wErr := conn.Write(initialPayload); wErr != nil {
		close(stopDone)
		_ = conn.Close()
		return nil, 0, wErr
	}

	peekLen := 5
	if isHTTP {
		peekLen = 12
	}
	peek := make([]byte, peekLen)
	if _, rErr := io.ReadFull(conn, peek); rErr != nil {
		close(stopDone)
		_ = conn.Close()
		return nil, 0, rErr
	}
	close(stopDone)
	_ = conn.SetDeadline(time.Time{})

	// Verify genuine ServerHello / non-censored response!
	if isTLS {
		// ContentType MUST be 0x16 (Handshake: ServerHello), NEVER 0x15 (Alert) or 'H' (HTTP 403 blockpage)!
		if peek[0] != 0x16 || peek[1] != 0x03 {
			_ = conn.Close()
			return nil, 0, fmt.Errorf("non-ServerHello byte 0x%02x on %s leg", peek[0], sp.kind)
		}
	} else if isHTTP {
		head := string(peek)
		if !strings.HasPrefix(head, "HTTP/") || strings.Contains(head, " 451") || (sp.kind == MethodDirectDPI && strings.Contains(head, " 403")) {
			_ = conn.Close()
			return nil, 0, fmt.Errorf("invalid or blocked HTTP status %q on %s leg", head, sp.kind)
		}
	}

	rttMs := time.Since(start).Milliseconds()
	return &prefixedConn{
		Conn:   conn,
		prefix: peek,
	}, rttMs, nil
}

type prefixedConn struct {
	net.Conn
	mu     sync.Mutex
	prefix []byte
}

func (pc *prefixedConn) Read(b []byte) (int, error) {
	pc.mu.Lock()
	if len(pc.prefix) > 0 {
		n := copy(b, pc.prefix)
		pc.prefix = pc.prefix[n:]
		pc.mu.Unlock()
		return n, nil
	}
	pc.mu.Unlock()
	return pc.Conn.Read(b)
}
