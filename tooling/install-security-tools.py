"""Fetch exact official security-tool releases and verify pinned archive SHA-256."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import urllib.request
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--directory', type=Path, default=Path('.tools/security'))
args = parser.parse_args()
if sys.platform not in ('win32', 'linux'):
    raise SystemExit('This bootstrap supports Windows x64 and Linux x64.')
config = json.loads(Path(__file__).with_name('security-tools.json').read_text())
args.directory.mkdir(parents=True, exist_ok=True)
for tool, spec in config.items():
    suffix = 'windows' if sys.platform == 'win32' else 'linux'
    asset, expected = next((n, sha) for n, sha in spec['checksums'].items() if suffix in n)
    repo = 'gitleaks/gitleaks' if tool == 'gitleaks' else 'rhysd/actionlint'
    url = f'https://github.com/{repo}/releases/download/{spec["version"]}/{asset}'
    data = urllib.request.urlopen(url, timeout=60).read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise SystemExit('Security tool archive checksum mismatch: ' + tool)
    binary = tool + ('.exe' if sys.platform == 'win32' else '')
    if asset.endswith('.zip'):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            content = archive.read(binary)
    else:
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
            member = archive.getmember(binary)
            if not member.isfile():
                raise SystemExit('Unexpected tool archive member')
            content = archive.extractfile(member).read()
    output = args.directory / binary
    output.write_bytes(content)
    if os.name != 'nt':
        output.chmod(0o755)
    print(tool + ' ' + spec['version'] + ': archive SHA-256 verified')
