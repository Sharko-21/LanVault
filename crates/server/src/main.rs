use lanvault_server::Engine;
use serde_json::{json, Value};
use std::{io::Write, path::PathBuf};
use tokio::io::{AsyncBufReadExt, BufReader};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let arguments: Vec<_> = std::env::args().collect();
    let option = |name: &str| {
        arguments
            .iter()
            .position(|s| s == name)
            .and_then(|i| arguments.get(i + 1))
            .cloned()
    };
    let state = option("--state-dir").ok_or("--state-dir required")?;
    let engine = Engine::open(
        PathBuf::from(state),
        option("--reserve-bytes")
            .map(|s| s.parse())
            .transpose()?
            .unwrap_or(512 * 1024 * 1024),
    )
    .await?;
    if let Some(folder) = option("--backup-dir") {
        engine.set_folder(PathBuf::from(folder)).await?;
    }
    let address = if arguments.iter().any(|s| s == "--loopback-test") {
        engine
            .start_loopback(
                option("--port")
                    .map(|s| s.parse())
                    .transpose()?
                    .unwrap_or(0),
            )
            .await?
    } else if let Some(interface) = option("--interface") {
        engine
            .start(
                interface,
                option("--port")
                    .map(|s| s.parse())
                    .transpose()?
                    .unwrap_or(43827),
            )
            .await?
    } else {
        json!({"listening":false})
    };
    emit(json!({"event":"ready","endpoint":address,"snapshot":engine.snapshot().await?}));
    // Local administrative stdin, not a remotely accessible HTTP API. Secret QR payloads
    // are emitted only in response to an explicit `pair` command.
    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    while let Some(line) = lines.next_line().await? {
        let input: Value = match serde_json::from_str(&line) {
            Ok(v) => v,
            Err(_) => {
                emit(json!({"error":"INVALID_COMMAND"}));
                continue;
            }
        };
        let command = input["command"].as_str().unwrap_or("");
        let response = match command {
            "status" => engine.snapshot().await,
            "interfaces" => engine.interfaces().await.map(|v| json!(v)),
            "pair" => engine.create_pairing().await,
            "approve" | "reject" => engine
                .approve_pairing(
                    input["requestId"].as_str().unwrap_or("").into(),
                    command == "approve",
                )
                .await
                .map(|_| json!({"ok":true})),
            "revoke" => engine
                .revoke(input["deviceId"].as_str().unwrap_or("").into())
                .await
                .map(|_| json!({"ok":true})),
            "pause" => {
                engine.set_paused(true);
                Ok(json!({"ok":true}))
            }
            "resume" => {
                engine.set_paused(false);
                Ok(json!({"ok":true}))
            }
            "stop" => engine.stop().await.map(|_| json!({"ok":true})),
            "quit" => {
                engine.stop().await?;
                emit(json!({"ok":true}));
                break;
            }
            _ => Err(lanvault_server::Error::BadRequest("UNKNOWN_COMMAND")),
        };
        match response {
            Ok(value) => emit(json!({"result":value})),
            Err(error) => emit(json!({"error":error.to_string()})),
        }
    }
    engine.stop().await?;
    Ok(())
}
fn emit(value: Value) {
    println!("{value}");
    let _ = std::io::stdout().flush();
}
