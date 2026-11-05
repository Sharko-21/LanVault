use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde::Serialize;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    BadRequest(&'static str),
    #[error("authorization required")]
    Unauthorized,
    #[error("{0}")]
    Conflict(&'static str),
    #[error("not found")]
    NotFound,
    #[error("request expired")]
    Expired,
    #[error("not enough free space")]
    DiskFull,
    #[error("server paused")]
    Paused,
    #[error("rate limited")]
    RateLimited,
    #[error("checksum mismatch")]
    Checksum,
    #[error("upload too large")]
    TooLarge,
    #[error("{0}")]
    Unavailable(&'static str),
    #[error("storage operation failed")]
    Io(#[from] std::io::Error),
    #[error("database operation failed")]
    Database(#[from] lanvault_database::sqlx::Error),
    #[error("internal operation failed")]
    Internal,
}

#[derive(Serialize)]
struct ErrorBody {
    code: &'static str,
    message: &'static str,
}

impl IntoResponse for Error {
    fn into_response(self) -> Response {
        // Local diagnostics contain codes only: never paths, tokens or request bodies.
        if let Self::Io(ref e) = self {
            eprintln!(
                "storage_error kind={:?} win32={:?}",
                e.kind(),
                e.raw_os_error()
            );
        }
        if let Self::Database(ref e) = self {
            eprintln!(
                "database_error code={:?}",
                e.as_database_error()
                    .and_then(|v| v.code())
                    .map(|v| v.into_owned())
            );
        }
        let (status, code, message) = match self {
            Self::BadRequest(code) => (StatusCode::BAD_REQUEST, code, "Некорректный запрос."),
            Self::Unauthorized => (
                StatusCode::UNAUTHORIZED,
                "UNAUTHORIZED",
                "Требуется действующее доверие устройства.",
            ),
            Self::Conflict(code) => (
                StatusCode::CONFLICT,
                code,
                "Состояние изменилось. Запросите его повторно.",
            ),
            Self::NotFound => (StatusCode::NOT_FOUND, "NOT_FOUND", "Объект не найден."),
            Self::Expired => (StatusCode::GONE, "EXPIRED", "Срок запроса истёк."),
            Self::DiskFull => (
                StatusCode::INSUFFICIENT_STORAGE,
                "INSUFFICIENT_STORAGE",
                "Недостаточно места на диске.",
            ),
            Self::Paused => (StatusCode::LOCKED, "SERVER_PAUSED", "Приём приостановлен."),
            Self::RateLimited => (
                StatusCode::TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Повторите запрос позже.",
            ),
            Self::Checksum => (
                StatusCode::UNPROCESSABLE_ENTITY,
                "CHECKSUM_MISMATCH",
                "Контрольная сумма не совпала.",
            ),
            Self::TooLarge => (
                StatusCode::PAYLOAD_TOO_LARGE,
                "TOO_LARGE",
                "Превышен допустимый объём.",
            ),
            Self::Unavailable(code) => (
                StatusCode::SERVICE_UNAVAILABLE,
                code,
                "Хранилище или разрешённая сеть недоступны.",
            ),
            Self::Io(e) if matches!(e.raw_os_error(), Some(39 | 112)) => (
                StatusCode::INSUFFICIENT_STORAGE,
                "INSUFFICIENT_STORAGE",
                "Недостаточно места на диске.",
            ),
            _ => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "INTERNAL",
                "Операция не завершена. Успешное сохранение не подтверждено.",
            ),
        };
        (status, Json(ErrorBody { code, message })).into_response()
    }
}
