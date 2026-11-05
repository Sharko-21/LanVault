//! Versioned wire DTOs. There is no HTTP listener in stage 2.

use chrono::{DateTime, FixedOffset};
use lanvault_domain::{ByteCount, ValidationError, PROTOCOL_VERSION};
use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeInfo {
    pub app_version: String,
    pub protocol_version: u32,
    pub platform: String,
    pub server_listening: bool,
}

impl RuntimeInfo {
    pub fn not_configured(version: &str, platform: &str) -> Self {
        Self {
            app_version: version.into(),
            protocol_version: PROTOCOL_VERSION,
            platform: platform.into(),
            server_listening: false,
        }
    }
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum MediaType {
    Photo,
    Video,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct UploadDescriptor {
    #[serde(deserialize_with = "deserialize_uuid")]
    pub dataset_id: Uuid,
    #[serde(deserialize_with = "deserialize_uuid")]
    pub media_id: Uuid,
    #[serde(deserialize_with = "deserialize_uuid")]
    pub revision_id: Uuid,
    #[serde(deserialize_with = "deserialize_uuid")]
    pub upload_request_id: Uuid,
    pub original_name: String,
    pub size: String,
    pub sha256: String,
    pub media_type: MediaType,
    pub mime_type: String,
    pub captured_at: Option<DateTime<FixedOffset>>,
    pub modified_at: Option<DateTime<FixedOffset>>,
    pub album: Option<String>,
}

fn deserialize_uuid<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<Uuid, D::Error> {
    let text = String::deserialize(deserializer)?;
    let canonical = text.len() == 36
        && text.bytes().enumerate().all(|(index, byte)| {
            if matches!(index, 8 | 13 | 18 | 23) {
                byte == b'-'
            } else {
                byte.is_ascii_hexdigit()
            }
        });
    if !canonical {
        return Err(serde::de::Error::custom("expected a hyphenated UUID"));
    }
    Uuid::parse_str(&text).map_err(serde::de::Error::custom)
}

#[derive(Debug, Error)]
pub enum DescriptorError {
    #[error(transparent)]
    ByteCount(#[from] ValidationError),
    #[error("invalid SHA-256, name, MIME type, or album")]
    InvalidMetadata,
}

impl UploadDescriptor {
    /// Validating metadata does not make a filename a safe filesystem path.
    pub fn validate(&self) -> Result<ByteCount, DescriptorError> {
        let size = ByteCount::parse(&self.size)?;
        let valid_hash = self.sha256.len() == 64
            && self
                .sha256
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte));
        let valid_name = (1..=255).contains(&self.original_name.chars().count());
        let valid_album = self
            .album
            .as_ref()
            .is_none_or(|album| album.chars().count() <= 255);
        let valid_mime = self.mime_type.len() <= 127
            && self
                .mime_type
                .split_once('/')
                .is_some_and(|(kind, subtype)| {
                    let token = |part: &str| {
                        !part.is_empty()
                            && part.bytes().all(|byte| {
                                byte.is_ascii_lowercase()
                                    || byte.is_ascii_digit()
                                    || b"!#$&^_.+-".contains(&byte)
                            })
                    };
                    token(kind) && token(subtype)
                });
        if !valid_hash || !valid_name || !valid_album || !valid_mime {
            return Err(DescriptorError::InvalidMetadata);
        }
        Ok(size)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn shared_wire_fixtures() -> Result<(), Box<dyn std::error::Error>> {
        let fixtures: Value = serde_json::from_str(include_str!(
            "../../../protocol/compatibility-fixtures/uploads.json"
        ))?;
        let cases = fixtures["cases"]
            .as_array()
            .ok_or("fixtures.cases missing")?;
        for case in cases {
            let mut payload = fixtures["base"].clone();
            for (key, value) in case["patch"].as_object().ok_or("fixture patch missing")? {
                payload[key] = value.clone();
            }
            let valid = serde_json::from_value::<UploadDescriptor>(payload)
                .ok()
                .and_then(|descriptor| descriptor.validate().ok())
                .is_some();
            assert_eq!(Some(valid), case["valid"].as_bool(), "{}", case["name"]);
        }
        Ok(())
    }
}
