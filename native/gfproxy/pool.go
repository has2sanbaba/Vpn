package main

import (
	"context"
	"crypto/tls"
	_ "embed"
	"encoding/json"
	stderrors "errors"
	"io"
	"log"
	"math/rand"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

//go:embed seed_nodes.txt
var embeddedSeedNodes string

var liveSubscriptionFeeds = []string{
	"https://raw.githubusercontent.com/noahclanman/free-xray-gcp/main/output/fast/configs.txt",
	"https://cdn.jsdelivr.net/gh/noahclanman/free-xray-gcp@main/output/fast/configs.txt",
	"https://raw.githubusercontent.com/noahclanman/free-xray-gcp/main/output/protocols/vless.txt",
	"https://raw.githubusercontent.com/noahclanman/free-xray-gcp/main/output/protocols/shadowsocks.txt",
	"https://raw.githubusercontent.com/noahclanman/free-xray-gcp/main/output/protocols/trojan.txt",
	"https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Sub1.txt",
}

type scoredNode struct {
	Node           OutboundNode `json:"node"`
	AvgRTTMs       int64        `json:"avg_rtt_ms"`
	ConsecFailures int          `json:"-"`
	LastVerified   int64        `json:"last_verified"`
}

// NodePool implements GreatFire EnvoyConnectWorker-style concurrent multi-transport racing
// completely in the background so browser startup is instantaneous (0ms wait).
type NodePool struct {
	doh        *DoHResolver
	cachePath  string

	mu         sync.RWMutex
	ready      []scoredNode
	customNode *OutboundNode

	firstReadyOnce sync.Once
	firstReadyCh   chan struct{}
	refreshing     atomic.Bool
	stopCh         chan struct{}
}

func NewNodePool(doh *DoHResolver, dataDir string) *NodePool {
	var cachePath string
	if dataDir != "" {
		_ = os.MkdirAll(dataDir, 0o755)
		cachePath = filepath.Join(dataDir, "nodes_cache.json")
	}
	p := &NodePool{
		doh:          doh,
		cachePath:    cachePath,
		firstReadyCh: make(chan struct{}),
		stopCh:       make(chan struct{}),
	}
	p.loadCachedNodes()
	return p
}

func (p *NodePool) loadCachedNodes() {
	if p.cachePath == "" {
		return
	}
	b, err := os.ReadFile(p.cachePath)
	if err != nil || len(b) == 0 {
		return
	}
	var cached []scoredNode
	if err := json.Unmarshal(b, &cached); err != nil || len(cached) == 0 {
		return
	}
	p.mu.Lock()
	if len(cached) > 10 {
		cached = cached[:10]
	}
	p.ready = cached
	p.mu.Unlock()
	p.signalFirstReady()
}

func (p *NodePool) saveCachedNodes() {
	if p.cachePath == "" {
		return
	}
	p.mu.RLock()
	snapshot := make([]scoredNode, len(p.ready))
	copy(snapshot, p.ready)
	p.mu.RUnlock()

	if len(snapshot) == 0 {
		return
	}
	if b, err := json.Marshal(snapshot); err == nil {
		_ = os.WriteFile(p.cachePath, b, 0o600)
	}
}

func (p *NodePool) signalFirstReady() {
	p.firstReadyOnce.Do(func() {
		close(p.firstReadyCh)
	})
}

// StartBackground launches non-blocking concurrent probing of seed + live subscription nodes.
func (p *NodePool) StartBackground() {
	go func() {
		// Phase 1 (t = 0ms): Immediately probe cached + embedded seed nodes across 20 concurrent workers!
		seeds := ParseShareLinks(embeddedSeedNodes)
		p.mu.RLock()
		existing := make([]OutboundNode, 0, len(p.ready)+len(seeds))
		for _, s := range p.ready {
			existing = append(existing, s.Node)
		}
		p.mu.RUnlock()
		existing = append(existing, seeds...)
		p.probeCandidatesConcurrent(existing, 20, 10)

		// Phase 2: Fetch fresh verified nodes from live GitHub/jsDelivr feeds and probe fastest ones
		p.RefreshFromFeedsAsync()

		// Periodic background health maintenance every 7 minutes
		ticker := time.NewTicker(7 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if p.ReadyCount() < 4 {
					p.RefreshFromFeedsAsync()
				} else {
					p.reverifyCurrentPool()
				}
			case <-p.stopCh:
				return
			}
		}
	}()
}

func (p *NodePool) Close() {
	select {
	case <-p.stopCh:
	default:
		close(p.stopCh)
	}
}

func (p *NodePool) SetCustomURI(uri string) error {
	uri = strings.TrimSpace(uri)
	if uri == "" {
		p.mu.Lock()
		p.customNode = nil
		p.mu.Unlock()
		return nil
	}
	node, ok := ParseSingleShareLink(uri)
	if !ok {
		return stderrors.New("لینک پروکسی نامعتبر است (پشتیبانی از vless, trojan, ss, socks5, http)")
	}
	p.mu.Lock()
	p.customNode = &node
	p.mu.Unlock()
	p.signalFirstReady()
	return nil
}

func (p *NodePool) GetCustomURI() string {
	p.mu.RLock()
	defer p.mu.RUnlock()
	if p.customNode == nil {
		return ""
	}
	return p.customNode.RawURI
}

// AddStaticTestNode injects a pre-verified node directly (used by unit/integration tests).
func (p *NodePool) AddStaticTestNode(node OutboundNode, rttMs int64) {
	p.upsertVerifiedNode(node, rttMs)
}

func (p *NodePool) ReadyCount() int {
	p.mu.RLock()
	defer p.mu.RUnlock()
	cnt := len(p.ready)
	if p.customNode != nil {
		cnt++
	}
	return cnt
}

func (p *NodePool) BestNodeSummary() (name string, proto string, rttMs int64, count int) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	if p.customNode != nil {
		return p.customNode.Name, string(p.customNode.Protocol), p.customNode.AvgRTTMs, len(p.ready) + 1
	}
	if len(p.ready) == 0 {
		return "", "", 0, 0
	}
	top := p.ready[0]
	return top.Node.Name, string(top.Node.Protocol), top.AvgRTTMs, len(p.ready)
}

// PickNodes returns up to maxNodes distinct ready nodes ordered by lowest RTT (waiting briefly on very first cold boot if needed).
func (p *NodePool) PickNodes(ctx context.Context, maxNodes int, coldWait time.Duration) []OutboundNode {
	if p.ReadyCount() == 0 && coldWait > 0 {
		timer := time.NewTimer(coldWait)
		select {
		case <-p.firstReadyCh:
		case <-timer.C:
		case <-ctx.Done():
		}
		timer.Stop()
	}

	p.mu.RLock()
	defer p.mu.RUnlock()

	var out []OutboundNode
	if p.customNode != nil {
		out = append(out, *p.customNode)
	}
	for i := 0; i < len(p.ready) && len(out) < maxNodes; i++ {
		out = append(out, p.ready[i].Node)
	}
	return out
}

func (p *NodePool) RecordSuccess(nodeID string, rttMs int64) {
	if rttMs <= 0 {
		rttMs = 150
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	for i := range p.ready {
		if p.ready[i].Node.ID == nodeID {
			if p.ready[i].AvgRTTMs <= 0 {
				p.ready[i].AvgRTTMs = rttMs
			} else {
				p.ready[i].AvgRTTMs = (p.ready[i].AvgRTTMs*3 + rttMs) / 4
			}
			p.ready[i].Node.AvgRTTMs = p.ready[i].AvgRTTMs
			p.ready[i].ConsecFailures = 0
			p.ready[i].LastVerified = time.Now().Unix()
			sort.Slice(p.ready, func(a, b int) bool {
				return p.ready[a].AvgRTTMs < p.ready[b].AvgRTTMs
			})
			return
		}
	}
}

func (p *NodePool) RecordFailure(nodeID string) {
	needRefresh := false
	p.mu.Lock()
	for i := range p.ready {
		if p.ready[i].Node.ID == nodeID {
			p.ready[i].ConsecFailures++
			p.ready[i].AvgRTTMs += 600
			if p.ready[i].ConsecFailures >= 2 {
				p.ready = append(p.ready[:i], p.ready[i+1:]...)
				if len(p.ready) < 3 {
					needRefresh = true
				}
			} else {
				sort.Slice(p.ready, func(a, b int) bool {
					return p.ready[a].AvgRTTMs < p.ready[b].AvgRTTMs
				})
			}
			break
		}
	}
	p.mu.Unlock()

	if needRefresh {
		p.RefreshFromFeedsAsync()
	}
}

func (p *NodePool) upsertVerifiedNode(node OutboundNode, rttMs int64) {
	node.AvgRTTMs = rttMs
	now := time.Now().Unix()

	p.mu.Lock()
	found := false
	for i := range p.ready {
		if p.ready[i].Node.ID == node.ID {
			p.ready[i].AvgRTTMs = rttMs
			p.ready[i].Node = node
			p.ready[i].ConsecFailures = 0
			p.ready[i].LastVerified = now
			found = true
			break
		}
	}
	if !found {
		p.ready = append(p.ready, scoredNode{
			Node:         node,
			AvgRTTMs:     rttMs,
			LastVerified: now,
		})
	}
	sort.Slice(p.ready, func(a, b int) bool {
		return p.ready[a].AvgRTTMs < p.ready[b].AvgRTTMs
	})
	if len(p.ready) > 12 {
		p.ready = p.ready[:12]
	}
	p.mu.Unlock()

	p.signalFirstReady()
}

func (p *NodePool) RefreshFromFeedsAsync() {
	if !p.refreshing.CompareAndSwap(false, true) {
		return
	}
	go func() {
		defer p.refreshing.Store(false)

		type feedRes struct {
			nodes []OutboundNode
		}
		ch := make(chan feedRes, len(liveSubscriptionFeeds))
		var wg sync.WaitGroup

		for _, feedURL := range liveSubscriptionFeeds {
			wg.Add(1)
			go func(u string) {
				defer wg.Done()
				body := p.fetchFeedText(u)
				if body != "" {
					parsed := ParseShareLinks(body)
					if len(parsed) > 45 {
						parsed = parsed[:45]
					}
					ch <- feedRes{nodes: parsed}
				}
			}(feedURL)
		}

		go func() {
			wg.Wait()
			close(ch)
		}()

		seen := make(map[string]bool)
		var candidates []OutboundNode
		for res := range ch {
			for _, n := range res.nodes {
				if !seen[n.ID] {
					seen[n.ID] = true
					candidates = append(candidates, n)
				}
			}
		}
		if len(candidates) == 0 {
			return
		}

		rand.Shuffle(len(candidates), func(i, j int) {
			candidates[i], candidates[j] = candidates[j], candidates[i]
		})
		if len(candidates) > 90 {
			candidates = candidates[:90]
		}

		log.Printf("[gfproxy-pool] Testing %d fresh nodes concurrently...", len(candidates))
		p.probeCandidatesConcurrent(candidates, 24, 10)
		p.saveCachedNodes()
	}()
}

func (p *NodePool) reverifyCurrentPool() {
	p.mu.RLock()
	curr := make([]OutboundNode, 0, len(p.ready))
	for _, s := range p.ready {
		curr = append(curr, s.Node)
	}
	p.mu.RUnlock()
	if len(curr) > 0 {
		p.probeCandidatesConcurrent(curr, 8, 10)
		p.saveCachedNodes()
	}
}

func (p *NodePool) fetchFeedText(feedURL string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 9*time.Second)
	defer cancel()

	// Try Anti-DPI DoH + TLS-Fragmented direct fetch first
	tr := &http.Transport{
		ForceAttemptHTTP2: true,
		DialTLSContext: func(dialCtx context.Context, _, addr string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(addr)
			if err != nil {
				host = addr
				port = "443"
			}
			ips, err := p.doh.Resolve(dialCtx, host)
			if err != nil || len(ips) == 0 {
				return nil, err
			}
			var d net.Dialer
			d.Timeout = 4 * time.Second
			raw, err := d.DialContext(dialCtx, "tcp", net.JoinHostPort(ips[0], port))
			if err != nil {
				return nil, err
			}
			frag := NewFragmentingConn(raw, 20, 2*time.Millisecond)
			return handshakeChromeUTLS(dialCtx, frag, host, false, false)
		},
	}
	defer tr.CloseIdleConnections()

	client := &http.Client{Transport: tr, Timeout: 8 * time.Second}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, feedURL, nil)
	if err == nil {
		req.Header.Set("User-Agent", "Mozilla/5.0 Chrome/130.0.0.0")
		if resp, doErr := client.Do(req); doErr == nil {
			defer resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				if b, rErr := io.ReadAll(io.LimitReader(resp.Body, 768*1024)); rErr == nil && len(b) > 32 {
					return string(b)
				}
			}
		}
	}

	// Fallback: fetch through an already-ready tunnel node if direct GitHub was IP-throttled
	nodes := p.PickNodes(ctx, 1, 0)
	if len(nodes) > 0 {
		tunTr := &http.Transport{
			DialTLSContext: func(dialCtx context.Context, _, addr string) (net.Conn, error) {
				host, port, _ := net.SplitHostPort(addr)
				raw, dErr := DialOutboundNode(dialCtx, p.doh, nodes[0], host, port)
				if dErr != nil {
					return nil, dErr
				}
				tlsConn := tls.Client(raw, &tls.Config{
					ServerName: host,
					RootCAs:    AndroidRootCertPool(),
					MinVersion: tls.VersionTLS12,
				})
				if hsErr := tlsConn.HandshakeContext(dialCtx); hsErr != nil {
					_ = raw.Close()
					return nil, hsErr
				}
				return tlsConn, nil
			},
		}
		defer tunTr.CloseIdleConnections()
		tunClient := &http.Client{Transport: tunTr, Timeout: 7 * time.Second}
		req2, _ := http.NewRequestWithContext(ctx, http.MethodGet, feedURL, nil)
		if resp2, err2 := tunClient.Do(req2); err2 == nil {
			defer resp2.Body.Close()
			if resp2.StatusCode == http.StatusOK {
				if b, rErr := io.ReadAll(io.LimitReader(resp2.Body, 768*1024)); rErr == nil {
					return string(b)
				}
			}
		}
	}
	return ""
}

func (p *NodePool) probeCandidatesConcurrent(candidates []OutboundNode, workers int, stopAfterSuccesses int) {
	if len(candidates) == 0 {
		return
	}
	if workers > len(candidates) {
		workers = len(candidates)
	}

	jobs := make(chan OutboundNode, len(candidates))
	for _, c := range candidates {
		jobs <- c
	}
	close(jobs)

	var foundCount atomic.Int32
	var wg sync.WaitGroup
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for node := range jobs {
				if stopAfterSuccesses > 0 && int(foundCount.Load()) >= stopAfterSuccesses {
					return
				}
				if rtt, ok := p.testNodeEndToEnd(node); ok {
					foundCount.Add(1)
					p.upsertVerifiedNode(node, rtt)
				}
			}
		}()
	}
	wg.Wait()
	if foundCount.Load() > 0 {
		p.saveCachedNodes()
	}
}

// testNodeEndToEnd verifies that the node genuinely reaches a filtered HTTPS endpoint (www.youtube.com)
// within 3.5 seconds — guaranteeing zero dead or captive-portal nodes enter the ready pool.
func (p *NodePool) testNodeEndToEnd(node OutboundNode) (int64, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 3500*time.Millisecond)
	defer cancel()

	start := time.Now()
	raw, err := DialOutboundNode(ctx, p.doh, node, "www.youtube.com", "443")
	if err != nil {
		return 0, false
	}
	defer raw.Close()

	_ = raw.SetDeadline(time.Now().Add(3000 * time.Millisecond))
	tlsConn := tls.Client(raw, &tls.Config{
		ServerName: "www.youtube.com",
		RootCAs:    AndroidRootCertPool(),
		NextProtos: []string{"http/1.1"},
		MinVersion: tls.VersionTLS12,
	})
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		return 0, false
	}

	reqBytes := []byte("GET /generate_204 HTTP/1.1\r\nHost: www.youtube.com\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n")
	if _, err := tlsConn.Write(reqBytes); err != nil {
		return 0, false
	}
	var respPrefix [16]byte
	if _, err := io.ReadFull(tlsConn, respPrefix[:]); err != nil {
		return 0, false
	}
	line := string(respPrefix[:])
	if !strings.HasPrefix(line, "HTTP/1.1 204") && !strings.HasPrefix(line, "HTTP/1.1 200") && !strings.HasPrefix(line, "HTTP/1.1 30") {
		return 0, false
	}

	rtt := time.Since(start).Milliseconds()
	return rtt, true
}
