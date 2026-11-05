"""Consistent local backup; never copy runtime credentials into a public artifact."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--state-dir', required=True, type=Path)
parser.add_argument('--destination', required=True, type=Path)
parser.add_argument('--owner-sid', required=True)
args = parser.parse_args()
source = args.state_dir.resolve(strict=True)
destination = args.destination.absolute()
if destination.exists():
    raise SystemExit('Choose a new backup directory; existing backups are never overwritten')
subprocess.run([sys.executable, str(Path(__file__).with_name('protect-private-directory.py')), str(destination), '--owner-sid', args.owner_sid], check=True)
connection = sqlite3.connect((source / 'catalog.sqlite').as_uri() + '?mode=ro', uri=True)
backup = sqlite3.connect(destination / 'catalog.sqlite')
try:
    connection.backup(backup)
    assert backup.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
finally:
    backup.close()
    connection.close()
shutil.copyfile(source / 'identity.dpapi', destination / 'identity.dpapi')
manifest = {name: hashlib.sha256((destination / name).read_bytes()).hexdigest() for name in ('identity.dpapi', 'catalog.sqlite')}
(destination / 'backup-manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print(json.dumps({'backupCreated': True, 'identitySha256': manifest['identity.dpapi']}))
