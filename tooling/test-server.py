"""Real HTTPS integration tests against lanvaultd. Standard library only; synthetic files only."""
import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import queue
import secrets
import socket
import sqlite3
import ssl
import subprocess
import tempfile
import threading
import time
import uuid


class Connection(http.client.HTTPSConnection):
    def __init__(self, hostname, port, context):
        super().__init__(hostname, port, context=context, timeout=15)
        self.checked_context = context

    def connect(self):
        raw = socket.create_connection(("127.0.0.1", self.port), timeout=15)
        self.sock = self.checked_context.wrap_socket(raw, server_hostname=self.host)


class Backend:
    def __init__(self, executable, state, archive, reserve=0):
        self.stderr = tempfile.TemporaryFile()
        self.process = subprocess.Popen(
            [str(executable), "--state-dir", str(state), *(["--backup-dir", str(archive)] if archive else []),
             "--loopback-test", "--reserve-bytes", str(reserve)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr,
            text=True, encoding="utf-8", bufsize=1,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        self.messages = queue.Queue()
        def read():
            for line in self.process.stdout:
                self.messages.put(json.loads(line))
            self.messages.put(None)
        threading.Thread(target=read, daemon=True).start()
        try:
            first = self.read()
            assert first.get("event") == "ready", "backend did not become ready"
        except BaseException:
            self.close(kill=True)
            raise
        self.port = first["endpoint"]["port"]
        self.hostname = first["endpoint"]["hostname"]
        self.dataset = first["snapshot"]["folder"]["datasetId"]
        self.server_id = first["snapshot"]["serverId"]

    def read(self):
        result = self.messages.get(timeout=30)
        if result is None:
            self.stderr.seek(0)
            raise RuntimeError("backend exited: " + self.stderr.read().decode(errors="replace")[-2000:])
        return result

    def admin(self, command, **parameters):
        self.process.stdin.write(json.dumps({"command": command, **parameters}) + "\n")
        self.process.stdin.flush()
        response = self.read()
        assert "error" not in response, f"local admin command failed: {command}"
        return response["result"]

    def close(self, kill=False):
        if self.process.poll() is None:
            if kill:
                self.process.kill()
            else:
                self.process.stdin.write('{"command":"quit"}\n')
                self.process.stdin.flush()
            self.process.wait(timeout=15)
        self.stderr.close()


def context_for(pair):
    ca = ssl.DER_cert_to_PEM_cert(base64.b64decode(pair["caCertificate"]))
    context = ssl.create_default_context(cadata=ca)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.set_alpn_protocols(["http/1.1"])
    return context


def request(backend, context, method, path, payload=None, token=None, claim=None, data=None, length=None, extra=None):
    headers = {"Connection": "close"}
    if token: headers["Authorization"] = "Bearer " + token
    if claim: headers["X-Pairing-Claim"] = claim
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    if length is not None: headers["Content-Length"] = str(length)
    if extra: headers.update(extra)
    connection = Connection(backend.hostname, backend.port, context)
    try:
        connection.request(method, path, body=data, headers=headers)
        response = connection.getresponse()
        raw = response.read(65536)
        return response.status, json.loads(raw) if raw else {}
    finally:
        connection.close()


def pair_device(backend, name="Integration phone"):
    pairing = backend.admin("pair")
    context = context_for(pairing)
    claim = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("=")
    code, response = request(backend, context, "POST", "/v1/pairing/requests", {
        "pairingId": pairing["pairingId"], "secret": pairing["secret"],
        "claimSecret": claim, "clientName": name,
    })
    assert code == 200, f"pair request HTTP {code}"
    pending = backend.admin("status")["pendingPairing"]
    assert pending["verificationCode"] == response["verificationCode"], "comparison codes differ"
    request_id = response["requestId"]
    code, before = request(backend, context, "GET", f"/v1/pairing/requests/{request_id}", claim=claim)
    assert before["state"] == "pending" and "credentials" not in before, "credentials issued without approval"
    backend.admin("approve", requestId=request_id)
    code, approved = request(backend, context, "GET", f"/v1/pairing/requests/{request_id}", claim=claim)
    assert code == 200 and approved["state"] == "approved", "approval failed"
    code, repeated = request(backend, context, "GET", f"/v1/pairing/requests/{request_id}", claim=claim)
    assert repeated == approved, "credential delivery is not idempotent"
    credentials = approved["credentials"]
    code, _ = request(backend, context, "POST", f"/v1/pairing/requests/{request_id}/confirm-received", claim=claim)
    assert code == 200
    return context, credentials


def descriptor(backend, data, name="IMG_20261104.heic"):
    return {"datasetId": backend.dataset, "mediaId": str(uuid.uuid4()), "revisionId": str(uuid.uuid4()),
            "uploadRequestId": str(uuid.uuid4()), "originalName": name, "size": str(len(data)),
            "sha256": hashlib.sha256(data).hexdigest(), "mediaType": "photo", "mimeType": "image/heic"}


def upload(backend, context, token, desc, data):
    code, created = request(backend, context, "POST", "/v1/uploads", desc, token=token)
    assert code == 200, f"create upload HTTP {code}"
    if created["state"] == "committed": return created["receipt"]
    upload_id = created["uploadId"]
    code, _ = request(backend, context, "PUT", f"/v1/uploads/{upload_id}/content", token=token, data=data, length=int(desc["size"]))
    assert code == 200, f"stream upload HTTP {code}"
    code, completed = request(backend, context, "POST", f"/v1/uploads/{upload_id}/complete", token=token)
    if code != 200:
        backend.stderr.seek(0)
        raise AssertionError(f"complete upload HTTP {code}: " + backend.stderr.read().decode(errors="replace")[-2000:])
    code, retry = request(backend, context, "POST", f"/v1/uploads/{upload_id}/complete", token=token)
    assert retry == completed, "completion is not idempotent"
    return completed["receipt"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--exe", type=Path, required=True)
    args = parser.parse_args()
    executable = args.exe.resolve(strict=True)
    passed = []
    def ok(name):
        passed.append(name)
        print("PASS " + name, flush=True)

    with tempfile.TemporaryDirectory(prefix="lanvault-stage3-") as temporary:
        base = Path(temporary)
        state = base / "state"
        archive = base / "archive"
        archive.mkdir()
        backend = Backend(executable, state, archive)
        try:
            context, device = pair_device(backend)
            token = device["token"]
            ok("TLS pairing requires local approval and delivers credentials idempotently")
            try:
                request(backend, ssl.create_default_context(), "GET", "/v1/server")
                raise AssertionError("untrusted CA accepted")
            except ssl.SSLCertVerificationError: pass
            ok("untrusted TLS certificate rejected")

            connection = Connection("wrong.lanvault.invalid", backend.port, context)
            try:
                connection.connect()
                raise AssertionError("wrong TLS hostname accepted")
            except ssl.SSLCertVerificationError: pass
            finally: connection.close()
            ok("TLS hostname verification rejects an unrelated server name")

            plain = http.client.HTTPConnection("127.0.0.1", backend.port, timeout=5)
            try:
                plain.request("GET", "/v1/server")
                response = plain.getresponse()
                assert response.status != 200
            except (OSError, http.client.HTTPException): pass
            finally: plain.close()
            ok("plaintext HTTP is not accepted by the TLS listener")
            code, _ = request(backend, context, "GET", "/v1/server")
            assert code == 401
            code, _ = request(backend, context, "GET", "/v1/server", token="A" * 43)
            assert code == 401
            ok("missing and forged credentials rejected")
            code, _ = request(backend, context, "GET", "/v1/server", token=token, extra={"Origin": "https://example.invalid"})
            assert code == 401
            ok("browser-origin requests rejected")

            data = b"HEIC original EXIF GPS opaque bytes\x00\xff" * 2048
            desc = descriptor(backend, data)
            receipt = upload(backend, context, token, desc, data)
            destination = archive / receipt["relativePath"]
            assert destination.resolve().is_relative_to(archive.resolve())
            assert destination.exists(), f"published path missing; archive entries: {list(archive.rglob('*'))}"
            assert destination.read_bytes() == data
            assert receipt["sha256"] == hashlib.sha256(data).hexdigest()
            ok("original bytes preserved and committed after full SHA-256")

            code, repeated = request(backend, context, "POST", "/v1/uploads", desc, token=token)
            assert code == 200 and repeated["receipt"]["receiptId"] == receipt["receiptId"]
            changed = dict(desc, originalName="changed.heic")
            code, _ = request(backend, context, "POST", "/v1/uploads", changed, token=token)
            assert code == 409
            ok("idempotency key replay returns same receipt; conflicting metadata rejected")

            duplicate = descriptor(backend, data, "renamed.heic")
            duplicate_receipt = upload(backend, context, token, duplicate, data)
            assert duplicate_receipt["relativePath"] == receipt["relativePath"]
            assert duplicate_receipt["mediaId"] == duplicate["mediaId"]
            ok("content deduplication creates a correctly bound alias without retransmission")

            outside = base / "outside.jpg"
            outside.write_bytes(b"do not overwrite")
            hostile = descriptor(backend, b"new content", "../../outside.jpg")
            safe = upload(backend, context, token, hostile, b"new content")
            assert (archive / safe["relativePath"]).resolve().is_relative_to(archive.resolve())
            assert outside.read_bytes() == b"do not overwrite"
            ok("path traversal name cannot escape archive")

            bad = descriptor(backend, b"damaged")
            bad["sha256"] = "0" * 64
            code, created = request(backend, context, "POST", "/v1/uploads", bad, token=token)
            assert code == 200
            code, _ = request(backend, context, "PUT", f'/v1/uploads/{created["uploadId"]}/content', token=token, data=b"damaged", length=7)
            assert code == 422
            code, _ = request(backend, context, "POST", f'/v1/uploads/{created["uploadId"]}/complete', token=token)
            assert code == 409
            ok("bad checksum never produces a committed receipt")

            backend.admin("pause")
            code, _ = request(backend, context, "POST", "/v1/uploads", descriptor(backend, b"paused"), token=token)
            assert code == 423
            backend.admin("resume")
            ok("pause blocks new uploads")

            block = bytes(range(256)) * 256
            count = 1024
            digest = hashlib.sha256()
            for _ in range(count): digest.update(block)
            large = descriptor(backend, b"x", "large.mp4")
            large.update(size=str(len(block) * count), sha256=digest.hexdigest(), mediaType="video", mimeType="video/mp4")
            large_receipt = upload(backend, context, token, large, (block for _ in range(count)))
            with (archive / large_receipt["relativePath"]).open("rb") as saved:
                assert hashlib.file_digest(saved, "sha256").hexdigest() == digest.hexdigest()
            ok("64 MiB video streamed with bounded client buffers")

            # Persist a mid-body interruption, then prove the whole-file retry after a crash.
            interrupted_data = b"interrupted video bytes" * 65536
            interrupted = descriptor(backend, interrupted_data)
            code, created = request(backend, context, "POST", "/v1/uploads", interrupted, token=token)
            interrupted_id = created["uploadId"]
            connection = Connection(backend.hostname, backend.port, context)
            connection.putrequest("PUT", f"/v1/uploads/{interrupted_id}/content")
            connection.putheader("Authorization", "Bearer " + token)
            connection.putheader("Content-Length", str(len(interrupted_data)))
            connection.endheaders()
            connection.send(interrupted_data[:65536])
            connection.close()
            time.sleep(0.2)
            backend.close(kill=True)
            backend = Backend(executable, state, None)
            retry_receipt = upload(backend, context, token, interrupted, interrupted_data)
            assert (archive / retry_receipt["relativePath"]).read_bytes() == interrupted_data
            ok("interrupted upload survives process crash and retries from byte zero")

            # Exercise both durable commit crash windows without adding fault switches to production.
            for renamed in (False, True):
                recovery_data = secrets.token_bytes(4096)
                recovery = descriptor(backend, recovery_data)
                code, created = request(backend, context, "POST", "/v1/uploads", recovery, token=token)
                upload_id = created["uploadId"]
                code, _ = request(backend, context, "PUT", f"/v1/uploads/{upload_id}/content", token=token, data=recovery_data, length=len(recovery_data))
                assert code == 200
                backend.close(kill=True)
                database = sqlite3.connect(state / "catalog.sqlite")
                relative = database.execute("SELECT relative_path FROM uploads WHERE id=?", (upload_id,)).fetchone()[0]
                database.execute("UPDATE uploads SET state='committing',committed_at='2026-11-04T00:00:00Z' WHERE id=?", (upload_id,))
                database.commit()
                database.close()
                if renamed:
                    final = archive / relative
                    final.parent.mkdir(parents=True, exist_ok=True)
                    (archive / ".lanvault" / "staging" / (upload_id + ".part")).rename(final)
                backend = Backend(executable, state, None)
                code, recovered = request(backend, context, "GET", f"/v1/uploads/{upload_id}", token=token)
                assert code == 200 and recovered["state"] == "committed"
                assert (archive / recovered["receipt"]["relativePath"]).read_bytes() == recovery_data
            ok("durable commit recovers before and after final rename")

            # Device ownership is checked for every upload endpoint.
            other_context, other_device = pair_device(backend, "Second synthetic phone")
            code, created = request(backend, context, "POST", "/v1/uploads", descriptor(backend, b"owner only"), token=token)
            code, _ = request(backend, other_context, "GET", f'/v1/uploads/{created["uploadId"]}', token=other_device["token"])
            assert code == 404
            ok("another trusted device cannot read an upload owned by the first device")

            # A stored receipt cannot acknowledge a file manually removed from the archive.
            altered_data = b"external deletion test"
            altered = descriptor(backend, altered_data)
            altered_receipt = upload(backend, context, token, altered, altered_data)
            (archive / altered_receipt["relativePath"]).unlink()
            code, _ = request(backend, context, "POST", "/v1/uploads", altered, token=token)
            assert code == 409
            ok("missing saved file never yields a cached success receipt")

            first_server_id = backend.server_id
            corrupt_data = b"startup same-size corruption test" * 1024
            corrupt_desc = descriptor(backend, corrupt_data, "corruption.heic")
            corrupt_receipt = upload(backend, context, token, corrupt_desc, corrupt_data)
            (archive / corrupt_receipt["relativePath"]).write_bytes(b"X" * len(corrupt_data))
            audit_db = sqlite3.connect(state / "catalog.sqlite")
            audit_db.execute("""CREATE TRIGGER no_restage_committed BEFORE UPDATE OF state ON uploads
                WHEN OLD.state='committed' AND NEW.state='committing'
                BEGIN SELECT RAISE(ABORT,'committed rows must not be restaged'); END""")
            audit_db.commit()
            audit_db.close()
            backend.close(kill=True)
            backend = Backend(executable, state, None)
            assert backend.server_id == first_server_id
            code, _ = request(backend, context, "GET", "/v1/server", token=token)
            assert code == 200
            code, same = request(backend, context, "POST", "/v1/uploads", desc, token=token)
            assert code == 200 and same["receipt"]["receiptId"] == receipt["receiptId"]
            ok("restart retains TLS trust, credentials, folder and committed receipts")
            ok("startup never restages durable committed uploads")
            code, _ = request(backend, context, "POST", "/v1/uploads", corrupt_desc, token=token)
            assert code == 409, "same-size corruption received a false success receipt after restart"
            ok("full receipt hash rejects same-size corruption after fast startup audit")

            backend.admin("revoke", deviceId=device["deviceId"])
            code, _ = request(backend, context, "GET", "/v1/server", token=token)
            assert code == 401
            assert destination.read_bytes() == data
            ok("revocation rejects token and retains existing backups")

            connection = sqlite3.connect(state / "catalog.sqlite")
            assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            assert connection.execute("SELECT count(*) FROM devices WHERE token_hash=?", (token,)).fetchone()[0] == 0
            connection.close()
            ok("SQLite integrity and hashed credential persistence")

            backend.close()
            backend = Backend(executable, state, None, reserve=2**63-1)
            code, _ = request(backend, other_context, "POST", "/v1/uploads", descriptor(backend, b"no free reservation"), token=other_device["token"])
            assert code == 507
            ok("disk space reservation fails closed before receiving bytes")

            if os.name == "nt":
                link = base / "junction"
                environment = dict(os.environ, LANVAULT_TEST_LINK=str(link), LANVAULT_TEST_TARGET=str(archive))
                subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "New-Item -ItemType Junction -Path $env:LANVAULT_TEST_LINK -Target $env:LANVAULT_TEST_TARGET | Out-Null"], env=environment, check=True, capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
                rejected = subprocess.run([str(executable), "--state-dir", str(base / "junction-state"), "--backup-dir", str(link), "--loopback-test"], input='{"command":"quit"}\n', text=True, capture_output=True, timeout=30, creationflags=subprocess.CREATE_NO_WINDOW)
                assert rejected.returncode != 0 and '"event":"ready"' not in rejected.stdout
                link.rmdir()
                ok("NTFS junction root rejected before receiver starts")

                cloud_environment = dict(os.environ, OneDrive=str(archive))
                rejected = subprocess.run([str(executable), "--state-dir", str(base / "cloud-state"), "--backup-dir", str(archive), "--loopback-test"], env=cloud_environment, input='{"command":"quit"}\n', text=True, capture_output=True, timeout=30, creationflags=subprocess.CREATE_NO_WINDOW)
                assert rejected.returncode != 0 and '"event":"ready"' not in rejected.stdout
                ok("OneDrive root rejected after canonical Windows path normalization")
        finally:
            backend.close()
    print(json.dumps({"passed": len(passed), "tests": passed}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
