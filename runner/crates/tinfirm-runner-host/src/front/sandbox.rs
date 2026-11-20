//! A sandbox's front, as `run` holds it (ADR-0112, path C, part 2): one front process per
//! sandbox, which serves every listener of that sandbox's proxies, and the part of `run` that
//! answers its requests.
//!
//! A sandbox gets its front with its first listener: a VM's when the VM is made (its egress
//! listener, crate::pool), any other sandbox's when its run starts its model proxy or its
//! egress proxy (crate::run). Each listener's guard holds the front, and so does the run's
//! access (or the VM, until a run claims it): the front ends with the last of them, so with the
//! sandbox. A listener whose guard is dropped is closed in the front ([`ToFront::Unlisten`]),
//! and `run` stops answering its requests at once. A sandbox has one listener of each service
//! in its life, so a front serves each once: `run` refuses a second one itself.
//!
//! `run` decides here and the front only asks:
//! - **egress:** a destination that the front asks for is decided and connected by the egress
//!   proxy's broker, and the connection's descriptor goes to the front, which holds it until it
//!   says it ended; its place among the run's 256 is held until then;
//! - **model, git and platform tools:** a request's head is checked again and sent on with the
//!   key or the run's git token, or answered by the run's tools, by the run half of the model
//!   proxy or of the git proxy (crate::model_proxy, crate::git_proxy). Its body comes on a socket pair of its own, which `run`
//!   makes for each request: the front writes the request's body on it, `run` writes the
//!   answer's body back, and the kernel holds either side back when the other does not read.
//!   The control channel carries only the heads and the ends. A request ends with `run`'s
//!   `end`, also when the front gives it up (`abort`, when its client left): the upstream
//!   request ends with it, and nothing of it stays in `run`.

use std::collections::HashMap;
use std::os::fd::OwnedFd;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, Weak};

use anyhow::{Context, Result, bail};
use http_body_util::BodyExt;
use hyper::body::{Bytes, Frame};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tokio_util::sync::CancellationToken;

use super::handle::{FrontCommand, FrontHandle, FrontRequest};
use super::head::{Backend, BoxError, ProxyBody, RequestHead};
use super::wire::{FrontLimits, GitRouteSpec, McpRouteSpec};
use super::wire::{Service, ToFront};
use crate::egress_proxy::{Connector, Refusal};
use crate::model_proxy::ModelApi;

/// How much of a body one read takes.
const CHUNK: usize = 64 * 1024;
/// How long the front has, after a request body's socket ended, to say whether the body is
/// whole: it says so on the control channel right after, so a front that does not is stuck or
/// hostile, and the request ends without its body.
const BODY_END_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(10);

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// What answers the front's requests: the services its listeners stand for.
#[derive(Default)]
struct Router {
    egress: Mutex<Option<Arc<dyn Connector>>>,
    model: Mutex<Option<Arc<dyn Backend>>>,
    /// The run's git proxy and platform tools.
    git: Mutex<Option<Arc<dyn Backend>>>,
    /// The connections given to the front, each with its place, until it says they ended.
    held: Mutex<HashMap<u64, Box<dyn Send + Sync>>>,
    /// The requests being answered (of the model proxy, the git proxy and the platform
    /// tools), by id.
    requests: Mutex<HashMap<u64, Answering>>,
    /// The tasks that answer requests, alive now.
    tasks: Arc<AtomicUsize>,
    /// The services whose listener the front has served: each once in the sandbox's life.
    served: Mutex<std::collections::HashSet<Service>>,
}

/// A request that `run` answers.
struct Answering {
    service: Service,
    /// Resolved when the front says the request body is whole.
    body_end: Option<oneshot::Sender<()>>,
    /// Cancelled when the front gives the request up, or the listener closes.
    stop: CancellationToken,
}

/// The front process of one sandbox, and what answers it.
pub struct SandboxFront {
    handle: Arc<FrontHandle>,
    router: Arc<Router>,
    dispatcher: tokio::task::JoinHandle<()>,
}

impl std::fmt::Debug for SandboxFront {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SandboxFront")
            .field("pgid", &self.handle.pgid())
            .finish_non_exhaustive()
    }
}

impl SandboxFront {
    /// Starts a sandbox's front. One that cannot confine itself serves all the same, with a
    /// warning in the log (path C part 2 decides in its last slice whether `run` refuses it).
    pub async fn start(command: &FrontCommand) -> Result<Arc<SandboxFront>> {
        let handle = FrontHandle::spawn(command, uuid::Uuid::nil())
            .await
            .context("cannot start the sandbox's front process")?;
        if !handle.status().seccomp {
            tracing::warn!(
                problem = handle.status().problem.as_deref().unwrap_or("-"),
                "the sandbox's front process is not confined"
            );
        }
        let requests = handle
            .take_requests()
            .context("the front's requests are served already")?;
        let handle = Arc::new(handle);
        let router = Arc::new(Router::default());
        let dispatcher = tokio::spawn(dispatch(requests, Arc::downgrade(&handle), router.clone()));
        Ok(Arc::new(SandboxFront {
            handle,
            router,
            dispatcher,
        }))
    }

    /// The front process.
    pub fn handle(&self) -> &FrontHandle {
        &self.handle
    }

    /// The tasks of `run` that answer the front's model requests now.
    pub fn answering(&self) -> usize {
        self.router.tasks.load(Ordering::SeqCst)
    }

    /// A sandbox has one listener of each service in its life (its run's, or its VM's), so a
    /// front serves each once: a second one is refused here, before the front hears of it,
    /// which would end the front and the sandbox's other listener with it.
    fn first(&self, service: Service) -> Result<()> {
        if !lock(&self.router.served).insert(service) {
            bail!(
                "the sandbox's front has served a {} listener already; a sandbox has one in its life",
                match service {
                    Service::Egress => "egress",
                    Service::Model => "model",
                    Service::Git => "git proxy",
                }
            );
        }
        Ok(())
    }

    /// The front serves the egress proxy's HTTP on `listener`, for clients that send
    /// `authorization`; `connector` decides and connects each destination.
    pub(crate) async fn listen_egress(
        &self,
        listener: TcpListener,
        authorization: String,
        connector: Arc<dyn Connector>,
    ) -> Result<()> {
        self.first(Service::Egress)?;
        *lock(&self.router.egress) = Some(connector);
        let listener = OwnedFd::from(listener.into_std()?);
        self.handle
            .send(&ToFront::ListenEgress { authorization }, Some(listener))
            .await
    }

    /// The front serves the model proxy's HTTP on `listener`, for the run whose secret is
    /// `secret`; `backend` answers each request it lets through.
    pub(crate) async fn listen_model(
        &self,
        listener: TcpListener,
        secret: String,
        api: ModelApi,
        base_path: String,
        backend: Arc<dyn Backend>,
    ) -> Result<()> {
        self.first(Service::Model)?;
        *lock(&self.router.model) = Some(backend);
        let listener = OwnedFd::from(listener.into_std()?);
        self.handle
            .send(
                &ToFront::ListenModel {
                    secret,
                    api,
                    base_path,
                },
                Some(listener),
            )
            .await
    }

    /// The front serves a run's git proxy and platform tools on `listener`, within `limits`,
    /// for the routes that [`SandboxFront::git_routes`] gives it; `backend` answers each request
    /// it lets through.
    pub(crate) async fn listen_git(
        &self,
        listener: TcpListener,
        limits: FrontLimits,
        backend: Arc<dyn Backend>,
    ) -> Result<()> {
        self.first(Service::Git)?;
        *lock(&self.router.git) = Some(backend);
        let listener = OwnedFd::from(listener.into_std()?);
        self.handle
            .send(&ToFront::ListenGit { limits }, Some(listener))
            .await
    }

    /// The run's routes as they are now, for the front's git listener; returns once the front
    /// has them (a round trip after them, which the front answers in order), so that a request
    /// that follows finds them.
    pub(crate) async fn git_routes(
        &self,
        routes: Vec<GitRouteSpec>,
        mcp: Vec<McpRouteSpec>,
    ) -> Result<()> {
        self.handle
            .send(&ToFront::GitRoutes { routes, mcp }, None)
            .await?;
        self.handle.ping().await
    }

    /// The listener of `service` closes: `run` answers none of its requests from now on, ends
    /// those it answers, and tells the front to stop serving it.
    pub(crate) fn unlisten(self: &Arc<Self>, service: Service) {
        match service {
            Service::Egress => {
                lock(&self.router.egress).take();
                lock(&self.router.held).clear();
            }
            Service::Model | Service::Git => {
                match service {
                    Service::Model => lock(&self.router.model).take(),
                    _ => lock(&self.router.git).take(),
                };
                for answering in lock(&self.router.requests).values() {
                    if answering.service == service {
                        answering.stop.cancel();
                    }
                }
            }
        }
        if let Ok(runtime) = tokio::runtime::Handle::try_current() {
            let front = self.clone();
            runtime.spawn(async move {
                if let Err(e) = front
                    .handle
                    .send(&ToFront::Unlisten { service }, None)
                    .await
                {
                    tracing::debug!(error = %format!("{e:#}"), "cannot close a listener of the sandbox's front");
                }
            });
        }
    }
}

impl Drop for SandboxFront {
    fn drop(&mut self) {
        // `run` ends this front: nothing it still sends, and no reset of its channel, is a
        // fault from now on (the dispatcher that took its requests goes first).
        self.handle.ending();
        self.dispatcher.abort();
        for (_, answering) in lock(&self.router.requests).drain() {
            answering.stop.cancel();
        }
        lock(&self.router.held).clear();
        // The front goes with the handle's last owner: its group is killed and it is reaped.
    }
}

/// Answers the front's requests until it ends.
async fn dispatch(
    mut requests: tokio::sync::mpsc::Receiver<FrontRequest>,
    front: Weak<FrontHandle>,
    router: Arc<Router>,
) {
    while let Some(request) = requests.recv().await {
        match request {
            FrontRequest::Connect { req, host, port } => {
                let connector = lock(&router.egress).clone();
                let (front, router) = (front.clone(), router.clone());
                tokio::spawn(async move {
                    let granted = match connector {
                        Some(connector) => connector.connect(host, port).await,
                        None => Err(Refusal {
                            status: 503,
                            message: "tinfirm egress proxy: the listener is closed\n".into(),
                            off_allowlist: false,
                        }),
                    };
                    let Some(front) = front.upgrade() else {
                        return;
                    };
                    let answered = match granted {
                        Ok(granted) => match granted.stream.into_std() {
                            Ok(stream) => {
                                lock(&router.held).insert(req, granted.held);
                                front.grant(req, OwnedFd::from(stream)).await
                            }
                            Err(e) => {
                                let refusal = Refusal {
                                    status: 502,
                                    message: format!("tinfirm egress proxy: {e}\n"),
                                    off_allowlist: false,
                                };
                                front.refuse(req, &refusal).await
                            }
                        },
                        Err(refusal) => front.refuse(req, &refusal).await,
                    };
                    if let Err(e) = answered {
                        lock(&router.held).remove(&req);
                        tracing::debug!(error = %format!("{e:#}"), "cannot answer the sandbox's front");
                    }
                });
            }
            FrontRequest::Closed { req } => {
                lock(&router.held).remove(&req);
            }
            FrontRequest::Request { req, head } => {
                let service = head.target.service();
                let backend = match service {
                    Service::Model => lock(&router.model).clone(),
                    _ => lock(&router.git).clone(),
                };
                let (body_end, body_end_rx) = oneshot::channel();
                let stop = CancellationToken::new();
                lock(&router.requests).insert(
                    req,
                    Answering {
                        service,
                        body_end: Some(body_end),
                        stop: stop.clone(),
                    },
                );
                let task = Task::new(&router);
                let (front, router) = (front.clone(), router.clone());
                tokio::spawn(async move {
                    let _task = task;
                    answer(&front, backend, req, head, body_end_rx, stop).await;
                    lock(&router.requests).remove(&req);
                });
            }
            FrontRequest::BodyEnd { req } => {
                if let Some(answering) = lock(&router.requests).get_mut(&req)
                    && let Some(body_end) = answering.body_end.take()
                {
                    let _ = body_end.send(());
                }
            }
            FrontRequest::Abort { req } => {
                if let Some(answering) = lock(&router.requests).get_mut(&req) {
                    answering.body_end.take();
                    answering.stop.cancel();
                }
            }
            FrontRequest::Declined { service, why } => {
                let backend = match service {
                    Service::Model => lock(&router.model).clone(),
                    Service::Git => lock(&router.git).clone(),
                    Service::Egress => None,
                };
                if let Some(backend) = backend {
                    backend.declined(why);
                }
            }
        }
    }
    // The front ended: its requests end with it, upstream requests included, and the places
    // of its connections come back.
    for answering in lock(&router.requests).values() {
        answering.stop.cancel();
    }
    lock(&router.held).clear();
}

/// One task of [`Router::tasks`], counted while it lives.
struct Task(Arc<AtomicUsize>);

impl Task {
    fn new(router: &Router) -> Task {
        router.tasks.fetch_add(1, Ordering::SeqCst);
        Task(router.tasks.clone())
    }
}

impl Drop for Task {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

/// Answers the front's model request `req`: its socket, the run half's answer, its body on the
/// socket, and the end. Whatever happens, the request ends with `end`, unless the front is gone.
async fn answer(
    front: &Weak<FrontHandle>,
    backend: Option<Arc<dyn Backend>>,
    req: u64,
    head: RequestHead,
    body_end: oneshot::Receiver<()>,
    stop: CancellationToken,
) {
    let ok = answer_body(front, backend, req, head, body_end, &stop).await;
    if let Some(front) = front.upgrade()
        && let Err(e) = front.end(req, ok).await
    {
        tracing::debug!(error = %format!("{e:#}"), "cannot end a request of the sandbox's front");
    }
}

/// Whether the answer's body went whole on the request's socket.
async fn answer_body(
    front: &Weak<FrontHandle>,
    backend: Option<Arc<dyn Backend>>,
    req: u64,
    head: RequestHead,
    body_end: oneshot::Receiver<()>,
    stop: &CancellationToken,
) -> bool {
    let Ok((ours, theirs)) = std::os::unix::net::UnixStream::pair() else {
        return false;
    };
    let Ok(ours) = ours
        .set_nonblocking(true)
        .and_then(|()| tokio::net::UnixStream::from_std(ours))
    else {
        return false;
    };
    let Some(handle) = front.upgrade() else {
        return false;
    };
    if handle.body(req, OwnedFd::from(theirs)).await.is_err() {
        return false;
    }
    drop(handle);
    let (read, mut write) = ours.into_split();
    let body = socket_body(read, body_end);
    let (response, body) = match backend {
        Some(backend) => {
            tokio::select! {
                answer = backend.call(head, body) => answer,
                _ = stop.cancelled() => return false,
            }
        }
        None => {
            let response = crate::model_proxy::closed_listener();
            (response.0, response.1)
        }
    };
    let Some(handle) = front.upgrade() else {
        return false;
    };
    if handle.respond(req, response).await.is_err() {
        return false;
    }
    drop(handle);
    let copied = tokio::select! {
        copied = copy_body(body, &mut write) => copied,
        _ = stop.cancelled() => false,
    };
    copied && write.shutdown().await.is_ok()
}

/// Writes an answer's body on the socket, part by part: `false` when it broke, or the front
/// stopped reading for good.
async fn copy_body(mut body: ProxyBody, write: &mut tokio::net::unix::OwnedWriteHalf) -> bool {
    while let Some(frame) = body.frame().await {
        let Ok(frame) = frame else {
            return false;
        };
        if let Ok(data) = frame.into_data()
            && write.write_all(&data).await.is_err()
        {
            return false;
        }
    }
    true
}

/// A request body as it comes on its socket: whole only once the front says so, after the
/// socket's end. A Unix socket closed with bytes unread resets its peer instead of ending it,
/// so a reset counts as the end here, and the front's word decides.
fn socket_body(
    read: tokio::net::unix::OwnedReadHalf,
    body_end: oneshot::Receiver<()>,
) -> ProxyBody {
    let stream = futures::stream::unfold(Some((read, body_end)), |state| async move {
        let (mut read, body_end) = state?;
        let mut buffer = vec![0u8; CHUNK];
        match read.read(&mut buffer).await {
            Ok(n) if n > 0 => {
                buffer.truncate(n);
                Some((Ok(Frame::data(Bytes::from(buffer))), Some((read, body_end))))
            }
            // The socket's end, or its reset when the front closed it with bytes of the
            // answer unread: the front's word says whether the body is whole.
            _ => match tokio::time::timeout(BODY_END_TIMEOUT, body_end).await {
                Ok(Ok(())) => None,
                _ => Some((
                    Err::<Frame<Bytes>, BoxError>("the request body did not end".into()),
                    None,
                )),
            },
        }
    });
    http_body_util::StreamBody::new(stream).boxed_unsync()
}
