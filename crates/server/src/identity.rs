use crate::{Error, Result};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use lanvault_platform_windows::{protect, unprotect, DirectoryGuard};
use rcgen::{
    BasicConstraints, CertificateParams, DnType, ExtendedKeyUsagePurpose, IsCa, Issuer, KeyPair,
    KeyUsagePurpose, PublicKeyData,
};
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
    ServerConfig,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    io::{Read, Write},
    sync::Arc,
};
use uuid::Uuid;
use zeroize::Zeroizing;

#[derive(Serialize, Deserialize)]
struct StoredIdentity {
    server_id: Uuid,
    ca_key: String,
    ca_der: String,
}

pub struct Identity {
    pub id: Uuid,
    pub hostname: String,
    pub ca_der: String,
    pub fingerprint: String,
    pub tls: Arc<ServerConfig>,
}

fn ca_parameters(id: Uuid) -> CertificateParams {
    let mut params = CertificateParams::default();
    params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    params.use_authority_key_identifier_extension = true;
    params
        .distinguished_name
        .push(DnType::CommonName, format!("LanVault {id}"));
    params.key_usages = vec![
        KeyUsagePurpose::KeyCertSign,
        KeyUsagePurpose::CrlSign,
        KeyUsagePurpose::DigitalSignature,
    ];
    params.not_before = time::OffsetDateTime::now_utc() - time::Duration::days(1);
    params.not_after = time::OffsetDateTime::now_utc() + time::Duration::days(3650);
    params
}

impl Identity {
    pub fn load(state: &DirectoryGuard) -> Result<Self> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let mut stored = match state.open_file("identity.dpapi", false) {
            Ok(file) => {
                let mut encrypted = Vec::new();
                file.take(65536).read_to_end(&mut encrypted)?;
                let clear = Zeroizing::new(unprotect(&encrypted)?);
                serde_json::from_slice::<StoredIdentity>(&clear).map_err(|_| Error::Internal)?
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                let id = Uuid::new_v4();
                let key = KeyPair::generate().map_err(|_| Error::Internal)?;
                let params = ca_parameters(id);
                let cert = params.self_signed(&key).map_err(|_| Error::Internal)?;
                let value = StoredIdentity {
                    server_id: id,
                    ca_key: key.serialize_pem(),
                    ca_der: STANDARD.encode(cert.der()),
                };
                let clear =
                    Zeroizing::new(serde_json::to_vec(&value).map_err(|_| Error::Internal)?);
                let encrypted = protect(&clear)?;
                let mut file = state.create_file("identity.dpapi")?;
                file.write_all(&encrypted)?;
                file.sync_all()?;
                value
            }
            Err(e) => return Err(e.into()),
        };
        let private_pem = Zeroizing::new(std::mem::take(&mut stored.ca_key));
        let key = KeyPair::from_pem(&private_pem).map_err(|_| Error::Internal)?;
        let fingerprint = format!("{:x}", Sha256::digest(key.subject_public_key_info()));
        let hostname = format!("{}.lanvault.invalid", stored.server_id);
        let issuer = Issuer::new(ca_parameters(stored.server_id), key);
        let leaf_key = KeyPair::generate().map_err(|_| Error::Internal)?;
        let mut leaf =
            CertificateParams::new(vec![hostname.clone()]).map_err(|_| Error::Internal)?;
        leaf.distinguished_name
            .push(DnType::CommonName, hostname.clone());
        leaf.extended_key_usages = vec![ExtendedKeyUsagePurpose::ServerAuth];
        leaf.key_usages = vec![KeyUsagePurpose::DigitalSignature];
        leaf.use_authority_key_identifier_extension = true;
        leaf.not_before = time::OffsetDateTime::now_utc() - time::Duration::days(1);
        leaf.not_after = time::OffsetDateTime::now_utc() + time::Duration::days(365);
        let cert = leaf
            .signed_by(&leaf_key, &issuer)
            .map_err(|_| Error::Internal)?;
        let root = STANDARD
            .decode(&stored.ca_der)
            .map_err(|_| Error::Internal)?;
        let mut tls = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(
                vec![cert.der().clone(), CertificateDer::from(root)],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(leaf_key.serialize_der())),
            )
            .map_err(|_| Error::Internal)?;
        tls.alpn_protocols = vec![b"http/1.1".to_vec()];
        tls.max_early_data_size = 0;
        Ok(Self {
            id: stored.server_id,
            hostname,
            ca_der: stored.ca_der,
            fingerprint,
            tls: Arc::new(tls),
        })
    }
}

pub fn random_secret() -> Result<String> {
    let mut bytes = [0_u8; 32];
    getrandom::fill(&mut bytes).map_err(|_| Error::Internal)?;
    Ok(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes))
}
pub fn secret_hash(secret: &str) -> String {
    format!("{:x}", Sha256::digest(secret.as_bytes()))
}
