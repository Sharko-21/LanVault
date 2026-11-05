"""Validate the exact public distribution, including source manifests and checksum coverage."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
args = parser.parse_args()
folder = args.directory.resolve()
listed = {}
for line in (folder / 'SHA256SUMS.txt').read_text().splitlines():
    digest, name = line.split('  ', 1)
    if PurePosixPath(name).name != name:
        raise SystemExit('Invalid checksum entry')
    listed[name] = digest
for artifact in folder.iterdir():
    if artifact.is_file() and artifact.suffix in {'.apk', '.zip'}:
        assert artifact.name in listed, 'Artifact missing checksum'
        assert hashlib.sha256(artifact.read_bytes()).hexdigest() == listed[artifact.name], 'Checksum mismatch'
        if artifact.suffix != '.zip':
            continue
        with zipfile.ZipFile(artifact) as archive:
            for name in archive.namelist():
                path = PurePosixPath(name)
                assert not path.is_absolute() and '..' not in path.parts
                assert path.suffix.lower() not in {'.dpapi', '.sqlite', '.db', '.p12', '.jks', '.keystore', '.key', '.pfx'}, 'Private file in archive'
                assert '.tools' not in path.parts and '.cache' not in path.parts and 'node_modules' not in path.parts
            if artifact.name.endswith('-windows-x64.zip'):
                assert {'lanvault-desktop.exe', 'WebView2Loader.dll', 'LICENSE', 'THIRD_PARTY_NOTICES.txt', 'INSTALL.md', 'setup-firewall.ps1'} <= set(archive.namelist())
            if artifact.name.endswith('-source.zip'):
                prefix = archive.namelist()[0].split('/')[0] + '/'
                lines = archive.read(prefix + 'SOURCE_SHA256SUMS.txt').decode().splitlines()
                for row in lines:
                    digest, name = row.split('  ', 1)
                    assert hashlib.sha256(archive.read(prefix + name)).hexdigest() == digest, 'Source snapshot checksum mismatch'
                assert prefix + '.github/workflows/ci.yml' in archive.namelist()
                assert prefix + 'LICENSE' in archive.namelist()
print(json.dumps({'publicArtifactsVerified': len(listed), 'privateFilesInArchives': False, 'sourceManifestVerified': True}))
