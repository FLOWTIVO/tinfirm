//! TLS of the bridge (ADR-0095): the host's self-signed certificate, created when its listener
//! starts, and the bridge's verifier, which accepts that one certificate by its SHA-256.

use std::sync::Arc;

use anyhow::{Context, Result, anyhow};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, ServerConfig, SignatureScheme};

/// The name in the certificate and in the bridge's ClientHello. Nothing resolves it.
pub const SERVER_NAME: &str = "tinfirm-bridge";

fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

/// The host's TLS identity: a key pair and its self-signed certificate. A runner host keeps it
/// on its disk while its runs' sandboxes live, so that it takes them back after a restart
/// (ADR-0101): their bridges pin it.
pub struct Identity {
    config: Arc<ServerConfig>,
    pin: [u8; 32],
    cert: Vec<u8>,
    key: Vec<u8>,
}

impl Identity {
    pub fn generate() -> Result<Identity> {
        let certified = rcgen::generate_simple_self_signed(vec![SERVER_NAME.to_string()])
            .context("cannot create the bridge's certificate")?;
        Identity::from_der(
            certified.cert.der().to_vec(),
            certified.signing_key.serialize_der(),
        )
    }

    /// The identity of [`Identity::to_der`]: the certificate, and its PKCS #8 key.
    pub fn from_der(cert: Vec<u8>, key: Vec<u8>) -> Result<Identity> {
        let pin = sha256(&cert);
        let config = ServerConfig::builder_with_provider(provider())
            .with_protocol_versions(&[&rustls::version::TLS13])
            .context("TLS 1.3 is not available")?
            .with_no_client_auth()
            .with_single_cert(
                vec![CertificateDer::from(cert.clone())],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key.clone())),
            )
            .context("cannot use the bridge's certificate")?;
        Ok(Identity {
            config: Arc::new(config),
            pin,
            cert,
            key,
        })
    }

    /// The certificate and its key, DER: the key is the listener's secret.
    pub fn to_der(&self) -> (&[u8], &[u8]) {
        (&self.cert, &self.key)
    }

    pub fn server_config(&self) -> Arc<ServerConfig> {
        self.config.clone()
    }

    /// The SHA-256 of the certificate, in hex: `TINFIRM_BRIDGE_PIN`.
    pub fn pin_hex(&self) -> String {
        crate::to_hex(&self.pin)
    }
}

fn sha256(bytes: &[u8]) -> [u8; 32] {
    let digest = ring::digest::digest(&ring::digest::SHA256, bytes);
    digest.as_ref().try_into().expect("SHA-256 is 32 bytes")
}

/// Parses `TINFIRM_BRIDGE_PIN`.
pub fn parse_pin(text: &str) -> Result<[u8; 32]> {
    let bytes = crate::from_hex(text.trim()).ok_or_else(|| anyhow!("the pin is not hex"))?;
    bytes
        .try_into()
        .map_err(|_| anyhow!("the pin is not 32 bytes (64 hex digits)"))
}

/// The bridge's TLS configuration: TLS 1.3, and only the certificate whose SHA-256 is `pin`.
pub fn client_config(pin: [u8; 32]) -> Arc<ClientConfig> {
    let provider = provider();
    let verifier = Arc::new(Pinned {
        pin,
        provider: provider.clone(),
    });
    let config = ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .expect("the ring provider has TLS 1.3")
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();
    Arc::new(config)
}

/// The bridge's server name, for the connector.
pub fn server_name() -> ServerName<'static> {
    ServerName::try_from(SERVER_NAME).expect("a valid DNS name")
}

#[derive(Debug)]
struct Pinned {
    pin: [u8; 32],
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if crate::secret_eq(&sha256(end_entity), &self.pin) {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(
                "the runner host's certificate does not match the pin".into(),
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identities_are_new_each_time_and_pins_parse() {
        let a = Identity::generate().unwrap();
        let b = Identity::generate().unwrap();
        assert_ne!(a.pin_hex(), b.pin_hex());
        assert_eq!(parse_pin(&a.pin_hex()).unwrap(), a.pin);
        assert!(parse_pin("abcd").is_err());
        assert!(parse_pin(&"g".repeat(64)).is_err());
    }

    #[tokio::test]
    async fn the_bridge_accepts_only_the_pinned_certificate() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let identity = Identity::generate().unwrap();
        let other = Identity::generate().unwrap();
        for (pin, accepted) in [(identity.pin, true), (other.pin, false)] {
            let (client, server) = tokio::io::duplex(64 * 1024);
            let acceptor = tokio_rustls::TlsAcceptor::from(identity.server_config());
            let server = tokio::spawn(async move {
                let mut tls = acceptor.accept(server).await.ok()?;
                let mut buf = [0u8; 4];
                tls.read_exact(&mut buf).await.ok()?;
                Some(buf)
            });
            let connector = tokio_rustls::TlsConnector::from(client_config(pin));
            let result = connector.connect(server_name(), client).await;
            if accepted {
                let mut tls = result.unwrap();
                tls.write_all(b"ping").await.unwrap();
                tls.flush().await.unwrap();
                assert_eq!(server.await.unwrap(), Some(*b"ping"));
            } else {
                let error = result.unwrap_err().to_string();
                assert!(error.contains("does not match the pin"), "{error}");
                assert_eq!(server.await.unwrap(), None);
            }
        }
    }
}
