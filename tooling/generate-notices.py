"""Collect license/notice texts from the exact cached dependencies, without local paths."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--cargo-metadata', required=True, type=Path)
parser.add_argument('--inventory', required=True, type=Path)
parser.add_argument('--gradle-cache', required=True, type=Path)
parser.add_argument('--output', type=Path, default=Path('THIRD_PARTY_NOTICES.txt'))
args = parser.parse_args()
inventory = json.loads(args.inventory.read_text())['packages']
texts = {}
entries = []
missing = []


def include(label, data):
    try:
        text = data.decode('utf-8-sig').strip()
    except UnicodeError:
        return
    if not text:
        return
    digest = hashlib.sha256(text.encode()).hexdigest()
    record = texts.setdefault(digest, {'labels': set(), 'text': text})
    record['labels'].add(label)


def zip_notices(label, data, nested=False):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            base = Path(name).name.upper()
            if base.startswith(('LICENSE', 'NOTICE', 'COPYING')) and not name.endswith('/'):
                include(label + ' / ' + name, archive.read(name))
            elif not nested and name == 'classes.jar':
                zip_notices(label, archive.read(name), True)


metadata = json.loads(args.cargo_metadata.read_text(encoding='utf-8-sig'))
cargo = {(p['name'], p['version']): p for p in metadata['packages']}
vendors = json.loads(Path('licenses/third-party/sources.json').read_text())
def repo_key(p): return (p.get('repository') or '').rstrip('/').removesuffix('.git')
def license_files(p):
    folder = Path(p['manifest_path']).parent
    return [f for f in folder.iterdir() if f.is_file() and f.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE', 'UNLICENSE'))]

for package in inventory:
    name, version = package['name'], package['version']
    label = package['ecosystem'] + ': ' + name + ' ' + version
    if package['ecosystem'] == 'crates.io':
        p = cargo[(name, version)]
        entries.append(label + ' | ' + str(p.get('license') or 'see upstream license') + ' | authors: ' + ', '.join(p.get('authors', [])) + ' | source: https://crates.io/api/v1/crates/' + name + '/' + version + '/download')
        folder = Path(p['manifest_path']).parent
        found = [f for f in folder.iterdir() if f.is_file() and f.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE', 'UNLICENSE'))]
        if p.get('license_file'):
            found.append(folder / p['license_file'])
        if not found:
            # Workspace subcrates can omit a root license in their published tarball.
            for other in metadata['packages']:
                if repo_key(other) and repo_key(other) == repo_key(p):
                    found.extend(license_files(other))
            for record in vendors:
                if record['repository'] == repo_key(p):
                    file = Path('licenses/third-party') / record['file']
                    if hashlib.sha256(file.read_bytes()).hexdigest() != record['sha256']:
                        raise SystemExit('Vendored license checksum mismatch')
                    found.append(file)
            if not found: missing.append(label)
        for f in set(found):
            if f.exists():
                include(label + ' / ' + f.name, f.read_bytes())
    elif package['ecosystem'] == 'Maven':
        group, artifact = name.split(':', 1)
        folder = args.gradle_cache / group / artifact / version
        poms = list(folder.glob('*/*.pom'))
        licenses = []
        for pom in poms:
            root = ET.parse(pom).getroot()
            for item in root.findall('.//{*}licenses/{*}license'):
                licenses.append((item.findtext('{*}name') or '') + ' ' + (item.findtext('{*}url') or ''))
        entries.append(label + ' | ' + ('; '.join(sorted(set(licenses))) or 'see upstream POM'))
        for binary in [*folder.glob('*/*.jar'), *folder.glob('*/*.aar')]:
            zip_notices(label, binary.read_bytes())

for name in ['react', 'react-dom']:
    folder = Path('desktop/node_modules') / name
    package = json.loads((folder / 'package.json').read_text())
    entries.append('npm: ' + name + ' ' + package['version'] + ' | MIT')
    include('npm: ' + name + ' ' + package['version'], (folder / 'LICENSE').read_bytes())
for folder in Path('node_modules/.pnpm').glob('scheduler@*/node_modules/scheduler'):
    package = json.loads((folder / 'package.json').read_text())
    entries.append('npm: scheduler ' + package['version'] + ' | MIT')
    include('npm: scheduler ' + package['version'], (folder / 'LICENSE').read_bytes())

body = ['LanVault third-party notices', '',
        'LanVault source is MIT licensed. Third-party components retain their own licenses.',
        'This inventory includes Windows dependency/build tools, Android runtime, and bundled React.',
        'Optional platform code may be absent from a particular compiled artifact.', '',
        'DEPENDENCY INVENTORY', *sorted(entries), '', 'LICENSE AND NOTICE TEXTS']
for record in sorted(texts.values(), key=lambda r: sorted(r['labels'])[0]):
    body.extend(['', '=' * 72, *sorted(record['labels']), '', record['text']])
args.output.write_text('\n'.join(body) + '\n', encoding='utf-8')
print(json.dumps({'dependencies': len(entries), 'distinctLicenseTexts': len(texts), 'cargoLicenseFilesNotBundled': missing}))
