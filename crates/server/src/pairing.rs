use crate::{
    engine::verification_code,
    identity::{random_secret, secret_hash},
    Engine, Error, Result,
};
use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine as _,
};
use lanvault_database::{sqlx, Row};
use serde::Deserialize;
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use uuid::Uuid;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct PairRequest {
    pairing_id: Uuid,
    secret: String,
    client_name: String,
    claim_secret: String,
}

impl Engine {
    pub async fn create_pairing(&self) -> Result<Value> {
        let _operation = self.operation.lock().await;
        let running = self.running.lock().await;
        let run = running
            .as_ref()
            .filter(|r| r.live.load(std::sync::atomic::Ordering::Acquire))
            .ok_or(Error::Unavailable("START_SERVER_FIRST"))?;
        let id = Uuid::new_v4();
        let secret = random_secret()?;
        let expires = chrono::Utc::now().timestamp() + 120;
        let mut tx = self.db.begin().await?;
        sqlx::query(
            "UPDATE pairing SET state='rejected',result=NULL WHERE state IN ('open','pending')",
        )
        .execute(&mut *tx)
        .await?;
        sqlx::query("UPDATE pairing SET result=NULL WHERE result_expires<?")
            .bind(chrono::Utc::now().timestamp())
            .execute(&mut *tx)
            .await?;
        sqlx::query("INSERT INTO pairing(id,secret_hash,expires_at,state) VALUES(?,?,?,'open')")
            .bind(id.to_string())
            .bind(secret_hash(&secret))
            .bind(expires)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        let mut payload = json!({"protocolVersion":1,"serverId":self.identity.id,"hostname":self.identity.hostname,"caCertificate":self.identity.ca_der,"spkiSha256":self.identity.fingerprint,"pairingId":id,"secret":secret,"expiresAt":expires,"addresses":[run.address.ip()],"port":run.address.port()});
        let encoded = serde_json::to_vec(&payload).map_err(|_| Error::Internal)?;
        let code = qrcode::QrCode::new(encoded).map_err(|_| Error::Internal)?;
        let svg = code
            .render::<qrcode::render::svg::Color>()
            .max_dimensions(720, 720)
            .dark_color(qrcode::render::svg::Color("#000000"))
            .light_color(qrcode::render::svg::Color("#ffffff"))
            .build();
        payload["qrDataUrl"] = json!(format!(
            "data:image/svg+xml;base64,{}",
            STANDARD.encode(svg)
        ));
        Ok(payload)
    }
    pub(crate) async fn request_pair(&self, input: PairRequest) -> Result<Value> {
        {
            let mut attempts = self.pairing_attempts.lock().await;
            let now = Instant::now();
            while attempts
                .front()
                .is_some_and(|t| now.duration_since(*t) > Duration::from_secs(60))
            {
                attempts.pop_front();
            }
            if attempts.len() >= 10 {
                return Err(Error::RateLimited);
            }
            attempts.push_back(now);
        }
        if input.client_name.is_empty()
            || input.client_name.chars().count() > 80
            || input.client_name.chars().any(char::is_control)
            || URL_SAFE_NO_PAD
                .decode(&input.claim_secret)
                .map(|v| v.len())
                .ok()
                != Some(32)
            || input.secret.len() > 128
        {
            return Err(Error::BadRequest("INVALID_PAIRING_REQUEST"));
        }
        let _operation = self.operation.lock().await;
        let row = sqlx::query("SELECT * FROM pairing WHERE id=?")
            .bind(input.pairing_id.to_string())
            .fetch_optional(&self.db)
            .await?
            .ok_or(Error::Unauthorized)?;
        if row.try_get::<String, _>("secret_hash")? != secret_hash(&input.secret) {
            return Err(Error::Unauthorized);
        }
        if row.try_get::<i64, _>("expires_at")? < chrono::Utc::now().timestamp() {
            return Err(Error::Expired);
        }
        let state: String = row.try_get("state")?;
        if state == "pending"
            && row.try_get::<Option<String>, _>("claim_hash")?.as_deref()
                == Some(secret_hash(&input.claim_secret).as_str())
        {
            let id: String = row.try_get("request_id")?;
            return Ok(
                json!({"requestId":id,"verificationCode":verification_code(&id),"state":"pending"}),
            );
        }
        if state != "open" {
            return Err(Error::Conflict("PAIRING_CONSUMED"));
        }
        let request_id = Uuid::new_v4().to_string();
        sqlx::query("UPDATE pairing SET state='pending',request_id=?,client_name=?,claim_hash=? WHERE id=? AND state='open'")
            .bind(&request_id).bind(input.client_name).bind(secret_hash(&input.claim_secret)).bind(input.pairing_id.to_string()).execute(&self.db).await?;
        Ok(
            json!({"requestId":request_id,"verificationCode":verification_code(&request_id),"state":"pending"}),
        )
    }
    pub async fn approve_pairing(&self, request_id: String, approve: bool) -> Result<()> {
        let _operation = self.operation.lock().await;
        let row = sqlx::query("SELECT * FROM pairing WHERE request_id=? AND state='pending'")
            .bind(&request_id)
            .fetch_optional(&self.db)
            .await?
            .ok_or(Error::NotFound)?;
        if row.try_get::<i64, _>("expires_at")? < chrono::Utc::now().timestamp() {
            return Err(Error::Expired);
        }
        if !approve {
            sqlx::query("UPDATE pairing SET state='rejected' WHERE request_id=?")
                .bind(request_id)
                .execute(&self.db)
                .await?;
            return Ok(());
        }
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM devices WHERE revoked=0")
            .fetch_one(&self.db)
            .await?;
        if count >= 20 {
            return Err(Error::Conflict("DEVICE_LIMIT"));
        }
        let device_id = Uuid::new_v4().to_string();
        let token = random_secret()?;
        let result = json!({"deviceId":device_id,"token":token,"serverId":self.identity.id});
        let encoded =
            zeroize::Zeroizing::new(serde_json::to_vec(&result).map_err(|_| Error::Internal)?);
        let protected = lanvault_platform_windows::protect(&encoded)?;
        let mut tx = self.db.begin().await?;
        sqlx::query("INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)")
            .bind(&device_id)
            .bind(row.try_get::<String, _>("client_name")?)
            .bind(secret_hash(&token))
            .bind(chrono::Utc::now().to_rfc3339())
            .execute(&mut *tx)
            .await?;
        sqlx::query("UPDATE pairing SET state='approved',result=?,device_id=?,result_expires=? WHERE request_id=?")
            .bind(protected).bind(device_id).bind(chrono::Utc::now().timestamp()+300).bind(request_id).execute(&mut *tx).await?;
        tx.commit().await?;
        Ok(())
    }
    pub(crate) async fn pairing_result(
        &self,
        id: Uuid,
        claim: &str,
        received: bool,
    ) -> Result<Value> {
        if claim.len() > 128 {
            return Err(Error::Unauthorized);
        }
        let row = sqlx::query("SELECT * FROM pairing WHERE request_id=?")
            .bind(id.to_string())
            .fetch_optional(&self.db)
            .await?
            .ok_or(Error::Unauthorized)?;
        if row.try_get::<Option<String>, _>("claim_hash")?.as_deref()
            != Some(secret_hash(claim).as_str())
        {
            return Err(Error::Unauthorized);
        }
        let state: String = row.try_get("state")?;
        if state == "pending"
            && row.try_get::<i64, _>("expires_at")? < chrono::Utc::now().timestamp()
        {
            return Err(Error::Expired);
        }
        if received {
            if !matches!(state.as_str(), "approved" | "received") {
                return Err(Error::Conflict("PAIRING_NOT_APPROVED"));
            }
            sqlx::query("UPDATE pairing SET state='received',result=NULL WHERE request_id=?")
                .bind(id.to_string())
                .execute(&self.db)
                .await?;
            return Ok(json!({"state":"received"}));
        }
        if state != "approved" {
            return Ok(json!({"state":state}));
        }
        if row
            .try_get::<Option<i64>, _>("result_expires")?
            .unwrap_or(0)
            < chrono::Utc::now().timestamp()
        {
            sqlx::query("UPDATE pairing SET result=NULL WHERE request_id=?")
                .bind(id.to_string())
                .execute(&self.db)
                .await?;
            return Err(Error::Expired);
        }
        let encrypted: Vec<u8> = row.try_get("result")?;
        let clear = zeroize::Zeroizing::new(lanvault_platform_windows::unprotect(&encrypted)?);
        let credentials: Value = serde_json::from_slice(&clear).map_err(|_| Error::Internal)?;
        Ok(json!({"state":"approved","credentials":credentials}))
    }
}
