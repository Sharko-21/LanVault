"""Build reviewable beta artifacts from an explicit source set; never include state or keys."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/releases')
parser.add_argument('--windows-directory', type=Path)
parser.add_argument('--android-apk', type=Path)
parser.add_argument('--runtime-dll', type=Path, action='append', default=[])
args = parser.parse_args()
version = json.loads((ROOT / 'package.json').read_text())['version']
if not re.fullmatch(r'[0-9][0-9A-Za-z.+-]*', version):
    raise SystemExit('Unsafe release version')
args.output.mkdir(parents=True, exist_ok=True)
out = args.output.resolve()
names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
names = sorted(set(filter(None, names)))
subprocess.run(['python' if shutil.which('python') else 'python3', str(ROOT / 'tooling/check-public-files.py')], cwd=ROOT, check=True)
snapshot = Path(tempfile.mkdtemp(prefix='LanVault-source-', dir=out))
manifest = []
for name in names:
    source = ROOT / name
    target = snapshot / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    manifest.append(hashlib.sha256(source.read_bytes()).hexdigest() + '  ' + name)
(snapshot / 'SOURCE_SHA256SUMS.txt').write_text('\n'.join(manifest) + '\n', encoding='utf-8')


def archive(path, files):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for source, name in sorted(files, key=lambda item: item[1]):
            info = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name.endswith('/gradlew') else 0o100644) << 16
            output.writestr(info, source.read_bytes())


archive(out / f'LanVault-{version}-source.zip', [(p, f'LanVault-{version}-source/' + p.relative_to(snapshot).as_posix()) for p in snapshot.rglob('*') if p.is_file()])
if args.windows_directory:
    folder = args.windows_directory.resolve()
    exe = folder / 'lanvault-desktop.exe'
    if not exe.is_file():
        raise SystemExit('Windows receiver EXE missing')
    files = [(exe, exe.name)] + [(p, p.name) for p in folder.glob('*.dll')]
    files += [(p, p.name) for p in args.runtime_dll]
    files += [(ROOT / 'tooling/setup-firewall.ps1', 'setup-firewall.ps1'), (ROOT / 'LICENSE', 'LICENSE'), (ROOT / 'THIRD_PARTY_NOTICES.txt', 'THIRD_PARTY_NOTICES.txt'), (ROOT / 'docs/installation/beta.md', 'INSTALL.md')]
    archive(out / f'LanVault-{version}-windows-x64.zip', files)
if args.android_apk:
    destination = out / f'LanVault-{version}-android.apk'
    if args.android_apk.resolve() != destination:
        shutil.copyfile(args.android_apk, destination)
checksums = [hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name for p in sorted(out.iterdir()) if p.is_file() and p.suffix in {'.zip', '.apk'}]
(out / 'SHA256SUMS.txt').write_text('\n'.join(checksums) + '\n', encoding='utf-8')
print(json.dumps({'version': version, 'sourceFiles': len(names), 'sourceSnapshot': str(snapshot), 'output': str(out)}))
