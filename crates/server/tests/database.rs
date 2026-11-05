use lanvault_database::{sqlx, SqlitePool};

#[tokio::test]
async fn catalog_has_durable_settings_and_integrity() -> Result<(), Box<dyn std::error::Error>> {
    let temp = tempfile::tempdir()?;
    let pool = lanvault_database::open(&temp.path().join("catalog.sqlite")).await?;
    let journal: String = sqlx::query_scalar("PRAGMA journal_mode")
        .fetch_one(&pool)
        .await?;
    let synchronous: i64 = sqlx::query_scalar("PRAGMA synchronous")
        .fetch_one(&pool)
        .await?;
    let foreign_keys: i64 = sqlx::query_scalar("PRAGMA foreign_keys")
        .fetch_one(&pool)
        .await?;
    let integrity: String = sqlx::query_scalar("PRAGMA integrity_check")
        .fetch_one(&pool)
        .await?;
    let version: String = sqlx::query_scalar("SELECT sqlite_version()")
        .fetch_one(&pool)
        .await?;
    println!("bundled SQLite {version}");
    assert_eq!(journal, "wal");
    assert_eq!(synchronous, 2);
    assert_eq!(foreign_keys, 1);
    assert_eq!(integrity, "ok");
    pool.close().await;
    Ok(())
}

#[tokio::test]
async fn newer_schema_is_rejected_before_migration() -> Result<(), Box<dyn std::error::Error>> {
    let temp = tempfile::tempdir()?;
    let path = temp.path().join("future.sqlite");
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(&path)
        .create_if_missing(true);
    let pool = sqlx::sqlite::SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options)
        .await?;
    sqlx::raw_sql("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT); INSERT INTO metadata VALUES('schema_version','2');CREATE TABLE future_marker(value TEXT);").execute(&pool).await?;
    pool.close().await;
    assert!(lanvault_database::open(&path).await.is_err());
    let options = sqlx::sqlite::SqliteConnectOptions::new().filename(path);
    let check = SqlitePool::connect_with(options).await?;
    let tables: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='table'")
        .fetch_one(&check)
        .await?;
    assert_eq!(tables, 2);
    check.close().await;
    Ok(())
}
