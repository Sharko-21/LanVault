use crate::{identity::Identity, Error, Result};
use lanvault_database::{sqlx, Row, SqlitePool};
use lanvault_platform_windows::{DirectoryGuard, NetworkInterface};
use lanvault_storage::ArchiveRoot;
use serde_json::{json, Value};
use std::{
    collections::{HashMap, VecDeque},
    net::{Ipv4Addr, SocketAddr},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex as StdMutex,
    },
    time::{Duration, Instant},
};
use tokio::{
    net::TcpSocket,
    sync::{Mutex, RwLock, Semaphore},
};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

#[derive(Clone)]
pub(crate) struct ActiveRoot {
    pub id: Uuid,
    pub store: Arc<ArchiveRoot>,
}
pub(crate) struct Running {
    pub cancel: CancellationToken,
    pub live: Arc<AtomicBool>,
    pub address: SocketAddr,
    pub task: tokio::task::JoinHandle<()>,
}

pub struct Engine {
    pub(crate) db: SqlitePool,
    pub(crate) identity: Identity,
    pub(crate) root: RwLock<Option<ActiveRoot>>,
    pub(crate) operation: Mutex<()>,
    pub(crate) running: Mutex<Option<Running>>,
    pub(crate) paused: AtomicBool,
    pub(crate) progress: Arc<StdMutex<HashMap<Uuid, u64>>>,
    pub(crate) received_bytes_total: AtomicU64,
    pub(crate) upload_locks: Mutex<HashMap<Uuid, std::sync::Weak<Mutex<()>>>>,
    pub(crate) slots: Arc<Semaphore>,
    pub(crate) device_cancellations: Mutex<HashMap<String, CancellationToken>>,
    presence_updates: Mutex<HashMap<String, Instant>>,
    pub(crate) pairing_attempts: Mutex<VecDeque<Instant>>,
    pub(crate) last_error: StdMutex<Option<String>>,
    pub(crate) reserve_bytes: u64,
    pub(crate) auto_receiver: AtomicBool,
    _state: DirectoryGuard,
    _instance: std::fs::File,
}

impl Engine {
    pub async fn open(path: PathBuf, reserve_bytes: u64) -> Result<Arc<Self>> {
        let state = tokio::task::spawn_blocking(move || {
            let guard = DirectoryGuard::create(&path).inspect_err(|e| {
                eprintln!("state_init phase=directory win32={:?}", e.raw_os_error())
            })?;
            lanvault_platform_windows::restrict_state_directory(guard.path())?;
            let instance = match guard.create_file("instance.lock") {
                Ok(file) => file,
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {
                    guard.open_file("instance.lock", true)?
                }
                Err(e) => return Err(Error::Io(e)),
            };
            let identity =
                Identity::load(&guard).inspect_err(|_| eprintln!("state_init phase=identity"))?;
            Ok::<_, Error>((guard, identity, instance))
        })
        .await
        .map_err(|_| Error::Internal)??;
        let db = lanvault_database::open(&state.0.path().join("catalog.sqlite")).await?;
        let version: String =
            sqlx::query_scalar("SELECT value FROM metadata WHERE key='schema_version'")
                .fetch_one(&db)
                .await?;
        if version != "1" {
            return Err(Error::Unavailable("UNSUPPORTED_DATABASE_VERSION"));
        }
        let engine = Arc::new(Self {
            db,
            identity: state.1,
            root: RwLock::new(None),
            operation: Mutex::new(()),
            running: Mutex::new(None),
            paused: AtomicBool::new(false),
            progress: Arc::new(StdMutex::new(HashMap::new())),
            received_bytes_total: AtomicU64::new(0),
            upload_locks: Mutex::new(HashMap::new()),
            slots: Arc::new(Semaphore::new(crate::MAX_CONCURRENT_UPLOADS)),
            device_cancellations: Mutex::new(HashMap::new()),
            presence_updates: Mutex::new(HashMap::new()),
            pairing_attempts: Mutex::new(VecDeque::new()),
            last_error: StdMutex::new(None),
            reserve_bytes,
            auto_receiver: AtomicBool::new(false),
            _state: state.0,
            _instance: state.2,
        });
        if let Some(row) = sqlx::query("SELECT id,path,identity FROM roots WHERE active=1")
            .fetch_optional(&engine.db)
            .await?
        {
            let id = Uuid::parse_str(row.try_get("id")?).map_err(|_| Error::Internal)?;
            let path = PathBuf::from(row.try_get::<String, _>("path")?);
            let expected: String = row.try_get("identity")?;
            let restored = tokio::task::spawn_blocking(move || {
                let store = ArchiveRoot::open(&path)?;
                if store.identity != expected {
                    return Err(std::io::Error::other("root identity changed"));
                }
                store.ensure_marker(id)?;
                Ok::<_, std::io::Error>(store)
            })
            .await
            .map_err(|_| Error::Internal)?;
            match restored {
                Ok(store) => {
                    *engine.root.write().await = Some(ActiveRoot {
                        id,
                        store: Arc::new(store),
                    })
                }
                Err(_) => {
                    *engine.last_error.lock().unwrap_or_else(|e| e.into_inner()) =
                        Some("ROOT_UNAVAILABLE".into())
                }
            }
        }
        engine.recover().await?;
        let enabled: Option<String> =
            sqlx::query_scalar("SELECT value FROM metadata WHERE key='auto_receiver'")
                .fetch_optional(&engine.db)
                .await?;
        engine
            .auto_receiver
            .store(enabled.as_deref() == Some("1"), Ordering::Release);
        Ok(engine)
    }

    pub async fn set_folder(&self, path: PathBuf) -> Result<Value> {
        let _operation = self.operation.lock().await;
        if self.is_running().await
            || !self
                .progress
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .is_empty()
        {
            return Err(Error::Conflict("STOP_SERVER_FIRST"));
        }
        let store = tokio::task::spawn_blocking(move || ArchiveRoot::open(&path))
            .await
            .map_err(|_| Error::Internal)??;
        let existing: Option<String> = sqlx::query_scalar("SELECT id FROM roots WHERE identity=?")
            .bind(&store.identity)
            .fetch_optional(&self.db)
            .await?;
        let id = match existing {
            Some(id) => Uuid::parse_str(&id).map_err(|_| Error::Internal)?,
            None => Uuid::new_v4(),
        };
        sqlx::query("INSERT INTO roots(id,path,identity,active) VALUES(?,?,?,0) ON CONFLICT(id) DO UPDATE SET path=excluded.path")
            .bind(id.to_string()).bind(store.path().to_string_lossy().as_ref()).bind(&store.identity).execute(&self.db).await?;
        store.ensure_marker(id)?;
        let mut tx = self.db.begin().await?;
        sqlx::query("UPDATE roots SET active=0")
            .execute(&mut *tx)
            .await?;
        sqlx::query("UPDATE roots SET active=1 WHERE id=?")
            .bind(id.to_string())
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        *self.root.write().await = Some(ActiveRoot {
            id,
            store: Arc::new(store),
        });
        *self.last_error.lock().unwrap_or_else(|e| e.into_inner()) = None;
        self.recover().await?;
        self.folder_info().await
    }
    pub(crate) async fn active_root(&self) -> Result<ActiveRoot> {
        self.root
            .read()
            .await
            .clone()
            .ok_or(Error::Unavailable("ROOT_NOT_CONFIGURED"))
    }
    pub async fn folder_info(&self) -> Result<Value> {
        let root = self.active_root().await?;
        let id = root.id;
        let info = tokio::task::spawn_blocking(move || root.store.space())
            .await
            .map_err(|_| Error::Internal)??;
        Ok(
            json!({"datasetId":id,"path":info.path,"availableBytes":info.available_bytes,"totalBytes":info.total_bytes}),
        )
    }
    pub async fn interfaces(&self) -> Result<Vec<NetworkInterface>> {
        tokio::task::spawn_blocking(lanvault_platform_windows::network_interfaces)
            .await
            .map_err(|_| Error::Internal)?
            .map_err(|_| Error::Unavailable("NETWORK_POLICY_UNAVAILABLE"))
    }
    pub async fn remember_receiver(&self, interface_id: &str) -> Result<()> {
        let port = self
            .running
            .lock()
            .await
            .as_ref()
            .ok_or(Error::Unavailable("START_SERVER_FIRST"))?
            .address
            .port();
        let adapter = interface_id
            .split('@')
            .next()
            .ok_or(Error::BadRequest("INVALID_INTERFACE"))?;
        let mut tx = self.db.begin().await?;
        sqlx::query("INSERT INTO metadata(key,value) VALUES('receiver_adapter',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").bind(adapter).execute(&mut *tx).await?;
        sqlx::query("INSERT INTO metadata(key,value) VALUES('auto_receiver','1') ON CONFLICT(key) DO UPDATE SET value='1'").execute(&mut *tx).await?;
        sqlx::query("INSERT INTO metadata(key,value) VALUES('receiver_port',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").bind(port.to_string()).execute(&mut *tx).await?;
        tx.commit().await?;
        self.auto_receiver.store(true, Ordering::Release);
        Ok(())
    }
    pub async fn probe_discovery(&self) -> Result<Value> {
        let address = self
            .running
            .lock()
            .await
            .as_ref()
            .filter(|r| r.live.load(Ordering::Acquire))
            .ok_or(Error::Unavailable("START_SERVER_FIRST"))?
            .address
            .ip();
        let std::net::IpAddr::V4(address) = address else {
            return Err(Error::Unavailable("PRIVATE_PHYSICAL_NETWORK_REQUIRED"));
        };
        if !self
            .interfaces()
            .await?
            .iter()
            .any(|n| n.allowed() && n.address == address)
        {
            return Err(Error::Unavailable("PRIVATE_PHYSICAL_NETWORK_REQUIRED"));
        }
        let id = self.identity.id;
        let found = tokio::task::spawn_blocking(move || crate::discovery::probe(id, address))
            .await
            .map_err(|_| Error::Internal)?
            .map_err(|_| Error::Unavailable("DISCOVERY_UNAVAILABLE"))?;
        Ok(json!({"found":found}))
    }
    pub async fn disable_auto_receiver(&self) -> Result<()> {
        self.auto_receiver.store(false, Ordering::Release);
        sqlx::query("INSERT INTO metadata(key,value) VALUES('auto_receiver','0') ON CONFLICT(key) DO UPDATE SET value='0'").execute(&self.db).await?;
        self.stop().await
    }
    pub fn suspend_auto_receiver(&self) {
        self.auto_receiver.store(false, Ordering::Release);
    }
    pub async fn restore_receiver(self: &Arc<Self>) -> Result<()> {
        if !self.auto_receiver.load(Ordering::Acquire) || self.is_running().await {
            return Ok(());
        }
        let adapter: Option<String> =
            sqlx::query_scalar("SELECT value FROM metadata WHERE key='receiver_adapter'")
                .fetch_optional(&self.db)
                .await?;
        let Some(adapter) = adapter else {
            return Ok(());
        };
        let interface = self
            .interfaces()
            .await?
            .into_iter()
            .find(|n| n.allowed() && n.id.split('@').next() == Some(adapter.as_str()))
            .ok_or(Error::Unavailable("PRIVATE_PHYSICAL_NETWORK_REQUIRED"))?;
        let port: Option<String> =
            sqlx::query_scalar("SELECT value FROM metadata WHERE key='receiver_port'")
                .fetch_optional(&self.db)
                .await?;
        let port = port
            .and_then(|value| value.parse::<u16>().ok())
            .filter(|port| *port >= 1024)
            .unwrap_or(43827);
        self.stop().await?;
        self.start(interface.id, port).await?;
        if !self.auto_receiver.load(Ordering::Acquire) {
            self.stop().await?;
        }
        Ok(())
    }
    pub async fn start(self: &Arc<Self>, interface_id: String, port: u16) -> Result<Value> {
        let interface = self
            .interfaces()
            .await?
            .into_iter()
            .find(|n| n.id == interface_id && n.allowed())
            .ok_or(Error::Unavailable("PRIVATE_PHYSICAL_NETWORK_REQUIRED"))?;
        if port < 1024 {
            return Err(Error::BadRequest("INVALID_PORT"));
        }
        self.launch(interface.address, port, Some(interface)).await
    }
    /// Loopback only. Used by the test client and local diagnostics; never opens LAN.
    pub async fn start_loopback(self: &Arc<Self>, port: u16) -> Result<Value> {
        self.launch(Ipv4Addr::LOCALHOST, port, None).await
    }
    async fn launch(
        self: &Arc<Self>,
        address: Ipv4Addr,
        port: u16,
        network: Option<NetworkInterface>,
    ) -> Result<Value> {
        let _operation = self.operation.lock().await;
        self.active_root().await?;
        if self.is_running().await {
            return Err(Error::Conflict("ALREADY_RUNNING"));
        }
        self.recover().await?;
        let socket = TcpSocket::new_v4()?;
        if let Some(policy) = &network {
            lanvault_platform_windows::pin_tcp_interface(&socket, policy.index)?;
        }
        socket.bind(SocketAddr::from((address, port)))?;
        let listener = socket.listen(128)?;
        let address = listener.local_addr()?;
        let advertisement = network.as_ref().and_then(|_| {
            crate::discovery::Advertisement::start(
                self.identity.id,
                match address.ip() {
                    std::net::IpAddr::V4(ip) => ip,
                    _ => return None,
                },
                address.port(),
            )
            .ok()
        });
        let cancel = CancellationToken::new();
        let live = Arc::new(AtomicBool::new(true));
        let this = self.clone();
        let task_cancel = cancel.clone();
        let task_live = live.clone();
        let task = tokio::spawn(async move {
            let _advertisement = advertisement;
            crate::http::serve(this, listener, task_cancel, network).await;
            task_live.store(false, Ordering::Release);
        });
        *self.running.lock().await = Some(Running {
            cancel,
            live,
            address,
            task,
        });
        Ok(json!({"address":address.ip(),"port":address.port(),"hostname":self.identity.hostname}))
    }
    pub async fn is_running(&self) -> bool {
        self.running
            .lock()
            .await
            .as_ref()
            .is_some_and(|r| r.live.load(Ordering::Acquire))
    }
    pub async fn stop(&self) -> Result<()> {
        if let Some(mut run) = self.running.lock().await.take() {
            run.cancel.cancel();
            run.live.store(false, Ordering::Release);
            if tokio::time::timeout(Duration::from_secs(6), &mut run.task)
                .await
                .is_err()
            {
                run.task.abort();
                let _ = run.task.await;
            }
        }
        self.cancel_pairing().await?;
        for _ in 0..100 {
            if self
                .progress
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .is_empty()
            {
                return Ok(());
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        Err(Error::Conflict("TRANSFER_STOPPING"))
    }
    pub fn set_paused(&self, value: bool) {
        self.paused.store(value, Ordering::Release);
    }
    pub fn paused(&self) -> bool {
        self.paused.load(Ordering::Acquire)
    }
    pub(crate) fn require_receiving(&self) -> Result<()> {
        if self.paused() {
            Err(Error::Paused)
        } else {
            Ok(())
        }
    }
    pub(crate) async fn authorize(&self, token: &str) -> Result<String> {
        if token.len() > 128 || token.len() < 40 {
            return Err(Error::Unauthorized);
        }
        let id: Option<String> =
            sqlx::query_scalar("SELECT id FROM devices WHERE token_hash=? AND revoked=0")
                .bind(crate::identity::secret_hash(token))
                .fetch_optional(&self.db)
                .await?;
        let id = id.ok_or(Error::Unauthorized)?;
        // Authorization/revocation is queried above on every request. Only the
        // informational presence timestamp is throttled to avoid a FULL-sync
        // SQLite write for every upload/create/complete request.
        let mut updates = self.presence_updates.lock().await;
        if updates
            .get(&id)
            .is_none_or(|at| at.elapsed() >= Duration::from_secs(60))
        {
            sqlx::query("UPDATE devices SET last_seen=? WHERE id=?")
                .bind(chrono::Utc::now().to_rfc3339())
                .bind(&id)
                .execute(&self.db)
                .await?;
            updates.insert(id.clone(), Instant::now());
        }
        Ok(id)
    }
    pub(crate) async fn require_device(&self, id: &str) -> Result<()> {
        let count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM devices WHERE id=? AND revoked=0")
                .bind(id)
                .fetch_one(&self.db)
                .await?;
        if count != 1 {
            return Err(Error::Unauthorized);
        }
        Ok(())
    }
    pub async fn revoke(&self, id: String) -> Result<()> {
        let _operation = self.operation.lock().await;
        sqlx::query("UPDATE devices SET revoked=1 WHERE id=?")
            .bind(&id)
            .execute(&self.db)
            .await?;
        self.device_cancellations
            .lock()
            .await
            .entry(id.clone())
            .or_default()
            .cancel();
        sqlx::query("UPDATE uploads SET state='cancelled' WHERE device_id=? AND state NOT IN ('committed','committing')").bind(&id).execute(&self.db).await?;
        sqlx::query("UPDATE pairing SET result=NULL,state='rejected' WHERE device_id=?")
            .bind(id)
            .execute(&self.db)
            .await?;
        Ok(())
    }
    pub(crate) async fn upload_lock(&self, id: Uuid) -> Arc<Mutex<()>> {
        let mut locks = self.upload_locks.lock().await;
        locks.retain(|_, v| v.strong_count() > 0);
        if let Some(lock) = locks.get(&id).and_then(std::sync::Weak::upgrade) {
            return lock;
        }
        let lock = Arc::new(Mutex::new(()));
        locks.insert(id, Arc::downgrade(&lock));
        lock
    }
    pub async fn cancel_pairing(&self) -> Result<()> {
        sqlx::query(
            "UPDATE pairing SET state='rejected',result=NULL WHERE state IN ('open','pending')",
        )
        .execute(&self.db)
        .await?;
        Ok(())
    }
    pub async fn snapshot(&self) -> Result<Value> {
        let folder = if self.root.read().await.is_some() {
            self.folder_info().await.ok()
        } else {
            None
        };
        let active_id = self
            .root
            .read()
            .await
            .as_ref()
            .map(|r| r.id.to_string())
            .unwrap_or_default();
        let stats=sqlx::query("SELECT COUNT(*) AS count,COALESCE(SUM(size),0) AS bytes FROM (SELECT MAX(size) AS size FROM receipts WHERE root_id=? AND healthy=1 GROUP BY relative_path)").bind(&active_id).fetch_one(&self.db).await?;
        let rows=sqlx::query("SELECT id,name,revoked,created_at,last_seen FROM devices ORDER BY created_at DESC LIMIT 50").fetch_all(&self.db).await?;
        let devices=rows.into_iter().map(|r|Ok(json!({"id":r.try_get::<String,_>("id")?,"name":r.try_get::<String,_>("name")?,"revoked":r.try_get::<i64,_>("revoked")?!=0,"createdAt":r.try_get::<String,_>("created_at")?,"lastSeen":r.try_get::<Option<String>,_>("last_seen")?}))).collect::<Result<Vec<_>>>()?;
        let rows=sqlx::query("SELECT r.*,u.descriptor FROM receipts r JOIN uploads u ON u.id=r.upload_id ORDER BY r.committed_at DESC LIMIT 30").fetch_all(&self.db).await?;
        let history=rows.into_iter().map(|r|{
            let descriptor:Value=serde_json::from_str(r.try_get("descriptor")?).map_err(|_|Error::Internal)?;
            Ok(json!({"id":r.try_get::<String,_>("id")?,"name":descriptor["originalName"],"size":r.try_get::<i64,_>("size")?.to_string(),"path":r.try_get::<String,_>("relative_path")?,"committedAt":r.try_get::<String,_>("committed_at")?,"healthy":r.try_get::<i64,_>("healthy")?!=0}))
        }).collect::<Result<Vec<_>>>()?;
        let pending=sqlx::query("SELECT request_id,client_name,expires_at FROM pairing WHERE state='pending' AND expires_at>? LIMIT 1").bind(chrono::Utc::now().timestamp()).fetch_optional(&self.db).await?;
        let pending = match pending {
            Some(row) => {
                let id: String = row.try_get("request_id")?;
                Some(
                    json!({"id":id,"name":row.try_get::<String,_>("client_name")?,"verificationCode":verification_code(&id),"expiresAt":row.try_get::<i64,_>("expires_at")?}),
                )
            }
            None => None,
        };
        // Clone before awaiting SQLite; never hold the progress mutex across I/O.
        let progress = self
            .progress
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        let mut active_uploads = Vec::with_capacity(progress.len());
        for (id, bytes) in progress {
            let descriptor: Option<String> =
                sqlx::query_scalar("SELECT descriptor FROM uploads WHERE id=?")
                    .bind(id.to_string())
                    .fetch_optional(&self.db)
                    .await?;
            let descriptor: Value = descriptor
                .as_deref()
                .and_then(|s| serde_json::from_str(s).ok())
                .unwrap_or(Value::Null);
            active_uploads.push(json!({"id":id,"receivedBytes":bytes.to_string(),"name":descriptor["originalName"],"size":descriptor["size"]}));
        }
        active_uploads.sort_by_key(|value| value["id"].as_str().unwrap_or_default().to_owned());
        let running = self.running.lock().await;
        Ok(
            json!({"appVersion":env!("CARGO_PKG_VERSION"),"protocolVersion":1,"platform":std::env::consts::OS,"serverId":self.identity.id,"hostname":self.identity.hostname,"fingerprint":self.identity.fingerprint,
            "serverListening":running.as_ref().is_some_and(|r|r.live.load(Ordering::Acquire)),"bindAddress":running.as_ref().map(|r|r.address.to_string()),"paused":self.paused(),"folder":folder,"devices":devices,"pendingPairing":pending,"history":history,
            "completedFiles":stats.try_get::<i64,_>("count")?,"completedBytes":stats.try_get::<i64,_>("bytes")?.to_string(),"receivedBytesTotal":self.received_bytes_total.load(Ordering::Relaxed).to_string(),"activeUploads":active_uploads,"lastError":self.last_error.lock().unwrap_or_else(|e|e.into_inner()).clone()}),
        )
    }
}

pub(crate) fn verification_code(id: &str) -> String {
    crate::identity::secret_hash(id)
        .chars()
        .take(6)
        .collect::<String>()
        .to_uppercase()
}
