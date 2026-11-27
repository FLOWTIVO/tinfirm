//! The network probe of a sandbox: from inside, before the agent starts, the bridge tries
//! what the sandbox must not reach (the internet, DNS, a metadata service's credentials, the
//! operator's other networks), and reports what got through. The host destroys a sandbox for
//! which anything did. The checks are the host's: the same everywhere, whatever the provider
//! promises.

use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::control::ProbeCheck;

/// How long one check may take; one that does not finish in time got nowhere.
const CHECK_TIMEOUT: Duration = Duration::from_secs(3);

/// The names of the checks that got through, in the order they were given.
pub async fn run(checks: &[ProbeCheck]) -> Vec<String> {
    let mut set = tokio::task::JoinSet::new();
    for (index, check) in checks.iter().cloned().enumerate() {
        set.spawn(async move {
            let open = tokio::time::timeout(CHECK_TIMEOUT, open(&check))
                .await
                .unwrap_or(false);
            (index, check.name(), open)
        });
    }
    let mut open = Vec::new();
    while let Some(joined) = set.join_next().await {
        if let Ok((index, name, true)) = joined {
            open.push((index, name));
        }
    }
    open.sort();
    open.into_iter().map(|(_, name)| name).collect()
}

async fn open(check: &ProbeCheck) -> bool {
    match check {
        ProbeCheck::Tcp { addr } => TcpStream::connect(addr.as_str()).await.is_ok(),
        ProbeCheck::Dns { name } => tokio::net::lookup_host((name.as_str(), 443))
            .await
            .is_ok_and(|mut addrs| addrs.next().is_some()),
        ProbeCheck::Http {
            addr,
            path,
            headers,
        } => http_ok(addr, path, headers).await,
    }
}

/// Whether `GET path` at `addr` answers 200.
async fn http_ok(addr: &str, path: &str, headers: &[(String, String)]) -> bool {
    let unsafe_text = |text: &str| text.chars().any(|c| c.is_control());
    if !path.starts_with('/')
        || unsafe_text(path)
        || headers
            .iter()
            .any(|(name, value)| unsafe_text(name) || unsafe_text(value))
    {
        return false;
    }
    let Ok(mut tcp) = TcpStream::connect(addr).await else {
        return false;
    };
    let host = addr.rsplit_once(':').map_or(addr, |(host, _)| host);
    let mut request = format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n");
    for (name, value) in headers {
        request.push_str(&format!("{name}: {value}\r\n"));
    }
    request.push_str("\r\n");
    if tcp.write_all(request.as_bytes()).await.is_err() {
        return false;
    }
    let mut head = Vec::new();
    let mut buf = [0u8; 256];
    while !head.contains(&b'\n') && head.len() < 1024 {
        match tcp.read(&mut buf).await {
            Ok(0) | Err(_) => break,
            Ok(n) => head.extend_from_slice(&buf[..n]),
        }
    }
    let line = String::from_utf8_lossy(&head);
    let mut parts = line.split_whitespace();
    matches!(
        (parts.next(), parts.next()),
        (Some(version), Some("200")) if version.starts_with("HTTP/")
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    /// Answers every request with `status`.
    async fn server(status: &'static str) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        tokio::spawn(async move {
            while let Ok((mut socket, _)) = listener.accept().await {
                let mut buf = [0u8; 1024];
                let _ = socket.read(&mut buf).await;
                let _ = socket
                    .write_all(format!("HTTP/1.1 {status}\r\nContent-Length: 0\r\n\r\n").as_bytes())
                    .await;
            }
        });
        addr
    }

    #[tokio::test]
    async fn only_what_gets_through_is_reported() {
        let ok = server("200 OK").await;
        let missing = server("404 Not Found").await;
        let closed = {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            listener.local_addr().unwrap().to_string()
        };
        let checks = vec![
            ProbeCheck::Tcp { addr: ok.clone() },
            ProbeCheck::Tcp {
                addr: closed.clone(),
            },
            ProbeCheck::Http {
                addr: ok.clone(),
                path: "/latest/meta-data/iam/security-credentials/".into(),
                headers: vec![("Metadata-Flavor".into(), "Google".into())],
            },
            ProbeCheck::Http {
                addr: missing.clone(),
                path: "/token".into(),
                headers: Vec::new(),
            },
            ProbeCheck::Dns {
                name: "nothing.invalid".into(),
            },
            // A path that would split the request is never sent.
            ProbeCheck::Http {
                addr: ok.clone(),
                path: "/x\r\nEvil: 1".into(),
                headers: Vec::new(),
            },
        ];
        let open = run(&checks).await;
        assert_eq!(
            open,
            [
                format!("a connection to {ok}"),
                format!("http://{ok}/latest/meta-data/iam/security-credentials/"),
            ]
        );
    }
}
