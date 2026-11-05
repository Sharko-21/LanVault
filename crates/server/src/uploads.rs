use crate::{Engine, Error, Result};
use axum::body::Body;
use futures_util::StreamExt;
use lanvault_database::{sqlx, Row};
use lanvault_protocol::{MediaType, UploadDescriptor};
use lanvault_storage::{destination, verify};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{io, sync::Arc, time::Duration};
use tokio::io::{AsyncWriteExt, BufWriter};
use uuid::Uuid;

const MAX_FILE_BYTES: u64 = 1024 * 1024 * 1024 * 1024;

struct ProgressGuard {
    id: Uuid,
    registry: Arc<std::sync::Mutex<std::collections::HashMap<Uuid, u64>>>,
}
impl Drop for ProgressGuard {
    fn drop(&mut self) {
        self.registry
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&self.id);
    }
}

impl Engine {
    pub(crate) async fn create_upload(
        &self,
        device: &str,
        descriptor: UploadDescriptor,
    ) -> Result<Value> {
        self.require_receiving()?;
        let size = descriptor
            .validate()
            .map_err(|_| Error::BadRequest("INVALID_DESCRIPTOR"))?
            .get();
        if size > MAX_FILE_BYTES {
            return Err(Error::TooLarge);
        }
        if !match descriptor.media_type {
            MediaType::Photo => descriptor.mime_type.starts_with("image/"),
            MediaType::Video => descriptor.mime_type.starts_with("video/"),
        } {
            return Err(Error::BadRequest("INVALID_MEDIA_TYPE"));
        }
        let _operation = self.operation.lock().await;
        self.require_device(device).await?;
        let root = self.active_root().await?;
        if root.id != descriptor.dataset_id {
            return Err(Error::Conflict("DATASET_CHANGED"));
        }
        self.sweep_uploads_locked().await?;
        let encoded = serde_json::to_string(&descriptor).map_err(|_| Error::Internal)?;
        if let Some(row)=sqlx::query("SELECT id,descriptor,state FROM uploads WHERE device_id=? AND root_id=? AND request_id=?")
            .bind(device).bind(root.id.to_string()).bind(descriptor.upload_request_id.to_string()).fetch_optional(&self.db).await? {
            if row.try_get::<String,_>("descriptor")?!=encoded {return Err(Error::Conflict("IDEMPOTENCY_CONFLICT"));}
            let id=Uuid::parse_str(row.try_get("id")?).map_err(|_|Error::Internal)?;
            if row.try_get::<String,_>("state")? == "cancelled" {
                self.reserve_upload(device, root.id, size).await?;
                sqlx::query("UPDATE uploads SET state='created',received_bytes=0 WHERE id=?")
                    .bind(id.to_string()).execute(&self.db).await?;
            }
            if row.try_get::<String,_>("state")? != "committed" { self.touch_upload(id).await?; }
            return self.upload_status(device,id).await;
        }
        let receipts=sqlx::query("SELECT * FROM receipts WHERE device_id=? AND root_id=? AND sha256=? AND size=? AND healthy=1")
            .bind(device).bind(root.id.to_string()).bind(&descriptor.sha256).bind(size as i64).fetch_all(&self.db).await?;
        for receipt in receipts {
            let relative: String = receipt.try_get("relative_path")?;
            let store = root.store.clone();
            let sha = descriptor.sha256.clone();
            let verified = tokio::task::spawn_blocking(move || {
                let mut file = store.saved(&relative).ok()?;
                if verify(&mut file, size, &sha).ok()? {
                    Some(file)
                } else {
                    None
                }
            })
            .await
            .map_err(|_| Error::Internal)?;
            if let Some(_guard) = verified {
                let upload_id = Uuid::new_v4().to_string();
                let receipt_id = Uuid::new_v4().to_string();
                let committed_at = chrono::Utc::now().to_rfc3339();
                let path: String = receipt.try_get("relative_path")?;
                let mut tx = self.db.begin().await?;
                sqlx::query("INSERT INTO uploads(id,root_id,device_id,request_id,descriptor,size,sha256,state,relative_path,receipt_id,created_at,committed_at,received_bytes) VALUES(?,?,?,?,?,?,?,'committed',?,?,?,?,0)")
                    .bind(&upload_id).bind(root.id.to_string()).bind(device).bind(descriptor.upload_request_id.to_string()).bind(&encoded).bind(size as i64).bind(&descriptor.sha256).bind(&path).bind(&receipt_id).bind(&committed_at).bind(&committed_at).execute(&mut *tx).await?;
                sqlx::query("INSERT INTO receipts(id,upload_id,root_id,device_id,sha256,size,relative_path,committed_at) VALUES(?,?,?,?,?,?,?,?)")
                    .bind(&receipt_id).bind(upload_id).bind(root.id.to_string()).bind(device).bind(&descriptor.sha256).bind(size as i64).bind(path).bind(committed_at).execute(&mut *tx).await?;
                tx.commit().await?;
                return self.receipt(&receipt_id).await;
            }
            sqlx::query("UPDATE receipts SET healthy=0 WHERE id=?")
                .bind(receipt.try_get::<String, _>("id")?)
                .execute(&self.db)
                .await?;
        }
        self.reserve_upload(device, root.id, size).await?;
        let id = Uuid::new_v4();
        let receipt_id = Uuid::new_v4();
        let device_id = Uuid::parse_str(device).map_err(|_| Error::Internal)?;
        let date = descriptor
            .captured_at
            .map(|d| d.with_timezone(&chrono::Utc))
            .unwrap_or_else(chrono::Utc::now);
        let path = destination(
            device_id,
            id,
            matches!(descriptor.media_type, MediaType::Photo),
            date,
            &descriptor.original_name,
        );
        sqlx::query("INSERT INTO uploads(id,root_id,device_id,request_id,descriptor,size,sha256,state,relative_path,receipt_id,created_at) VALUES(?,?,?,?,?,?,?,'created',?,?,?)")
            .bind(id.to_string()).bind(root.id.to_string()).bind(device).bind(descriptor.upload_request_id.to_string()).bind(encoded)
            .bind(size as i64).bind(descriptor.sha256).bind(path).bind(receipt_id.to_string()).bind(chrono::Utc::now().to_rfc3339()).execute(&self.db).await?;
        self.touch_upload(id).await?;
        self.upload_status(device, id).await
    }
    pub(crate) async fn upload_status(&self, device: &str, id: Uuid) -> Result<Value> {
        let row = sqlx::query("SELECT * FROM uploads WHERE id=? AND device_id=?")
            .bind(id.to_string())
            .bind(device)
            .fetch_optional(&self.db)
            .await?
            .ok_or(Error::NotFound)?;
        let state: String = row.try_get("state")?;
        if state == "committed" {
            return self.checked_receipt(&row).await;
        }
        Ok(
            json!({"uploadId":id,"state":state,"size":row.try_get::<i64,_>("size")?.to_string(),"receivedBytes":row.try_get::<i64,_>("received_bytes")?.to_string(),"resumable":false}),
        )
    }
    pub(crate) async fn receive(
        &self,
        device: &str,
        id: Uuid,
        length: u64,
        body: Body,
    ) -> Result<Value> {
        self.require_receiving()?;
        let lock = self.upload_lock(id).await;
        let _lock = lock
            .try_lock()
            .map_err(|_| Error::Conflict("UPLOAD_BUSY"))?;
        let _slot = self
            .slots
            .clone()
            .try_acquire_owned()
            .map_err(|_| Error::RateLimited)?;
        let row = sqlx::query("SELECT * FROM uploads WHERE id=? AND device_id=?")
            .bind(id.to_string())
            .bind(device)
            .fetch_optional(&self.db)
            .await?
            .ok_or(Error::NotFound)?;
        let size = row.try_get::<i64, _>("size")? as u64;
        if length != size {
            return Err(Error::BadRequest("LENGTH_MISMATCH"));
        }
        if !matches!(
            row.try_get::<String, _>("state")?.as_str(),
            "created" | "writing" | "failed"
        ) {
            return Err(Error::Conflict("UPLOAD_ALREADY_STAGED"));
        }
        let root = self.active_root().await?;
        if root.id.to_string() != row.try_get::<String, _>("root_id")? {
            return Err(Error::Conflict("DATASET_CHANGED"));
        }
        self.require_device(device).await?;
        let cancellation = self
            .device_cancellations
            .lock()
            .await
            .entry(device.into())
            .or_default()
            .clone();
        if cancellation.is_cancelled() {
            return Err(Error::Unauthorized);
        }
        let file = root.store.reset_upload(id)?;
        sqlx::query("UPDATE uploads SET state='writing',received_bytes=0 WHERE id=?")
            .bind(id.to_string())
            .execute(&self.db)
            .await?;
        self.touch_upload(id).await?;
        self.progress
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(id, 0);
        let _progress = ProgressGuard {
            id,
            registry: self.progress.clone(),
        };
        // Coalesce small HTTP/TLS chunks before dispatching filesystem writes.
        let mut file = BufWriter::with_capacity(256 * 1024, tokio::fs::File::from_std(file));
        let mut stream = body.into_data_stream();
        let mut hash = Sha256::new();
        let mut received = 0_u64;
        let mut checkpoint = 0_u64;
        let result=async {
            loop {
                self.require_receiving()?;
                if cancellation.is_cancelled(){return Err(Error::Unauthorized);}
                let next=tokio::select!{_=cancellation.cancelled()=>return Err(Error::Unauthorized),next=tokio::time::timeout(Duration::from_secs(30),stream.next())=>next.map_err(|_|Error::BadRequest("BODY_TIMEOUT"))?};
                let Some(chunk)=next else {break;};let chunk=chunk.map_err(|_|Error::BadRequest("BODY_INTERRUPTED"))?;
                received=received.checked_add(chunk.len() as u64).ok_or(Error::TooLarge)?;
                self.received_bytes_total.fetch_add(chunk.len() as u64, std::sync::atomic::Ordering::Relaxed);
                if received>size {return Err(Error::BadRequest("LENGTH_MISMATCH"));}
                if lanvault_platform_windows::disk_space(root.store.path())?.available_bytes < self.reserve_bytes.saturating_add(chunk.len() as u64).saturating_add(file.buffer().len() as u64) {return Err(Error::DiskFull);}
                file.write_all(&chunk).await?;hash.update(&chunk);
                self.progress.lock().unwrap_or_else(|e|e.into_inner()).insert(id,received);
                if received-checkpoint>=8*1024*1024 {checkpoint=received;sqlx::query("UPDATE uploads SET received_bytes=? WHERE id=?").bind(received as i64).bind(id.to_string()).execute(&self.db).await?;self.touch_upload(id).await?;}
            }
            if received!=size {return Err(Error::BadRequest("LENGTH_MISMATCH"));}
            if format!("{:x}",hash.finalize())!=row.try_get::<String,_>("sha256")? {return Err(Error::Checksum);}
            file.flush().await?;file.get_ref().sync_all().await?;self.require_device(device).await?;
            Ok(())
        }.await;
        match result {
            Ok(()) => {
                sqlx::query("UPDATE uploads SET state='verified',received_bytes=size WHERE id=?")
                    .bind(id.to_string())
                    .execute(&self.db)
                    .await?;
            }
            Err(error) => {
                let _=sqlx::query("UPDATE uploads SET state=CASE WHEN state='cancelled' THEN 'cancelled' ELSE 'failed' END,received_bytes=? WHERE id=?").bind(received.min(size) as i64).bind(id.to_string()).execute(&self.db).await;
                return Err(error);
            }
        }
        self.upload_status(device, id).await
    }
    pub(crate) async fn complete(&self, device: &str, id: Uuid) -> Result<Value> {
        self.require_receiving()?;
        let lock = self.upload_lock(id).await;
        let _lock = lock.lock().await;
        self.require_device(device).await?;
        let row = sqlx::query("SELECT * FROM uploads WHERE id=? AND device_id=?")
            .bind(id.to_string())
            .bind(device)
            .fetch_optional(&self.db)
            .await?
            .ok_or(Error::NotFound)?;
        if row.try_get::<String, _>("state")? == "committed" {
            return self.checked_receipt(&row).await;
        }
        if !matches!(
            row.try_get::<String, _>("state")?.as_str(),
            "verified" | "committing"
        ) {
            return Err(Error::Conflict("UPLOAD_NOT_VERIFIED"));
        }
        // Hash this file without holding the global archive transaction lock:
        // other files may be created/received/verified in parallel.
        let prepared = self.prepare_commit_row(&row).await?;
        let _operation = self.operation.lock().await;
        self.require_receiving()?;
        self.require_device(device).await?;
        self.commit_prepared_row(row, prepared).await
    }
    async fn checked_receipt(&self, row: &sqlx::sqlite::SqliteRow) -> Result<Value> {
        let root = self.active_root().await?;
        if root.id.to_string() != row.try_get::<String, _>("root_id")? {
            return Err(Error::Conflict("DATASET_CHANGED"));
        }
        let relative: String = row.try_get("relative_path")?;
        let path = relative.clone();
        let size = row.try_get::<i64, _>("size")? as u64;
        let sha: String = row.try_get("sha256")?;
        let store = root.store.clone();
        let guard = tokio::task::spawn_blocking(move || {
            let mut file = store.saved(&path).ok()?;
            if verify(&mut file, size, &sha).ok()? {
                Some(file)
            } else {
                None
            }
        })
        .await
        .map_err(|_| Error::Internal)?;
        if let Some(_guard) = guard {
            return self.receipt(row.try_get("receipt_id")?).await;
        }
        sqlx::query("UPDATE receipts SET healthy=0 WHERE root_id=? AND relative_path=?")
            .bind(root.id.to_string())
            .bind(relative)
            .execute(&self.db)
            .await?;
        Err(Error::Conflict("SAVED_FILE_UNAVAILABLE"))
    }
    async fn commit_row(&self, row: sqlx::sqlite::SqliteRow) -> Result<Value> {
        let prepared = self.prepare_commit_row(&row).await?;
        self.commit_prepared_row(row, prepared).await
    }
    async fn prepare_commit_row(
        &self,
        row: &sqlx::sqlite::SqliteRow,
    ) -> Result<(std::fs::File, bool)> {
        let root = self.active_root().await?;
        if row.try_get::<String, _>("root_id")? != root.id.to_string() {
            return Err(Error::Conflict("DATASET_CHANGED"));
        }
        let id = Uuid::parse_str(row.try_get("id")?).map_err(|_| Error::Internal)?;
        let size = row.try_get::<i64, _>("size")? as u64;
        let sha: String = row.try_get("sha256")?;
        let path: String = row.try_get("relative_path")?;
        let store = root.store.clone();
        let expected = sha.clone();
        let relative = path.clone();
        let prepared = tokio::task::spawn_blocking(move || {
            // Recovery can encounter the rename completed before the DB transaction.
            if let Ok(mut saved) = store.saved(&relative) {
                return if verify(&mut saved, size, &expected)? {
                    Ok((saved, false))
                } else {
                    Err(io::Error::new(
                        io::ErrorKind::AlreadyExists,
                        "destination conflict",
                    ))
                };
            }
            let mut file = store.staged(id)?;
            if !verify(&mut file, size, &expected)? {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "checksum mismatch",
                ));
            }
            Ok((file, true))
        })
        .await
        .map_err(|_| Error::Internal)??;
        Ok(prepared)
    }
    async fn commit_prepared_row(
        &self,
        row: sqlx::sqlite::SqliteRow,
        (staged, needs_rename): (std::fs::File, bool),
    ) -> Result<Value> {
        let root = self.active_root().await?;
        if row.try_get::<String, _>("root_id")? != root.id.to_string() {
            return Err(Error::Conflict("DATASET_CHANGED"));
        }
        let id = Uuid::parse_str(row.try_get("id")?).map_err(|_| Error::Internal)?;
        let size = row.try_get::<i64, _>("size")? as u64;
        let sha: String = row.try_get("sha256")?;
        let path: String = row.try_get("relative_path")?;
        let committed_at = row
            .try_get::<Option<String>, _>("committed_at")?
            .unwrap_or_else(|| chrono::Utc::now().to_rfc3339());
        // Durable intent precedes the rename. No SQLite transaction is kept during file I/O.
        sqlx::query("UPDATE uploads SET state='committing',committed_at=? WHERE id=?")
            .bind(&committed_at)
            .bind(id.to_string())
            .execute(&self.db)
            .await?;
        let _published_guard = if needs_rename {
            let store = root.store.clone();
            let relative = path.clone();
            tokio::task::spawn_blocking(move || {
                store.publish(&staged, &relative)?;
                Ok::<_, io::Error>(staged)
            })
            .await
            .map_err(|_| Error::Internal)??
        } else {
            staged
        };
        let receipt: String = row.try_get("receipt_id")?;
        let mut tx = self.db.begin().await?;
        sqlx::query("INSERT INTO receipts(id,upload_id,root_id,device_id,sha256,size,relative_path,committed_at,healthy) VALUES(?,?,?,?,?,?,?,?,1) ON CONFLICT(id) DO UPDATE SET healthy=1")
            .bind(&receipt).bind(id.to_string()).bind(root.id.to_string()).bind(row.try_get::<String,_>("device_id")?).bind(sha).bind(size as i64).bind(path).bind(&committed_at).execute(&mut *tx).await?;
        sqlx::query("UPDATE uploads SET state='committed',received_bytes=size WHERE id=?")
            .bind(id.to_string())
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM upload_leases WHERE upload_id=?")
            .bind(id.to_string())
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        self.receipt(&receipt).await
    }
    pub(crate) async fn receipt(&self, id: &str) -> Result<Value> {
        let row=sqlx::query("SELECT r.*,u.descriptor FROM receipts r JOIN uploads u ON r.upload_id=u.id WHERE r.id=? AND r.healthy=1").bind(id).fetch_optional(&self.db).await?.ok_or(Error::NotFound)?;
        let descriptor: Value =
            serde_json::from_str(row.try_get("descriptor")?).map_err(|_| Error::Internal)?;
        Ok(
            json!({"state":"committed","receipt":{"receiptId":id,"serverId":self.identity.id,"datasetId":row.try_get::<String,_>("root_id")?,"deviceId":row.try_get::<String,_>("device_id")?,"mediaId":descriptor["mediaId"],"revisionId":descriptor["revisionId"],"sha256":row.try_get::<String,_>("sha256")?,"size":row.try_get::<i64,_>("size")?.to_string(),"relativePath":row.try_get::<String,_>("relative_path")?,"committedAt":row.try_get::<String,_>("committed_at")?}}),
        )
    }
    pub(crate) async fn recover(&self) -> Result<()> {
        let Ok(root) = self.active_root().await else {
            return Ok(());
        };
        sqlx::query("UPDATE uploads SET state='created',received_bytes=0 WHERE state='writing' AND root_id=?").bind(root.id.to_string()).execute(&self.db).await?;
        // A committed upload and receipt are one durable SQLite transaction.
        // Only unfinished intents need replay; re-committing the entire archive
        // rehashes every byte and performs two FULL-sync transactions per file.
        let rows = sqlx::query("SELECT * FROM uploads WHERE root_id=? AND state='committing'")
            .bind(root.id.to_string())
            .fetch_all(&self.db)
            .await?;
        for row in rows {
            let id: String = row.try_get("id")?;
            if self.commit_row(row).await.is_err() {
                sqlx::query("UPDATE receipts SET healthy=0 WHERE upload_id=?")
                    .bind(&id)
                    .execute(&self.db)
                    .await?;
                sqlx::query("UPDATE uploads SET state='failed' WHERE id=?")
                    .bind(id)
                    .execute(&self.db)
                    .await?;
                *self.last_error.lock().unwrap_or_else(|e| e.into_inner()) =
                    Some("RECOVERY_NEEDS_ATTENTION".into());
            }
        }
        // Fast startup audit. Full hashes are still checked by checked_receipt
        // before returning any existing receipt, and by commit_row on publication.
        let files = sqlx::query(
            "SELECT DISTINCT relative_path,size FROM receipts WHERE root_id=? AND healthy=1",
        )
        .bind(root.id.to_string())
        .fetch_all(&self.db)
        .await?;
        let store = root.store.clone();
        let missing = tokio::task::spawn_blocking(move || {
            let mut missing = Vec::new();
            for row in files {
                let path: String = row.try_get("relative_path")?;
                let size = row.try_get::<i64, _>("size")? as u64;
                if !store
                    .saved(&path)
                    .and_then(|file| file.metadata())
                    .is_ok_and(|metadata| metadata.len() == size)
                {
                    missing.push(path);
                }
            }
            Ok::<_, Error>(missing)
        })
        .await
        .map_err(|_| Error::Internal)??;
        for path in missing {
            // Retain committed state so retries get SAVED_FILE_UNAVAILABLE and
            // allocate a new destination instead of overwriting an existing file.
            sqlx::query("UPDATE receipts SET healthy=0 WHERE root_id=? AND relative_path=?")
                .bind(root.id.to_string())
                .bind(path)
                .execute(&self.db)
                .await?;
            *self.last_error.lock().unwrap_or_else(|e| e.into_inner()) =
                Some("RECOVERY_NEEDS_ATTENTION".into());
        }
        Ok(())
    }
}

pub fn can_reserve(free: u64, reserved: u64, size: u64, safety: u64) -> bool {
    reserved
        .checked_add(size)
        .and_then(|v| v.checked_add(safety))
        .is_some_and(|needed| free >= needed)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn disk_accounting_does_not_overflow() {
        assert!(can_reserve(100, 20, 30, 50));
        assert!(!can_reserve(99, 20, 30, 50));
        assert!(!can_reserve(u64::MAX, u64::MAX, 1, 0));
    }
}
