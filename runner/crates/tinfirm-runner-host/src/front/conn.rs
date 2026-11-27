//! Where a front's HTTP services get their connections: a TCP listener that `run` passed, or
//! connections handed to the service in the same process ([`Handoff`]). The bridge's tunnels
//! end in a front this way, at the service a tunnel names, so that no listener is needed on
//! hosts whose sandboxes reach the services only through their tunnels.

use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpListener;
use tokio::sync::mpsc;

/// A connection a service serves: a TCP stream, or one side of an in-process pipe.
pub trait Connection: AsyncRead + AsyncWrite + Unpin + Send + 'static {}

impl<T> Connection for T where T: AsyncRead + AsyncWrite + Unpin + Send + 'static {}

pub type BoxConnection = Box<dyn Connection>;

/// Connections handed to a service that it has not taken yet; more wait for room.
const HANDED: usize = 64;

/// A service's sources of connections.
pub struct Acceptor {
    listener: Option<TcpListener>,
    handed: mpsc::Receiver<BoxConnection>,
    handed_open: bool,
    nodelay: bool,
}

/// Hands connections to the service of an [`Acceptor`]. Clones share it; once every clone is
/// dropped, the service takes no handed connection any more.
#[derive(Clone)]
pub struct Handoff(mpsc::Sender<BoxConnection>);

impl Acceptor {
    /// A service's connections from `listener` (if any) and from the returned [`Handoff`].
    /// With `nodelay`, a TCP connection sends each write at once (no Nagle).
    pub fn new(listener: Option<TcpListener>, nodelay: bool) -> (Acceptor, Handoff) {
        let (tx, rx) = mpsc::channel(HANDED);
        (
            Acceptor {
                listener,
                handed: rx,
                handed_open: true,
                nodelay,
            },
            Handoff(tx),
        )
    }

    /// The connections of `listener` alone.
    pub fn listener(listener: TcpListener, nodelay: bool) -> Acceptor {
        Acceptor::new(Some(listener), nodelay).0
    }

    /// The next connection, from whichever source has one first; `None` once neither can give
    /// one any more (no listener, and every [`Handoff`] dropped).
    pub async fn accept(&mut self) -> Option<std::io::Result<BoxConnection>> {
        loop {
            let Some(listener) = &self.listener else {
                if !self.handed_open {
                    return None;
                }
                return match self.handed.recv().await {
                    Some(connection) => Some(Ok(connection)),
                    None => {
                        self.handed_open = false;
                        None
                    }
                };
            };
            tokio::select! {
                accepted = listener.accept() => {
                    return Some(accepted.map(|(stream, _)| {
                        if self.nodelay {
                            let _ = stream.set_nodelay(true);
                        }
                        Box::new(stream) as BoxConnection
                    }));
                }
                handed = self.handed.recv(), if self.handed_open => match handed {
                    Some(connection) => return Some(Ok(connection)),
                    None => self.handed_open = false,
                },
            }
        }
    }
}

impl Handoff {
    /// Hands `connection` to the service; back when the service is gone.
    pub async fn hand(&self, connection: BoxConnection) -> Result<(), BoxConnection> {
        self.0.send(connection).await.map_err(|e| e.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[tokio::test]
    async fn an_acceptor_takes_both_sources_and_ends_when_neither_can_give_more() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (mut acceptor, handoff) = Acceptor::new(Some(listener), true);
        let mut client = tokio::net::TcpStream::connect(addr).await.unwrap();
        let mut over_tcp = acceptor.accept().await.unwrap().unwrap();
        client.write_all(b"tcp").await.unwrap();
        let mut got = [0u8; 3];
        over_tcp.read_exact(&mut got).await.unwrap();
        assert_eq!(&got, b"tcp");
        let (ours, mut theirs) = tokio::io::duplex(1024);
        assert!(handoff.hand(Box::new(ours)).await.is_ok());
        let mut handed = acceptor.accept().await.unwrap().unwrap();
        theirs.write_all(b"pipe").await.unwrap();
        let mut got = [0u8; 4];
        handed.read_exact(&mut got).await.unwrap();
        assert_eq!(&got, b"pipe");
        // Without a listener, the acceptor ends with its last handoff.
        let (mut alone, handoff) = Acceptor::new(None, false);
        drop(handoff);
        assert!(alone.accept().await.is_none());
        // A handoff whose service is gone gives the connection back.
        let (gone, handoff) = Acceptor::new(None, false);
        drop(gone);
        let (ours, _theirs) = tokio::io::duplex(1024);
        assert!(handoff.hand(Box::new(ours)).await.is_err());
    }

    /// Writes `request` on a connection handed to `handoff`'s service, and reads the head of
    /// its answer.
    async fn ask(handoff: &Handoff, request: &str) -> String {
        let (ours, mut theirs) = tokio::io::duplex(64 * 1024);
        assert!(handoff.hand(Box::new(ours)).await.is_ok());
        theirs.write_all(request.as_bytes()).await.unwrap();
        let mut out = Vec::new();
        tokio::time::timeout(std::time::Duration::from_secs(5), async {
            let mut buf = [0u8; 4096];
            while !out.windows(4).any(|w| w == b"\r\n\r\n") {
                match theirs.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => out.extend_from_slice(&buf[..n]),
                }
            }
        })
        .await
        .expect("the service answers");
        String::from_utf8_lossy(&out).into_owned()
    }

    /// A run half that no request may reach.
    struct Unreached;

    impl crate::front::head::Backend for Unreached {
        fn call(
            &self,
            _head: crate::front::head::RequestHead,
            _body: crate::front::head::ProxyBody,
        ) -> std::pin::Pin<
            Box<
                dyn std::future::Future<
                        Output = (
                            crate::front::head::ResponseHead,
                            crate::front::head::ProxyBody,
                        ),
                    > + Send,
            >,
        > {
            panic!("the front half let a request through")
        }

        fn declined(&self, _why: crate::front::head::Declined) {}
    }

    /// A broker that refuses every destination.
    struct Refusing;

    impl crate::egress_proxy::Connector for Refusing {
        fn connect(
            &self,
            _host: String,
            _port: u16,
        ) -> std::pin::Pin<
            Box<
                dyn std::future::Future<
                        Output = Result<crate::egress_proxy::Granted, crate::egress_proxy::Refusal>,
                    > + Send,
            >,
        > {
            Box::pin(async {
                Err(crate::egress_proxy::Refusal {
                    status: 403,
                    message: "no\n".into(),
                    off_allowlist: true,
                })
            })
        }
    }

    #[tokio::test]
    async fn each_front_service_serves_a_connection_handed_to_it() {
        let shutdown = tokio_util::sync::CancellationToken::new();
        // The egress proxy: its credential, then the broker's word.
        let (acceptor, egress) = Acceptor::new(None, false);
        let half = crate::egress_proxy::FrontHalf::new(
            "Basic c2VjcmV0".into(),
            std::sync::Arc::new(Refusing),
            shutdown.clone(),
        );
        tokio::spawn(crate::egress_proxy::serve(acceptor, half));
        let connect = "CONNECT example.com:443 HTTP/1.1\r\nhost: example.com:443\r\n";
        let answer = ask(&egress, &format!("{connect}\r\n")).await;
        assert!(answer.starts_with("HTTP/1.1 407"), "{answer}");
        let answer = ask(
            &egress,
            &format!("{connect}proxy-authorization: Basic c2VjcmV0\r\n\r\n"),
        )
        .await;
        assert!(answer.starts_with("HTTP/1.1 403"), "{answer}");
        assert!(answer.contains("x-tinfirm-egress: denied"), "{answer}");
        // The model proxy and the git proxy: a path of no route is not found, and nothing
        // reaches the run half.
        let (acceptor, model) = Acceptor::new(None, false);
        let half = crate::model_proxy::FrontHalf::new(
            "s".repeat(43),
            crate::model_proxy::ModelApi::Anthropic,
            String::new(),
            std::sync::Arc::new(Unreached),
            shutdown.clone(),
        );
        tokio::spawn(crate::model_proxy::serve(acceptor, half));
        let answer = ask(
            &model,
            "POST /wrong/v1/messages HTTP/1.1\r\nhost: p\r\ncontent-length: 0\r\n\r\n",
        )
        .await;
        assert!(answer.starts_with("HTTP/1.1 404"), "{answer}");
        let (acceptor, git) = Acceptor::new(None, false);
        tokio::spawn(crate::git_proxy::serve_in_front(
            acceptor,
            std::sync::Arc::new(crate::git_proxy::FrontRoutes::default()),
            std::sync::Arc::new(Unreached),
            crate::front::wire::FrontLimits {
                max_connections: 4,
                header_read_ms: 5_000,
                idle_ms: 5_000,
            },
            shutdown.clone(),
        ));
        let answer = ask(
            &git,
            "GET /nope/acme/widgets.git/info/refs?service=git-upload-pack HTTP/1.1\r\nhost: p\r\n\r\n",
        )
        .await;
        assert!(answer.starts_with("HTTP/1.1 404"), "{answer}");
        shutdown.cancel();
    }
}
