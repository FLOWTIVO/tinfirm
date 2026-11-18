//! The listener's TLS: TLS 1.3 with rustls and ring, as the sandbox bridge's (ADR-0095). Each
//! side pins the other's certificate by its SHA-256: the driver pins the node's, and the
//! listener accepts exactly one client certificate, the control host's. A client with another
//! certificate, or with none, fails the handshake and gets nothing more.

use std::os::unix::fs::MetadataExt;
use std::path::Path;
use std::sync::Arc;

use anyhow::{Context, Result, anyhow, bail};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{
    ClientConfig, DigitallySignedStruct, DistinguishedName, ServerConfig, SignatureScheme,
};

/// The name in the node's certificate and in the driver's ClientHello. Nothing resolves it.
pub const SERVER_NAME: &str = "tinfirm-node-agent";

fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

/// The SHA-256 of a certificate's DER.
pub fn sha256(bytes: &[u8]) -> [u8; 32] {
    let digest = ring::digest::digest(&ring::digest::SHA256, bytes);
    digest.as_ref().try_into().expect("SHA-256 is 32 bytes")
}

pub fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// A pin: 64 hex digits.
pub fn parse_pin(text: &str) -> Result<[u8; 32]> {
    let text = text.trim();
    if text.len() != 64 || !text.bytes().all(|b| b.is_ascii_hexdigit()) {
        bail!("a pin is the SHA-256 of a certificate, in 64 hex digits");
    }
    let mut pin = [0u8; 32];
    for (i, byte) in pin.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&text[2 * i..2 * i + 2], 16).expect("hex digits");
    }
    Ok(pin)
}

/// A key pair and its self-signed certificate, DER: `tinfirm-node-agent identity`.
pub fn generate(name: &str) -> Result<(Vec<u8>, Vec<u8>)> {
    let certified = rcgen::generate_simple_self_signed(vec![name.to_string()])
        .context("cannot create a certificate")?;
    Ok((
        certified.cert.der().to_vec(),
        certified.signing_key.serialize_der(),
    ))
}

/// A certificate file (DER) and its key file (PKCS #8 DER). The key must be its owner's alone.
pub fn load(cert: &Path, key: &Path) -> Result<(Vec<u8>, Vec<u8>)> {
    let meta = std::fs::metadata(key).with_context(|| format!("cannot read {}", key.display()))?;
    let own = crate::local::own_uid()?;
    if meta.uid() != own || meta.mode() & 0o077 != 0 {
        bail!(
            "the key {} must belong to this process's user with mode 0600",
            key.display()
        );
    }
    Ok((
        crate::plan::read_bounded(cert, 64 * 1024)?,
        crate::plan::read_bounded(key, 64 * 1024)?,
    ))
}

/// The listener's TLS configuration: the node's certificate, and only the client whose
/// certificate's SHA-256 is `client_pin`.
pub fn server_config(
    cert: Vec<u8>,
    key: Vec<u8>,
    client_pin: [u8; 32],
) -> Result<Arc<ServerConfig>> {
    let provider = provider();
    let verifier = Arc::new(PinnedClient {
        pin: client_pin,
        provider: provider.clone(),
    });
    let config = ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .context("TLS 1.3 is not available")?
        .with_client_cert_verifier(verifier)
        .with_single_cert(
            vec![CertificateDer::from(cert)],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key)),
        )
        .context("cannot use the node's certificate")?;
    Ok(Arc::new(config))
}

/// The driver's TLS configuration: the node whose certificate's SHA-256 is `node_pin`, and the
/// control host's own certificate. The `node` driver of the runner host does the same (planned).
pub fn client_config(
    node_pin: [u8; 32],
    cert: Option<(Vec<u8>, Vec<u8>)>,
) -> Result<Arc<ClientConfig>> {
    let provider = provider();
    let verifier = Arc::new(PinnedServer {
        pin: node_pin,
        provider: provider.clone(),
    });
    let builder = ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .context("TLS 1.3 is not available")?
        .dangerous()
        .with_custom_certificate_verifier(verifier);
    let config = match cert {
        Some((cert, key)) => builder
            .with_client_auth_cert(
                vec![CertificateDer::from(cert)],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key)),
            )
            .context("cannot use the control host's certificate")?,
        None => builder.with_no_client_auth(),
    };
    Ok(Arc::new(config))
}

pub fn server_name() -> ServerName<'static> {
    ServerName::try_from(SERVER_NAME).expect("a valid DNS name")
}

fn tls12(
    provider: &CryptoProvider,
    message: &[u8],
    cert: &CertificateDer<'_>,
    dss: &DigitallySignedStruct,
) -> Result<HandshakeSignatureValid, rustls::Error> {
    rustls::crypto::verify_tls12_signature(
        message,
        cert,
        dss,
        &provider.signature_verification_algorithms,
    )
}

fn tls13(
    provider: &CryptoProvider,
    message: &[u8],
    cert: &CertificateDer<'_>,
    dss: &DigitallySignedStruct,
) -> Result<HandshakeSignatureValid, rustls::Error> {
    rustls::crypto::verify_tls13_signature(
        message,
        cert,
        dss,
        &provider.signature_verification_algorithms,
    )
}

#[derive(Debug)]
struct PinnedClient {
    pin: [u8; 32],
    provider: Arc<CryptoProvider>,
}

impl ClientCertVerifier for PinnedClient {
    fn offer_client_auth(&self) -> bool {
        true
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }

    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, rustls::Error> {
        if sha256(end_entity) == self.pin {
            Ok(ClientCertVerified::assertion())
        } else {
            Err(rustls::Error::General(
                "the client's certificate is not the control host's".into(),
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        tls12(&self.provider, message, cert, dss)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        tls13(&self.provider, message, cert, dss)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[derive(Debug)]
struct PinnedServer {
    pin: [u8; 32],
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinnedServer {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if sha256(end_entity) == self.pin {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(
                "the node's certificate does not match the pin".into(),
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        tls12(&self.provider, message, cert, dss)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        tls13(&self.provider, message, cert, dss)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// Writes a new identity to `cert` and `key` (0600), which must not exist.
pub fn write_identity(cert: &Path, key: &Path, name: &str) -> Result<String> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let (cert_der, key_der) = generate(name)?;
    for (path, bytes, mode) in [(key, &key_der, 0o600), (cert, &cert_der, 0o644)] {
        let mut file = std::fs::File::options()
            .write(true)
            .create_new(true)
            .mode(mode)
            .custom_flags(libc::O_NOFOLLOW)
            .open(path)
            .with_context(|| format!("cannot create {}", path.display()))?;
        file.write_all(bytes)?;
    }
    Ok(to_hex(&sha256(&cert_der)))
}

/// The pin of a certificate file.
pub fn fingerprint(cert: &Path) -> Result<String> {
    let bytes = crate::plan::read_bounded(cert, 64 * 1024)?;
    if bytes.is_empty() {
        return Err(anyhow!("{} is empty", cert.display()));
    }
    Ok(to_hex(&sha256(&bytes)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[test]
    fn pins_parse_and_identities_differ() {
        let (a, _) = generate("x").unwrap();
        let (b, _) = generate("x").unwrap();
        assert_ne!(sha256(&a), sha256(&b));
        assert_eq!(parse_pin(&to_hex(&sha256(&a))).unwrap(), sha256(&a));
        assert!(parse_pin("abcd").is_err());
        assert!(parse_pin(&"g".repeat(64)).is_err());
        assert!(parse_pin(&format!("{}\n0", "a".repeat(63))).is_err());
    }

    /// One handshake: the node with `node`, pinning the client `allowed`; the client pinning
    /// `node_pin` and presenting `client`. Whether the listener read the client's bytes.
    async fn handshake(
        node: (Vec<u8>, Vec<u8>),
        allowed: [u8; 32],
        node_pin: [u8; 32],
        client: Option<(Vec<u8>, Vec<u8>)>,
    ) -> (bool, bool) {
        let (a, b) = tokio::io::duplex(64 * 1024);
        let acceptor =
            tokio_rustls::TlsAcceptor::from(server_config(node.0, node.1, allowed).unwrap());
        let server = tokio::spawn(async move {
            let mut tls = acceptor.accept(b).await.ok()?;
            let mut buf = [0u8; 4];
            tls.read_exact(&mut buf).await.ok()?;
            Some(buf)
        });
        let connector = tokio_rustls::TlsConnector::from(client_config(node_pin, client).unwrap());
        let client_ok = match connector.connect(server_name(), a).await {
            Ok(mut tls) => {
                // In TLS 1.3 the client learns of a refused certificate only on its next read.
                let _ = tls.write_all(b"ping").await;
                let _ = tls.flush().await;
                let mut buf = [0u8; 1];
                tls.read(&mut buf).await.is_ok()
            }
            Err(_) => false,
        };
        let served = server.await.unwrap() == Some(*b"ping");
        (client_ok, served)
    }

    #[tokio::test]
    async fn the_listener_takes_only_the_pinned_client_and_the_driver_only_the_pinned_node() {
        let node = generate(SERVER_NAME).unwrap();
        let control = generate("control").unwrap();
        let other = generate("control").unwrap();
        let node_pin = sha256(&node.0);
        let control_pin = sha256(&control.0);

        let (_, served) =
            handshake(node.clone(), control_pin, node_pin, Some(control.clone())).await;
        assert!(served, "the pinned client is served");
        let (client_ok, served) =
            handshake(node.clone(), control_pin, node_pin, Some(other.clone())).await;
        assert!(
            !served && !client_ok,
            "a client with another certificate is refused"
        );
        let (client_ok, served) = handshake(node.clone(), control_pin, node_pin, None).await;
        assert!(
            !served && !client_ok,
            "a client without a certificate is refused"
        );
        let (client_ok, served) =
            handshake(node.clone(), control_pin, sha256(&other.0), Some(control)).await;
        assert!(
            !served && !client_ok,
            "the driver refuses a node of another certificate"
        );
    }

    #[test]
    fn an_identity_is_written_once_with_its_key_closed() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let cert = dir.path().join("node.crt");
        let key = dir.path().join("node.key");
        let pin = write_identity(&cert, &key, SERVER_NAME).unwrap();
        assert_eq!(fingerprint(&cert).unwrap(), pin);
        assert_eq!(
            std::fs::metadata(&key).unwrap().permissions().mode() & 0o777,
            0o600
        );
        assert!(write_identity(&cert, &key, SERVER_NAME).is_err());
        let (c, _) = load(&cert, &key).unwrap();
        assert_eq!(to_hex(&sha256(&c)), pin);
        std::fs::set_permissions(&key, std::fs::Permissions::from_mode(0o640)).unwrap();
        assert!(load(&cert, &key).is_err());
    }
}
