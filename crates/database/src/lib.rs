use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous};
use std::{path::Path, time::Duration};

pub use sqlx::{self, Row, SqlitePool};

pub async fn open(path: &Path) -> Result<SqlitePool, sqlx::Error> {
    let options = SqliteConnectOptions::new()
        .filename(path)
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .synchronous(SqliteSynchronous::Full)
        .foreign_keys(true)
        .busy_timeout(Duration::from_secs(5));
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options)
        .await?;
    let metadata: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='metadata'",
    )
    .fetch_one(&pool)
    .await?;
    if metadata != 0 {
        let version: Option<String> =
            sqlx::query_scalar("SELECT value FROM metadata WHERE key='schema_version'")
                .fetch_optional(&pool)
                .await?;
        if version.as_deref() != Some("1") {
            return Err(sqlx::Error::Protocol("unsupported catalog schema".into()));
        }
    }
    sqlx::raw_sql(include_str!("../migrations/0001_mvp.sql"))
        .execute(&pool)
        .await?;
    // Additive feature table: schema-1 archives and older clients remain compatible.
    sqlx::raw_sql(include_str!("../migrations/0002_upload_leases.sql"))
        .execute(&pool)
        .await?;
    Ok(pool)
}
