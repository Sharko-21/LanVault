"""Apply the reviewed LAN patch to the exact ASTER source used for overlay tests."""
import hashlib
import json
from pathlib import Path

project = Path(__file__).resolve().parent.parent
artifacts = project / "artifacts/aster-lan"
manifest = json.loads((artifacts / "source.json").read_text(encoding="utf-8"))
source = Path(manifest["source"]).resolve(strict=True)
guard = source / "internal/winclient/guard_windows.go"
original = guard.read_bytes()
new_guard = (artifacts / "guard_windows.go").read_bytes()
assert hashlib.sha256(original).hexdigest() == manifest["guard_sha256"] or original == new_guard, "ASTER changed since review; refusing to overwrite"
names = ["guard_windows.go", "local_services_windows.go", "local_services_windows_test.go"]
for name in names[1:]:
    target = source / "internal/winclient" / name
    assert not target.exists() or target.read_bytes() == (artifacts / name).read_bytes(), f"Existing ASTER file differs: {name}"
backup = artifacts / "guard_windows.before.go"
if not backup.exists() and original != new_guard:
    backup.write_bytes(original)
for name in names:
    (source / "internal/winclient" / name).write_bytes((artifacts / name).read_bytes())
print("Applied three source files. Installed ASTER and its settings were not changed.")
