"""Synthetic loopback TLS/disk benchmark; does not measure phone or Wi-Fi speed."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import json
from pathlib import Path
import statistics
import tempfile
import threading
import time

spec = importlib.util.spec_from_file_location("server_checks", Path(__file__).with_name("test-server.py"))
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)
parser = argparse.ArgumentParser()
parser.add_argument("--before", type=Path, required=True)
parser.add_argument("--after", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--before-lanes", type=int, default=1)
parser.add_argument("--after-lanes", type=int, nargs="+", default=[1, 2])
parser.add_argument("--repeats", type=int, default=3)
parser.add_argument("--file-count", type=int, default=12)
parser.add_argument("--file-kib", type=int, default=4096)
parser.add_argument("--archive-base", type=Path)
parser.add_argument("--keep-alive", action="store_true")
args = parser.parse_args()
assert args.before_lanes in range(1, 5) and all(n in range(1, 5) for n in args.after_lanes)
assert 1 <= args.repeats <= 5 and 1 <= args.file_count <= 128 and args.file_kib > 0
assert args.file_count * args.file_kib <= 256 * 1024
if args.archive_base:
    args.archive_base = args.archive_base.resolve(strict=True)
    assert args.archive_base.is_dir()
payloads = [bytes([n]) * (args.file_kib * 1024) for n in range(1, args.file_count + 1)]
mib = args.file_count * args.file_kib / 1024
cases = [(f"before-{args.before_lanes}", args.before, args.before_lanes)] + [(f"after-{n}", args.after, n) for n in args.after_lanes]
results = []
for repeat in range(args.repeats):
    for label, executable, lanes in cases:
        # Match Desktop: its state/catalog remains on the system disk while the
        # archive may live on a separate HDD. Do not move SQLite with the archive.
        with tempfile.TemporaryDirectory(prefix="LanVault-parallel-benchmark-") as directory, tempfile.TemporaryDirectory(prefix="LanVault-parallel-benchmark-archive-", dir=args.archive_base) as archive_directory:
            root = Path(directory)
            assert root.name.startswith("LanVault-parallel-benchmark-")
            assert root.resolve().parent == Path(tempfile.gettempdir()).resolve()
            archive_root = Path(archive_directory)
            assert archive_root.name.startswith("LanVault-parallel-benchmark-archive-")
            assert archive_root.resolve().parent == (args.archive_base or Path(tempfile.gettempdir()).resolve())
            archive = archive_root / "archive"
            archive.mkdir()
            backend = checks.Backend(executable.resolve(strict=True), root / "state", archive)
            connections = []
            local = threading.local()
            try:
                context, device = checks.pair_device(backend)
                descriptors = [checks.descriptor(backend, data, f"synthetic-{n}.heic") for n, data in enumerate(payloads)]
                def request(method, path, payload=None, data=None, length=None):
                    if not args.keep_alive:
                        return checks.request(backend, context, method, path, payload, token=device["token"], data=data, length=length)
                    if not hasattr(local, "connection"):
                        local.connection = checks.Connection(backend.hostname, backend.port, context)
                        connections.append(local.connection)
                    headers = {"Authorization": "Bearer " + device["token"]}
                    if payload is not None:
                        data = json.dumps(payload).encode()
                        headers["Content-Type"] = "application/json"
                    if length is not None:
                        headers["Content-Length"] = str(length)
                    local.connection.request(method, path, body=data, headers=headers)
                    response = local.connection.getresponse()
                    body = response.read(65536)
                    return response.status, json.loads(body) if body else {}
                def send(index):
                    stages = {}
                    data, desc = payloads[index], descriptors[index]
                    began = time.perf_counter()
                    code, result = request("POST", "/v1/uploads", desc)
                    assert code == 200 and result["state"] != "committed"
                    stages["create"] = time.perf_counter() - began
                    upload = result["uploadId"]
                    began = time.perf_counter()
                    code, _ = request("PUT", f"/v1/uploads/{upload}/content", data=data, length=len(data))
                    assert code == 200
                    stages["content"] = time.perf_counter() - began
                    began = time.perf_counter()
                    code, result = request("POST", f"/v1/uploads/{upload}/complete")
                    assert code == 200 and result["state"] == "committed"
                    stages["complete"] = time.perf_counter() - began
                    return result["receipt"], stages
                start = time.perf_counter()
                with ThreadPoolExecutor(max_workers=lanes) as pool:
                    transfers = list(pool.map(send, range(len(payloads))))
                seconds = time.perf_counter() - start
                for data, (receipt, _) in zip(payloads, transfers):
                    actual = (archive / receipt["relativePath"]).read_bytes()
                    assert actual == data and hashlib.sha256(actual).hexdigest() == receipt["sha256"]
                result = {"case": label, "repeat": repeat + 1, "files": len(transfers), "mib": mib, "seconds": round(seconds, 3), "mib_per_second": round(mib / seconds, 2), "mean_stage_seconds": {stage: round(statistics.mean(times[stage] for _, times in transfers), 4) for stage in ["create", "content", "complete"]}}
                results.append(result)
                print(json.dumps(result), flush=True)
            finally:
                for connection in connections:
                    connection.close()
                backend.close()
medians = {label: round(statistics.median(r["mib_per_second"] for r in results if r["case"] == label), 2) for label, _, _ in cases}
output = {"environment": "Windows loopback TLS with synthetic files; no phone/Wi-Fi", "keep_alive": args.keep_alive, "state_base": tempfile.gettempdir(), "archive_base": str(args.archive_base or Path(tempfile.gettempdir())), "file_count": args.file_count, "file_kib": args.file_kib, "repeats": args.repeats, "results": results, "median_mib_per_second": medians}
args.output.write_text(json.dumps(output, indent=2), encoding="utf-8")
print(json.dumps({"medians": medians}), flush=True)
