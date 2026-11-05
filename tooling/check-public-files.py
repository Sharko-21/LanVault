"""Check the exact prospective source set, without printing matched secret values."""
import argparse
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument('--snapshot', action='store_true')
args = parser.parse_args()
root = args.root.resolve()
if args.snapshot:
    names = [p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()]
else:
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root).decode().split('\0')
    names = sorted(set(filter(None, names)))
patterns = {
    'private-key': r'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----',
    'github-token': r'\bgh[pousr]_[A-Za-z0-9]{30,}\b',
    'openai-token': r'\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{30,}\b',
    'aws-key': r'\bAKIA[0-9A-Z]{16}\b',
    'personal-windows-path': r'[A-Z]:[\\/]+Users[\\/]+(?!<|%|\$)[A-Za-z0-9_.-]+[\\/]',
}
findings = []
for name in names:
    path = root / name
    if path.is_symlink() or not path.resolve().is_relative_to(root):
        findings.append({'file': name, 'type': 'source-link'})
        continue
    if path.suffix.lower() in {'.dpapi', '.sqlite', '.db', '.jks', '.keystore', '.p12', '.pfx', '.key', '.p8', '.pk8'}:
        findings.append({'file': name, 'type': 'private-file'})
        continue
    try:
        text = path.read_text(encoding='utf-8')
    except UnicodeError:
        continue
    for kind, pattern in patterns.items():
        for match in re.finditer(pattern, text):
            findings.append({'file': name, 'type': kind, 'line': text.count('\n', 0, match.start()) + 1})
print(json.dumps({'files': len(names), 'findings': findings}))
raise SystemExit(bool(findings))
