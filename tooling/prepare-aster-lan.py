"""Prepare a testable Go overlay and reviewable patch without editing ASTER sources."""
import argparse
import difflib
import hashlib
import json
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--source", type=Path, required=True)
parser.add_argument("--gofmt", type=Path, required=True)
args = parser.parse_args()
source = args.source.resolve(strict=True)
project = Path(__file__).resolve().parent.parent
out = project / "artifacts" / "aster-lan"
out.mkdir(parents=True, exist_ok=True)
relative = "internal/winclient/guard_windows.go"
original = (source / relative).read_text(encoding="utf-8")
anchor = '\ts, err := wf.New(&wf.Options{Name: "ASTER persistent guard", TransactionStartTimeout: 5e9})'
assert original.count(anchor) == 1, "ASTER source changed; re-review guard integration"
replacement = '''\t// Local listeners are opt-in, independent of domain routing. Maintenance
\t// and recovery guards deliberately retain their original strict policy.
\tvar localRules []*wf.Rule
\tif providerID == winclientProviderID() && luid != 0 {
\t\tlocalRules, err = configuredLocalServiceRules(luid)
\t\tif err != nil { return err }
\t}
''' + anchor
modified = original.replace(anchor, replacement)
anchor = '\t\tfor _, r := range firewallPolicyRules(app, luid, endpoints, p, controls, dhcpApp) {'
assert modified.count(anchor) == 1
modified = modified.replace(anchor, '\t\tpolicyRules := append(firewallPolicyRules(app, luid, endpoints, p, controls, dhcpApp), localRules...)\n\t\tfor _, r := range policyRules {')
# Parameter shadows package-level providerID in setGuardForPolicy.
modified += '\nfunc winclientProviderID() wf.ProviderID { return providerID }\n'
files = {relative: modified}
for name in ("local_services_windows.go", "local_services_windows_test.go"):
    files["internal/winclient/" + name] = (project / "integrations/aster-lan" / name).read_text(encoding="utf-8")
overlay = {}
patch = []
for name, body in files.items():
    body = subprocess.run([str(args.gofmt.resolve(strict=True))], input=body, text=True, encoding="utf-8", capture_output=True, check=True).stdout
    target = out / Path(name).name
    target.write_text(body, encoding="utf-8", newline="\n")
    overlay[str(source / name)] = str(target)
    old = (source / name).read_text(encoding="utf-8") if (source / name).exists() else ""
    patch.extend(difflib.unified_diff(old.splitlines(True), body.splitlines(True), fromfile="a/" + name if old else "/dev/null", tofile="b/" + name))
(out / "overlay.json").write_text(json.dumps({"Replace": overlay}, indent=2), encoding="utf-8")
(out / "aster-local-services.patch").write_text("".join(patch), encoding="utf-8", newline="\n")
(out / "local-services.json").write_text(json.dumps({"version": 1, "services": [{
    "executable": str(project / "target/gnu/debug/lanvault-desktop.exe"),
    "local_ip": "192.168.1.68", "remote_cidr": "192.168.1.0/24", "port": 43827,
}]}, ensure_ascii=False, indent=2), encoding="utf-8")
(out / "source.json").write_text(json.dumps({"source": str(source), "guard_sha256": hashlib.sha256((source / relative).read_bytes()).hexdigest()}, indent=2), encoding="utf-8")
print(f"Prepared overlay and patch: {out}")
