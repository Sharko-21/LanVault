CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
INSERT OR IGNORE INTO metadata(key,value) VALUES('schema_version','1');
CREATE TABLE IF NOT EXISTS roots (
 id TEXT PRIMARY KEY, path TEXT NOT NULL, identity TEXT NOT NULL UNIQUE, active INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS one_active_root ON roots(active) WHERE active=1;
CREATE TABLE IF NOT EXISTS devices (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
 revoked INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, last_seen TEXT
);
CREATE TABLE IF NOT EXISTS pairing (
 id TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, expires_at INTEGER NOT NULL,
 request_id TEXT UNIQUE, client_name TEXT, claim_hash TEXT,
 state TEXT NOT NULL CHECK(state IN ('open','pending','approved','received','rejected')),
 result BLOB, device_id TEXT, result_expires INTEGER
);
CREATE TABLE IF NOT EXISTS uploads (
 id TEXT PRIMARY KEY, root_id TEXT NOT NULL REFERENCES roots(id), device_id TEXT NOT NULL REFERENCES devices(id),
 request_id TEXT NOT NULL, descriptor TEXT NOT NULL, size INTEGER NOT NULL CHECK(size>0), sha256 TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('created','writing','verified','committing','committed','failed','cancelled')),
 relative_path TEXT NOT NULL, receipt_id TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL, committed_at TEXT,
 received_bytes INTEGER NOT NULL DEFAULT 0,
 UNIQUE(device_id,root_id,request_id)
);
CREATE TABLE IF NOT EXISTS receipts (
 id TEXT PRIMARY KEY, upload_id TEXT NOT NULL UNIQUE REFERENCES uploads(id),
 root_id TEXT NOT NULL REFERENCES roots(id), device_id TEXT NOT NULL REFERENCES devices(id),
 sha256 TEXT NOT NULL, size INTEGER NOT NULL CHECK(size>0), relative_path TEXT NOT NULL,
 committed_at TEXT NOT NULL, healthy INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS content_dedup ON receipts(root_id,device_id,sha256,size,healthy);
