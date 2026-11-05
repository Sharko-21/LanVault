"""Optional isolated Android instrumentation -> local Desktop test. No personal photos."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import time

parser=argparse.ArgumentParser()
parser.add_argument('--adb',type=Path,required=True)
parser.add_argument('--serial',required=True)
parser.add_argument('--apk',type=Path,required=True)
parser.add_argument('--test-apk',type=Path,required=True)
parser.add_argument('--desktop-exe',type=Path)
args=parser.parse_args()
adb=str(args.adb.resolve(strict=True))
creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0

def run(*command,timeout=120):
    result=subprocess.run([adb,'-s',args.serial,*command],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout,creationflags=creationflags)
    if result.returncode!=0:raise RuntimeError('ADB action failed: '+command[0])
    return result.stdout

if args.desktop_exe and not args.serial.startswith('emulator-'):
    parser.error('--desktop-exe requires an emulator with its standard Wi-Fi/10.0.2.2 host mapping; use the QR UI on a physical phone')
run('install','-r',str(args.apk.resolve(strict=True)))
run('install','-r',str(args.test_apk.resolve(strict=True)))
backend=None
try:
    command=['shell','am','instrument','-w','-r']
    if args.desktop_exe:
        spec=importlib.util.spec_from_file_location('desktop_fixture',Path(__file__).with_name('test-server.py'))
        fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
        temporary=tempfile.TemporaryDirectory(prefix='LanVault-android-e2e-')
        base=Path(temporary.name);archive=base/'archive';archive.mkdir()
        backend=fixture.Backend(args.desktop_exe.resolve(strict=True),base/'state',archive)
        pairing=backend.admin('pair');pairing.pop('qrDataUrl',None);pairing['addresses']=['10.0.2.2']
        encoded=base64.urlsafe_b64encode(json.dumps(pairing,separators=(',',':')).encode()).decode().rstrip('=')
        command+=['-e','lanvaultQrB64',encoded]
    command+=['app.lanvault.android.test/androidx.test.runner.AndroidJUnitRunner']
    process=subprocess.Popen([adb,'-s',args.serial,*command],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',creationflags=creationflags)
    output=queue.Queue()
    def read():
        for line in process.stdout:output.put(line)
        output.put(None)
    threading.Thread(target=read,daemon=True).start()
    transcript=[];comparison=None;expected_hash=None;approved=False;deadline=time.monotonic()+180
    try:
        while True:
            if time.monotonic()>deadline:raise TimeoutError('Android instrumentation exceeded 180 seconds')
            try:line=output.get(timeout=0.5)
            except queue.Empty:line=''
            if line is None:break
            if line:
                transcript.append(line)
                if 'lanvaultCode=' in line:comparison=line.split('lanvaultCode=',1)[1].strip()
                if 'lanvaultHash=' in line:expected_hash=line.split('lanvaultHash=',1)[1].strip()
            if backend and comparison and not approved:
                pending=backend.admin('status')['pendingPairing']
                if pending:
                    if pending['verificationCode']!=comparison:raise RuntimeError('Pairing comparison codes differ')
                    backend.admin('approve',requestId=pending['id']);approved=True
        process.wait(timeout=10)
        result=''.join(transcript)
        print(result)
        if process.returncode!=0 or 'FAILURES!!!' in result or 'INSTRUMENTATION_FAILED' in result or 'OK (' not in result:raise RuntimeError('Android instrumentation did not pass')
        if backend:
            if not approved or not expected_hash:raise RuntimeError('Device transfer was not exercised')
            history=backend.admin('status')['history']
            if len(history)!=1 or hashlib.sha256((archive/history[0]['path']).read_bytes()).hexdigest()!=expected_hash:raise RuntimeError('Desktop original SHA-256 mismatch')
            print('PASS Android MediaStore -> bound Wi-Fi -> checked TLS -> Desktop original bytes and receipt')
    finally:
        if process.poll() is None:process.kill();process.wait(timeout=10)
finally:
    if backend:backend.close();temporary.cleanup()
