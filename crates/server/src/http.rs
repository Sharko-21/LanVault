use crate::{pairing::PairRequest, Engine, Error, Result};
use axum::{
    body::Body,
    extract::{DefaultBodyLimit, Path, Request, State},
    http::{header, HeaderMap},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post, put},
    Json, Router,
};
use hyper_util::{
    rt::{TokioIo, TokioTimer},
    service::TowerToHyperService,
};
use lanvault_platform_windows::NetworkInterface;
use lanvault_protocol::UploadDescriptor;
use serde_json::{json, Value};
use std::{
    net::{IpAddr, Ipv4Addr},
    sync::Arc,
    time::Duration,
};
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

fn bearer(headers: &HeaderMap) -> Result<&str> {
    headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or(Error::Unauthorized)
}
fn claim(headers: &HeaderMap) -> Result<&str> {
    headers
        .get("x-pairing-claim")
        .and_then(|v| v.to_str().ok())
        .ok_or(Error::Unauthorized)
}

pub(crate) fn router(engine: Arc<Engine>) -> Router {
    Router::new()
        .route("/v1/pairing/requests", post(pair))
        .route("/v1/pairing/requests/{id}", get(pair_result))
        .route(
            "/v1/pairing/requests/{id}/confirm-received",
            post(pair_received),
        )
        .route("/v1/server", get(server_info))
        .route("/v1/datasets/current", get(dataset))
        .route("/v1/uploads", post(create))
        .route("/v1/uploads/{id}", get(status).delete(cancel))
        .route("/v1/uploads/{id}/content", put(content))
        .route("/v1/uploads/{id}/complete", post(complete))
        .layer(DefaultBodyLimit::max(16 * 1024))
        .layer(middleware::from_fn(security_headers))
        .with_state(engine)
}

async fn security_headers(request: Request, next: Next) -> Response {
    if request.headers().contains_key(header::ORIGIN) {
        return Error::Unauthorized.into_response();
    }
    let mut response = next.run(request).await;
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        "no-store"
            .parse()
            .unwrap_or_else(|_| header::HeaderValue::from_static("no-store")),
    );
    response.headers_mut().insert(
        "x-content-type-options",
        header::HeaderValue::from_static("nosniff"),
    );
    if response.status().as_u16() == 429 {
        response
            .headers_mut()
            .insert(header::RETRY_AFTER, header::HeaderValue::from_static("60"));
    }
    response
}
async fn pair(State(e): State<Arc<Engine>>, Json(input): Json<PairRequest>) -> Result<Json<Value>> {
    Ok(Json(e.request_pair(input).await?))
}
async fn pair_result(
    State(e): State<Arc<Engine>>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
) -> Result<Json<Value>> {
    Ok(Json(e.pairing_result(id, claim(&headers)?, false).await?))
}
async fn pair_received(
    State(e): State<Arc<Engine>>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
) -> Result<Json<Value>> {
    Ok(Json(e.pairing_result(id, claim(&headers)?, true).await?))
}
async fn server_info(State(e): State<Arc<Engine>>, headers: HeaderMap) -> Result<Json<Value>> {
    e.authorize(bearer(&headers)?).await?;
    Ok(Json(
        json!({"serverId":e.identity.id,"protocolVersion":1,"paused":e.paused(),"capabilities":{"resumable":false,"maxFileBytes":"1099511627776","maxConcurrentUploads":crate::MAX_CONCURRENT_UPLOADS}}),
    ))
}
async fn dataset(State(e): State<Arc<Engine>>, headers: HeaderMap) -> Result<Json<Value>> {
    e.authorize(bearer(&headers)?).await?;
    let root = e.active_root().await?;
    Ok(Json(
        json!({"datasetId":root.id,"availableBytes":root.store.space()?.available_bytes}),
    ))
}
async fn create(
    State(e): State<Arc<Engine>>,
    headers: HeaderMap,
    Json(descriptor): Json<UploadDescriptor>,
) -> Result<Json<Value>> {
    let device = e.authorize(bearer(&headers)?).await?;
    Ok(Json(e.create_upload(&device, descriptor).await?))
}
async fn status(
    State(e): State<Arc<Engine>>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
) -> Result<Json<Value>> {
    let device = e.authorize(bearer(&headers)?).await?;
    Ok(Json(e.upload_status(&device, id).await?))
}
async fn content(
    State(e): State<Arc<Engine>>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
    body: Body,
) -> Result<Json<Value>> {
    let device = e.authorize(bearer(&headers)?).await?;
    if headers.contains_key(header::CONTENT_ENCODING) {
        return Err(Error::BadRequest("CONTENT_ENCODING_NOT_SUPPORTED"));
    }
    let length = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok())
        .ok_or(Error::BadRequest("CONTENT_LENGTH_REQUIRED"))?;
    Ok(Json(e.receive(&device, id, length, body).await?))
}
async fn cancel(
    State(e): State<Arc<Engine>>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
) -> Result<Json<Value>> {
    let device = e.authorize(bearer(&headers)?).await?;
    Ok(Json(e.cancel_upload(&device, id).await?))
}
async fn complete(
    State(e): State<Arc<Engine>>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
) -> Result<Json<Value>> {
    let device = e.authorize(bearer(&headers)?).await?;
    Ok(Json(e.complete(&device, id).await?))
}

pub(crate) async fn serve(
    engine: Arc<Engine>,
    listener: TcpListener,
    cancel: CancellationToken,
    network: Option<NetworkInterface>,
) {
    let acceptor = TlsAcceptor::from(engine.identity.tls.clone());
    let connections = Arc::new(tokio::sync::Semaphore::new(32));
    let mut monitor = tokio::time::interval(Duration::from_secs(5));
    monitor.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut tasks = tokio::task::JoinSet::new();
    loop {
        tokio::select! {
            _=cancel.cancelled()=>break,
            Some(_)=tasks.join_next(),if !tasks.is_empty()=>{},
            _=monitor.tick(),if network.is_some()=>{
                let approved=network.as_ref();
                let available=engine.interfaces().await.ok();
                let valid=available.as_ref().is_some_and(|all|all.iter().any(|item|Some(item)==approved && item.allowed()));
                if !valid {*engine.last_error.lock().unwrap_or_else(|e|e.into_inner())=Some("NETWORK_CHANGED".into());cancel.cancel();break;}
            },
            accepted=listener.accept()=>{
                let Ok((stream,peer))=accepted else {break;};
                let allowed=match (network.as_ref(),peer.ip()) { (Some(policy),IpAddr::V4(ip))=>policy.allows_peer(ip),(None,IpAddr::V4(ip))=>ip==Ipv4Addr::LOCALHOST,_=>false };
                if !allowed {continue;}
                if let Some(policy) = &network {
                    if lanvault_platform_windows::pin_tcp_interface(&stream, policy.index).is_err() {continue;}
                }
                let Ok(slot)=connections.clone().try_acquire_owned() else {continue;};
                let acceptor=acceptor.clone();let router=router(engine.clone());let shutdown=cancel.clone();
                tasks.spawn(async move{
                    let _slot=slot;
                    let tls=tokio::select!{_=shutdown.cancelled()=>return,handshake=tokio::time::timeout(Duration::from_secs(5),acceptor.accept(stream))=>match handshake {Ok(Ok(tls))=>tls,_=>return}};
                    let service=TowerToHyperService::new(router);
                    let mut builder=hyper::server::conn::http1::Builder::new();
                    builder.timer(TokioTimer::new()).header_read_timeout(Duration::from_secs(10)).max_buf_size(32768);
                    tokio::select!{_=shutdown.cancelled()=>{},_=builder.serve_connection(TokioIo::new(tls),service)=>{}}
                });
            }
        }
    }
    cancel.cancel();
    tasks.shutdown().await;
}
