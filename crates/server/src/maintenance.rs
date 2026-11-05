use crate::{uploads::can_reserve, Engine, Error, Result};
use lanvault_database::{sqlx, Row};
use serde_json::{json, Value};
use uuid::Uuid;

const LEASE_SECONDS: i64 = 24 * 60 * 60;
const PENDING_PER_DEVICE: i64 = 16;
const PENDING_TOTAL: i64 = 320; // 20 trusted devices * 16; one cannot monopolize the queue.

impl Engine {
    pub(crate) async fn touch_upload(&self, id: Uuid) -> Result<()> {
        sqlx::query("INSERT INTO upload_leases(upload_id,touched_at) VALUES(?,?) ON CONFLICT(upload_id) DO UPDATE SET touched_at=excluded.touched_at")
            .bind(id.to_string()).bind(chrono::Utc::now().timestamp()).execute(&self.db).await?;
        Ok(())
    }
    // Caller holds operation. Per-upload locks are only tried, never awaited while holding it.
    pub(crate) async fn sweep_uploads_locked(&self) -> Result<usize> {
        let root = self.active_root().await?;
        let cutoff = chrono::Utc::now().timestamp() - LEASE_SECONDS;
        let rows = sqlx::query("SELECT u.id,u.root_id FROM uploads u JOIN devices d ON d.id=u.device_id LEFT JOIN upload_leases l ON l.upload_id=u.id WHERE u.state IN ('created','writing','verified','failed','cancelled') AND (COALESCE(l.touched_at,unixepoch(u.created_at),0)<? OR (u.state<>'cancelled' AND (d.revoked=1 OR u.root_id<>?))) ORDER BY COALESCE(l.touched_at,unixepoch(u.created_at),0) LIMIT 320")
            .bind(cutoff).bind(root.id.to_string()).fetch_all(&self.db).await?;
        let mut cleaned = 0;
        for row in rows {
            let id = Uuid::parse_str(row.try_get("id")?).map_err(|_| Error::Internal)?;
            let lock = self.upload_lock(id).await;
            let Ok(_guard) = lock.try_lock() else {
                continue;
            };
            // Keep the idempotency tombstone so an old phone can retry the same request ID.
            sqlx::query("UPDATE uploads SET state='cancelled',received_bytes=0 WHERE id=? AND state NOT IN ('committed','committing')")
                .bind(id.to_string()).execute(&self.db).await?;
            if row.try_get::<String, _>("root_id")? == root.id.to_string() {
                let store = root.store.clone();
                if tokio::task::spawn_blocking(move || store.discard_staged(id))
                    .await
                    .map_err(|_| Error::Internal)?
                    .is_ok()
                {
                    // Move an already cleaned tombstone out of the next sweep's batch.
                    self.touch_upload(id).await?;
                    cleaned += 1;
                }
            } else {
                self.touch_upload(id).await?;
            }
        }
        Ok(cleaned)
    }
    pub async fn sweep_uploads(&self) -> Result<usize> {
        let _operation = self.operation.lock().await;
        self.sweep_uploads_locked().await
    }
    pub(crate) async fn reserve_upload(&self, device: &str, root: Uuid, size: u64) -> Result<()> {
        let own: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM uploads WHERE device_id=? AND state IN ('created','writing','verified','committing')")
            .bind(device).fetch_one(&self.db).await?;
        let total: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM uploads WHERE state IN ('created','writing','verified','committing')").fetch_one(&self.db).await?;
        if own >= PENDING_PER_DEVICE || total >= PENDING_TOTAL {
            return Err(Error::RateLimited);
        }
        let reserved:i64=sqlx::query_scalar("SELECT COALESCE(SUM(MAX(0,size-received_bytes)),0) FROM uploads WHERE root_id=? AND state IN ('created','writing','verified','committing')")
            .bind(root.to_string()).fetch_one(&self.db).await?;
        let store = self.active_root().await?;
        if !can_reserve(
            store
                .store
                .space()?
                .available_bytes
                .parse()
                .map_err(|_| Error::Internal)?,
            reserved as u64,
            size,
            self.reserve_bytes,
        ) {
            return Err(Error::DiskFull);
        }
        Ok(())
    }
    pub(crate) async fn cancel_upload(&self, device: &str, id: Uuid) -> Result<Value> {
        let owned: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM uploads WHERE id=? AND device_id=?")
                .bind(id.to_string())
                .bind(device)
                .fetch_one(&self.db)
                .await?;
        if owned == 0 {
            return Err(Error::NotFound);
        }
        let lock = self.upload_lock(id).await;
        let _guard = lock
            .try_lock()
            .map_err(|_| Error::Conflict("UPLOAD_BUSY"))?;
        let _operation = self.operation.lock().await;
        self.require_device(device).await?;
        let row = sqlx::query("SELECT state,root_id FROM uploads WHERE id=? AND device_id=?")
            .bind(id.to_string())
            .bind(device)
            .fetch_optional(&self.db)
            .await?
            .ok_or(Error::NotFound)?;
        if matches!(
            row.try_get::<String, _>("state")?.as_str(),
            "committed" | "committing"
        ) {
            return Err(Error::Conflict("UPLOAD_ALREADY_COMMITTED"));
        }
        sqlx::query("UPDATE uploads SET state='cancelled',received_bytes=0 WHERE id=?")
            .bind(id.to_string())
            .execute(&self.db)
            .await?;
        if let Ok(root) = self.active_root().await {
            if root.id.to_string() == row.try_get::<String, _>("root_id")? {
                tokio::task::spawn_blocking(move || root.store.discard_staged(id))
                    .await
                    .map_err(|_| Error::Internal)??;
            }
        }
        self.touch_upload(id).await?;
        Ok(json!({"state":"cancelled","uploadId":id}))
    }
}
