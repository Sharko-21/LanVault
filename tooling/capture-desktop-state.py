"""Read-only continuity check; stores fingerprints, never credentials or private keys."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3

parser = argparse.ArgumentParser()
parser.add_argument("--state-dir", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
root = args.state_dir.resolve(strict=True)
connection = sqlite3.connect((root / "catalog.sqlite").as_uri() + "?mode=ro", uri=True)
devices = connection.execute("SELECT id,token_hash,revoked FROM devices ORDER BY id").fetchall()
result = {
    "identity_sha256": hashlib.sha256((root / "identity.dpapi").read_bytes()).hexdigest(),
    "credentials_sha256": hashlib.sha256(json.dumps(devices).encode()).hexdigest(),
    "device_ids": [r[0] for r in devices],
    "active_root": connection.execute("SELECT id,path,identity FROM roots WHERE active=1").fetchall(),
    "receiver": connection.execute("SELECT key,value FROM metadata WHERE key IN ('auto_receiver','receiver_adapter','receiver_port') ORDER BY key").fetchall(),
    "receipts": connection.execute("SELECT COUNT(*) FROM receipts").fetchone()[0],
    "active_writes": connection.execute("SELECT COUNT(*) FROM uploads WHERE state IN ('writing','committing')").fetchone()[0],
}
connection.close()
args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({k:result[k] for k in ['identity_sha256','credentials_sha256','receipts','active_writes']}))
