//go:build windows

package winclient

import (
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"strings"

	"github.com/tailscale/wf"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

// Administrator-owned ProgramData/ASTER/local-services.json. Independent of
// domain routing: grant only a named TCP listener on one on-link physical LAN.
type localServices struct {
	Version  int            `json:"version"`
	Services []localService `json:"services"`
}
type localService struct {
	Executable string `json:"executable"`
	LocalIP    string `json:"local_ip"`
	RemoteCIDR string `json:"remote_cidr"`
	Port       uint16 `json:"port"`
}

func decodeLocalServices(raw []byte) (localServices, error) {
	var config localServices
	if err := strictRoutingJSON(raw, &config); err != nil {
		return config, err
	}
	if config.Version != 1 || len(config.Services) > 8 {
		return config, errors.New("invalid local services configuration")
	}
	for _, s := range config.Services {
		ip, e := netip.ParseAddr(s.LocalIP)
		prefix, pe := netip.ParsePrefix(s.RemoteCIDR)
		if e != nil || pe != nil || !ip.Is4() || !ip.IsPrivate() || !prefix.Addr().Is4() || !prefix.Contains(ip) || !privateLANPrefix(prefix) || s.Port < 1024 {
			return config, errors.New("local service requires a private IPv4 subnet, local address and unprivileged port")
		}
		if !filepath.IsAbs(s.Executable) || strings.HasPrefix(s.Executable, `\\`) || filepath.Clean(s.Executable) != s.Executable || !strings.EqualFold(filepath.Ext(s.Executable), ".exe") {
			return config, errors.New("local service requires an absolute local executable path")
		}
	}
	return config, nil
}
func privateLANPrefix(p netip.Prefix) bool {
	for _, allowed := range []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"} {
		a := netip.MustParsePrefix(allowed)
		if p.Bits() >= a.Bits() && a.Contains(p.Masked().Addr()) {
			return true
		}
	}
	return false
}
func localServiceRules(s localService, app string, luid uint64) []*wf.Rule {
	if luid == 0 {
		return nil
	}
	// ALE reports a lower-case device path, including non-ASCII characters.
	// FwpmGetAppIdFromFileName on this host preserves Cyrillic path case.
	// The application ID is a byte blob, so its Unicode case must match ALE.
	app = strings.ToLower(app)
	// Only accept inbound TCP connections. ALE authorizes the accepted flow in
	// both directions, including its replies and reauthorization. Granting
	// AUTH_CONNECT as well would let this app initiate direct LAN DNS requests
	// by binding the registered source port in full-tunnel mode.
	return []*wf.Rule{rule(wf.LayerALEAuthRecvAcceptV4, "registered local TCP listener", 75,
		match(wf.FieldALEAppID, app), match(wf.FieldIPLocalInterface, luid),
		match(wf.FieldIPLocalAddress, netip.MustParseAddr(s.LocalIP)),
		match(wf.FieldIPRemoteAddress, netip.MustParsePrefix(s.RemoteCIDR).Masked()),
		match(wf.FieldIPLocalPort, s.Port), match(wf.FieldIPProtocol, wf.IPProtoTCP))}
}

func resolvedLocalServiceRules(s localService, luid uint64) []*wf.Rule {
	app, err := wf.AppID(s.Executable)
	if err != nil {
		// An unavailable optional service must stay blocked without preventing
		// the mandatory VPN guard from being installed or refreshed.
		traceStage(fmt.Sprintf("local TCP service skipped: %q: %v", s.Executable, err))
		return nil
	}
	return localServiceRules(s, app, luid)
}
func configuredLocalServiceRules(tun uint64) ([]*wf.Rule, error) {
	dir, err := dataDir()
	if err != nil {
		return nil, err
	}
	path := filepath.Join(dir, "local-services.json")
	raw, err := readBounded(path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if err = rejectReparse(path); err != nil {
		return nil, err
	}
	config, err := decodeLocalServices(raw)
	if err != nil {
		return nil, err
	}
	if len(config.Services) == 0 {
		return nil, nil
	}
	addresses, err := winipcfg.GetUnicastIPAddressTable(windows.AF_INET)
	if err != nil {
		return nil, err
	}
	var rules []*wf.Rule
	for _, s := range config.Services {
		ip := netip.MustParseAddr(s.LocalIP)
		remote := netip.MustParsePrefix(s.RemoteCIDR).Masked()
		for _, a := range addresses {
			if uint64(a.InterfaceLUID) == tun || a.Address.Addr() != ip || a.DadState != winipcfg.DadStatePreferred {
				continue
			}
			onLink := netip.PrefixFrom(ip, int(a.OnLinkPrefixLength)).Masked()
			if remote.Bits() < onLink.Bits() || !onLink.Contains(remote.Addr()) {
				continue
			}
			iface, e := a.InterfaceLUID.Interface()
			if e != nil || iface.OperStatus != winipcfg.IfOperStatusUp || iface.InterfaceAndOperStatusFlags&winipcfg.IAOSFHardwareInterface == 0 || (iface.Type != winipcfg.IfTypeEthernetCSMACD && iface.Type != winipcfg.IfTypeIEEE80211) {
				continue
			}
			rules = append(rules, resolvedLocalServiceRules(s, uint64(a.InterfaceLUID))...)
		}
	}
	return rules, nil
}
