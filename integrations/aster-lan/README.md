# ASTER local TCP service integration

The authoritative source changes have been applied to `vpn/ASTER-Multi-Server/source/internal/winclient`: `guard_windows.go`, `local_services_windows.go`, `local_services_windows_test.go`. This directory retains the two added files as the LanVault integration reference. `artifacts/aster-lan/aster-local-services.patch` contains the full reviewable diff and `guard_windows.before.go` the original guard.

The registered service is opt-in through administrator-owned `%ProgramData%/ASTER/local-services.json`. Its permit matches all six fields: executable AppID, hardware interface LUID, local IPv4, remote private on-link IPv4 prefix, local TCP port, TCP protocol. It is added only to the connected main provider. Maintenance/recovery guards do not inherit it. Missing configuration keeps the original policy; malformed configuration fails before replacing current filters.

Use the signed update flow described in [the implementation report](../../docs/stages/stage-06-parallel.md). The working Windows VPN was updated to `0.13.3-experimental` on 4 November 2026. Actual Pixel-to-PC transfer with ASTER enabled was confirmed by the user and 20 new receipts. The installed AppID now matches the kernel's observed Unicode lowercase path. Never replace its EXE with an unsigned build.

`tooling/prepare-aster-lan.py --source PATH --gofmt PATH` prepares a Go overlay and patch against the original guard. `tooling/apply-aster-lan.py` verifies the recorded original hash and refuses unrelated changes before applying the three files. The preparer is for an unpatched source tree; it intentionally refuses to apply twice. No signing keys or profiles are copied into this integration directory.

## Review fixes, 4 November 2026

Candidate `0.13.2-experimental` contains the reviewed fixes. The exception is now
created only at ALE_AUTH_RECV_ACCEPT_V4. Accepted TCP connections can send replies
using the same authorized ALE flow; the registered app gets no permission to
initiate direct LAN connections or direct DNS from the registered source port.
All six app/interface/address/port/protocol restrictions remain in force.

An unavailable registered EXE is skipped and recorded in ASTER's protected
`service-startup.log`; its LAN exception is not created, and other services and
the VPN guard remain usable. Malformed configuration still fails closed.

The installer reads JSON explicitly as UTF-8 and verifies that the registered
executable exists before signing or changing the installed configuration.
The candidate and signing version are both `0.13.2-experimental`.

Validation: 265 normal Go checks passed, 7 live-system opt-ins skipped, Go vet
passed. An isolated elevated WFP test verified TCP accept and replies while new
outbound connections from the test listener socket were blocked. It used only
temporary loopback filters for the test EXE, removed on session closure.
Windows PowerShell 5.1 executed the actual config-reading statement with the real
Cyrillic path and verified its UTF-8 persistence. No installed ASTER binary,
permanent filters, domain lists, routes, or server settings were changed.
Physical Pixel-to-PC transfer with this candidate remains untested.

## Unicode AppID correction, 4 November 2026

The 0.13.2 validation missed a Cyrillic case mismatch: the configured WFP AppID contained `Документы/Хранилище` while actual kernel drop events used lowercase. Normalize the local service's AppID with Unicode lowercase before creating its byte-blob condition. All six restrictions and the inbound-only layer remain unchanged.

Two new regressions reproduced the failure on the old code, including a real Windows AppID for an existing EXE in a Cyrillic directory. All 267 Go checks and vet passed with the release's `http2legacy` configuration. Version 0.13.3 was signed and installed through the normal updater, and its WFP AppID was compared directly with the recorded kernel event. Real transfer succeeded while ASTER remained connected; this supersedes the earlier untested-device status above.
