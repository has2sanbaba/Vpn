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

// Multi-CDN verified feeds (jsDelivr + Fastly + GitHub Raw) so even if raw.githubusercontent.com
// is throttled on the user's ISP, CDN mirrors deliver fresh <400ms configs in <250ms.
var liveSubscriptionFeeds = []string{
	"https://cdn.jsdelivr.net/gh/noahclanman/free-xray-gcp@main/output/fast/configs.txt",
	"https://fastly.jsdelivr.net/gh/noahclanman/free-xray-gcp@main/output/fast/configs.txt",
	"https://raw.githubusercontent.com/noahclanman/free-xray-gcp/main/output/fast/configs.txt",
	"https://cdn.jsdelivr.net/gh/noahclanman/free-xray-gcp@main/output/protocols/vless.txt",
	"https://cdn.jsdelivr.net/gh/noahclanman/free-xray-gcp@main/output/protocols/shadowsocks.txt",
	"https://cdn.jsdelivr.net/gh/noahclanman/free-xray-gcp@main/output/protocols/trojan.txt",
	"https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Sub1.txt",
}

type scoredNode struct {
	Node           OutboundNode `json:"node"`
	AvgRTTMs       int64        `json:"avg_rtt_ms"`
	SpeedKbps      int64        `json:"speed_kbps"`
	ScoreMs        int64        `json:"score_ms"`
	ConsecFailures int          `json:"-"`
	LastVerified   int64        `json:"last_verified"`
}

type NodeSummaryItem struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Protocol  string `json:"protocol"`
	RTTMs     int64  `json:"rtt_ms"`
	SpeedKbps int64  `json:"speed_kbps"`
	Active    bool   `json:"active"`
}

type SwitchBestResult struct {
	OK            bool   `json:"ok"`
	SwitchedFrom  string `json:"switched_from,omitempty"`
	BestID        string `json:"best_id,omitempty"`
	BestNode      string `json:"best_node,omitempty"`
	BestProto     string `json:"best_proto,omitempty"`
	BestRTTMs     int64  `json:"best_rtt_ms,omitempty"`
	BestSpeedKbps int64  `json:"best_speed_kbps,omitempty"`
	ReadyNodes    int    `json:"ready_nodes"`
	Error         string `json:"error,omitempty"`
}

// computeEffectiveScore combines round-trip latency and real download throughput on the user's ISP
// so high-speed proxies always outrank congested low-bandwidth proxies.
func computeEffectiveScore(rttMs, speedKbps int64) int64 {
	if rttMs <= 0 {
		rttMs = 350
	}
	score := rttMs
	switch {
	case speedKbps >= 12000:
		score -= 110
	case speedKbps >= 6000:
		score -= 75
	case speedKbps >= 2500:
		score -= 40
	case speedKbps > 0 && speedKbps < 350:
		score += 450
	}
	if score < 5 {
		score = 5
	}
	return score
}

// NodePool manages concurrent multi-protocol proxy benchmarking, instant first-launch auto-selection
// of the fastest proxy for the user's current internet, and 1-tap smart proxy switching.
type NodePool struct {
	doh       *DoHResolver
	cachePath string

	mu            sync.RWMutex
	ready         []scoredNode
	feedPool      []OutboundNode
	pinnedID      string
	excludedUntil map[string]time.Time
	customNode    *OutboundNode

	// Custom probe hook for deterministic unit tests; nil in production (uses real HTTPS speed+ping test)
	probeHook func(node OutboundNode) (rttMs int64, speedKbps int64, ok bool)

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
		doh:           doh,
		cachePath:     cachePath,
		excludedUntil: make(map[string]time.Time),
		firstReadyCh:  make(chan struct{}),
		stopCh:        make(chan struct{}),
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
	for i := range cached {
		if cached[i].ScoreMs <= 0 {
			cached[i].ScoreMs = computeEffectiveScore(cached[i].AvgRTTMs, cached[i].SpeedKbps)
		}
	}
	p.ready = cached
	p.sortReadyLocked()
	if len(p.ready) > 16 {
		p.ready = p.ready[:16]
	}
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

func (p *NodePool) sortReadyLocked() {
	now := time.Now()
	for id, exp := range p.excludedUntil {
		if now.After(exp) {
			delete(p.excludedUntil, id)
		}
	}
	sort.SliceStable(p.ready, func(a, b int) bool {
		idA := p.ready[a].Node.ID
		idB := p.ready[b].Node.ID
		if p.pinnedID != "" {
			if idA == p.pinnedID && idB != p.pinnedID {
				return true
			}
			if idB == p.pinnedID && idA != p.pinnedID {
				return false
			}
		}
		exA := !p.excludedUntil[idA].IsZero()
		exB := !p.excludedUntil[idB].IsZero()
		if exA != exB {
			return !exA
		}
		scA := p.ready[a].ScoreMs
		if scA <= 0 {
			scA = computeEffectiveScore(p.ready[a].AvgRTTMs, p.ready[a].SpeedKbps)
		}
		scB := p.ready[b].ScoreMs
		if scB <= 0 {
			scB = computeEffectiveScore(p.ready[b].AvgRTTMs, p.ready[b].SpeedKbps)
		}
		return scA < scB
	})
}

// StartBackground launches BOTH embedded-seed speed probing AND live multi-CDN feed fetching
// simultaneously at t=0ms so on the very first cold open, the app locks onto the fastest proxy
// for the user's internet within ~0.7–1.5s without waiting for dead seeds to time out.
func (p *NodePool) StartBackground() {
	// Launch live CDN feed fetch immediately in parallel (zero wait on seeds!)
	p.RefreshFromFeedsAsync()

	go func() {
		seeds := ParseShareLinks(embeddedSeedNodes)
		p.mu.RLock()
		existing := make([]OutboundNode, 0, len(p.ready)+len(seeds))
		seen := make(map[string]bool)
		for _, s := range p.ready {
			if !seen[s.Node.ID] {
				seen[s.Node.ID] = true
				existing = append(existing, s.Node)
			}
		}
		p.mu.RUnlock()
		for _, s := range seeds {
			if !seen[s.ID] {
				seen[s.ID] = true
				existing = append(existing, s)
			}
		}

		// Probe across 28 concurrent workers so the fastest seed finishes in <1s on first launch
		p.probeCandidatesConcurrent(existing, 28, 14)

		ticker := time.NewTicker(5 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if p.ReadyCount() < 5 {
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
	p.upsertVerifiedNode(node, rttMs, 20000)
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

func (p *NodePool) BestNodeSummary() (name string, proto string, rttMs int64, speedKbps int64, count int) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	if p.customNode != nil {
		return p.customNode.Name, string(p.customNode.Protocol), p.customNode.AvgRTTMs, 0, len(p.ready) + 1
	}
	if len(p.ready) == 0 {
		return "", "", 0, 0, 0
	}
	top := p.ready[0]
	return top.Node.Name, string(top.Node.Protocol), top.AvgRTTMs, top.SpeedKbps, len(p.ready)
}

func (p *NodePool) ListReadyNodes() []NodeSummaryItem {
	p.mu.RLock()
	defer p.mu.RUnlock()
	out := make([]NodeSummaryItem, 0, len(p.ready))
	for i, s := range p.ready {
		out = append(out, NodeSummaryItem{
			ID:        s.Node.ID,
			Name:      s.Node.Name,
			Protocol:  strings.ToUpper(string(s.Node.Protocol)),
			RTTMs:     s.AvgRTTMs,
			SpeedKbps: s.SpeedKbps,
			Active:    p.customNode == nil && i == 0,
		})
	}
	return out
}

func (p *NodePool) SelectNodeByID(nodeID string) (SwitchBestResult, bool) {
	nodeID = strings.TrimSpace(nodeID)
	if nodeID == "" {
		return SwitchBestResult{}, false
	}
	p.mu.Lock()
	var prevName string
	if len(p.ready) > 0 {
		prevName = p.ready[0].Node.Name
	}
	found := false
	for _, s := range p.ready {
		if s.Node.ID == nodeID {
			found = true
			break
		}
	}
	if !found {
		p.mu.Unlock()
		return SwitchBestResult{OK: false, Error: "سرور مورد نظر در لیست فعال یافت نشد"}, false
	}
	p.customNode = nil
	delete(p.excludedUntil, nodeID)
	p.pinnedID = nodeID
	p.sortReadyLocked()
	top := p.ready[0]
	cnt := len(p.ready)
	p.mu.Unlock()

	p.saveCachedNodes()
	return SwitchBestResult{
		OK:            true,
		SwitchedFrom:  prevName,
		BestID:        top.Node.ID,
		BestNode:      top.Node.Name,
		BestProto:     string(top.Node.Protocol),
		BestRTTMs:     top.AvgRTTMs,
		BestSpeedKbps: top.SpeedKbps,
		ReadyNodes:    cnt,
	}, true
}

// SwitchToBestDifferentNode benchmarks multiple candidate proxies concurrently on the user's
// current internet connection, excludes the previous proxy so it genuinely switches to a fresh
// server, and locks onto the proxy with the highest speed + lowest ping.
func (p *NodePool) SwitchToBestDifferentNode(ctx context.Context) SwitchBestResult {
	p.mu.Lock()
	var prevID, prevName string
	if p.customNode != nil {
		prevName = p.customNode.Name
		p.customNode = nil
	} else if len(p.ready) > 0 {
		prevID = p.ready[0].Node.ID
		prevName = p.ready[0].Node.Name
	}
	if prevID != "" {
		p.excludedUntil[prevID] = time.Now().Add(8 * time.Minute)
		if p.pinnedID == prevID {
			p.pinnedID = ""
		}
	}

	// Collect candidate pool: existing ready nodes (excluding prevID) + fresh feed/seed candidates
	seen := make(map[string]bool)
	if prevID != "" {
		seen[prevID] = true
	}
	var candidates []OutboundNode
	for _, s := range p.ready {
		if !seen[s.Node.ID] && p.excludedUntil[s.Node.ID].IsZero() {
			seen[s.Node.ID] = true
			candidates = append(candidates, s.Node)
		}
	}
	for _, fn := range p.feedPool {
		if len(candidates) >= 22 {
			break
		}
		if !seen[fn.ID] && p.excludedUntil[fn.ID].IsZero() {
			seen[fn.ID] = true
			candidates = append(candidates, fn)
		}
	}
	p.mu.Unlock()

	if len(candidates) < 10 {
		for _, sn := range ParseShareLinks(embeddedSeedNodes) {
			if len(candidates) >= 22 {
				break
			}
			if !seen[sn.ID] {
				seen[sn.ID] = true
				candidates = append(candidates, sn)
			}
		}
	}

	// Live parallel speed + ping benchmark on the user's current network
	if len(candidates) > 0 {
		benchCtx, cancel := context.WithTimeout(ctx, 4200*time.Millisecond)
		p.benchmarkCandidatesWithContext(benchCtx, candidates, 16)
		cancel()
	}

	p.mu.Lock()
	// If all servers were previously cycled through and excluded, clear exclusions except prevID
	hasNonExcluded := false
	for _, s := range p.ready {
		if s.Node.ID != prevID && p.excludedUntil[s.Node.ID].IsZero() {
			hasNonExcluded = true
			break
		}
	}
	if !hasNonExcluded && len(p.ready) > 1 {
		p.excludedUntil = make(map[string]time.Time)
		if prevID != "" {
			p.excludedUntil[prevID] = time.Now().Add(3 * time.Minute)
		}
	}

	p.pinnedID = ""
	p.sortReadyLocked()

	if len(p.ready) == 0 {
		p.mu.Unlock()
		p.RefreshFromFeedsAsync()
		return SwitchBestResult{
			OK:           false,
			SwitchedFrom: prevName,
			ReadyNodes:   0,
			Error:        "درحال دریافت سرورهای جدید — چند ثانیه دیگر امتحان کنید",
		}
	}

	// Pin the top non-previous node as the active #1 proxy
	chosenIdx := 0
	for i, s := range p.ready {
		if s.Node.ID != prevID {
			chosenIdx = i
			break
		}
	}
	chosen := p.ready[chosenIdx]
	p.pinnedID = chosen.Node.ID
	p.sortReadyLocked()
	cnt := len(p.ready)
	p.mu.Unlock()

	p.saveCachedNodes()
	// Also refresh background pool asynchronously so more fast servers stay warm
	p.RefreshFromFeedsAsync()

	return SwitchBestResult{
		OK:            true,
		SwitchedFrom:  prevName,
		BestID:        chosen.Node.ID,
		BestNode:      chosen.Node.Name,
		BestProto:     string(chosen.Node.Protocol),
		BestRTTMs:     chosen.AvgRTTMs,
		BestSpeedKbps: chosen.SpeedKbps,
		ReadyNodes:    cnt,
	}
}

// PickNodes returns up to maxNodes distinct ready nodes ordered by active/fastest score.
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
			p.ready[i].ScoreMs = computeEffectiveScore(p.ready[i].AvgRTTMs, p.ready[i].SpeedKbps)
			p.ready[i].ConsecFailures = 0
			p.ready[i].LastVerified = time.Now().Unix()
			p.sortReadyLocked()
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
			p.ready[i].ScoreMs = computeEffectiveScore(p.ready[i].AvgRTTMs, p.ready[i].SpeedKbps)
			if p.pinnedID == nodeID && p.ready[i].ConsecFailures >= 2 {
				p.pinnedID = ""
			}
			if p.ready[i].ConsecFailures >= 2 {
				p.ready = append(p.ready[:i], p.ready[i+1:]...)
				if len(p.ready) < 4 {
					needRefresh = true
				}
			} else {
				p.sortReadyLocked()
			}
			break
		}
	}
	p.mu.Unlock()

	if needRefresh {
		p.RefreshFromFeedsAsync()
	}
}

func (p *NodePool) upsertVerifiedNode(node OutboundNode, rttMs int64, speedKbps int64) {
	node.AvgRTTMs = rttMs
	now := time.Now().Unix()
	score := computeEffectiveScore(rttMs, speedKbps)

	p.mu.Lock()
	found := false
	for i := range p.ready {
		if p.ready[i].Node.ID == node.ID {
			p.ready[i].AvgRTTMs = rttMs
			if speedKbps > 0 {
				p.ready[i].SpeedKbps = speedKbps
			}
			p.ready[i].ScoreMs = computeEffectiveScore(rttMs, p.ready[i].SpeedKbps)
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
			SpeedKbps:    speedKbps,
			ScoreMs:      score,
			LastVerified: now,
		})
	}
	p.sortReadyLocked()
	if len(p.ready) > 16 {
		p.ready = p.ready[:16]
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
			idx   int
			nodes []OutboundNode
		}
		ch := make(chan feedRes, len(liveSubscriptionFeeds))
		var wg sync.WaitGroup

		for idx, feedURL := range liveSubscriptionFeeds {
			wg.Add(1)
			go func(i int, u string) {
				defer wg.Done()
				body := p.fetchFeedText(u)
				if body != "" {
					parsed := ParseShareLinks(body)
					if len(parsed) > 55 {
						parsed = parsed[:55]
					}
					ch <- feedRes{idx: i, nodes: parsed}
				}
			}(idx, feedURL)
		}

		go func() {
			wg.Wait()
			close(ch)
		}()

		collected := make([][]OutboundNode, len(liveSubscriptionFeeds))
		for res := range ch {
			if res.idx >= 0 && res.idx < len(collected) {
				collected[res.idx] = res.nodes
			}
		}

		// Preserve feed priority order (fast/configs.txt <400ms nodes come FIRST!)
		seen := make(map[string]bool)
		var candidates []OutboundNode
		for _, group := range collected {
			for _, n := range group {
				if !seen[n.ID] {
					seen[n.ID] = true
					candidates = append(candidates, n)
				}
			}
		}
		if len(candidates) == 0 {
			return
		}

		p.mu.Lock()
		if len(candidates) > 120 {
			p.feedPool = append([]OutboundNode(nil), candidates[:120]...)
		} else {
			p.feedPool = append([]OutboundNode(nil), candidates...)
		}
		p.mu.Unlock()

		// Keep top 35 pre-ranked fast nodes in order + shuffle remainder for diversity
		if len(candidates) > 35 {
			tail := candidates[35:]
			rand.Shuffle(len(tail), func(i, j int) {
				tail[i], tail[j] = tail[j], tail[i]
			})
		}
		if len(candidates) > 90 {
			candidates = candidates[:90]
		}

		log.Printf("[gfproxy-pool] Benchmarking %d live fast nodes for current ISP...", len(candidates))
		p.probeCandidatesConcurrent(candidates, 26, 14)
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
		p.probeCandidatesConcurrent(curr, 10, 12)
		p.saveCachedNodes()
	}
}

func (p *NodePool) fetchFeedText(feedURL string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

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
			d.Timeout = 3500 * time.Millisecond
			raw, err := d.DialContext(dialCtx, "tcp", net.JoinHostPort(ips[0], port))
			if err != nil {
				return nil, err
			}
			frag := NewFragmentingConn(raw, 20, 2*time.Millisecond)
			return handshakeChromeUTLS(dialCtx, frag, host, false, false)
		},
	}
	defer tr.CloseIdleConnections()

	client := &http.Client{Transport: tr, Timeout: 7500 * time.Millisecond}
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
		tunClient := &http.Client{Transport: tunTr, Timeout: 6500 * time.Millisecond}
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
	ctx, cancel := context.WithTimeout(context.Background(), 9*time.Second)
	defer cancel()
	p.runConcurrentProbe(ctx, candidates, workers, stopAfterSuccesses)
}

func (p *NodePool) benchmarkCandidatesWithContext(ctx context.Context, candidates []OutboundNode, workers int) {
	p.runConcurrentProbe(ctx, candidates, workers, 8)
}

func (p *NodePool) runConcurrentProbe(ctx context.Context, candidates []OutboundNode, workers int, stopAfterSuccesses int) {
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
				if ctx.Err() != nil {
					return
				}
				if stopAfterSuccesses > 0 && int(foundCount.Load()) >= stopAfterSuccesses {
					return
				}
				rtt, kbps, ok := p.evaluateNode(ctx, node)
				if ok {
					foundCount.Add(1)
					p.upsertVerifiedNode(node, rtt, kbps)
				}
			}
		}()
	}
	wg.Wait()
	if foundCount.Load() > 0 {
		p.saveCachedNodes()
	}
}

func (p *NodePool) evaluateNode(parentCtx context.Context, node OutboundNode) (int64, int64, bool) {
	p.mu.RLock()
	hook := p.probeHook
	p.mu.RUnlock()
	if hook != nil {
		return hook(node)
	}
	return p.testNodeEndToEndAndSpeed(parentCtx, node)
}

// testNodeEndToEndAndSpeed verifies genuine access to www.youtube.com AND measures actual payload
// download speed on the user's current ISP over the same warm TLS socket (zero extra handshake!).
func (p *NodePool) testNodeEndToEndAndSpeed(parentCtx context.Context, node OutboundNode) (int64, int64, bool) {
	ctx, cancel := context.WithTimeout(parentCtx, 3400*time.Millisecond)
	defer cancel()

	start := time.Now()
	raw, err := DialOutboundNode(ctx, p.doh, node, "www.youtube.com", "443")
	if err != nil {
		return 0, 0, false
	}
	defer raw.Close()

	_ = raw.SetDeadline(time.Now().Add(2900 * time.Millisecond))
	tlsConn := tls.Client(raw, &tls.Config{
		ServerName: "www.youtube.com",
		RootCAs:    AndroidRootCertPool(),
		NextProtos: []string{"http/1.1"},
		MinVersion: tls.VersionTLS12,
	})
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		return 0, 0, false
	}

	// Step 1: Verify filtered endpoint reachability & measure exact round-trip latency (RTT)
	reqPing := []byte("GET /generate_204 HTTP/1.1\r\nHost: www.youtube.com\r\nUser-Agent: Mozilla/5.0\r\nConnection: keep-alive\r\n\r\n")
	if _, err := tlsConn.Write(reqPing); err != nil {
		return 0, 0, false
	}
	var hdrBuf [256]byte
	nHdr, err := tlsConn.Read(hdrBuf[:])
	if err != nil || nHdr < 12 {
		return 0, 0, false
	}
	line := string(hdrBuf[:nHdr])
	if !strings.HasPrefix(line, "HTTP/1.1 204") && !strings.HasPrefix(line, "HTTP/1.1 200") && !strings.HasPrefix(line, "HTTP/1.1 30") {
		return 0, 0, false
	}
	rtt := time.Since(start).Milliseconds()
	if rtt <= 0 {
		rtt = 1
	}

	// Drain remainder of /generate_204 headers if split
	if !strings.Contains(line, "\r\n\r\n") {
		var tmp [512]byte
		_, _ = tlsConn.Read(tmp[:])
	}

	// Step 2: Stream real payload bytes from www.youtube.com/s/desktop/favicon (or homepage chunk)
	// over the ALREADY-OPEN TLS connection to measure actual ISP download bandwidth!
	speedKbps := int64(0)
	dlStart := time.Now()
	_ = raw.SetDeadline(time.Now().Add(1100 * time.Millisecond))
	reqPayload := []byte("GET /favicon.ico HTTP/1.1\r\nHost: www.youtube.com\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n")
	if _, wErr := tlsConn.Write(reqPayload); wErr == nil {
		totalBytes := 0
		var chunk [4096]byte
		for totalBytes < 32768 {
			nr, rErr := tlsConn.Read(chunk[:])
			if nr > 0 {
				totalBytes += nr
			}
			if rErr != nil {
				break
			}
		}
		dlMs := time.Since(dlStart).Milliseconds()
		if totalBytes > 512 && dlMs > 0 {
			speedKbps = (int64(totalBytes) * 8 * 1000) / dlMs
		}
	}
	if speedKbps <= 0 {
		// Estimate conservative kbps from RTT if favicon stream closed early
		speedKbps = 640000 / (rtt + 40)
	}

	return rtt, speedKbps, true
}
