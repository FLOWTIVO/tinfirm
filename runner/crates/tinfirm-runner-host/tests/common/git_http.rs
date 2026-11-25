//! A git smart HTTP server for the tests: `git http-backend` as CGI behind a small hyper
//! server. It can require an `Authorization` header, like GitHub does for a private
//! repository, and it records every request it gets. A request's body goes to `http-backend`
//! as it comes, never held whole, so that a large push costs this process no memory; and a hook
//! can act just before a push (`POST …/git-receive-pack`) reaches `http-backend`.

use std::convert::Infallible;
use std::path::Path;
use std::process::Stdio;
use std::sync::{Arc, Mutex};

use base64::Engine;
use http_body_util::{BodyExt, Full};
use hyper::body::{Bytes, Incoming};
use hyper::{Request, Response, StatusCode};
use hyper_util::rt::TokioIo;
use tokio::io::AsyncWriteExt;
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use super::TEST_GIT_TOKEN;

/// `Authorization` value for the test git token, as the runner host sends it.
pub fn test_git_authorization() -> String {
    let basic = base64::engine::general_purpose::STANDARD
        .encode(format!("x-access-token:{TEST_GIT_TOKEN}"));
    format!("Basic {basic}")
}

/// One request the server got.
#[derive(Debug, Clone)]
pub struct SeenRequest {
    pub method: String,
    pub path: String,
    pub query: String,
    pub authorization: Option<String>,
    pub git_protocol: Option<String>,
    /// The client's `User-Agent`: git's (`git/2.43.0`) or the runner host's own push client's
    /// (`git/2.0 (tinfirm-runner-host …)`).
    pub user_agent: Option<String>,
    /// The request's `Content-Length`, and whether its body came in chunks.
    pub content_length: Option<String>,
    pub chunked: bool,
    /// 0 until the server answered: a request is recorded when it comes, so that one broken
    /// off is recorded too.
    pub status: u16,
}

/// Whether the request came from the runner host's own push client (crate::git_push).
pub fn from_host_client(request: &SeenRequest) -> bool {
    request
        .user_agent
        .as_deref()
        .is_some_and(|agent| agent.starts_with("git/2.0 (tinfirm-runner-host "))
}

/// The requests of pushes (the ref discovery for a push, and the push), each of them made by
/// the runner host's own client when `host` is true, and by git otherwise; their count.
pub fn assert_pushed_by(seen: &[SeenRequest], host: bool) -> usize {
    let pushes: Vec<&SeenRequest> = seen
        .iter()
        .filter(|r| r.query == "service=git-receive-pack" || r.path.ends_with("/git-receive-pack"))
        .collect();
    for request in &pushes {
        assert_eq!(from_host_client(request), host, "{request:?}");
        if !host {
            assert!(
                request
                    .user_agent
                    .as_deref()
                    .is_some_and(|a| a.starts_with("git/") && !a.contains("tinfirm")),
                "{request:?}"
            );
        }
    }
    pushes.len()
}

/// Run just before a push reaches `http-backend`.
pub type BeforePush = Arc<dyn Fn() + Send + Sync>;

pub struct GitHttpServer {
    /// `http://127.0.0.1:<port>`; a repository `<root>/x.git` is at `<url>/x.git`.
    pub url: String,
    /// Replaced at runtime by [`GitHttpServer::set_authorization`].
    authorization: Arc<Mutex<Option<String>>>,
    seen: Arc<Mutex<Vec<SeenRequest>>>,
    before_push: Arc<Mutex<Option<BeforePush>>>,
    shutdown: CancellationToken,
}

impl GitHttpServer {
    /// From now on, `hook` runs before each push reaches `http-backend` (a change to the
    /// repository between a client's ref discovery and its push).
    pub fn before_push(&self, hook: impl Fn() + Send + Sync + 'static) {
        *self.before_push.lock().unwrap() = Some(Arc::new(hook));
    }

    /// Serves the repositories under `root`. With `authorization`, other requests get 401.
    pub async fn start(root: &Path, authorization: Option<String>) -> GitHttpServer {
        GitHttpServer::start_with(root, authorization, StatusCode::UNAUTHORIZED).await
    }

    /// Like [`GitHttpServer::start`], but requests without the right header get 403, like a
    /// token that lost its access.
    pub async fn start_forbidding(root: &Path, authorization: Option<String>) -> GitHttpServer {
        GitHttpServer::start_with(root, authorization, StatusCode::FORBIDDEN).await
    }

    /// From now on, only `authorization` is accepted (a token revoked in the middle).
    pub fn set_authorization(&self, authorization: Option<String>) {
        *self.authorization.lock().unwrap() = authorization;
    }

    async fn start_with(
        root: &Path,
        authorization: Option<String>,
        denied: StatusCode,
    ) -> GitHttpServer {
        let authorization = Arc::new(Mutex::new(authorization));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let seen = Arc::new(Mutex::new(Vec::new()));
        let before_push: Arc<Mutex<Option<BeforePush>>> = Arc::new(Mutex::new(None));
        let before_push_task = before_push.clone();
        let shutdown = CancellationToken::new();
        let root = root.to_path_buf();
        let seen_task = seen.clone();
        let stop = shutdown.clone();
        let authorization_task = authorization.clone();
        tokio::spawn(async move {
            loop {
                let stream = tokio::select! {
                    _ = stop.cancelled() => break,
                    accepted = listener.accept() => match accepted {
                        Ok((stream, _)) => stream,
                        Err(_) => break,
                    },
                };
                let root = root.clone();
                let authorization = authorization_task.clone();
                let seen = seen_task.clone();
                let before_push = before_push_task.clone();
                tokio::spawn(async move {
                    let service = hyper::service::service_fn(move |req| {
                        let root = root.clone();
                        let authorization = authorization.lock().unwrap().clone();
                        let seen = seen.clone();
                        let hook = before_push.lock().unwrap().clone();
                        async move {
                            Ok::<_, Infallible>(
                                serve(&root, authorization, denied, &seen, hook, req).await,
                            )
                        }
                    });
                    let _ = hyper::server::conn::http1::Builder::new()
                        .serve_connection(TokioIo::new(stream), service)
                        .await;
                });
            }
        });
        GitHttpServer {
            url,
            authorization,
            seen,
            before_push,
            shutdown,
        }
    }

    pub fn seen(&self) -> Vec<SeenRequest> {
        self.seen.lock().unwrap().clone()
    }
}

impl Drop for GitHttpServer {
    fn drop(&mut self) {
        self.shutdown.cancel();
    }
}

async fn serve(
    root: &Path,
    authorization: Option<String>,
    denied: StatusCode,
    seen: &Mutex<Vec<SeenRequest>>,
    before_push: Option<BeforePush>,
    req: Request<Incoming>,
) -> Response<Full<Bytes>> {
    let header = |name: &str| {
        req.headers()
            .get(name)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string)
    };
    let record = SeenRequest {
        method: req.method().to_string(),
        path: req.uri().path().to_string(),
        query: req.uri().query().unwrap_or_default().to_string(),
        authorization: header("authorization"),
        git_protocol: header("git-protocol"),
        user_agent: header("user-agent"),
        content_length: header("content-length"),
        chunked: header("transfer-encoding").is_some_and(|v| v.eq_ignore_ascii_case("chunked")),
        status: 0,
    };
    let index = {
        let mut seen = seen.lock().unwrap();
        seen.push(record.clone());
        seen.len() - 1
    };
    let response = if authorization.is_some() && record.authorization != authorization {
        let mut response = Response::new(Full::new(Bytes::from_static(b"auth required\n")));
        *response.status_mut() = denied;
        if denied == StatusCode::UNAUTHORIZED {
            response.headers_mut().insert(
                "www-authenticate",
                hyper::header::HeaderValue::from_static("Basic realm=\"test\""),
            );
        }
        response
    } else {
        let content_type = header("content-type").unwrap_or_default();
        let content_encoding = header("content-encoding");
        if let Some(hook) = before_push
            .filter(|_| record.method == "POST" && record.path.ends_with("/git-receive-pack"))
        {
            hook();
        }
        http_backend(
            root,
            &record,
            &content_type,
            content_encoding.as_deref(),
            req.into_body(),
        )
        .await
    };
    seen.lock().unwrap()[index].status = response.status().as_u16();
    response
}

/// Runs `git http-backend` for one request (CGI) and turns its output into a response.
async fn http_backend(
    root: &Path,
    request: &SeenRequest,
    content_type: &str,
    content_encoding: Option<&str>,
    mut body: Incoming,
) -> Response<Full<Bytes>> {
    let mut cmd = tokio::process::Command::new("git");
    cmd.arg("http-backend")
        .env_clear()
        .env("PATH", std::env::var("PATH").unwrap_or_default())
        .env("HOME", root)
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env("GIT_PROJECT_ROOT", root)
        .env("GIT_HTTP_EXPORT_ALL", "1")
        .env("REQUEST_METHOD", &request.method)
        .env("PATH_INFO", &request.path)
        .env("QUERY_STRING", &request.query)
        .env("CONTENT_TYPE", content_type)
        // An authenticated user may push (receive-pack), like a token with write access.
        .env("REMOTE_USER", "x-access-token")
        .env("REMOTE_ADDR", "127.0.0.1")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    if let Some(protocol) = &request.git_protocol {
        cmd.env("HTTP_GIT_PROTOCOL", protocol);
    }
    if let Some(encoding) = content_encoding {
        cmd.env("HTTP_CONTENT_ENCODING", encoding);
    }
    // Without a length (a body in chunks), http-backend reads to the end of its input.
    if let Some(length) = &request.content_length {
        cmd.env("CONTENT_LENGTH", length);
    } else if request.method == "GET" {
        cmd.env("CONTENT_LENGTH", "0");
    }
    let mut child = cmd.spawn().expect("git http-backend starts");
    let mut stdin = child.stdin.take().unwrap();
    let writer = tokio::spawn(async move {
        while let Some(Ok(frame)) = body.frame().await {
            if let Some(data) = frame.data_ref()
                && stdin.write_all(data).await.is_err()
            {
                break;
            }
        }
        let _ = stdin.shutdown().await;
    });
    let output = child.wait_with_output().await.unwrap();
    let _ = writer.await;
    parse_cgi(&output.stdout)
}

fn parse_cgi(output: &[u8]) -> Response<Full<Bytes>> {
    let (head_end, separator) = match output.windows(4).position(|w| w == b"\r\n\r\n") {
        Some(pos) => (pos, 4),
        None => match output.windows(2).position(|w| w == b"\n\n") {
            Some(pos) => (pos, 2),
            None => (output.len(), 0),
        },
    };
    let head = String::from_utf8_lossy(&output[..head_end]);
    let body = Bytes::copy_from_slice(&output[(head_end + separator).min(output.len())..]);
    let mut response = Response::new(Full::new(body));
    for line in head.lines() {
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        let value = value.trim();
        if name.eq_ignore_ascii_case("status") {
            let code = value.split(' ').next().and_then(|c| c.parse().ok());
            *response.status_mut() = code
                .and_then(|c| StatusCode::from_u16(c).ok())
                .unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
        } else if let (Ok(name), Ok(value)) = (
            hyper::header::HeaderName::from_bytes(name.trim().as_bytes()),
            hyper::header::HeaderValue::from_str(value),
        ) {
            response.headers_mut().append(name, value);
        }
    }
    response
}
