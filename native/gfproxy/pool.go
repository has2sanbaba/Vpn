package main

import (
	"context"
	"crypto/tls"
	_ "embed"
	"encoding/json"
	stderrors "errors"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	"net/http"
	"net/url"
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

// FeedSource represents an independent proxy aggregator with multi-CDN mirror failover
// so censorship of raw.githubusercontent.com or any single collector never starves the pool.
type FeedSource struct {
	Name    string
	Mirrors []string
}

func ghMirrors(ownerRepo, branch, path string) []string {
	return []string{
		fmt.Sprintf("https://cdn.jsdelivr.net/gh/%s@%s/%s", ownerRepo, branch, path),
		fmt.Sprintf("https://fastly.jsdelivr.net/gh/%s@%s/%s", ownerRepo, branch, path),
		fmt.Sprintf("https://testingcf.jsdelivr.net/gh/%s@%s/%s", ownerRepo, branch, path),
		fmt.Sprintf("https://raw.githubusercontent.com/%s/%s/%s", ownerRepo, branch, path),
	}
}

// 10 genuinely independent, actively updated multi-protocol proxy sources (VLESS, VMess, Trojan, SS)
var defaultFeedSources = []FeedSource{
	{Name: "FreeXray", Mirrors: ghMirrors("noahclanman/free-xray-gcp", "main", "output/fast/configs.txt")},
	{Name: "F0rc3Run", Mirrors: ghMirrors("F0rc3Run/F0rc3Run", "main", "Best-Results/proxies.txt")},
	{Name: "Bahemmat", Mirrors: ghMirrors("MohammadBahemmat/V2ray-Collector", "main", "Tested.txt")},
	{Name: "iBoxz", Mirrors: ghMirrors("iboxz/free-v2ray-collector", "main", "main/mix.txt")},
	{Name: "MahdiBland", Mirrors: ghMirrors("mahdibland/V2RayAggregator", "master", "Eternity.txt")},
	{Name: "Radikal", Mirrors: ghMirrors("0xRadikal/Free-v2ray-Configs", "main", "Countries/Germany.txt")},
	{Name: "Matin", Mirrors: ghMirrors("MatinGhanbari/v2ray-configs", "main", "subscriptions/base64/subs/sub1.txt")},
	{Name: "BarryFar", Mirrors: ghMirrors("barry-far/V2ray-Config", "main", "Base64/Sub1_base64.txt")},
	{Name: "Au1rxx", Mirrors: ghMirrors("Au1rxx/free-vpn-subscriptions", "main", "output/all-verified/v2ray-base64-0001.txt")},
	{Name: "Igareck", Mirrors: ghMirrors("igareck/vpn-configs-for-russia", "main", "BLACK_SS+All_RUS.txt")},
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
	Source    string `json:"source"`
	Protocol  string `json:"protocol"`
	RTTMs     int64  `json:"rtt_ms"`
	SpeedKbps int64  `json:"speed_kbps"`
	Active    bool   `json:"active"`
	Custom    bool   `json:"custom"`
}

type SwitchBestResult struct {
	OK            bool   `json:"ok"`
	SwitchedFrom  string `json:"switched_from,omitempty"`
	BestID        string `json:"best_id,omitempty"`
	BestNode      string `json:"best_node,omitempty"`
	BestSource    string `json:"best_source,omitempty"`
	BestProto     string `json:"best_proto,omitempty"`
	BestRTTMs     int64  `json:"best_rtt_ms,omitempty"`
	BestSpeedKbps int64  `json:"best_speed_kbps,omitempty"`
	ReadyNodes    int    `json:"ready_nodes"`
	ActiveSources int    `json:"active_sources"`
	Error         string `json:"error,omitempty"`
}

type CustomAddResult struct {
	OK           bool   `json:"ok"`
	AddedCount   int    `json:"added_count"`
	WorkingCount int    `json:"working_count"`
	IsSub        bool   `json:"is_sub"`
	BestID       string `json:"best_id,omitempty"`
	BestNode     string `json:"best_node,omitempty"`
	BestProto    string `json:"best_proto,omitempty"`
	BestRTTMs    int64  `json:"best_rtt_ms,omitempty"`
	Message      string `json:"message,omitempty"`
	Error        string `json:"error,omitempty"`
}

type persistedCustomStore struct {
	Nodes []OutboundNode `json:"nodes"`
	Subs  []string       `json:"subs"`
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

// interleaveNodeGroups performs fair round-robin selection across all independent proxy sources
// so no single repository can monopolize the candidate pool.
func interleaveNodeGroups(groups [][]OutboundNode, maxTotal int) []OutboundNode {
	var out []OutboundNode
	seen := make(map[string]bool)
	maxLen := 0
	for _, g := range groups {
		if len(g) > maxLen {
			maxLen = len(g)
		}
	}
	for row := 0; row < maxLen; row++ {
		for _, g := range groups {
			if row < len(g) {
				n := g[row]
				if !seen[n.ID] {
					seen[n.ID] = true
					out = append(out, n)
					if maxTotal > 0 && len(out) >= maxTotal {
						return out
					}
				}
			}
		}
	}
	return out
}

type NodePool struct {
	doh             *DoHResolver
	cachePath       string
	customStorePath string

	mu            sync.RWMutex
	ready         []scoredNode
	feedPool      []OutboundNode
	customNodes   []OutboundNode
	customSubs    []string
	pinnedID      string
	excludedUntil map[string]time.Time
	customNode    *OutboundNode

	// Custom probe hook for deterministic unit tests; nil in production
	probeHook func(node OutboundNode) (rttMs int64, speedKbps int64, ok bool)

	firstReadyOnce sync.Once
	firstReadyCh   chan struct{}
	refreshing     atomic.Bool
	stopCh         chan struct{}
}

func NewNodePool(doh *DoHResolver, dataDir string) *NodePool {
	var cachePath, customPath string
	if dataDir != "" {
		_ = os.MkdirAll(dataDir, 0o755)
		cachePath = filepath.Join(dataDir, "nodes_cache.json")
		customPath = filepath.Join(dataDir, "custom_configs.json")
	}
	p := &NodePool{
		doh:             doh,
		cachePath:       cachePath,
		customStorePath: customPath,
		excludedUntil:   make(map[string]time.Time),
		firstReadyCh:    make(chan struct{}),
		stopCh:          make(chan struct{}),
	}
	p.loadCustomStore()
	p.loadCachedNodes()
	return p
}

func (p *NodePool) loadCustomStore() {
	if p.customStorePath == "" {
		return
	}
	b, err := os.ReadFile(p.customStorePath)
	if err != nil || len(b) == 0 {
		return
	}
	var st persistedCustomStore
	if json.Unmarshal(b, &st) == nil {
		p.mu.Lock()
		p.customNodes = st.Nodes
		p.customSubs = st.Subs
		p.mu.Unlock()
	}
}

func (p *NodePool) saveCustomStore() {
	if p.customStorePath == "" {
		return
	}
	p.mu.RLock()
	st := persistedCustomStore{
		Nodes: append([]OutboundNode(nil), p.customNodes...),
		Subs:  append([]string(nil), p.customSubs...),
	}
	p.mu.RUnlock()
	if b, err := json.Marshal(st); err == nil {
		_ = os.WriteFile(p.customStorePath, b, 0o600)
	}
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
		if cached[i].Node.Source == "" {
			cached[i].Node.Source = inferSourceFromRemarkOrDefault(cached[i].Node.Name, "Global")
		}
		if cached[i].ScoreMs <= 0 {
			cached[i].ScoreMs = computeEffectiveScore(cached[i].AvgRTTMs, cached[i].SpeedKbps)
		}
	}
	p.ready = cached
	p.sortReadyLocked()
	if len(p.ready) > 28 {
		p.ready = p.ready[:28]
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

// StartBackground launches BOTH multi-source seed probing AND 10-source live CDN feed fetching
// simultaneously at t=0ms, then triggers a second tunnel-assisted multi-source sweep if needed.
func (p *NodePool) StartBackground() {
	p.RefreshFromFeedsAsync()

	go func() {
		seeds := ParseShareLinksWithSource(embeddedSeedNodes, "MultiSeed")
		p.mu.RLock()
		existing := make([]OutboundNode, 0, len(p.customNodes)+len(p.ready)+len(seeds))
		seen := make(map[string]bool)
		for _, cn := range p.customNodes {
			if !seen[cn.ID] {
				seen[cn.ID] = true
				existing = append(existing, cn)
			}
		}
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

		p.probeCandidatesConcurrent(existing, 28, 18)

		// Once at least one seed/tunnel node is up, ensure diverse live sources were collected
		if p.ActiveSourcesCount() < 4 {
			p.RefreshFromFeedsAsync()
		}

		ticker := time.NewTicker(5 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if p.ReadyCount() < 6 || p.ActiveSourcesCount() < 3 {
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
		p.pinnedID = ""
		p.mu.Unlock()
		return nil
	}
	parsed := ParseShareLinksWithSource(uri, "اختصاصی کاربر")
	if len(parsed) == 0 {
		return stderrors.New(DiagnoseUnsupportedShareLink(uri))
	}
	first := parsed[0]
	p.mu.Lock()
	p.customNode = &first
	p.mergeCustomNodesLocked(parsed)
	p.mu.Unlock()
	p.saveCustomStore()
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

func (p *NodePool) mergeCustomNodesLocked(incoming []OutboundNode) int {
	existing := make(map[string]int, len(p.customNodes))
	for i, n := range p.customNodes {
		existing[n.ID] = i
	}
	added := 0
	for _, n := range incoming {
		if idx, ok := existing[n.ID]; ok {
			p.customNodes[idx] = n
		} else {
			p.customNodes = append([]OutboundNode{n}, p.customNodes...)
			// Rebuild index map
			existing = make(map[string]int, len(p.customNodes))
			for i, item := range p.customNodes {
				existing[item.ID] = i
			}
			added++
		}
	}
	if len(p.customNodes) > 80 {
		p.customNodes = p.customNodes[:80]
	}
	return added
}

func looksLikeSubscriptionURL(raw string) bool {
	trimmed := strings.TrimSpace(raw)
	if strings.ContainsAny(trimmed, " \n\r\t") {
		return false
	}
	u, err := url.Parse(trimmed)
	if err != nil || u.Host == "" {
		return false
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme == "https" {
		return true
	}
	if scheme == "http" {
		return (u.Path != "" && u.Path != "/") || u.RawQuery != ""
	}
	return false
}

// AddCustomInput handles user-submitted configs: single share links (vless/vmess/trojan/ss/socks5/http),
// multi-line Telegram config dumps, Base64 bundles, AND live Subscription URLs (https://...).
func (p *NodePool) AddCustomInput(ctx context.Context, rawInput string) CustomAddResult {
	trimmed := strings.TrimSpace(rawInput)
	if trimmed == "" {
		return CustomAddResult{OK: false, Error: "لطفاً لینک کانفیگ یا اشتراک (Sub) را وارد کنید."}
	}

	if looksLikeSubscriptionURL(trimmed) {
		body := p.fetchSingleURL(ctx, trimmed)
		if body == "" {
			return CustomAddResult{
				OK:    false,
				IsSub: true,
				Error: "دریافت لینک اشتراک ناموفق بود (آدرس در دسترس نیست یا پاسخ خالی است).",
			}
		}
		parsed := ParseShareLinksWithSource(body, "اشتراک کاربر")
		if len(parsed) == 0 {
			return CustomAddResult{
				OK:    false,
				IsSub: true,
				Error: "هیچ کانفیگ سازگاری (VLESS/VMess/Trojan/SS) در این لینک اشتراک یافت نشد.",
			}
		}

		p.mu.Lock()
		hasSub := false
		for _, s := range p.customSubs {
			if strings.EqualFold(s, trimmed) {
				hasSub = true
				break
			}
		}
		if !hasSub {
			p.customSubs = append([]string{trimmed}, p.customSubs...)
			if len(p.customSubs) > 15 {
				p.customSubs = p.customSubs[:15]
			}
		}
		sample := parsed
		if len(sample) > 30 {
			sample = sample[:30]
		}
		p.mergeCustomNodesLocked(sample)
		p.mu.Unlock()
		p.saveCustomStore()

		benchCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		working := p.runConcurrentProbeCount(benchCtx, sample, 16, 10)
		cancel()

		bestRes := p.pinFastestAmongIDs(sample)
		msg := fmt.Sprintf("لینک اشتراک اضافه شد (%d کانفیگ دریافت و %d سرور فعال تایید شد)", len(parsed), working)
		return CustomAddResult{
			OK:           true,
			AddedCount:   len(parsed),
			WorkingCount: working,
			IsSub:        true,
			BestID:       bestRes.BestID,
			BestNode:     bestRes.BestNode,
			BestProto:    bestRes.BestProto,
			BestRTTMs:    bestRes.BestRTTMs,
			Message:      msg,
		}
	}

	parsed := ParseShareLinksWithSource(trimmed, "اختصاصی کاربر")
	if len(parsed) == 0 {
		return CustomAddResult{
			OK:    false,
			Error: DiagnoseUnsupportedShareLink(trimmed),
		}
	}

	p.mu.Lock()
	p.mergeCustomNodesLocked(parsed)
	p.customNode = nil
	p.mu.Unlock()
	p.saveCustomStore()

	benchCtx, cancel := context.WithTimeout(ctx, 4800*time.Millisecond)
	working := p.runConcurrentProbeCount(benchCtx, parsed, 14, 10)
	cancel()

	if working > 0 {
		bestRes := p.pinFastestAmongIDs(parsed)
		return CustomAddResult{
			OK:           true,
			AddedCount:   len(parsed),
			WorkingCount: working,
			BestID:       bestRes.BestID,
			BestNode:     bestRes.BestNode,
			BestProto:    bestRes.BestProto,
			BestRTTMs:    bestRes.BestRTTMs,
			Message:      fmt.Sprintf("✅ متصل به کانفیگ اختصاصی: %s (%dms)", bestRes.BestNode, bestRes.BestRTTMs),
		}
	}

	// Even if live probe to youtube timed out right now, if the user explicitly added 1 config and wants to try it,
	// keep it in customNodes and test basic TCP dial; if reachable, upsert with conservative RTT so user can select it!
	first := parsed[0]
	dialCtx, dialCancel := context.WithTimeout(ctx, 2500*time.Millisecond)
	rawConn, dialErr := DialOutboundNode(dialCtx, p.doh, first, "www.gstatic.com", "443")
	dialCancel()
	if dialErr == nil && rawConn != nil {
		_ = rawConn.Close()
		p.upsertVerifiedNode(first, 320, 1500)
		bestRes := p.pinFastestAmongIDs([]OutboundNode{first})
		return CustomAddResult{
			OK:           true,
			AddedCount:   len(parsed),
			WorkingCount: 1,
			BestID:       bestRes.BestID,
			BestNode:     bestRes.BestNode,
			BestProto:    bestRes.BestProto,
			BestRTTMs:    320,
			Message:      fmt.Sprintf("✅ کانفیگ ذخیره و فعال شد: %s", first.Name),
		}
	}

	return CustomAddResult{
		OK:           true,
		AddedCount:   len(parsed),
		WorkingCount: 0,
		BestNode:     first.Name,
		BestProto:    string(first.Protocol),
		Message:      "⚠ کانفیگ ذخیره شد ولی روی اینترنت فعلی پاسخ نداد؛ اتصال هوشمند خودکار فعال ماند.",
	}
}

func (p *NodePool) pinFastestAmongIDs(candidates []OutboundNode) SwitchBestResult {
	idSet := make(map[string]bool, len(candidates))
	for _, c := range candidates {
		idSet[c.ID] = true
	}
	p.mu.Lock()
	p.customNode = nil
	bestID := ""
	bestScore := int64(1 << 60)
	for _, s := range p.ready {
		if idSet[s.Node.ID] {
			delete(p.excludedUntil, s.Node.ID)
			sc := s.ScoreMs
			if sc <= 0 {
				sc = computeEffectiveScore(s.AvgRTTMs, s.SpeedKbps)
			}
			if sc < bestScore {
				bestScore = sc
				bestID = s.Node.ID
			}
		}
	}
	if bestID != "" {
		p.pinnedID = bestID
	}
	p.sortReadyLocked()
	var res SwitchBestResult
	if len(p.ready) > 0 {
		top := p.ready[0]
		res = SwitchBestResult{
			OK:            true,
			BestID:        top.Node.ID,
			BestNode:      top.Node.Name,
			BestSource:    top.Node.Source,
			BestProto:     string(top.Node.Protocol),
			BestRTTMs:     top.AvgRTTMs,
			BestSpeedKbps: top.SpeedKbps,
			ReadyNodes:    len(p.ready),
		}
	}
	p.mu.Unlock()
	p.saveCachedNodes()
	return res
}

func (p *NodePool) ListCustomConfigs() ([]NodeSummaryItem, []string) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	readyMap := make(map[string]scoredNode, len(p.ready))
	activeID := ""
	if p.customNode != nil {
		activeID = p.customNode.ID
	} else if len(p.ready) > 0 {
		activeID = p.ready[0].Node.ID
	}
	for _, s := range p.ready {
		readyMap[s.Node.ID] = s
	}

	items := make([]NodeSummaryItem, 0, len(p.customNodes))
	for _, cn := range p.customNodes {
		sc, isReady := readyMap[cn.ID]
		rtt := cn.AvgRTTMs
		spd := int64(0)
		if isReady {
			rtt = sc.AvgRTTMs
			spd = sc.SpeedKbps
		}
		items = append(items, NodeSummaryItem{
			ID:        cn.ID,
			Name:      cn.Name,
			Source:    firstNonEmpty(cn.Source, "اختصاصی کاربر"),
			Protocol:  strings.ToUpper(string(cn.Protocol)),
			RTTMs:     rtt,
			SpeedKbps: spd,
			Active:    cn.ID == activeID,
			Custom:    true,
		})
	}
	subs := append([]string(nil), p.customSubs...)
	return items, subs
}

func (p *NodePool) DeleteCustomConfig(nodeID, subURL string, clearAll bool) {
	p.mu.Lock()
	if clearAll {
		customIDs := make(map[string]bool, len(p.customNodes))
		for _, cn := range p.customNodes {
			customIDs[cn.ID] = true
		}
		p.customNodes = nil
		p.customSubs = nil
		p.customNode = nil
		if customIDs[p.pinnedID] {
			p.pinnedID = ""
		}
		filtered := p.ready[:0]
		for _, s := range p.ready {
			if !customIDs[s.Node.ID] {
				filtered = append(filtered, s)
			}
		}
		p.ready = filtered
	} else {
		if nodeID != "" {
			if p.customNode != nil && p.customNode.ID == nodeID {
				p.customNode = nil
			}
			if p.pinnedID == nodeID {
				p.pinnedID = ""
			}
			nextCustom := p.customNodes[:0]
			for _, cn := range p.customNodes {
				if cn.ID != nodeID {
					nextCustom = append(nextCustom, cn)
				}
			}
			p.customNodes = nextCustom

			nextReady := p.ready[:0]
			for _, s := range p.ready {
				if s.Node.ID != nodeID {
					nextReady = append(nextReady, s)
				}
			}
			p.ready = nextReady
		}
		if subURL != "" {
			nextSubs := p.customSubs[:0]
			for _, s := range p.customSubs {
				if !strings.EqualFold(s, subURL) {
					nextSubs = append(nextSubs, s)
				}
			}
			p.customSubs = nextSubs
		}
	}
	p.sortReadyLocked()
	p.mu.Unlock()
	p.saveCustomStore()
	p.saveCachedNodes()
}

// AddStaticTestNode injects a pre-verified node directly (used by unit/integration tests).
func (p *NodePool) AddStaticTestNode(node OutboundNode, rttMs int64) {
	if node.Source == "" {
		node.Source = "TestSource"
	}
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

func (p *NodePool) ActiveSourcesCount() int {
	p.mu.RLock()
	defer p.mu.RUnlock()
	sources := make(map[string]bool)
	if p.customNode != nil {
		sources["اختصاصی کاربر"] = true
	}
	for _, s := range p.ready {
		src := firstNonEmpty(s.Node.Source, inferSourceFromRemarkOrDefault(s.Node.Name, "Global"))
		sources[src] = true
	}
	return len(sources)
}

func (p *NodePool) BestNodeSummary() (name string, source string, proto string, rttMs int64, speedKbps int64, count int, activeSources int) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	srcSet := make(map[string]bool)
	for _, s := range p.ready {
		srcSet[firstNonEmpty(s.Node.Source, "Global")] = true
	}
	if p.customNode != nil {
		srcSet["اختصاصی کاربر"] = true
		return p.customNode.Name, "اختصاصی کاربر", string(p.customNode.Protocol), p.customNode.AvgRTTMs, 0, len(p.ready) + 1, len(srcSet)
	}
	if len(p.ready) == 0 {
		return "", "", "", 0, 0, 0, 0
	}
	top := p.ready[0]
	topSrc := firstNonEmpty(top.Node.Source, inferSourceFromRemarkOrDefault(top.Node.Name, "Global"))
	return top.Node.Name, topSrc, string(top.Node.Protocol), top.AvgRTTMs, top.SpeedKbps, len(p.ready), len(srcSet)
}

func (p *NodePool) ListReadyNodes() []NodeSummaryItem {
	p.mu.RLock()
	defer p.mu.RUnlock()

	customIDs := make(map[string]bool, len(p.customNodes))
	for _, cn := range p.customNodes {
		customIDs[cn.ID] = true
	}

	out := make([]NodeSummaryItem, 0, len(p.ready))
	for i, s := range p.ready {
		src := firstNonEmpty(s.Node.Source, inferSourceFromRemarkOrDefault(s.Node.Name, "Global"))
		out = append(out, NodeSummaryItem{
			ID:        s.Node.ID,
			Name:      s.Node.Name,
			Source:    src,
			Protocol:  strings.ToUpper(string(s.Node.Protocol)),
			RTTMs:     s.AvgRTTMs,
			SpeedKbps: s.SpeedKbps,
			Active:    p.customNode == nil && i == 0,
			Custom:    customIDs[s.Node.ID],
		})
	}
	return out
}

func (p *NodePool) SelectNodeByID(nodeID string) (SwitchBestResult, bool) {
	nodeID = strings.TrimSpace(nodeID)
	if nodeID == "" {
		return SwitchBestResult{}, false
	}

	// Check if node is already in ready pool; if it's a saved custom node not yet in ready, probe/activate it!
	p.mu.RLock()
	inReady := false
	for _, s := range p.ready {
		if s.Node.ID == nodeID {
			inReady = true
			break
		}
	}
	var customMatch *OutboundNode
	if !inReady {
		for i := range p.customNodes {
			if p.customNodes[i].ID == nodeID {
				cp := p.customNodes[i]
				customMatch = &cp
				break
			}
		}
	}
	p.mu.RUnlock()

	if customMatch != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 3800*time.Millisecond)
		rtt, kbps, ok := p.evaluateNode(ctx, *customMatch)
		cancel()
		if ok {
			p.upsertVerifiedNode(*customMatch, rtt, kbps)
		} else {
			p.upsertVerifiedNode(*customMatch, 350, 1000)
		}
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
		BestSource:    firstNonEmpty(top.Node.Source, "Global"),
		BestProto:     string(top.Node.Protocol),
		BestRTTMs:     top.AvgRTTMs,
		BestSpeedKbps: top.SpeedKbps,
		ReadyNodes:    cnt,
		ActiveSources: p.ActiveSourcesCount(),
	}, true
}

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
		if len(candidates) >= 26 {
			break
		}
		if !seen[fn.ID] && p.excludedUntil[fn.ID].IsZero() {
			seen[fn.ID] = true
			candidates = append(candidates, fn)
		}
	}
	p.mu.Unlock()

	if len(candidates) < 12 {
		for _, sn := range ParseShareLinksWithSource(embeddedSeedNodes, "MultiSeed") {
			if len(candidates) >= 26 {
				break
			}
			if !seen[sn.ID] {
				seen[sn.ID] = true
				candidates = append(candidates, sn)
			}
		}
	}

	if len(candidates) > 0 {
		benchCtx, cancel := context.WithTimeout(ctx, 4200*time.Millisecond)
		p.benchmarkCandidatesWithContext(benchCtx, candidates, 18)
		cancel()
	}

	p.mu.Lock()
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
			Error:        "درحال دریافت سرورهای جدید از منابع چندگانه — چند ثانیه دیگر امتحان کنید",
		}
	}

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
	p.RefreshFromFeedsAsync()

	return SwitchBestResult{
		OK:            true,
		SwitchedFrom:  prevName,
		BestID:        chosen.Node.ID,
		BestNode:      chosen.Node.Name,
		BestSource:    firstNonEmpty(chosen.Node.Source, "Global"),
		BestProto:     string(chosen.Node.Protocol),
		BestRTTMs:     chosen.AvgRTTMs,
		BestSpeedKbps: chosen.SpeedKbps,
		ReadyNodes:    cnt,
		ActiveSources: p.ActiveSourcesCount(),
	}
}

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
	seen := make(map[string]bool)
	if p.customNode != nil {
		out = append(out, *p.customNode)
		seen[p.customNode.ID] = true
	}
	for i := 0; i < len(p.ready) && len(out) < maxNodes; i++ {
		if !seen[p.ready[i].Node.ID] {
			seen[p.ready[i].Node.ID] = true
			out = append(out, p.ready[i].Node)
		}
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
				if len(p.ready) < 5 {
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
	if node.Source == "" {
		node.Source = inferSourceFromRemarkOrDefault(node.Name, "Global")
	}
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
	if len(p.ready) > 28 {
		p.ready = p.ready[:28]
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

		p.mu.RLock()
		userSubs := append([]string(nil), p.customSubs...)
		p.mu.RUnlock()

		sources := make([]FeedSource, 0, len(userSubs)+len(defaultFeedSources))
		for i, u := range userSubs {
			sources = append(sources, FeedSource{
				Name:    fmt.Sprintf("Sub-%d", i+1),
				Mirrors: []string{u},
			})
		}
		sources = append(sources, defaultFeedSources...)

		type feedRes struct {
			idx   int
			nodes []OutboundNode
		}
		ch := make(chan feedRes, len(sources))
		var wg sync.WaitGroup

		for idx, src := range sources {
			wg.Add(1)
			go func(i int, fs FeedSource) {
				defer wg.Done()
				nodes := p.fetchSourceWithFailover(fs)
				if len(nodes) > 0 {
					ch <- feedRes{idx: i, nodes: nodes}
				}
			}(idx, src)
		}

		go func() {
			wg.Wait()
			close(ch)
		}()

		collected := make([][]OutboundNode, len(sources))
		sourcesSucceeded := 0
		for res := range ch {
			if res.idx >= 0 && res.idx < len(collected) && len(res.nodes) > 0 {
				collected[res.idx] = res.nodes
				sourcesSucceeded++
			}
		}

		// Round-robin interleave across ALL independent sources so every collector contributes!
		candidates := interleaveNodeGroups(collected, 140)
		if len(candidates) == 0 {
			return
		}

		p.mu.Lock()
		p.feedPool = append([]OutboundNode(nil), candidates...)
		p.mu.Unlock()

		// Keep top 40 interleaved fast nodes in order + shuffle remainder for maximum diversity
		if len(candidates) > 40 {
			tail := candidates[40:]
			rand.Shuffle(len(tail), func(i, j int) {
				tail[i], tail[j] = tail[j], tail[i]
			})
		}
		if len(candidates) > 100 {
			candidates = candidates[:100]
		}

		log.Printf("[gfproxy-pool] Fetched from %d independent sources; benchmarking %d interleaved nodes...", sourcesSucceeded, len(candidates))
		p.probeCandidatesConcurrent(candidates, 28, 20)
		p.saveCachedNodes()
	}()
}

func (p *NodePool) fetchSourceWithFailover(fs FeedSource) []OutboundNode {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	for _, mirrorURL := range fs.Mirrors {
		body := p.fetchSingleURL(ctx, mirrorURL)
		if body == "" {
			continue
		}
		parsed := ParseShareLinksWithSource(body, fs.Name)
		if len(parsed) > 0 {
			for i := range parsed {
				parsed[i].Source = fs.Name
			}
			if len(parsed) > 36 {
				parsed = parsed[:36]
			}
			return parsed
		}
	}
	return nil
}

// fetchSingleURL fetches subscription content via HTTP/1.1 DoH + TLS SNI fragmentation
// (forceHTTP11=true so Go's http.Transport never speaks HTTP/1.1 over an ALPN h2 socket!),
// with automatic fallback through the fastest active tunnel node.
func (p *NodePool) fetchSingleURL(parentCtx context.Context, feedURL string) string {
	ctx, cancel := context.WithTimeout(parentCtx, 6500*time.Millisecond)
	defer cancel()

	tr := &http.Transport{
		ForceAttemptHTTP2: false,
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
			d.Timeout = 3000 * time.Millisecond
			raw, err := d.DialContext(dialCtx, "tcp", net.JoinHostPort(ips[0], port))
			if err != nil {
				return nil, err
			}
			frag := NewFragmentingConn(raw, 20, 2*time.Millisecond)
			return handshakeChromeUTLS(dialCtx, frag, host, true, false)
		},
		DialContext: func(dialCtx context.Context, _, addr string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(addr)
			if err != nil {
				host = addr
				port = "80"
			}
			ips, err := p.doh.Resolve(dialCtx, host)
			if err == nil && len(ips) > 0 {
				host = ips[0]
			}
			var d net.Dialer
			d.Timeout = 3000 * time.Millisecond
			return d.DialContext(dialCtx, "tcp", net.JoinHostPort(host, port))
		},
	}
	defer tr.CloseIdleConnections()

	client := &http.Client{Transport: tr, Timeout: 6000 * time.Millisecond}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, feedURL, nil)
	if err == nil {
		req.Header.Set("User-Agent", "v2rayNG/1.8.38 Mozilla/5.0 Chrome/130.0.0.0")
		if resp, doErr := client.Do(req); doErr == nil {
			b, rErr := io.ReadAll(io.LimitReader(resp.Body, 896*1024))
			_ = resp.Body.Close()
			if resp.StatusCode == http.StatusOK && rErr == nil && len(b) > 24 {
				return string(b)
			}
		}
	}

	// Fallback: fetch through an already-verified tunnel node if ISP blocked direct CDN access
	nodes := p.PickNodes(parentCtx, 1, 0)
	if len(nodes) > 0 {
		tunCtx, tunCancel := context.WithTimeout(parentCtx, 5500*time.Millisecond)
		defer tunCancel()
		tunTr := &http.Transport{
			ForceAttemptHTTP2: false,
			DialTLSContext: func(dialCtx context.Context, _, addr string) (net.Conn, error) {
				host, port, sErr := net.SplitHostPort(addr)
				if sErr != nil {
					host = addr
					port = "443"
				}
				raw, dErr := DialOutboundNode(dialCtx, p.doh, nodes[0], host, port)
				if dErr != nil {
					return nil, dErr
				}
				tlsConn := tls.Client(raw, &tls.Config{
					ServerName: host,
					RootCAs:    AndroidRootCertPool(),
					NextProtos: []string{"http/1.1"},
					MinVersion: tls.VersionTLS12,
				})
				if hsErr := tlsConn.HandshakeContext(dialCtx); hsErr != nil {
					_ = raw.Close()
					return nil, hsErr
				}
				return tlsConn, nil
			},
			DialContext: func(dialCtx context.Context, _, addr string) (net.Conn, error) {
				host, port, sErr := net.SplitHostPort(addr)
				if sErr != nil {
					host = addr
					port = "80"
				}
				return DialOutboundNode(dialCtx, p.doh, nodes[0], host, port)
			},
		}
		defer tunTr.CloseIdleConnections()
		tunClient := &http.Client{Transport: tunTr, Timeout: 5200 * time.Millisecond}
		req2, r2Err := http.NewRequestWithContext(tunCtx, http.MethodGet, feedURL, nil)
		if r2Err == nil {
			req2.Header.Set("User-Agent", "v2rayNG/1.8.38 Mozilla/5.0 Chrome/130.0.0.0")
			if resp2, err2 := tunClient.Do(req2); err2 == nil {
				b, rErr := io.ReadAll(io.LimitReader(resp2.Body, 896*1024))
				_ = resp2.Body.Close()
				if resp2.StatusCode == http.StatusOK && rErr == nil && len(b) > 24 {
					return string(b)
				}
			}
		}
	}
	return ""
}

func (p *NodePool) reverifyCurrentPool() {
	p.mu.RLock()
	curr := make([]OutboundNode, 0, len(p.ready))
	for _, s := range p.ready {
		curr = append(curr, s.Node)
	}
	p.mu.RUnlock()
	if len(curr) > 0 {
		p.probeCandidatesConcurrent(curr, 12, 16)
		p.saveCachedNodes()
	}
}

func (p *NodePool) probeCandidatesConcurrent(candidates []OutboundNode, workers int, stopAfterSuccesses int) {
	ctx, cancel := context.WithTimeout(context.Background(), 9*time.Second)
	defer cancel()
	_ = p.runConcurrentProbeCount(ctx, candidates, workers, stopAfterSuccesses)
}

func (p *NodePool) benchmarkCandidatesWithContext(ctx context.Context, candidates []OutboundNode, workers int) {
	_ = p.runConcurrentProbeCount(ctx, candidates, workers, 10)
}

func (p *NodePool) runConcurrentProbeCount(ctx context.Context, candidates []OutboundNode, workers int, stopAfterSuccesses int) int {
	if len(candidates) == 0 {
		return 0
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
	cnt := int(foundCount.Load())
	if cnt > 0 {
		p.saveCachedNodes()
	}
	return cnt
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

	if !strings.Contains(line, "\r\n\r\n") {
		var tmp [512]byte
		_, _ = tlsConn.Read(tmp[:])
	}

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
		speedKbps = 640000 / (rtt + 40)
	}

	return rtt, speedKbps, true
}
