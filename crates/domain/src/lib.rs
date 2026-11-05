//! Platform-independent invariants. This crate does not perform network or file I/O.

use thiserror::Error;

pub const PROTOCOL_VERSION: u32 = 1;
pub const DEFAULT_CHUNK_BYTES: u64 = 8 * 1024 * 1024;
pub const MAX_BYTE_COUNT: u64 = i64::MAX as u64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ByteCount(u64);

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ValidationError {
    #[error("byte count must be a positive canonical signed 64-bit decimal")]
    InvalidByteCount,
    #[error("chunk is empty, exceeds its limit, or falls outside the source")]
    InvalidChunkRange,
}

impl ByteCount {
    pub fn parse(value: &str) -> Result<Self, ValidationError> {
        if value.is_empty()
            || value.len() > 19
            || value.starts_with('0')
            || !value.bytes().all(|byte| byte.is_ascii_digit())
        {
            return Err(ValidationError::InvalidByteCount);
        }
        let parsed = value
            .parse::<u64>()
            .map_err(|_| ValidationError::InvalidByteCount)?;
        if parsed > MAX_BYTE_COUNT {
            return Err(ValidationError::InvalidByteCount);
        }
        Ok(Self(parsed))
    }

    pub const fn get(self) -> u64 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ChunkRange {
    pub offset: u64,
    pub length: u64,
}

impl ChunkRange {
    pub fn checked(
        offset: u64,
        length: u64,
        total: ByteCount,
        limit: u64,
    ) -> Result<Self, ValidationError> {
        if length == 0
            || length > limit
            || offset
                .checked_add(length)
                .is_none_or(|end| end > total.get())
        {
            return Err(ValidationError::InvalidChunkRange);
        }
        Ok(Self { offset, length })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransferState {
    Discovered,
    Hashing,
    Queued,
    Uploading,
    Verifying,
    Committed,
    Paused,
    WaitingNetwork,
    WaitingServer,
    WaitingPower,
    BlockedPermission,
    BlockedStorage,
    SourceChanged,
    RetryableError,
    NeedsAttention,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_chunk_overflow_and_past_end() -> Result<(), ValidationError> {
        let total = ByteCount::parse("9223372036854775807")?;
        assert!(ChunkRange::checked(u64::MAX, 1, total, 8).is_err());
        assert!(ChunkRange::checked(total.get(), 1, total, 8).is_err());
        assert!(ChunkRange::checked(0, 0, total, 8).is_err());
        assert!(ChunkRange::checked(0, 9, total, 8).is_err());
        Ok(())
    }

    #[test]
    fn accepts_short_last_chunk() -> Result<(), ValidationError> {
        let total = ByteCount::parse("10")?;
        assert_eq!(
            ChunkRange::checked(8, 2, total, 8)?,
            ChunkRange {
                offset: 8,
                length: 2
            }
        );
        Ok(())
    }
}
