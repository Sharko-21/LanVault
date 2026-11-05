-- No credentials, roots, receipts or existing upload identifiers are rewritten.
CREATE TABLE IF NOT EXISTS upload_leases (
 upload_id TEXT PRIMARY KEY REFERENCES uploads(id),
 touched_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS pending_uploads ON uploads(state,root_id,device_id)
 WHERE state IN ('created','writing','verified','committing','failed','cancelled');
