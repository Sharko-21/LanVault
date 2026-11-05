//go:build windows

package winclient

import (
	"aster/internal/split"
	"encoding/json"
	"github.com/tailscale/wf"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLocalServiceMatchesObservedCyrillicKernelAppID(t *testing.T) {
	configured := `\device\harddiskvolume3\users\yarl9\onedrive\Документы\chatgpt\Хранилище\lanvault\target\gnu\debug\lanvault-desktop.exe`
	kernel := `\device\harddiskvolume3\users\yarl9\onedrive\документы\chatgpt\хранилище\lanvault\target\gnu\debug\lanvault-desktop.exe`
	s := localService{LocalIP: "192.168.1.68", RemoteCIDR: "192.168.1.0/24", Port: 43827}
	rules := append(firewallPolicyRules("vpn", 1234, nil, split.Full(), nil, "dhcp"), localServiceRules(s, configured, 77)...)
	a := localServiceAttempt{layer: wf.LayerALEAuthRecvAcceptV4, local: netip.MustParseAddr(s.LocalIP), remote: netip.MustParseAddr("192.168.1.64"), app: kernel, luid: 77, localPort: 43827, remotePort: 49804, proto: wf.IPProtoTCP}
	if localServiceDecision(rules, a) != wf.ActionPermit {
		t.Fatal("observed phone connection still blocked by Cyrillic AppID case")
	}
	a.app = strings.Replace(kernel, "lanvault-desktop.exe", "other.exe", 1)
	if localServiceDecision(rules, a) != wf.ActionBlock {
		t.Fatal("different executable gained LAN access")
	}
	a.app, a.layer, a.remotePort = kernel, wf.LayerALEAuthConnectV4, 53
	if localServiceDecision(rules, a) != wf.ActionBlock {
		t.Fatal("normalization introduced outbound DNS bypass")
	}
}

func TestResolvedLocalServiceNormalizesRealUnicodeExecutablePath(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "Документы", "Хранилище")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(exe)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "LanVault.exe")
	if err := os.WriteFile(path, data, 0700); err != nil {
		t.Fatal(err)
	}
	raw, err := wf.AppID(path)
	if err != nil {
		t.Fatal(err)
	}
	s := localService{Executable: path, LocalIP: "192.168.1.68", RemoteCIDR: "192.168.1.0/24", Port: 43827}
	rules := resolvedLocalServiceRules(s, 77)
	if len(rules) != 1 {
		t.Fatal("existing Unicode executable was skipped")
	}
	for _, c := range rules[0].Conditions {
		if c.Field == wf.FieldALEAppID && c.Value != strings.ToLower(raw) {
			t.Fatal("real Windows AppID retained non-ASCII uppercase characters")
		}
	}
}

func TestLocalServiceConfigurationRejectsBroadOrInvalidDestinations(t *testing.T) {
	valid := localServices{Version: 1, Services: []localService{{Executable: `C:\Apps\LanVault.exe`, LocalIP: "192.168.1.68", RemoteCIDR: "192.168.1.0/24", Port: 43827}}}
	encode := func(c localServices) []byte { b, _ := json.Marshal(c); return b }
	if _, err := decodeLocalServices(encode(valid)); err != nil {
		t.Fatal(err)
	}
	for _, prefix := range []string{"0.0.0.0/0", "192.168.0.0/15", "192.0.0.0/8", "8.8.8.8/32", "224.0.0.0/4", "::/0", "10.0.0.0/8"} {
		copy := localServices{Version: 1, Services: append([]localService{}, valid.Services...)}
		copy.Services[0].RemoteCIDR = prefix
		if _, err := decodeLocalServices(encode(copy)); err == nil {
			t.Fatalf("unsafe prefix accepted: %s", prefix)
		}
	}
	for _, path := range []string{`relative.exe`, `\\server\share\LanVault.exe`, `C:\Apps\..\other.exe`, `C:\Apps\script.ps1`} {
		copy := localServices{Version: 1, Services: append([]localService{}, valid.Services...)}
		copy.Services[0].Executable = path
		if _, err := decodeLocalServices(encode(copy)); err == nil {
			t.Fatalf("unsafe path accepted: %s", path)
		}
	}
	for _, raw := range []string{`{"version":1,"services":[],"extra":true}`, `{"version":1,"services":[]} {}`, `{"version":2,"services":[]}`} {
		if _, err := decodeLocalServices([]byte(raw)); err == nil {
			t.Fatal("invalid configuration accepted")
		}
	}
}
func TestLocalServiceExceptionIsBoundToAppInterfaceAddressPortAndProtocol(t *testing.T) {
	s := localService{LocalIP: "192.168.1.68", RemoteCIDR: "192.168.1.0/24", Port: 43827}
	rules := localServiceRules(s, "lanvault-app", 77)
	if len(rules) != 1 || len(localServiceRules(s, "lanvault-app", 0)) != 0 {
		t.Fatal("unexpected layer or interface allowance")
	}
	for _, r := range rules {
		fields := map[wf.FieldID]any{}
		for _, c := range r.Conditions {
			fields[c.Field] = c.Value
		}
		if r.Layer != wf.LayerALEAuthRecvAcceptV4 || len(fields) != 6 || fields[wf.FieldALEAppID] != "lanvault-app" || fields[wf.FieldIPLocalInterface] != uint64(77) || fields[wf.FieldIPLocalAddress] != netip.MustParseAddr(s.LocalIP) || fields[wf.FieldIPRemoteAddress] != netip.MustParsePrefix(s.RemoteCIDR) || fields[wf.FieldIPLocalPort] != s.Port || fields[wf.FieldIPProtocol] != wf.IPProtoTCP || r.HardAction || r.Action != wf.ActionPermit || r.Weight >= 85 {
			t.Fatal("local exception broadened or overrides protected controls")
		}
	}
	// Existing full/domain policies and hard fail-closed defaults remain in place.
	for _, p := range []split.Policy{split.Full(), {Version: 2, Mode: "include", Domains: []string{"example.org"}}} {
		rules := firewallPolicyRules("vpn", 1234, nil, p, nil, "dhcp")
		blocks := 0
		for _, r := range rules {
			if r.Name == "ASTER: fail-closed default" && r.HardAction && r.Action == wf.ActionBlock {
				blocks++
			}
		}
		if blocks != 4 {
			t.Fatal("Internet kill switch changed")
		}
	}
}

type localServiceAttempt struct {
	layer                 wf.LayerID
	local, remote         netip.Addr
	app                   string
	luid                  uint64
	localPort, remotePort uint16
	proto                 wf.IPProto
}

func localServiceDecision(rules []*wf.Rule, a localServiceAttempt) wf.Action {
	var weight uint64
	action, found := wf.ActionBlock, false
	for _, r := range rules {
		if r.Layer != a.layer {
			continue
		}
		matches := true
		for _, c := range r.Conditions {
			if c.Op != wf.MatchTypeEqual {
				matches = false
				break
			}
			switch c.Field {
			case wf.FieldALEAppID:
				matches = matches && c.Value == a.app
			case wf.FieldIPLocalInterface:
				matches = matches && c.Value == a.luid
			case wf.FieldIPLocalAddress:
				matches = matches && c.Value == a.local
			case wf.FieldIPLocalPort:
				matches = matches && c.Value == a.localPort
			case wf.FieldIPRemotePort:
				matches = matches && c.Value == a.remotePort
			case wf.FieldIPProtocol:
				matches = matches && c.Value == a.proto
			case wf.FieldIPRemoteAddress:
				switch v := c.Value.(type) {
				case netip.Addr:
					matches = matches && v == a.remote
				case netip.Prefix:
					matches = matches && v.Contains(a.remote)
				default:
					matches = false
				}
			default:
				matches = false
			}
		}
		if matches && (!found || r.Weight > weight || r.Weight == weight && r.Action == wf.ActionBlock) {
			weight, action, found = r.Weight, r.Action, true
		}
	}
	return action
}

func TestLocalServiceAcceptsLANWithoutAllowingNewOutboundConnections(t *testing.T) {
	s := localService{LocalIP: "192.168.1.68", RemoteCIDR: "192.168.1.0/24", Port: 43827}
	policies := []split.Policy{split.Full(), {Version: 2, Mode: "include", Domains: []string{"example.org"}}, {Version: 2, Mode: "exclude", Domains: []string{"example.org"}}}
	for _, p := range policies {
		t.Run(p.Mode, func(t *testing.T) {
			rules := append(firewallPolicyRules("vpn", 1234, nil, p, nil, "dhcp"), localServiceRules(s, "lanvault", 77)...)
			incoming := localServiceAttempt{layer: wf.LayerALEAuthRecvAcceptV4, local: netip.MustParseAddr(s.LocalIP), remote: netip.MustParseAddr("192.168.1.2"), app: "lanvault", luid: 77, localPort: s.Port, remotePort: 50000, proto: wf.IPProtoTCP}
			if localServiceDecision(rules, incoming) != wf.ActionPermit {
				t.Fatal("registered inbound TCP service blocked")
			}
			for _, port := range []uint16{53, 443} {
				out := incoming
				out.layer = wf.LayerALEAuthConnectV4
				out.remote = netip.MustParseAddr("192.168.1.1")
				out.remotePort = port
				if localServiceDecision(rules, out) != wf.ActionBlock {
					t.Fatalf("new direct connection to LAN port %d bypassed VPN", port)
				}
			}
			cases := map[string]localServiceAttempt{}
			a := incoming
			a.app = "other"
			cases["other app"] = a
			a = incoming
			a.luid = 78
			cases["other interface"] = a
			a = incoming
			a.local = netip.MustParseAddr("192.168.1.69")
			cases["other local address"] = a
			a = incoming
			a.localPort++
			cases["other local port"] = a
			a = incoming
			a.remote = netip.MustParseAddr("8.8.8.8")
			cases["public peer"] = a
			a = incoming
			a.proto = wf.IPProtoUDP
			cases["UDP"] = a
			a = incoming
			a.layer = wf.LayerALEAuthRecvAcceptV6
			a.local = netip.MustParseAddr("fd00::68")
			a.remote = netip.MustParseAddr("fd00::2")
			cases["IPv6"] = a
			for name, a := range cases {
				if localServiceDecision(rules, a) != wf.ActionBlock {
					t.Errorf("exception expanded to %s", name)
				}
			}
		})
	}
}

func TestUnavailableLocalServiceDoesNotPreventOtherRules(t *testing.T) {
	s := localService{Executable: filepath.Join(t.TempDir(), "removed.exe"), LocalIP: "192.168.1.68", RemoteCIDR: "192.168.1.0/24", Port: 43827}
	if rules := resolvedLocalServiceRules(s, 77); len(rules) != 0 {
		t.Fatal("missing executable got an exception")
	}
	current, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	s.Executable = current
	rules := resolvedLocalServiceRules(s, 77)
	if len(rules) != 1 || rules[0].Layer != wf.LayerALEAuthRecvAcceptV4 {
		t.Fatal("valid service after a missing executable was skipped")
	}
}
