"""Offline build-input checks using only Python's standard library."""

import hashlib
import json
import os
from pathlib import Path
import re
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {"node_modules", "target", "dist", "build", ".tools", ".cache", ".gradle", "gen", ".git", "artifacts"}
counts = {"json": 0, "toml": 0, "xml": 0}

for current, directories, files in os.walk(ROOT):
    directories[:] = [name for name in directories if name not in EXCLUDED]
    for name in files:
        path = Path(current, name)
        if path.suffix == ".json":
            json.loads(path.read_text(encoding="utf-8"))
            counts["json"] += 1
        elif path.suffix == ".toml":
            tomllib.loads(path.read_text(encoding="utf-8"))
            counts["toml"] += 1
        elif path.suffix == ".xml":
            ET.parse(path)
            counts["xml"] += 1

checksums = json.loads((ROOT / "tooling/gradle-wrapper-checksums.json").read_text(encoding="utf-8"))
wrapper = ROOT / "android/gradle/wrapper/gradle-wrapper.jar"
assert hashlib.sha256(wrapper.read_bytes()).hexdigest() == checksums["wrapperJarSha256"], "Gradle wrapper SHA-256 mismatch"
properties = (wrapper.parent / "gradle-wrapper.properties").read_text(encoding="utf-8")
assert f"distributionSha256Sum={checksums['distributionSha256']}" in properties

settings = (ROOT / "android/settings.gradle.kts").read_text(encoding="utf-8")
modules = re.findall(r'"(:[a-z:]+)"', settings)
assert modules, "No Android modules declared"
for module in modules:
    folder = ROOT / "android" / module.lstrip(":").replace(":", "/")
    assert (folder / "build.gradle.kts").is_file(), f"Missing module: {module}"

ANDROID = "{http://schemas.android.com/apk/res/android}"
application = ET.parse(ROOT / "android/app/src/main/AndroidManifest.xml").getroot().find("application")
assert application is not None
assert application.attrib[ANDROID + "allowBackup"] == "false"
assert application.attrib[ANDROID + "usesCleartextTraffic"] == "false"

print(json.dumps({"parsed": counts, "android_modules": len(modules), "gradle_wrapper_sha256": "verified", "manifest_backup_and_cleartext": "disabled"}, indent=2))
