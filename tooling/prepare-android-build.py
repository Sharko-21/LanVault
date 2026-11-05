"""Prepare a byte-identical Android build snapshot in an ASCII temporary path on Windows."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import tempfile
import sys

ROOT=Path(__file__).resolve().parents[1]
(ROOT/'artifacts').mkdir(exist_ok=True)
DEST=Path(tempfile.gettempdir())/'LanVault-android-build'
if not str(DEST).isascii(): raise RuntimeError('Use an ASCII TEMP path for Android builds')
if '--verify' in sys.argv:
    saved=json.loads((ROOT/'artifacts/android-build-inputs.json').read_text(encoding='utf-8'))
    verified_dest=Path(saved['buildRoot'])
    owner=verified_dest/'.lanvault-build-owner'
    if not owner.exists() or owner.read_text(encoding='utf-8')!=str(ROOT):raise RuntimeError('Build snapshot ownership mismatch')
    for name,digest in saved['sha256'].items():
        relative=Path(name)
        if relative.is_absolute() or '..' in relative.parts:raise RuntimeError('Unsafe build input name')
        if hashlib.sha256((ROOT/relative).read_bytes()).hexdigest()!=digest or hashlib.sha256((verified_dest/relative).read_bytes()).hexdigest()!=digest:raise RuntimeError('Build input changed: '+name)
    print(json.dumps({'buildInputs':'verified','files':len(saved['sha256'])}))
    raise SystemExit(0)
DEST.mkdir(exist_ok=True)
marker=DEST/'.lanvault-build-owner'
if marker.exists() and marker.read_text(encoding='utf-8')!=str(ROOT):raise RuntimeError('Temporary build belongs to a different project')
marker.write_text(str(ROOT),encoding='utf-8')
IGNORE=shutil.ignore_patterns('build','.gradle','local.properties','*.lock')
for folder in ('android','protocol'):
    target=DEST/folder
    if target.exists():
        for current,dirs,files in os.walk(target):
            dirs[:]=[d for d in dirs if d not in ('build','.gradle')]
            for name in files:
                candidate=Path(current)/name
                relative=candidate.relative_to(DEST)
                generated_schema=relative.as_posix().startswith('android/core/database/schemas/')
                if name=='local.properties' or name.endswith('.lock') or generated_schema:continue
                if not (ROOT/relative).exists():
                    resolved=candidate.resolve(strict=True)
                    if not resolved.is_relative_to(target.resolve()) or candidate.is_symlink():raise RuntimeError('Unsafe stale input path')
                    candidate.unlink()
    shutil.copytree(ROOT/folder,DEST/folder,dirs_exist_ok=True,ignore=IGNORE)
jdk=next((ROOT/'.tools/android-jdk').iterdir())
if not (DEST/'jdk/bin/java.exe').exists():shutil.copytree(jdk,DEST/'jdk',dirs_exist_ok=True,ignore=IGNORE)
for folder in ('platforms','build-tools','platform-tools','licenses'):
    if not (DEST/'sdk'/folder).exists():shutil.copytree(ROOT/'.tools/android-sdk'/folder,DEST/'sdk'/folder,dirs_exist_ok=True,ignore=IGNORE)
for folder in ('caches','wrapper'):
    source=ROOT/'.cache/gradle'/folder
    if source.exists() and not (DEST/'gradle'/folder).exists():shutil.copytree(source,DEST/'gradle'/folder,dirs_exist_ok=True,ignore=IGNORE)
records={}
for folder in ('android','protocol'):
    for current,dirs,files in os.walk(ROOT/folder):
        dirs[:]=[d for d in dirs if d not in ('build','.gradle')]
        for name in files:
            if name=='local.properties' or name.endswith('.lock'):continue
            source=Path(current)/name;relative=source.relative_to(ROOT)
            digest=hashlib.sha256(source.read_bytes()).hexdigest()
            if hashlib.sha256((DEST/relative).read_bytes()).hexdigest()!=digest:raise RuntimeError('Build input mismatch: '+str(relative))
            records[relative.as_posix()]=digest
(ROOT/'artifacts/android-build-inputs.json').write_text(json.dumps({'buildRoot':str(DEST),'sha256':records},indent=2),encoding='utf-8')
(ROOT/'artifacts/android-build-root.txt').write_text(str(DEST),encoding='utf-8')
print(json.dumps({'buildRoot':str(DEST),'verifiedFiles':len(records)}))
