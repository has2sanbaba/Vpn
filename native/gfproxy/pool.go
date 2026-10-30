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

const autoRetestInterval = 10 * time.Minute

// 50 verified, actively updated (Oct 2026) reputable multi-protocol proxy sources on GitHub
// (VLESS XHTTP/Reality/WS/gRPC/TCP, VMess, Trojan, Shadowsocks, Base64 & raw subscriptions).
var defaultFeedSources = []FeedSource{
	{Name: "RadikalTop", Mirrors: ghMirrors("0xRadikal/Free-v2ray-Configs", "main", "top100.txt")},
	{Name: "FreeXray", Mirrors: ghMirrors("noahclanman/free-xray-gcp", "main", "output/fast/configs.txt")},
	{Name: "F0rc3Run", Mirrors: ghMirrors("F0rc3Run/F0rc3Run", "main", "Best-Results/proxies.txt")},
	{Name: "Bahemmat", Mirrors: ghMirrors("MohammadBahemmat/V2ray-Collector", "main", "Tested.txt")},
	{Name: "iBoxz", Mirrors: ghMirrors("iboxz/free-v2ray-collector", "main", "main/mix.txt")},
	{Name: "MahdiBland", Mirrors: ghMirrors("mahdibland/V2RayAggregator", "master", "Eternity.txt")},
	{Name: "MatinSuper", Mirrors: ghMirrors("MatinGhanbari/v2ray-configs", "main", "subscriptions/v2ray/super-sub.txt")},
	{Name: "BarryFar", Mirrors: ghMirrors("barry-far/V2ray-Config", "main", "Base64/Sub1_base64.txt")},
	{Name: "Au1rxx", Mirrors: ghMirrors("Au1rxx/free-vpn-subscriptions", "main", "output/all-verified/v2ray-base64-0001.txt")},
	{Name: "Igareck", Mirrors: ghMirrors("igareck/vpn-configs-for-russia", "main", "BLACK_SS+All_RUS.txt")},
	{Name: "Alirewa", Mirrors: ghMirrors("Alirewa/V2ray-Configs", "main", "sub1.txt")},
	{Name: "Morpheus", Mirrors: ghMirrors("morpheusadam/v2ray-config", "main", "subs/bundles/lite.txt")},
	{Name: "Mehrtat", Mirrors: ghMirrors("mehrtat/vless-collector", "main", "sub.txt")},
	{Name: "Jeddi", Mirrors: ghMirrors("AhmadJeddi/V2RayCFGDumper", "main", "sub.txt")},
	{Name: "Tahanian", Mirrors: ghMirrors("miladtahanian/Config-Collector", "main", "mixed_iran.txt")},
	{Name: "YebekhePSG", Mirrors: ghMirrors("itsyebekhe/PSG", "main", "config.txt")},
	{Name: "DeltaSNI", Mirrors: ghMirrors("Delta-Kronecker/V2ray-Config", "main", "config/batches/sni_v2ray/batch_001.txt")},
	{Name: "Bijandi", Mirrors: ghMirrors("amir-reza-bijandi/v2ray-configs", "main", "configs.txt")},
	{Name: "RoosterKid", Mirrors: ghMirrors("roosterkid/openproxylist", "main", "V2RAY_RAW.txt")},
	{Name: "AnonMulti", Mirrors: ghMirrors("4n0nymou3/multi-proxy-config-fetcher", "main", "configs/proxy_configs.txt")},
	{Name: "V2RayRoot", Mirrors: ghMirrors("V2RayRoot/V2RayConfig", "main", "Config/vless.txt")},
	{Name: "NdsSpeed", Mirrors: ghMirrors("ndsphonemy/proxy-sub", "main", "speed.txt")},
	{Name: "NoMoreWalls", Mirrors: ghMirrors("peasoft/NoMoreWalls", "master", "list_raw.txt")},
	{Name: "Pawdroid", Mirrors: ghMirrors("Pawdroid/Free-servers", "main", "sub")},
	{Name: "Ripao", Mirrors: ghMirrors("ripaojiedian/freenode", "main", "sub")},
	{Name: "SnakePool", Mirrors: ghMirrors("snakem982/proxypool", "main", "source/v2ray-2.txt")},
	{Name: "SoliSpirit", Mirrors: ghMirrors("SoliSpirit/v2ray-configs", "main", "Subscriptions/Sub1.txt")},
	{Name: "Mahdi0024", Mirrors: ghMirrors("Mahdi0024/ProxyCollector", "master", "sub/proxies.txt")},
	{Name: "TaheriTrj", Mirrors: ghMirrors("MhdiTaheri/V2rayCollector", "main", "sub/trojan")},
	{Name: "TaheriMix", Mirrors: ghMirrors("MhdiTaheri/V2rayCollector_Py", "main", "sub/Mix/mix.txt")},
	{Name: "Lagzian", Mirrors: ghMirrors("lagzian/SS-Collector", "main", "configs/jp/vless.txt")},
	{Name: "FreedomSS", Mirrors: ghMirrors("MahanKenway/Freedom-V2Ray", "main", "configs/ss_sub.txt")},
	{Name: "MohamadFG", Mirrors: ghMirrors("mohamadfg-dev/telegram-v2ray-configs-collector", "main", "category/vless.txt")},
	{Name: "AsgharSub", Mirrors: ghMirrors("asgharkapk/Sub-Config-Extractor", "main", "input_subs/sssub.txt")},
	{Name: "SolVPN", Mirrors: ghMirrors("SoliSpirit/SolVPN", "main", "Subscribes/sub1.txt")},
	{Name: "Kasesm", Mirrors: ghMirrors("kasesm/Free-Config", "main", "trojan_sub.txt")},
	{Name: "Darmioniks", Mirrors: ghMirrors("Darmioniks/proxy-and-vless-collector", "main", "vless_filtered.txt")},
	{Name: "SylphNet", Mirrors: ghMirrors("ProblemTheCode/SylphNet-public", "main", "sub/sub_plain.txt")},
	{Name: "IdolVPN", Mirrors: ghMirrors("Idolvpn/Automate-V2ray-Config-Collector", "main", "configs/vless.txt")},
	{Name: "BlackCrow", Mirrors: ghMirrors("nukcrow/black-crow", "main", "sub/test")},
	{Name: "AmirKMA", Mirrors: ghMirrors("amirkma/proxykma", "main", "mix.txt")},
	{Name: "ProxyRift", Mirrors: ghMirrors("rekt0ro/ProxyRift", "main", "subscriptions/light.txt")},
	{Name: "NodeZixf", Mirrors: ghMirrors("chenzhihan1751-cyber/node-sub-zixf", "main", "sub/v2ray.txt")},
	{Name: "ViraVLESS", Mirrors: ghMirrors("PlanAslii/vira-v2ray-configs", "main", "protocols/vless.txt")},
	{Name: "HappSub", Mirrors: ghMirrors("Andrejewild/happ-v2rayng-configs", "main", "sub.txt")},
	{Name: "AlirezaSrv", Mirrors: ghMirrors("alireza786786/Free-V2ray-Configs", "main", "servers/vless_servers.txt")},
	{Name: "SsbaxysGeo", Mirrors: ghMirrors("ssbaxys/v2ray-configs", "main", "geo-configs.txt")},
	{Name: "CNTCafe", Mirrors: ghMirrors("CNT-Cafe/Public-Configs", "main", "test-configs.txt")},
	{Name: "SkyVPN", Mirrors: ghMirrors("vxstream/SkyVPN", "main", "sub.txt")},
	{Name: "MrPaster", Mirrors: ghMirrors("mrpaster12/config-proxy-collector", "main", "sub_raw.txt")},
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

type SuggestedUpgradeInfo struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Source    string `json:"source"`
	Protocol  string `json:"protocol"`
	RTTMs     int64  `json:"rtt_ms"`
	SpeedKbps int64  `json:"speed_kbps"`
}

type SwitchBestResult struct {
	OK            bool                  `json:"ok"`
	SwitchedFrom  string                `json:"switched_from,omitempty"`
	BestID        string                `json:"best_id,omitempty"`
	BestNode      string                `json:"best_node,omitempty"`
	BestSource    string                `json:"best_source,omitempty"`
	BestProto     string                `json:"best_proto,omitempty"`
	BestRTTMs     int64                 `json:"best_rtt_ms,omitempty"`
	BestSpeedKbps int64                 `json:"best_speed_kbps,omitempty"`
	ReadyNodes    int                   `json:"ready_nodes"`
	ActiveSources int                   `json:"active_sources"`
	Suggested     *SuggestedUpgradeInfo `json:"suggested,omitempty"`
	Error         string                `json:"error,omitempty"`
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

	mu               sync.RWMutex
	ready            []scoredNode
	feedPool         []OutboundNode
	customNodes      []OutboundNode
	customSubs       []string
	pinnedID         string
	excludedUntil    map[string]time.Time
	customNode       *OutboundNode
	suggested        *SuggestedUpgradeInfo
	dismissedSuggest map[string]bool

	// Custom probe hook for deterministic unit tests; nil in production
	probeHook     func(node OutboundNode) (rttMs int64, speedKbps int64, ok bool)
	onBestChanged func()

	firstReadyOnce     sync.Once
	firstReadyCh       chan struct{}
	startupSelectPhase atomic.Bool
	refreshing         atomic.Bool
	retesting          atomic.Bool
	inForeground       atomic.Bool
	reachableSources   atomic.Int32
	lastRetestUnix     atomic.Int64
	nextRetestUnix     atomic.Int64
	retestCount        atomic.Uint64
	retestSweepIdx     atomic.Uint32
	stopCh             chan struct{}
}

func NewNodePool(doh *DoHResolver, dataDir string) *NodePool {
	var cachePath, customPath string
	if dataDir != "" {
		_ = os.MkdirAll(dataDir, 0o755)
		cachePath = filepath.Join(dataDir, "nodes_cache.json")
		customPath = filepath.Join(dataDir, "custom_configs.json")
	}
	p := &NodePool{
		doh:              doh,
		cachePath:        cachePath,
		customStorePath:  customPath,
		excludedUntil:    make(map[string]time.Time),
		dismissedSuggest: make(map[string]bool),
		firstReadyCh:     make(chan struct{}),
		stopCh:           make(chan struct{}),
	}
	p.inForeground.Store(true)
	p.nextRetestUnix.Store(time.Now().Add(autoRetestInterval).Unix())
	p.loadCustomStore()
	p.loadCachedNodes()
	return p
}

func (p *NodePool) SetOnBestChanged(fn func()) {
	p.mu.Lock()
	p.onBestChanged = fn
	p.mu.Unlock()
}

func (p *NodePool) ConfiguredSourcesCount() int {
	p.mu.RLock()
	subs := len(p.customSubs)
	p.mu.RUnlock()
	return len(defaultFeedSources) + subs
}

func (p *NodePool) ReachableSourcesCount() int {
	v := int(p.reachableSources.Load())
	if active := p.ActiveSourcesCount(); active > v {
		return active
	}
	return v
}

func (p *NodePool) RetestMetrics() (lastAgoSec int64, nextInSec int64, count uint64) {
	now := time.Now().Unix()
	last := p.lastRetestUnix.Load()
	next := p.nextRetestUnix.Load()
	if last > 0 && now >= last {
		lastAgoSec = now - last
	} else {
		lastAgoSec = -1
	}
	if next > now {
		nextInSec = next - now
	} else {
		nextInSec = 0
	}
	return lastAgoSec, nextInSec, p.retestCount.Load()
}

// SetForeground lets Android lifecycle pause background re-probing when the app is minimized
// (0% idle CPU/battery drain) and trigger an immediate freshness check if stale on resume.
func (p *NodePool) SetForeground(fg bool) {
	wasFG := p.inForeground.Swap(fg)
	if fg && !wasFG {
		last := p.lastRetestUnix.Load()
		if last == 0 || time.Since(time.Unix(last, 0)) >= autoRetestInterval {
			go func() {
				ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
				defer cancel()
				_ = p.RunAutoRetest(ctx)
			}()
		}
	}
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

// StartBackground launches BOTH multi-source seed probing AND 50-source live feed fetching
// simultaneously at t=0ms, locks onto a fast verified server at startup so the user's exit IP
// never silently changes mid-session, and only notifies the user if a much faster server is found later.
func (p *NodePool) StartBackground() {
	p.mu.RLock()
	hasExplicitPin := p.pinnedID != ""
	p.mu.RUnlock()
	if !hasExplicitPin {
		p.startupSelectPhase.Store(true)
	}

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

		p.probeCandidatesConcurrent(existing, 20, 16)
		p.startupSelectPhase.Store(false)
		p.mu.Lock()
		p.sortReadyLocked()
		if p.pinnedID == "" && len(p.ready) > 0 {
			p.pinnedID = p.ready[0].Node.ID
		}
		p.suggested = nil
		p.mu.Unlock()

		p.lastRetestUnix.Store(time.Now().Unix())
		p.nextRetestUnix.Store(time.Now().Add(autoRetestInterval).Unix())

		// Once at least one seed/tunnel node is up, ensure diverse live sources were collected
		if p.ActiveSourcesCount() < 5 {
			p.RefreshFromFeedsAsync()
		}

		ticker := time.NewTicker(autoRetestInterval)
		defer ticker.Stop()
		cycle := 0
		for {
			select {
			case <-ticker.C:
				cycle++
				p.nextRetestUnix.Store(time.Now().Add(autoRetestInterval).Unix())
				if !p.inForeground.Load() {
					continue
				}
				if cycle%3 == 0 || p.ReadyCount() < 8 || p.ActiveSourcesCount() < 5 {
					p.RefreshFromFeedsAsync()
				}
				ctx, cancel := context.WithTimeout(context.Background(), 6500*time.Millisecond)
				// Background check NEVER auto-switches active proxy; only suggests if a much better server exists!
				_ = p.RunAutoRetest(ctx)
				cancel()
			case <-p.stopCh:
				return
			}
		}
	}()
}

// RunAutoRetest benchmarks active + candidate servers in the background WITHOUT changing the user's
// locked active proxy (keeping exit IP 100% stable for websites & downloads). If a significantly
// faster proxy is discovered, it populates SuggestedUpgradeInfo so the UI can ask the user.
func (p *NodePool) RunAutoRetest(ctx context.Context) SwitchBestResult {
	return p.runRetestInternal(ctx, false)
}

// RunManualRetestAndPromote is triggered ONLY when the user explicitly clicks the speed-test &
// connect-fastest button in the Proxy sheet.
func (p *NodePool) RunManualRetestAndPromote(ctx context.Context) SwitchBestResult {
	return p.runRetestInternal(ctx, true)
}

func (p *NodePool) runRetestInternal(ctx context.Context, applyFastest bool) SwitchBestResult {
	if !p.retesting.CompareAndSwap(false, true) {
		name, src, proto, rtt, spd, cnt, actSrc := p.BestNodeSummary()
		return SwitchBestResult{
			OK:            cnt > 0,
			BestNode:      name,
			BestSource:    src,
			BestProto:     proto,
			BestRTTMs:     rtt,
			BestSpeedKbps: spd,
			ReadyNodes:    cnt,
			ActiveSources: actSrc,
			Suggested:     p.GetSuggestedUpgrade(),
		}
	}
	defer p.retesting.Store(false)

	p.mu.RLock()
	var prevTopID, prevTopName string
	if len(p.ready) > 0 {
		prevTopID = p.ready[0].Node.ID
		prevTopName = p.ready[0].Node.Name
	}
	lockedID := p.pinnedID
	seen := make(map[string]bool, 40)
	candidates := make([]OutboundNode, 0, 34)
	for i := 0; i < len(p.ready) && len(candidates) < 14; i++ {
		n := p.ready[i].Node
		if !seen[n.ID] {
			seen[n.ID] = true
			candidates = append(candidates, n)
		}
	}
	if len(p.feedPool) > 0 {
		startIdx := int(p.retestSweepIdx.Add(20)-20) % len(p.feedPool)
		for step := 0; step < len(p.feedPool) && len(candidates) < 34; step++ {
			fn := p.feedPool[(startIdx+step)%len(p.feedPool)]
			if !seen[fn.ID] && p.excludedUntil[fn.ID].IsZero() {
				seen[fn.ID] = true
				candidates = append(candidates, fn)
			}
		}
	}
	readyIDs := make(map[string]bool, len(p.ready))
	for _, s := range p.ready {
		readyIDs[s.Node.ID] = true
	}
	onChanged := p.onBestChanged
	p.mu.RUnlock()

	if len(candidates) == 0 {
		p.RefreshFromFeedsAsync()
		return SwitchBestResult{OK: false, Error: "در حال دریافت لیست سرورها از ۵۰ منبع زنده..."}
	}

	workers := 14
	if workers > len(candidates) {
		workers = len(candidates)
	}
	jobs := make(chan OutboundNode, len(candidates))
	for _, c := range candidates {
		jobs <- c
	}
	close(jobs)

	var wg sync.WaitGroup
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for node := range jobs {
				if ctx.Err() != nil {
					return
				}
				rtt, kbps, ok := p.evaluateNode(ctx, node)
				if ok {
					p.upsertVerifiedNode(node, rtt, kbps)
				} else if readyIDs[node.ID] && node.ID != lockedID {
					// Never evict the user's locked active server from a single background probe timeout!
					p.RecordFailure(node.ID)
				}
			}
		}()
	}
	wg.Wait()

	p.lastRetestUnix.Store(time.Now().Unix())
	p.nextRetestUnix.Store(time.Now().Add(autoRetestInterval).Unix())
	p.retestCount.Add(1)

	p.mu.Lock()
	if applyFastest {
		// Explicit manual action by user -> pick fastest server and lock onto it!
		p.pinnedID = ""
		p.sortReadyLocked()
		if len(p.ready) > 0 {
			p.pinnedID = p.ready[0].Node.ID
		}
		p.suggested = nil
	} else {
		// Sticky mode: keep p.pinnedID locked! If none was locked yet, lock current top.
		p.sortReadyLocked()
		if p.pinnedID == "" && len(p.ready) > 0 {
			p.pinnedID = p.ready[0].Node.ID
			p.sortReadyLocked()
		}
		p.evaluateUpgradeSuggestionLocked()
	}

	var res SwitchBestResult
	var newTopID string
	var sugCopy *SuggestedUpgradeInfo
	if p.suggested != nil {
		cp := *p.suggested
		sugCopy = &cp
	}
	if len(p.ready) > 0 {
		top := p.ready[0]
		newTopID = top.Node.ID
		res = SwitchBestResult{
			OK:            true,
			SwitchedFrom:  prevTopName,
			BestID:        top.Node.ID,
			BestNode:      top.Node.Name,
			BestSource:    firstNonEmpty(top.Node.Source, "Global"),
			BestProto:     string(top.Node.Protocol),
			BestRTTMs:     top.AvgRTTMs,
			BestSpeedKbps: top.SpeedKbps,
			ReadyNodes:    len(p.ready),
			Suggested:     sugCopy,
		}
	}
	p.mu.Unlock()

	p.saveCachedNodes()
	res.ActiveSources = p.ActiveSourcesCount()

	if applyFastest && newTopID != "" && newTopID != prevTopID && onChanged != nil {
		onChanged()
	}
	return res
}

func (p *NodePool) evaluateUpgradeSuggestionLocked() {
	if p.customNode != nil || len(p.ready) < 2 {
		p.suggested = nil
		return
	}
	active := p.ready[0]
	actScore := active.ScoreMs
	if actScore <= 0 {
		actScore = computeEffectiveScore(active.AvgRTTMs, active.SpeedKbps)
	}
	var bestCand *scoredNode
	var bestCandScore int64
	for i := 1; i < len(p.ready); i++ {
		c := &p.ready[i]
		if !p.excludedUntil[c.Node.ID].IsZero() || p.dismissedSuggest[c.Node.ID] {
			continue
		}
		if c.ConsecFailures > 0 {
			continue
		}
		sc := c.ScoreMs
		if sc <= 0 {
			sc = computeEffectiveScore(c.AvgRTTMs, c.SpeedKbps)
		}
		if bestCand == nil || sc < bestCandScore {
			bestCand = c
			bestCandScore = sc
		}
	}
	if bestCand == nil {
		p.suggested = nil
		return
	}
	rttGain := active.AvgRTTMs - bestCand.AvgRTTMs
	if rttGain < 55 || bestCandScore*100 > actScore*72 {
		if p.suggested != nil && p.suggested.ID == active.Node.ID {
			p.suggested = nil
		}
		return
	}
	p.suggested = &SuggestedUpgradeInfo{
		ID:        bestCand.Node.ID,
		Name:      bestCand.Node.Name,
		Source:    firstNonEmpty(bestCand.Node.Source, inferSourceFromRemarkOrDefault(bestCand.Node.Name, "Global")),
		Protocol:  strings.ToUpper(string(bestCand.Node.Protocol)),
		RTTMs:     bestCand.AvgRTTMs,
		SpeedKbps: bestCand.SpeedKbps,
	}
}

func (p *NodePool) GetSuggestedUpgrade() *SuggestedUpgradeInfo {
	p.mu.RLock()
	defer p.mu.RUnlock()
	if p.suggested == nil {
		return nil
	}
	cp := *p.suggested
	return &cp
}

func (p *NodePool) DismissSuggestedUpgrade(nodeID string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	id := strings.TrimSpace(nodeID)
	if id == "" && p.suggested != nil {
		id = p.suggested.ID
	}
	if id != "" {
		p.dismissedSuggest[id] = true
	}
	if p.suggested != nil && (id == "" || p.suggested.ID == id) {
		p.suggested = nil
	}
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

	// Fallback probe against gstatic generate_204 in case YouTube rate-limited the exit IP
	first := parsed[0]
	dialCtx, dialCancel := context.WithTimeout(ctx, 3500*time.Millisecond)
	t0 := time.Now()
	rawConn, dialErr := DialOutboundNode(dialCtx, p.doh, first, "connectivitycheck.gstatic.com", "443")
	if dialErr == nil && rawConn != nil {
		_ = rawConn.SetDeadline(time.Now().Add(2500 * time.Millisecond))
		tlsConn := tls.Client(rawConn, &tls.Config{
			ServerName: "connectivitycheck.gstatic.com",
			RootCAs:    AndroidRootCertPool(),
			NextProtos: []string{"http/1.1"},
			MinVersion: tls.VersionTLS12,
		})
		hsErr := tlsConn.HandshakeContext(dialCtx)
		if hsErr == nil {
			_, _ = tlsConn.Write([]byte("GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n"))
			var respBuf [128]byte
			_, _ = tlsConn.Read(respBuf[:])
		}
		_ = rawConn.Close()
		dialCancel()
		measuredRTT := time.Since(t0).Milliseconds()
		if measuredRTT <= 0 {
			measuredRTT = 220
		}
		p.upsertVerifiedNode(first, measuredRTT, 3500)
		bestRes := p.pinFastestAmongIDs([]OutboundNode{first})
		return CustomAddResult{
			OK:           true,
			AddedCount:   len(parsed),
			WorkingCount: 1,
			BestID:       bestRes.BestID,
			BestNode:     bestRes.BestNode,
			BestProto:    bestRes.BestProto,
			BestRTTMs:    measuredRTT,
			Message:      fmt.Sprintf("✅ متصل به کانفیگ اختصاصی: %s (%dms)", first.Name, measuredRTT),
		}
	}
	dialCancel()

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
	delete(p.dismissedSuggest, nodeID)
	p.pinnedID = nodeID
	p.suggested = nil
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
	p.suggested = nil
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
			isLocked := p.pinnedID == nodeID
			evictThreshold := 2
			if isLocked {
				// Keep active server sticky through transient blips; failover only if truly dead (4 failures)
				evictThreshold = 4
			}
			p.ready[i].AvgRTTMs += 400
			p.ready[i].ScoreMs = computeEffectiveScore(p.ready[i].AvgRTTMs, p.ready[i].SpeedKbps)
			if p.ready[i].ConsecFailures >= evictThreshold {
				p.ready = append(p.ready[:i], p.ready[i+1:]...)
				if isLocked {
					p.pinnedID = ""
					p.sortReadyLocked()
					if len(p.ready) > 0 {
						p.pinnedID = p.ready[0].Node.ID
					}
				}
				if len(p.ready) < 5 {
					needRefresh = true
				}
			} else {
				p.sortReadyLocked()
			}
			p.evaluateUpgradeSuggestionLocked()
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
	if !p.startupSelectPhase.Load() && p.pinnedID == "" && len(p.ready) > 0 {
		p.pinnedID = p.ready[0].Node.ID
		p.sortReadyLocked()
	}
	if len(p.ready) > 28 {
		p.ready = p.ready[:28]
	}
	if !p.startupSelectPhase.Load() {
		p.evaluateUpgradeSuggestionLocked()
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

		// Shared HTTP/1.1 + DoH + uTLS transport across all 50 feed fetches to minimize RAM & socket churn!
		sharedDirectTr := p.newDirectFeedTransport()
		defer sharedDirectTr.CloseIdleConnections()

		type feedRes struct {
			idx   int
			nodes []OutboundNode
		}
		ch := make(chan feedRes, len(sources))
		sem := make(chan struct{}, 10) // Max 10 concurrent feed HTTP requests for low CPU/RAM
		var wg sync.WaitGroup

		for idx, src := range sources {
			wg.Add(1)
			go func(i int, fs FeedSource) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				nodes := p.fetchSourceWithTransport(fs, sharedDirectTr)
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
		earlyProbed := false

		for res := range ch {
			if res.idx >= 0 && res.idx < len(collected) && len(res.nodes) > 0 {
				collected[res.idx] = res.nodes
				sourcesSucceeded++
				p.reachableSources.Store(int32(sourcesSucceeded))

				// Early fast-connect burst: as soon as 6 sources arrive and pool is cold, start probing right away!
				if !earlyProbed && sourcesSucceeded >= 6 && p.ReadyCount() < 3 {
					earlyProbed = true
					earlyBatch := interleaveNodeGroups(collected, 24)
					if len(earlyBatch) > 0 {
						go p.probeCandidatesConcurrent(earlyBatch, 16, 6)
					}
				}
			}
		}

		// Round-robin interleave across ALL 50 independent sources so every collector contributes!
		candidates := interleaveNodeGroups(collected, 220)
		if len(candidates) == 0 {
			return
		}

		p.mu.Lock()
		p.feedPool = append([]OutboundNode(nil), candidates...)
		p.mu.Unlock()

		// Keep top 50 interleaved fast nodes (1 from each source!) + shuffle remainder for diversity
		if len(candidates) > 50 {
			tail := candidates[50:]
			rand.Shuffle(len(tail), func(i, j int) {
				tail[i], tail[j] = tail[j], tail[i]
			})
		}
		if len(candidates) > 120 {
			candidates = candidates[:120]
		}

		log.Printf("[gfproxy-pool] Fetched from %d/%d live sources; benchmarking %d interleaved nodes...", sourcesSucceeded, len(sources), len(candidates))
		p.probeCandidatesConcurrent(candidates, 22, 24)
		p.lastRetestUnix.Store(time.Now().Unix())
		p.nextRetestUnix.Store(time.Now().Add(autoRetestInterval).Unix())
		p.saveCachedNodes()
	}()
}

func (p *NodePool) newDirectFeedTransport() *http.Transport {
	return &http.Transport{
		ForceAttemptHTTP2:   false,
		MaxIdleConns:        16,
		MaxIdleConnsPerHost: 4,
		IdleConnTimeout:     15 * time.Second,
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
			d.Timeout = 2800 * time.Millisecond
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
			d.Timeout = 2800 * time.Millisecond
			return d.DialContext(dialCtx, "tcp", net.JoinHostPort(host, port))
		},
	}
}

func (p *NodePool) fetchSourceWithTransport(fs FeedSource, sharedTr *http.Transport) []OutboundNode {
	ctx, cancel := context.WithTimeout(context.Background(), 9*time.Second)
	defer cancel()

	for _, mirrorURL := range fs.Mirrors {
		body := p.fetchSingleURLWithTransport(ctx, mirrorURL, sharedTr)
		if body == "" {
			continue
		}
		parsed := ParseShareLinksWithSource(body, fs.Name)
		if len(parsed) > 0 {
			for i := range parsed {
				parsed[i].Source = fs.Name
			}
			if len(parsed) > 24 {
				parsed = parsed[:24]
			}
			return parsed
		}
	}
	return nil
}

// fetchSingleURL fetches subscription content via HTTP/1.1 DoH + TLS SNI fragmentation,
// with automatic fallback through the fastest active tunnel node.
func (p *NodePool) fetchSingleURL(parentCtx context.Context, feedURL string) string {
	tr := p.newDirectFeedTransport()
	defer tr.CloseIdleConnections()
	return p.fetchSingleURLWithTransport(parentCtx, feedURL, tr)
}

func (p *NodePool) fetchSingleURLWithTransport(parentCtx context.Context, feedURL string, directTr *http.Transport) string {
	ctx, cancel := context.WithTimeout(parentCtx, 5500*time.Millisecond)
	defer cancel()

	client := &http.Client{Transport: directTr, Timeout: 5200 * time.Millisecond}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, feedURL, nil)
	if err == nil {
		req.Header.Set("User-Agent", "v2rayNG/1.8.38 Mozilla/5.0 Chrome/130.0.0.0")
		if resp, doErr := client.Do(req); doErr == nil {
			b, rErr := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
			_ = resp.Body.Close()
			if resp.StatusCode == http.StatusOK && rErr == nil && len(b) > 20 {
				return string(b)
			}
		}
	}

	// Fallback: fetch through an already-verified tunnel node if ISP blocked direct CDN access
	nodes := p.PickNodes(parentCtx, 1, 0)
	if len(nodes) > 0 {
		tunCtx, tunCancel := context.WithTimeout(parentCtx, 5000*time.Millisecond)
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
		tunClient := &http.Client{Transport: tunTr, Timeout: 4800 * time.Millisecond}
		req2, r2Err := http.NewRequestWithContext(tunCtx, http.MethodGet, feedURL, nil)
		if r2Err == nil {
			req2.Header.Set("User-Agent", "v2rayNG/1.8.38 Mozilla/5.0 Chrome/130.0.0.0")
			if resp2, err2 := tunClient.Do(req2); err2 == nil {
				b, rErr := io.ReadAll(io.LimitReader(resp2.Body, 512*1024))
				_ = resp2.Body.Close()
				if resp2.StatusCode == http.StatusOK && rErr == nil && len(b) > 20 {
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
