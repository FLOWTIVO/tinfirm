//! Runner images (docs/archive/v1/CONVENTIONS.md §15.2 `images build`, §15.3).
//!
//! The Dockerfiles in `runner/images/` and the files they copy are embedded into the binary,
//! so `images build` works from an installed binary without the repository. Images are built
//! on this machine with the Docker Engine API and tagged `tinfirm-runner-*:local`. Nothing is
//! pushed; the Claude image contains Claude Code and must never be published.
//! The pi and OpenCode images (ADR-0041) are built the same way, and so is the multi-runtime
//! image (ADR-0101), which holds every runtime for a pool of ready VMs that serves them all.
//!
//! `images build --build-arg KEY=VALUE` sets the `ARG`s that the Dockerfiles declare, such as
//! the package mirrors of a host without internet access (runner/README.md).
//!
//! The host keeps its images up to date itself (ADR-0062): every image carries the digest of
//! what it was built from ([`SPEC_LABEL`]: the Dockerfile, the files it copies, its build
//! arguments and, for a runtime image, the base's digest). `images build` records its build
//! arguments and runtimes in `images.json` ([`ImageSettings`]), and `run` rebuilds, before it
//! connects, every image whose digest is not the one this binary would build ([`refresh`]).

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::path::Path;

use anyhow::{Context, Result, bail};
use bollard::Docker;
use bollard::query_parameters::BuildImageOptionsBuilder;
use futures::StreamExt;
use serde::{Deserialize, Serialize};
use tinfirm_protocol::Runtime;

pub const BASE_IMAGE: &str = "tinfirm-runner-base:local";
pub const CLAUDE_IMAGE: &str = "tinfirm-runner-claude:local";
pub const CODEX_IMAGE: &str = "tinfirm-runner-codex:local";
pub const PI_IMAGE: &str = "tinfirm-runner-pi:local";
pub const OPENCODE_IMAGE: &str = "tinfirm-runner-opencode:local";
pub const MULTI_IMAGE: &str = "tinfirm-runner-multi:local";

/// Label with the pinned CLI and adapter versions, reported in `hello.runtimes[].version`.
pub const VERSION_LABEL: &str = "dev.tinfirm.runtime-version";

/// Label with the digest of what the image was built from ([`spec`]), set by the build.
pub const SPEC_LABEL: &str = "dev.tinfirm.image-spec";

#[derive(Debug, Clone, Copy)]
pub struct ImageDef {
    pub tag: &'static str,
    pub dockerfile: &'static str,
    pub runtime: Option<Runtime>,
    /// The multi-runtime image (ADR-0101): every runtime, and no command of its own.
    pub multi: bool,
    /// The files that the Dockerfile copies, by their path in the build context.
    pub files: &'static [(&'static str, &'static [u8])],
}

impl ImageDef {
    /// Whether it is built `FROM` the base: every image but the base itself.
    pub fn on_base(&self) -> bool {
        self.runtime.is_some() || self.multi
    }
}

pub const BASE: ImageDef = ImageDef {
    tag: BASE_IMAGE,
    dockerfile: include_str!("../../../images/base/Dockerfile"),
    runtime: None,
    multi: false,
    files: &[
        (
            "docker.asc",
            include_bytes!("../../../images/base/docker.asc"),
        ),
        (
            "tinfirm-sandbox-entrypoint",
            include_bytes!("../../../images/base/tinfirm-sandbox-entrypoint"),
        ),
        (
            "tinfirm-sandbox-setup",
            include_bytes!("../../../images/base/tinfirm-sandbox-setup"),
        ),
        // Run by the runtime images after `npm ci` (threat model Q16).
        (
            "tinfirm-npm-check",
            include_bytes!("../../../images/base/tinfirm-npm-check"),
        ),
    ],
};

pub const CLAUDE: ImageDef = ImageDef {
    tag: CLAUDE_IMAGE,
    dockerfile: include_str!("../../../images/claude/Dockerfile"),
    runtime: Some(Runtime::ClaudeCode),
    // The npm packages, installed with `npm ci --ignore-scripts` (I6 of the security review of
    // 2026-10-27).
    multi: false,
    files: &[
        (
            "package.json",
            include_bytes!("../../../images/claude/package.json"),
        ),
        (
            "package-lock.json",
            include_bytes!("../../../images/claude/package-lock.json"),
        ),
        // The SHA-256 of the Claude Code binary, from Anthropic's signed release manifest,
        // which the build checks the installed binary against (threat model Q16).
        (
            "claude-code.sha256",
            include_bytes!("../../../images/claude/claude-code.sha256"),
        ),
    ],
};

pub const CODEX: ImageDef = ImageDef {
    tag: CODEX_IMAGE,
    dockerfile: include_str!("../../../images/codex/Dockerfile"),
    runtime: Some(Runtime::Codex),
    // The npm packages, installed with `npm ci --ignore-scripts` (I6 of the security review of
    // 2026-10-27).
    multi: false,
    files: &[
        (
            "package.json",
            include_bytes!("../../../images/codex/package.json"),
        ),
        (
            "package-lock.json",
            include_bytes!("../../../images/codex/package-lock.json"),
        ),
    ],
};

pub const PI: ImageDef = ImageDef {
    tag: PI_IMAGE,
    dockerfile: include_str!("../../../images/pi/Dockerfile"),
    runtime: Some(Runtime::Pi),
    multi: false,
    // The adapter, and the npm packages, installed with `npm ci --ignore-scripts`.
    files: &[
        (
            "tinfirm-pi-acp.mjs",
            include_bytes!("../../../images/pi/tinfirm-pi-acp.mjs"),
        ),
        (
            "package.json",
            include_bytes!("../../../images/pi/package.json"),
        ),
        (
            "package-lock.json",
            include_bytes!("../../../images/pi/package-lock.json"),
        ),
    ],
};

pub const OPENCODE: ImageDef = ImageDef {
    tag: OPENCODE_IMAGE,
    dockerfile: include_str!("../../../images/opencode/Dockerfile"),
    runtime: Some(Runtime::Opencode),
    // The npm packages, installed with `npm ci --ignore-scripts` (I6 of the security review of
    // 2026-10-27).
    multi: false,
    files: &[
        (
            "package.json",
            include_bytes!("../../../images/opencode/package.json"),
        ),
        (
            "package-lock.json",
            include_bytes!("../../../images/opencode/package-lock.json"),
        ),
    ],
};

/// Every runtime in one image, for a pool of ready VMs that serves them all (ADR-0101). It
/// pins the versions that the runtimes' own images pin, and has no command: the host names the
/// run's adapter ([`adapter_command`]).
pub const MULTI: ImageDef = ImageDef {
    tag: MULTI_IMAGE,
    dockerfile: include_str!("../../../images/multi/Dockerfile"),
    runtime: None,
    multi: true,
    // pi's adapter, and the npm lockfiles of the runtimes' images, which it installs from as
    // they do (I6 of the security review of 2026-10-27).
    files: &[
        (
            "tinfirm-pi-acp.mjs",
            include_bytes!("../../../images/pi/tinfirm-pi-acp.mjs"),
        ),
        (
            "claude/package.json",
            include_bytes!("../../../images/claude/package.json"),
        ),
        (
            "claude/package-lock.json",
            include_bytes!("../../../images/claude/package-lock.json"),
        ),
        (
            "claude/claude-code.sha256",
            include_bytes!("../../../images/claude/claude-code.sha256"),
        ),
        (
            "codex/package.json",
            include_bytes!("../../../images/codex/package.json"),
        ),
        (
            "codex/package-lock.json",
            include_bytes!("../../../images/codex/package-lock.json"),
        ),
        (
            "pi/package.json",
            include_bytes!("../../../images/pi/package.json"),
        ),
        (
            "pi/package-lock.json",
            include_bytes!("../../../images/pi/package-lock.json"),
        ),
        (
            "opencode/package.json",
            include_bytes!("../../../images/opencode/package.json"),
        ),
        (
            "opencode/package-lock.json",
            include_bytes!("../../../images/opencode/package-lock.json"),
        ),
    ],
};

/// The image of every runtime, in the order of [`Runtime::ALL`].
pub const RUNTIME_IMAGES: [ImageDef; 4] = [CLAUDE, CODEX, PI, OPENCODE];

/// What built this binary, as its bridge names it in a VM (ADR-0101): its version, and a digest
/// of every image's definition (the [`spec`] of each, without build arguments). A VM image holds
/// the runner images that its copy of this binary built, so two builds whose digests differ
/// built different images, even of one version.
pub fn build_id() -> &'static str {
    static BUILD: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    BUILD.get_or_init(|| {
        let mut ctx = ring::digest::Context::new(&ring::digest::SHA256);
        for def in [BASE].iter().chain(&RUNTIME_IMAGES).chain([&MULTI]) {
            ctx.update(spec(def, &BASE, &[]).as_bytes());
            ctx.update(b"\n");
        }
        let digest: String = ctx.finish().as_ref()[..6]
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        format!("{}+images.{digest}", env!("CARGO_PKG_VERSION"))
    })
}

/// The ACP adapter of a runtime, as its own image's `CMD`: what the host names in the bridge's
/// `start` for the multi-runtime image, which has no command of its own.
pub fn adapter_command(runtime: Runtime) -> &'static [&'static str] {
    match runtime {
        Runtime::ClaudeCode => &["claude-agent-acp"],
        Runtime::Codex => &["codex-acp"],
        Runtime::Pi => &["tinfirm-pi-acp"],
        Runtime::Opencode => &["opencode", "acp"],
    }
}

/// The image definition of a runtime.
pub fn def_for(runtime: Runtime) -> ImageDef {
    match runtime {
        Runtime::ClaudeCode => CLAUDE,
        Runtime::Codex => CODEX,
        Runtime::Pi => PI,
        Runtime::Opencode => OPENCODE,
    }
}

/// The build arguments a Dockerfile declares (`ARG NAME` or `ARG NAME=default`), in order.
pub fn declared_args(dockerfile: &str) -> Vec<&str> {
    dockerfile
        .lines()
        .filter_map(|line| {
            let rest = line.trim_start().strip_prefix("ARG ")?;
            let name = rest.trim_start().split(['=', ' ', '\t']).next()?;
            (!name.is_empty()).then_some(name)
        })
        .collect()
}

/// Parses one `images build --build-arg KEY=VALUE`.
pub fn parse_build_arg(entry: &str) -> std::result::Result<(String, String), String> {
    let Some((key, value)) = entry.split_once('=') else {
        return Err("expected KEY=VALUE".into());
    };
    let key = key.trim();
    if key.is_empty() || !key.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        return Err(format!("`{key}` is not a build argument name"));
    }
    if value.chars().any(|c| c.is_control()) {
        return Err(format!("the value of {key} contains a control character"));
    }
    // The values end up in the image's history (`docker history`); never a credential.
    if crate::sandbox::has_url_userinfo(value) {
        return Err(format!(
            "the value of {key} contains a URL with a user name or password; build arguments are kept in the image's history"
        ));
    }
    Ok((key.to_string(), value.to_string()))
}

/// Checks build arguments against the ones the runner images declare: an unknown name is
/// a typo that Docker would only warn about.
pub fn check_build_args(args: &[(String, String)]) -> Result<()> {
    let known: BTreeSet<&str> = std::iter::once(&BASE)
        .chain(RUNTIME_IMAGES.iter())
        .flat_map(|def| declared_args(def.dockerfile))
        .collect();
    let mut seen = HashSet::new();
    for (key, _) in args {
        if !known.contains(key.as_str()) {
            bail!(
                "the runner images have no build argument {key}; they take: {}",
                known.into_iter().collect::<Vec<_>>().join(", ")
            );
        }
        if !seen.insert(key.as_str()) {
            bail!("--build-arg {key} is given more than once");
        }
    }
    Ok(())
}

/// The build arguments of `args` that `def`'s Dockerfile declares, so that Docker never
/// warns about one it did not use.
pub fn build_args_for(def: &ImageDef, args: &[(String, String)]) -> HashMap<String, String> {
    let declared = declared_args(def.dockerfile);
    args.iter()
        .filter(|(key, _)| declared.contains(&key.as_str()))
        .cloned()
        .collect()
}

/// The digest of what `def` is built from with `args`: its Dockerfile, the files it copies,
/// the build arguments it declares, and for a runtime image (built `FROM` `base`) the base's
/// digest, so that a changed base makes every runtime image stale too.
pub fn spec(def: &ImageDef, base: &ImageDef, args: &[(String, String)]) -> String {
    let mut ctx = ring::digest::Context::new(&ring::digest::SHA256);
    let mut field = |name: &str, data: &[u8]| {
        ctx.update(format!("{name} {}\n", data.len()).as_bytes());
        ctx.update(data);
        ctx.update(b"\n");
    };
    field("tinfirm-image-spec", b"1");
    field("Dockerfile", def.dockerfile.as_bytes());
    for (path, data) in def.files {
        field(&format!("file {path}"), data);
    }
    let declared: BTreeMap<String, String> = build_args_for(def, args).into_iter().collect();
    for (key, value) in &declared {
        field(&format!("arg {key}"), value.as_bytes());
    }
    if def.on_base() {
        field("base", spec(base, base, args).as_bytes());
    }
    ctx.finish()
        .as_ref()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// What `images build` chose on this host, for the rebuilds of `run` ([`refresh`]): the build
/// arguments of the last build, every runtime whose image was built, and whether the
/// multi-runtime image was. `images.json` in the config directory.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImageSettings {
    #[serde(default)]
    pub build_args: BTreeMap<String, String>,
    #[serde(default)]
    pub runtimes: Vec<Runtime>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub multi: bool,
}

impl ImageSettings {
    /// The recorded settings; none (the defaults) when `images build` never recorded any.
    pub fn load(path: &Path) -> Result<ImageSettings> {
        match std::fs::read(path) {
            Ok(bytes) => serde_json::from_slice(&bytes)
                .with_context(|| format!("{} is not valid", path.display())),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(ImageSettings::default()),
            Err(e) => Err(e).with_context(|| format!("cannot read {}", path.display())),
        }
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        let json = serde_json::to_vec_pretty(self)?;
        crate::secure_fs::write_private_file(path, &json)
    }

    /// A build of `defs` with `args`: its arguments replace the recorded ones, its runtimes
    /// join them.
    pub fn record(&mut self, defs: &[ImageDef], args: &[(String, String)]) {
        self.build_args = args.iter().cloned().collect();
        let built: Vec<Runtime> = defs.iter().filter_map(|def| def.runtime).collect();
        self.runtimes = Runtime::ALL
            .into_iter()
            .filter(|r| self.runtimes.contains(r) || built.contains(r))
            .collect();
        self.multi |= defs.iter().any(|def| def.multi);
    }

    /// Whether `images build` built `def` here, so that `run` builds it again when it is gone.
    pub fn built(&self, def: &ImageDef) -> bool {
        def.runtime.is_some_and(|r| self.runtimes.contains(&r)) || (def.multi && self.multi)
    }

    /// The recorded build arguments that the images still declare, and the names of the
    /// others, which a newer binary's Dockerfiles dropped.
    pub fn args(&self) -> (Vec<(String, String)>, Vec<String>) {
        let known: BTreeSet<&str> = [&BASE, &MULTI]
            .into_iter()
            .chain(RUNTIME_IMAGES.iter())
            .flat_map(|def| declared_args(def.dockerfile))
            .collect();
        let (kept, dropped): (Vec<_>, Vec<_>) = self
            .build_args
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .partition(|(k, _)| known.contains(k.as_str()));
        (kept, dropped.into_iter().map(|(k, _)| k).collect())
    }
}

/// Why an image is rebuilt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Staleness {
    /// It was built from other Dockerfiles or build arguments (or before the digest label).
    Outdated,
    /// `images build` built it here, and it is gone.
    Missing,
}

impl Staleness {
    pub fn describe(self) -> &'static str {
        match self {
            Staleness::Outdated => "built from other Dockerfiles or build arguments",
            Staleness::Missing => "missing",
        }
    }
}

/// The images to rebuild: the base first, when it is not current and a runtime image is
/// rebuilt on it, then the runtime images.
#[derive(Debug, Clone, Default)]
pub struct RefreshPlan {
    pub base: Option<Staleness>,
    pub runtimes: Vec<(ImageDef, Staleness)>,
}

impl RefreshPlan {
    pub fn is_empty(&self) -> bool {
        self.runtimes.is_empty()
    }
}

/// Plans the rebuild of the runtime images in `defs` (on `base`) from the digest labels in
/// `present` (tag → the image's [`SPEC_LABEL`], `None` without one; a tag that is absent has no
/// image). An image that is current stays; one that is not is rebuilt; a missing one only when
/// `recorded` says `images build` built it here, since that chooses which images a host has.
pub fn plan_refresh(
    base: &ImageDef,
    defs: &[ImageDef],
    args: &[(String, String)],
    recorded: &ImageSettings,
    present: &HashMap<String, Option<String>>,
) -> RefreshPlan {
    let mut plan = RefreshPlan::default();
    for def in defs {
        let staleness = match present.get(def.tag) {
            Some(label) if label.as_deref() == Some(spec(def, base, args).as_str()) => continue,
            Some(_) => Staleness::Outdated,
            None if recorded.built(def) => Staleness::Missing,
            None => continue,
        };
        plan.runtimes.push((*def, staleness));
    }
    if !plan.runtimes.is_empty() {
        plan.base = match present.get(base.tag) {
            Some(label) if label.as_deref() == Some(spec(base, base, args).as_str()) => None,
            Some(_) => Some(Staleness::Outdated),
            None => Some(Staleness::Missing),
        };
    }
    plan
}

/// What [`refresh`] did.
#[derive(Debug, Default)]
pub struct Refreshed {
    /// The images rebuilt, with why.
    pub built: Vec<(&'static str, Staleness)>,
    /// The images that could not be rebuilt; the host goes on with them as they are.
    pub failed: Vec<(&'static str, String)>,
}

/// Rebuilds the images that [`plan_refresh`] finds stale: the base (pulling its `FROM` image)
/// unless it is current, then each runtime image. When the base fails, no runtime image is
/// built on the old one. Each failure is reported, not returned: a host whose registry or
/// mirror is down goes on with the images it has.
pub async fn refresh(
    docker: &Docker,
    base: &ImageDef,
    plan: &RefreshPlan,
    args: &[(String, String)],
    mut log: impl FnMut(&str),
) -> Refreshed {
    let mut out = Refreshed::default();
    if let Some(staleness) = plan.base {
        match build_one(docker, base, base, false, args, &mut log).await {
            Ok(()) => out.built.push((base.tag, staleness)),
            Err(e) => {
                out.failed.push((base.tag, format!("{e:#}")));
                for (def, _) in &plan.runtimes {
                    out.failed.push((
                        def.tag,
                        format!("not built: {} could not be built", base.tag),
                    ));
                }
                return out;
            }
        }
    }
    for (def, staleness) in &plan.runtimes {
        match build_one(docker, def, base, false, args, &mut log).await {
            Ok(()) => out.built.push((def.tag, *staleness)),
            Err(e) => out.failed.push((def.tag, format!("{e:#}"))),
        }
    }
    out
}

/// Builds `def` (a runtime image on `base`) with the arguments it declares and its digest label.
async fn build_one(
    docker: &Docker,
    def: &ImageDef,
    base: &ImageDef,
    no_cache: bool,
    args: &[(String, String)],
    log: &mut impl FnMut(&str),
) -> Result<()> {
    log(&format!("==> building {}\n", def.tag));
    let context = build_context(def.dockerfile, def.files)?;
    let labels = HashMap::from([(SPEC_LABEL.to_string(), spec(def, base, args))]);
    // Pull only the public base (node); the runtime images build FROM our local base.
    build_image(
        docker,
        def.tag,
        context,
        no_cache,
        !def.on_base(),
        &build_args_for(def, args),
        &labels,
        log,
    )
    .await
}

/// The default image of a runtime.
pub fn default_image(runtime: Runtime) -> &'static str {
    def_for(runtime).tag
}

/// The multi-runtime image's override, `TINFIRM_RUNNER_IMAGE_MULTI` (development and tests only).
pub fn multi_override_from_env() -> Option<String> {
    std::env::var("TINFIRM_RUNNER_IMAGE_MULTI")
        .ok()
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

/// Image overrides from `TINFIRM_RUNNER_IMAGE_CLAUDE`, `_CODEX`, `_PI` and `_OPENCODE`
/// (development and tests only).
pub fn overrides_from_env() -> HashMap<Runtime, String> {
    let mut out = HashMap::new();
    for (runtime, var) in [
        (Runtime::ClaudeCode, "TINFIRM_RUNNER_IMAGE_CLAUDE"),
        (Runtime::Codex, "TINFIRM_RUNNER_IMAGE_CODEX"),
        (Runtime::Pi, "TINFIRM_RUNNER_IMAGE_PI"),
        (Runtime::Opencode, "TINFIRM_RUNNER_IMAGE_OPENCODE"),
    ] {
        if let Ok(value) = std::env::var(var)
            && !value.trim().is_empty()
        {
            out.insert(runtime, value.trim().to_string());
        }
    }
    out
}

/// A build context (tar) with a single Dockerfile plus extra files.
pub fn build_context(dockerfile: &str, extra: &[(&str, &[u8])]) -> Result<Vec<u8>> {
    let mut archive = tar::Builder::new(Vec::new());
    let mut add = |path: &str, data: &[u8], mode: u32| -> Result<()> {
        let mut header = tar::Header::new_gnu();
        header.set_path(path)?;
        header.set_size(data.len() as u64);
        header.set_mode(mode);
        header.set_mtime(0);
        header.set_cksum();
        archive.append(&header, data)?;
        Ok(())
    };
    add("Dockerfile", dockerfile.as_bytes(), 0o644)?;
    for (path, data) in extra {
        add(path, data, 0o644)?;
    }
    Ok(archive.into_inner()?)
}

/// Builds one image with the Engine API, streaming the build log to `log`. `labels` are added
/// to the image's own.
#[allow(clippy::too_many_arguments)]
pub async fn build_image(
    docker: &Docker,
    tag: &str,
    context: Vec<u8>,
    no_cache: bool,
    pull: bool,
    build_args: &HashMap<String, String>,
    labels: &HashMap<String, String>,
    mut log: impl FnMut(&str),
) -> Result<()> {
    let mut options = BuildImageOptionsBuilder::default()
        .dockerfile("Dockerfile")
        .t(tag)
        .rm(true)
        .forcerm(true)
        .nocache(no_cache);
    if pull {
        options = options.pull("true");
    }
    if !build_args.is_empty() {
        options = options.buildargs(build_args);
    }
    if !labels.is_empty() {
        options = options.labels(labels);
    }
    let mut stream = docker.build_image(
        options.build(),
        None,
        Some(bollard::body_full(context.into())),
    );
    while let Some(item) = stream.next().await {
        let info = item.with_context(|| format!("building {tag} failed"))?;
        if let Some(detail) = info.error_detail {
            bail!(
                "building {tag} failed: {}",
                detail.message.unwrap_or_else(|| "unknown error".into())
            );
        }
        if let Some(text) = info.stream {
            log(&text);
        } else if let Some(status) = info.status {
            log(&format!("{status}\n"));
        }
    }
    Ok(())
}

/// Builds the base image and the runtime images in `defs`, with the build arguments that
/// each Dockerfile declares (checked with [`check_build_args`]).
pub async fn build_all(
    docker: &Docker,
    defs: &[ImageDef],
    no_cache: bool,
    build_args: &[(String, String)],
    mut log: impl FnMut(&str),
) -> Result<()> {
    check_build_args(build_args)?;
    for def in std::iter::once(&BASE).chain(defs.iter()) {
        build_one(docker, def, &BASE, no_cache, build_args, &mut log).await?;
    }
    Ok(())
}

/// The digest labels of the base and the runtime images in `defs` that exist (tag → label),
/// for [`plan_refresh`].
pub async fn present_specs(
    docker: &Docker,
    base: &ImageDef,
    defs: &[ImageDef],
) -> Result<HashMap<String, Option<String>>> {
    let mut present = HashMap::new();
    for def in std::iter::once(base).chain(defs.iter()) {
        if let Some(status) = inspect(docker, def.tag).await? {
            present.insert(def.tag.to_string(), status.spec);
        }
    }
    Ok(present)
}

/// Presence, version label and digest label of an image.
#[derive(Debug, Clone)]
pub struct ImageStatus {
    pub id: String,
    pub version: Option<String>,
    pub spec: Option<String>,
}

pub async fn inspect(docker: &Docker, image: &str) -> Result<Option<ImageStatus>> {
    match docker.inspect_image(image).await {
        Ok(info) => {
            let labels = info.config.and_then(|c| c.labels).unwrap_or_default();
            Ok(Some(ImageStatus {
                id: info.id.unwrap_or_default(),
                version: labels.get(VERSION_LABEL).cloned(),
                spec: labels.get(SPEC_LABEL).cloned(),
            }))
        }
        Err(bollard::errors::Error::DockerResponseServerError {
            status_code: 404, ..
        }) => Ok(None),
        Err(e) => Err(e).context("cannot inspect image"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::path::Path;
    use std::process::{Command, Output};

    #[test]
    fn dockerfiles_are_embedded_and_pinned() {
        assert!(BASE.dockerfile.contains("FROM node:26."));
        assert!(BASE.dockerfile.contains("usermod --login agent"));
        assert!(BASE.dockerfile.contains("DOCKER_CE_VERSION=5:"));
        assert!(
            BASE.dockerfile
                .contains("ENTRYPOINT [\"tinfirm-sandbox-entrypoint\"]")
        );
        assert!(CLAUDE.dockerfile.contains("FROM tinfirm-runner-base:local"));
        assert!(CLAUDE.dockerfile.contains("CLAUDE_AGENT_ACP_VERSION=0."));
        assert!(CLAUDE.dockerfile.contains("CMD [\"claude-agent-acp\"]"));
        assert!(CODEX.dockerfile.contains("CODEX_ACP_VERSION=1."));
        assert!(CODEX.dockerfile.contains("CMD [\"codex-acp\"]"));
        assert!(PI.dockerfile.contains("PI_VERSION=1."));
        assert!(PI.dockerfile.contains("CMD [\"tinfirm-pi-acp\"]"));
        // OpenCode 2 is its own npm package (ADR-0061).
        assert!(OPENCODE.dockerfile.contains("OPENCODE_VERSION=2."));
        // The lockfile installs the versions the build arguments name and the build checks.
        for (def, arg, package) in [
            (
                CLAUDE,
                "CLAUDE_AGENT_ACP_VERSION",
                "@agentclientprotocol/claude-agent-acp",
            ),
            (CODEX, "CODEX_ACP_VERSION", "@agentclientprotocol/codex-acp"),
            (CODEX, "CODEX_VERSION", "@openai/codex"),
            (PI, "PI_VERSION", "@earendil-works/pi-coding-agent"),
            (OPENCODE, "OPENCODE_VERSION", "@opencode/cli"),
        ] {
            let version = def
                .dockerfile
                .lines()
                .find_map(|l| l.strip_prefix(&format!("ARG {arg}=")))
                .unwrap();
            let lock: serde_json::Value = serde_json::from_slice(
                def.files
                    .iter()
                    .find(|(path, _)| *path == "package-lock.json")
                    .unwrap()
                    .1,
            )
            .unwrap();
            assert_eq!(
                lock["packages"][format!("node_modules/{package}")]["version"],
                version,
                "{package}"
            );
        }
        assert!(OPENCODE.dockerfile.contains("CMD [\"opencode\", \"acp\"]"));
        for def in RUNTIME_IMAGES {
            assert!(def.dockerfile.contains("FROM tinfirm-runner-base:local"));
            assert!(
                def.dockerfile
                    .contains(&format!("dev.tinfirm.runtime=\"{}\"", def.runtime.unwrap()))
            );
            assert!(def.dockerfile.contains(VERSION_LABEL));
            assert!(def.dockerfile.contains("USER 1000:1000"));
            // The entrypoint runs first and execs the adapter.
            assert!(
                def.dockerfile
                    .contains("ENTRYPOINT [\"tinfirm-sandbox-entrypoint\"]")
            );
            assert!(def.dockerfile.contains("--registry \"${NPM_REGISTRY}\""));
        }
        // Every file an image copies is in its build context.
        for def in RUNTIME_IMAGES {
            for (path, _) in def.files {
                assert!(
                    def.dockerfile
                        .lines()
                        .any(|l| l.starts_with("COPY ") && l.split(' ').any(|w| w == *path)),
                    "{path}"
                );
            }
        }
        // I6: the npm packages of every runtime come from a committed lockfile, without
        // install scripts.
        for def in RUNTIME_IMAGES {
            assert!(
                def.dockerfile.contains("npm ci --ignore-scripts"),
                "{}",
                def.tag
            );
            assert!(
                !def.dockerfile.contains("npm install --global"),
                "{}",
                def.tag
            );
            // Q16: npm ci skips an optional package whose download fails its integrity check,
            // so every npm ci is followed by the check that the lockfile's packages are there.
            let installs = def.dockerfile.matches("npm ci --ignore-scripts").count();
            assert_eq!(
                def.dockerfile.matches(" && tinfirm-npm-check \\\n").count(),
                installs,
                "{}",
                def.tag
            );
            let lock: serde_json::Value = serde_json::from_slice(
                def.files
                    .iter()
                    .find(|(path, _)| *path == "package-lock.json")
                    .unwrap()
                    .1,
            )
            .unwrap();
            assert_eq!(lock["lockfileVersion"], 3, "{}", def.tag);
            for (name, package) in lock["packages"].as_object().unwrap() {
                if !name.is_empty() {
                    assert!(
                        package["integrity"]
                            .as_str()
                            .is_some_and(|i| i.starts_with("sha512-")),
                        "{} {name}",
                        def.tag
                    );
                }
            }
        }
        // The base image is pinned by digest.
        assert!(
            BASE.dockerfile
                .lines()
                .any(|l| l.starts_with("FROM node:") && l.contains("@sha256:"))
        );
        assert!(
            PI.files[0]
                .1
                .starts_with(b"#!/usr/bin/env node\n// Tinfirm's ACP adapter for pi")
        );
        for runtime in Runtime::ALL {
            assert_eq!(def_for(runtime).runtime, Some(runtime));
        }
        for name in [
            "docker.asc",
            "tinfirm-sandbox-entrypoint",
            "tinfirm-sandbox-setup",
            "tinfirm-npm-check",
        ] {
            assert!(BASE.files.iter().any(|(path, _)| *path == name), "{name}");
            assert!(BASE.dockerfile.contains(name), "{name}");
        }
        let key = BASE
            .files
            .iter()
            .find(|(path, _)| *path == "docker.asc")
            .unwrap()
            .1;
        assert!(key.starts_with(b"-----BEGIN PGP PUBLIC KEY BLOCK-----"));
    }

    /// Q16: the claude and multi images check the Claude Code binary against the SHA-256 that
    /// claude-code.sha256 pins for their CLAUDE_CODE_VERSION, on each Linux platform the base
    /// image runs on, before the binary runs.
    #[test]
    fn the_claude_code_binary_is_checked_against_a_pinned_sha256() {
        let sums = std::str::from_utf8(
            CLAUDE
                .files
                .iter()
                .find(|(path, _)| *path == "claude-code.sha256")
                .unwrap()
                .1,
        )
        .unwrap();
        let entries: Vec<(&str, &str, &str)> = sums
            .lines()
            .filter(|l| !l.starts_with('#') && !l.trim().is_empty())
            .map(|l| {
                let fields: Vec<&str> = l.split_whitespace().collect();
                assert_eq!(fields.len(), 3, "{l}");
                (fields[0], fields[1], fields[2])
            })
            .collect();
        for (sum, _, _) in &entries {
            assert!(
                sum.len() == 64
                    && sum
                        .chars()
                        .all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c)),
                "{sum}"
            );
        }
        for def in [&CLAUDE, &MULTI] {
            let version = def
                .dockerfile
                .lines()
                .find_map(|l| l.strip_prefix("ARG CLAUDE_CODE_VERSION="))
                .unwrap();
            for platform in ["linux-x64", "linux-arm64"] {
                let pinned = entries
                    .iter()
                    .filter(|(_, v, p)| *v == version && *p == platform)
                    .count();
                assert_eq!(pinned, 1, "{} {version} {platform}", def.tag);
            }
            // The check runs before the binary does, and stops the build on a mismatch.
            let check = def.dockerfile.find("sha256sum \"${claude_bin}\"").unwrap();
            let mismatch = def.dockerfile.find("is not the pinned one").unwrap();
            let first_run = def
                .dockerfile
                .find("/usr/local/bin/claude --version")
                .unwrap();
            assert!(check < mismatch && mismatch < first_run, "{}", def.tag);
            assert!(
                def.dockerfile
                    .contains("claude-code.sha256 /opt/tinfirm/claude/")
            );
        }
        assert!(MULTI.files.iter().any(|(path, bytes)| {
            *path == "claude/claude-code.sha256" && *bytes == sums.as_bytes()
        }));
    }

    /// tinfirm-npm-check fails when a package of the lockfile that this platform needs is
    /// missing or at another version, as after npm ci skipped an optional package whose
    /// download failed its integrity check (Q16), and ignores other platforms' and dev packages.
    #[test]
    fn the_npm_check_finds_a_skipped_package() {
        let script = BASE
            .files
            .iter()
            .find(|(p, _)| *p == "tinfirm-npm-check")
            .unwrap()
            .1;
        assert!(script.starts_with(b"#!/usr/bin/env node\n"));
        let dir = tempfile::tempdir().unwrap();
        let check = dir.path().join("tinfirm-npm-check");
        std::fs::write(&check, script).unwrap();
        let app = dir.path().join("app");
        let lock = serde_json::json!({
            "lockfileVersion": 3,
            "packages": {
                "": { "name": "app" },
                "node_modules/a": { "version": "1.0.0" },
                "node_modules/native-here": {
                    "version": "2.0.0", "optional": true,
                    "os": [std::env::consts::OS], "cpu": ["x64", "arm64"]
                },
                "node_modules/native-elsewhere": {
                    "version": "2.0.0", "optional": true, "os": ["win32"]
                },
                // npm's exclusions: installed everywhere but on the excluded platform.
                "node_modules/not-on-win32": {
                    "version": "4.0.0", "optional": true, "os": ["!win32"]
                },
                // npm's wildcard: installed on every platform.
                "node_modules/anywhere": {
                    "version": "5.0.0", "optional": true, "os": ["any"], "cpu": "any"
                },
                "node_modules/not-here": {
                    "version": "4.0.0", "optional": true,
                    "os": [format!("!{}", std::env::consts::OS)]
                },
                "node_modules/tool": { "version": "3.0.0", "dev": true }
            }
        });
        std::fs::create_dir_all(&app).unwrap();
        std::fs::write(app.join("package-lock.json"), lock.to_string()).unwrap();
        let install = |name: &str, version: &str| {
            let pkg = app.join("node_modules").join(name);
            std::fs::create_dir_all(&pkg).unwrap();
            std::fs::write(
                pkg.join("package.json"),
                serde_json::json!({ "name": name, "version": version }).to_string(),
            )
            .unwrap();
        };
        let run = || Command::new("node").arg(&check).arg(&app).output().unwrap();
        install("a", "1.0.0");
        let out = run();
        assert!(!out.status.success());
        assert!(
            stderr(&out).contains("node_modules/native-here (locked 2.0.0, installed none)")
                && stderr(&out).contains("node_modules/not-on-win32 (locked 4.0.0")
                && stderr(&out).contains("node_modules/anywhere (locked 5.0.0")
                && stderr(&out).contains("(Q16)")
                && !stderr(&out).contains("native-elsewhere")
                && !stderr(&out).contains("not-here")
                && !stderr(&out).contains("node_modules/tool"),
            "{}",
            stderr(&out)
        );
        install("native-here", "2.0.1");
        assert!(stderr(&run()).contains("installed 2.0.1"));
        install("native-here", "2.0.0");
        install("not-on-win32", "4.0.0");
        install("anywhere", "5.0.0");
        let out = run();
        assert!(out.status.success(), "{}", stderr(&out));
    }

    /// The tests of the pi adapter (`node --test`), against a fake pi; they need Node, like the
    /// end-to-end tests' fake agent.
    #[test]
    fn the_pi_adapter_passes_its_tests() {
        let tests = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../images/pi/test/tinfirm-pi-acp.test.mjs");
        let out = Command::new("node")
            .args(["--test", "--test-force-exit", "--test-timeout=60000"])
            .arg(&tests)
            .output()
            .expect("node is needed for the tests of the pi adapter");
        assert!(
            out.status.success(),
            "{}{}",
            String::from_utf8_lossy(&out.stdout),
            stderr(&out)
        );
    }

    #[test]
    fn build_args_are_the_declared_ones() {
        assert_eq!(
            declared_args("FROM x\nARG A\n  ARG B=1\nARG C=\"two words\"\nRUN echo ARG D\n"),
            vec!["A", "B", "C"]
        );
        for name in [
            "APT_MIRROR",
            "DOCKER_APT_URL",
            "PIP_INDEX_URL",
            "MAVEN_REPOSITORY_URL",
            "GRADLE_DOWNLOAD_URL",
            "JDK_PACKAGES",
        ] {
            assert!(declared_args(BASE.dockerfile).contains(&name), "{name}");
        }
        for def in RUNTIME_IMAGES {
            assert!(declared_args(def.dockerfile).contains(&"NPM_REGISTRY"));
        }

        let args = vec![
            parse_build_arg("NPM_REGISTRY=https://nexus.example/repository/npm/").unwrap(),
            parse_build_arg("APT_MIRROR=http://nexus.example/repository").unwrap(),
        ];
        check_build_args(&args).unwrap();
        // Each image gets the ones its Dockerfile declares.
        let base = build_args_for(&BASE, &args);
        assert_eq!(base.len(), 1);
        assert_eq!(base["APT_MIRROR"], "http://nexus.example/repository");
        let claude = build_args_for(&CLAUDE, &args);
        assert_eq!(claude.len(), 1);
        assert_eq!(
            claude["NPM_REGISTRY"],
            "https://nexus.example/repository/npm/"
        );

        let err = check_build_args(&[("NPM_REGISTERY".into(), "x".into())])
            .unwrap_err()
            .to_string();
        assert!(err.contains("no build argument NPM_REGISTERY"), "{err}");
        assert!(err.contains("NPM_REGISTRY"), "{err}");
        let twice = vec![args[0].clone(), args[0].clone()];
        let err = check_build_args(&twice).unwrap_err().to_string();
        assert!(err.contains("more than once"), "{err}");

        assert!(parse_build_arg("NO_VALUE").is_err());
        assert!(parse_build_arg("A B=1").is_err());
        let err = parse_build_arg("NPM_REGISTRY=https://u:p4ssw0rd@nexus.example/").unwrap_err();
        assert!(err.contains("user name or password"), "{err}");
        assert!(!err.contains("p4ssw0rd"), "{err}");
    }

    /// Runs an embedded script of the base image with `sh`, with only `env` (and PATH).
    fn run_script(name: &str, args: &[&str], env: &[(&str, &str)]) -> Output {
        let script = BASE.files.iter().find(|(path, _)| *path == name).unwrap().1;
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join(name);
        std::fs::write(&path, script).unwrap();
        Command::new("sh")
            .arg(&path)
            .args(args)
            .env_clear()
            .env("PATH", std::env::var("PATH").unwrap_or_default())
            .envs(env.iter().copied())
            .output()
            .unwrap()
    }

    fn stderr(output: &Output) -> String {
        String::from_utf8_lossy(&output.stderr).into_owned()
    }

    fn read(path: &Path) -> String {
        std::fs::read_to_string(path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
    }

    #[test]
    fn scripts_are_posix_sh() {
        for name in ["tinfirm-sandbox-entrypoint", "tinfirm-sandbox-setup"] {
            let script = BASE.files.iter().find(|(p, _)| *p == name).unwrap().1;
            assert!(script.starts_with(b"#!/bin/sh\n"), "{name}");
            let dir = tempfile::tempdir().unwrap();
            let path = dir.path().join(name);
            std::fs::write(&path, script).unwrap();
            let out = Command::new("sh").arg("-n").arg(&path).output().unwrap();
            assert!(out.status.success(), "{name}: {}", stderr(&out));
        }
        // Without a command, the entrypoint refuses to start.
        let out = run_script("tinfirm-sandbox-entrypoint", &[], &[]);
        assert!(!out.status.success());
        assert!(
            stderr(&out).contains("no command to run"),
            "{}",
            stderr(&out)
        );
        let out = run_script("tinfirm-sandbox-setup", &["bogus"], &[]);
        assert!(!out.status.success());
        assert!(stderr(&out).contains("usage:"), "{}", stderr(&out));
    }

    #[test]
    fn daemon_json_comes_from_the_environment() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("etc/docker/daemon.json");
        let file_arg = file.to_str().unwrap();
        let out = run_script(
            "tinfirm-sandbox-setup",
            &["docker-config", file_arg],
            &[
                (
                    "TINFIRM_SANDBOX_REGISTRY_MIRRORS",
                    " https://m1.example:8443/ , https://m2.example/v2,",
                ),
                (
                    "TINFIRM_SANDBOX_INSECURE_REGISTRIES",
                    "nexus.internal:8083,10.0.0.0/8",
                ),
            ],
        );
        assert!(out.status.success(), "{}", stderr(&out));
        assert!(out.stdout.is_empty(), "stdout belongs to ACP");
        let json: serde_json::Value = serde_json::from_str(&read(&file)).unwrap();
        assert_eq!(
            json,
            serde_json::json!({
                "data-root": "/var/lib/docker",
                "registry-mirrors": ["https://m1.example:8443/", "https://m2.example/v2"],
                "insecure-registries": ["nexus.internal:8083", "10.0.0.0/8"],
            })
        );

        // Nothing set: empty lists; a storage driver when one is given.
        let out = run_script(
            "tinfirm-sandbox-setup",
            &["docker-config", file_arg],
            &[("TINFIRM_SANDBOX_DOCKER_STORAGE_DRIVER", "overlay2")],
        );
        assert!(out.status.success(), "{}", stderr(&out));
        let json: serde_json::Value = serde_json::from_str(&read(&file)).unwrap();
        assert_eq!(
            json,
            serde_json::json!({
                "data-root": "/var/lib/docker",
                "storage-driver": "overlay2",
                "registry-mirrors": [],
                "insecure-registries": [],
            })
        );
    }

    #[test]
    fn daemon_json_takes_the_egress_proxy() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("daemon.json");
        // Built here: the proxy's URL carries a credential, as a literal it is a lint finding.
        let proxy = format!("http://{}:{}@172.20.0.1:34567", "tinfirm", "abc_DEF-123");
        let out = run_script(
            "tinfirm-sandbox-setup",
            &["docker-config", file.to_str().unwrap()],
            &[
                ("HTTP_PROXY", proxy.as_str()),
                ("https_proxy", proxy.as_str()),
                ("NO_PROXY", "localhost,127.0.0.1,::1,172.20.0.1"),
            ],
        );
        assert!(out.status.success(), "{}", stderr(&out));
        let json: serde_json::Value = serde_json::from_str(&read(&file)).unwrap();
        assert_eq!(
            json["proxies"],
            serde_json::json!({
                "http-proxy": proxy,
                "https-proxy": proxy,
                "no-proxy": "localhost,127.0.0.1,::1,172.20.0.1",
            })
        );
        // A proxy value with a quote is refused.
        let out = run_script(
            "tinfirm-sandbox-setup",
            &["docker-config", file.to_str().unwrap()],
            &[("HTTPS_PROXY", "http://u:p@evil\"")],
        );
        assert!(!out.status.success());
    }

    #[test]
    fn daemon_json_refuses_unsafe_values() {
        for (key, value, why) in [
            (
                "TINFIRM_SANDBOX_REGISTRY_MIRRORS",
                "https://ok.example,https://x\"y.example",
                "has a character outside",
            ),
            (
                "TINFIRM_SANDBOX_REGISTRY_MIRRORS",
                "https://user:pw@nexus.example",
                "has a character outside",
            ),
            (
                "TINFIRM_SANDBOX_REGISTRY_MIRRORS",
                "nexus.example:5000",
                "must be an http:// or https:// URL",
            ),
            (
                "TINFIRM_SANDBOX_INSECURE_REGISTRIES",
                "nexus.example:5000,\"}",
                "has a character outside",
            ),
            (
                "TINFIRM_SANDBOX_DOCKER_STORAGE_DRIVER",
                "overlay2\",\"x",
                "has a character outside",
            ),
        ] {
            let dir = tempfile::tempdir().unwrap();
            let file = dir.path().join("daemon.json");
            let out = run_script(
                "tinfirm-sandbox-setup",
                &["docker-config", file.to_str().unwrap()],
                &[(key, value)],
            );
            assert!(!out.status.success(), "{key}={value}");
            assert!(stderr(&out).contains(key), "{}", stderr(&out));
            assert!(stderr(&out).contains(why), "{}", stderr(&out));
            assert!(!file.exists(), "{key}={value}");
        }
    }

    #[test]
    fn mirror_settings_are_written_into_the_home() {
        let home = tempfile::tempdir().unwrap();
        let home_path = home.path();
        let home_arg = home_path.to_str().unwrap();
        // A file of the agent's own is never overwritten.
        std::fs::write(home_path.join(".npmrc"), "registry=https://own.example/\n").unwrap();
        let mirrors = [
            ("HOME", home_arg),
            (
                "TINFIRM_SANDBOX_MAVEN_MIRROR",
                "https://nexus.example/repository/maven-public/",
            ),
            ("TINFIRM_SANDBOX_MAVEN_MIRROR_OF", "external:*,!internal"),
            (
                "TINFIRM_SANDBOX_NPM_REGISTRY",
                "https://nexus.example/repository/npm/",
            ),
            (
                "TINFIRM_SANDBOX_PIP_INDEX",
                "https://nexus.example/repository/pypi/simple",
            ),
        ];
        let out = run_script("tinfirm-sandbox-setup", &["user-config"], &mirrors);
        assert!(out.status.success(), "{}", stderr(&out));
        assert!(out.stdout.is_empty(), "stdout belongs to ACP");
        assert!(
            stderr(&out).contains(".npmrc was not written by Tinfirm"),
            "{}",
            stderr(&out)
        );

        let settings = read(&home_path.join(".m2/settings.xml"));
        assert!(settings.starts_with("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"));
        assert!(settings.contains("<url>https://nexus.example/repository/maven-public/</url>"));
        assert!(settings.contains("<mirrorOf>external:*,!internal</mirrorOf>"));
        assert_eq!(
            read(&home_path.join(".npmrc")),
            "registry=https://own.example/\n"
        );
        let pip = read(&home_path.join(".config/pip/pip.conf"));
        assert!(
            pip.contains("[global]\nindex-url = https://nexus.example/repository/pypi/simple\n")
        );
        let uv = read(&home_path.join(".config/uv/uv.toml"));
        assert!(uv.contains(
            "[[index]]\nurl = \"https://nexus.example/repository/pypi/simple\"\ndefault = true\n"
        ));

        // The agent's file is gone: the next run writes the mirror.
        std::fs::remove_file(home_path.join(".npmrc")).unwrap();
        let out = run_script("tinfirm-sandbox-setup", &["user-config"], &mirrors);
        assert!(out.status.success(), "{}", stderr(&out));
        let npmrc = read(&home_path.join(".npmrc"));
        assert!(npmrc.starts_with("# Written by tinfirm-sandbox-setup"));
        assert!(npmrc.ends_with("\nregistry=https://nexus.example/repository/npm/\n"));

        // Without the variables, the files written here go; the agent's own stay.
        std::fs::write(home_path.join(".config/pip/pip.conf"), "[global]\n").unwrap();
        let out = run_script(
            "tinfirm-sandbox-setup",
            &["user-config"],
            &[("HOME", home_arg)],
        );
        assert!(out.status.success(), "{}", stderr(&out));
        assert!(!home_path.join(".m2/settings.xml").exists());
        assert!(!home_path.join(".npmrc").exists());
        assert!(!home_path.join(".config/uv/uv.toml").exists());
        assert_eq!(read(&home_path.join(".config/pip/pip.conf")), "[global]\n");
    }

    #[test]
    fn mirror_settings_refuse_unsafe_values() {
        let home = tempfile::tempdir().unwrap();
        let home_arg = home.path().to_str().unwrap();
        for (key, value) in [
            ("TINFIRM_SANDBOX_MAVEN_MIRROR", "https://n.example/<x>"),
            ("TINFIRM_SANDBOX_MAVEN_MIRROR", "https://u:p@n.example/"),
            ("TINFIRM_SANDBOX_NPM_REGISTRY", "ftp://n.example/"),
            ("TINFIRM_SANDBOX_NPM_REGISTRY", "https://n.example/${HOME}"),
            ("TINFIRM_SANDBOX_PIP_INDEX", "https://n.example/a b"),
        ] {
            let out = run_script(
                "tinfirm-sandbox-setup",
                &["user-config"],
                &[("HOME", home_arg), (key, value)],
            );
            assert!(!out.status.success(), "{key}={value}");
            assert!(stderr(&out).contains(key), "{}", stderr(&out));
        }
        // Nothing half-written was left behind.
        let left: Vec<_> = walk(home.path());
        assert!(left.iter().all(|p| p.is_dir()), "{left:?}");
    }

    fn walk(dir: &Path) -> Vec<std::path::PathBuf> {
        let mut out = Vec::new();
        for entry in std::fs::read_dir(dir).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                out.extend(walk(&path));
            }
            out.push(path);
        }
        out
    }

    fn args(pairs: &[(&str, &str)]) -> Vec<(String, String)> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn the_build_id_names_the_version_and_the_images_definitions() {
        let id = build_id();
        let digest = id
            .strip_prefix(concat!(env!("CARGO_PKG_VERSION"), "+images."))
            .unwrap();
        assert_eq!(digest.len(), 12);
        assert!(digest.chars().all(|c| c.is_ascii_hexdigit()));
        // Build arguments are the host's own: they are not in it.
        assert_eq!(id, build_id());
    }

    #[test]
    fn the_spec_covers_what_an_image_is_built_from() {
        let none = args(&[]);
        let spec_of = |def: &ImageDef, a: &[(String, String)]| spec(def, &BASE, a);
        let base = spec_of(&BASE, &none);
        let opencode = spec_of(&OPENCODE, &none);
        assert_eq!(base.len(), 64);
        assert_eq!(
            base,
            spec_of(&BASE, &none),
            "the same inputs, the same digest"
        );
        assert_ne!(base, opencode);
        assert_ne!(opencode, spec_of(&PI, &none));

        // An argument counts for the images that declare it.
        let npm = args(&[("NPM_REGISTRY", "https://nexus.example/npm/")]);
        assert_eq!(spec_of(&BASE, &npm), base);
        assert_ne!(spec_of(&OPENCODE, &npm), opencode);
        // The base's arguments change every runtime image, which is built on it.
        let apt = args(&[("APT_MIRROR", "http://nexus.example/repository")]);
        assert_ne!(spec_of(&BASE, &apt), base);
        assert_ne!(spec_of(&OPENCODE, &apt), opencode);
        // The order of the arguments does not matter.
        let both = args(&[
            ("NPM_REGISTRY", "https://nexus.example/npm/"),
            ("APT_MIRROR", "http://nexus.example/repository"),
        ]);
        let reversed: Vec<_> = both.iter().rev().cloned().collect();
        assert_eq!(spec_of(&OPENCODE, &both), spec_of(&OPENCODE, &reversed));

        // A changed Dockerfile, or a changed copied file, is another digest.
        let edited = ImageDef {
            dockerfile: "FROM tinfirm-runner-base:local\nARG NPM_REGISTRY\n",
            ..OPENCODE
        };
        assert_ne!(spec_of(&edited, &none), opencode);
        let other_file = ImageDef {
            files: &[("tinfirm-pi-acp.mjs", b"changed")],
            ..PI
        };
        assert_ne!(spec_of(&other_file, &none), spec_of(&PI, &none));
        // So is a changed base, for every runtime image.
        let other_base = ImageDef {
            dockerfile: "FROM node:24\n",
            ..BASE
        };
        assert_ne!(spec(&OPENCODE, &other_base, &none), opencode);
    }

    #[test]
    fn image_settings_record_the_last_build() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("images.json");
        // Nothing recorded yet: the defaults.
        let mut settings = ImageSettings::load(&file).unwrap();
        assert_eq!(settings, ImageSettings::default());

        settings.record(
            &[OPENCODE],
            &args(&[("NPM_REGISTRY", "https://nexus.example/npm/")]),
        );
        settings.save(&file).unwrap();
        assert_eq!(crate::secure_fs::mode_of(&file), Some(0o600));
        let mut settings = ImageSettings::load(&file).unwrap();
        assert_eq!(settings.runtimes, vec![Runtime::Opencode]);
        assert_eq!(
            settings.build_args["NPM_REGISTRY"],
            "https://nexus.example/npm/"
        );
        let json: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
        assert_eq!(
            json,
            serde_json::json!({
                "buildArgs": {"NPM_REGISTRY": "https://nexus.example/npm/"},
                "runtimes": ["OPENCODE"],
            })
        );

        // A later build's runtimes join; its arguments replace.
        settings.record(&[CLAUDE], &args(&[]));
        assert_eq!(
            settings.runtimes,
            vec![Runtime::ClaudeCode, Runtime::Opencode]
        );
        assert!(settings.build_args.is_empty());
        assert!(!settings.multi && !settings.built(&MULTI) && settings.built(&CLAUDE));
        // The multi-runtime image is recorded apart from the runtimes.
        settings.record(&[MULTI], &args(&[]));
        assert!(settings.multi && settings.built(&MULTI) && !settings.built(&PI));
        assert_eq!(serde_json::to_value(&settings).unwrap()["multi"], true);

        // An argument that the images no longer declare is left out.
        settings.build_args.insert("GONE_ARG".into(), "x".into());
        settings
            .build_args
            .insert("APT_MIRROR".into(), "http://m".into());
        let (kept, dropped) = settings.args();
        assert_eq!(kept, args(&[("APT_MIRROR", "http://m")]));
        assert_eq!(dropped, vec!["GONE_ARG".to_string()]);

        std::fs::write(&file, "{not json").unwrap();
        assert!(ImageSettings::load(&file).is_err());
    }

    #[test]
    fn plan_refresh_rebuilds_what_is_not_current() {
        let none = args(&[]);
        let current = |def: &ImageDef| Some(spec(def, &BASE, &none));
        let present = |entries: &[(&ImageDef, Option<String>)]| -> HashMap<String, Option<String>> {
            entries
                .iter()
                .map(|(def, label)| (def.tag.to_string(), label.clone()))
                .collect()
        };
        let tags = |plan: &RefreshPlan| -> Vec<(&str, Staleness)> {
            plan.runtimes.iter().map(|(d, s)| (d.tag, *s)).collect()
        };
        let built = |runtimes: &[Runtime], multi: bool| ImageSettings {
            runtimes: runtimes.to_vec(),
            multi,
            ..ImageSettings::default()
        };
        let nothing = built(&[], false);

        // Nothing built here: nothing to do.
        let plan = plan_refresh(&BASE, &RUNTIME_IMAGES, &none, &nothing, &HashMap::new());
        assert!(plan.is_empty());
        assert_eq!(plan.base, None);

        // Current images stay.
        let all_current = present(&[(&BASE, current(&BASE)), (&OPENCODE, current(&OPENCODE))]);
        let plan = plan_refresh(
            &BASE,
            &RUNTIME_IMAGES,
            &none,
            &built(&[Runtime::Opencode], false),
            &all_current,
        );
        assert!(plan.is_empty());

        // A runtime image from other Dockerfiles, or from before the label, is rebuilt; the
        // base only when it is not current either.
        let old = present(&[
            (&BASE, current(&BASE)),
            (&OPENCODE, Some("0".repeat(64))),
            (&PI, None),
        ]);
        let plan = plan_refresh(&BASE, &RUNTIME_IMAGES, &none, &nothing, &old);
        assert_eq!(
            tags(&plan),
            vec![
                (PI_IMAGE, Staleness::Outdated),
                (OPENCODE_IMAGE, Staleness::Outdated)
            ]
        );
        assert_eq!(plan.base, None);
        let old_base = present(&[(&BASE, None), (&OPENCODE, None)]);
        let plan = plan_refresh(&BASE, &RUNTIME_IMAGES, &none, &nothing, &old_base);
        assert_eq!(tags(&plan), vec![(OPENCODE_IMAGE, Staleness::Outdated)]);
        assert_eq!(plan.base, Some(Staleness::Outdated));
        // A current runtime image was built on the current base: an older base tag, retagged
        // since, does not matter until a runtime image is rebuilt on it.
        let retagged = present(&[(&BASE, None), (&OPENCODE, current(&OPENCODE))]);
        let plan = plan_refresh(&BASE, &RUNTIME_IMAGES, &none, &nothing, &retagged);
        assert!(plan.is_empty());

        // A missing image is built only for a runtime that `images build` built here.
        let plan = plan_refresh(
            &BASE,
            &RUNTIME_IMAGES,
            &none,
            &built(&[Runtime::ClaudeCode], false),
            &all_current,
        );
        assert_eq!(tags(&plan), vec![(CLAUDE_IMAGE, Staleness::Missing)]);
        assert_eq!(plan.base, None);
        let plan = plan_refresh(
            &BASE,
            &RUNTIME_IMAGES,
            &none,
            &built(&[Runtime::ClaudeCode], false),
            &HashMap::new(),
        );
        assert_eq!(plan.base, Some(Staleness::Missing));

        // Other build arguments make the images stale too.
        let mirror = args(&[("NPM_REGISTRY", "https://nexus.example/npm/")]);
        let plan = plan_refresh(&BASE, &RUNTIME_IMAGES, &mirror, &nothing, &all_current);
        assert_eq!(tags(&plan), vec![(OPENCODE_IMAGE, Staleness::Outdated)]);
        assert_eq!(plan.base, None, "the base does not declare NPM_REGISTRY");

        // Only the images in `defs` (those without TINFIRM_RUNNER_IMAGE_*) are considered.
        let plan = plan_refresh(&BASE, &[CLAUDE], &none, &nothing, &old);
        assert!(plan.is_empty());

        // The multi-runtime image, missing, is built again only where `images build` built it.
        let defs = [CLAUDE, MULTI];
        let plan = plan_refresh(&BASE, &defs, &none, &nothing, &all_current);
        assert!(plan.is_empty());
        let plan = plan_refresh(&BASE, &defs, &none, &built(&[], true), &all_current);
        assert_eq!(tags(&plan), vec![(MULTI_IMAGE, Staleness::Missing)]);
        let old_multi = present(&[(&BASE, current(&BASE)), (&MULTI, Some("0".repeat(64)))]);
        let plan = plan_refresh(&BASE, &defs, &none, &nothing, &old_multi);
        assert_eq!(tags(&plan), vec![(MULTI_IMAGE, Staleness::Outdated)]);
    }

    /// The multi-runtime image holds what each runtime's own image holds, at the same versions,
    /// and each adapter is the command of its runtime's image.
    #[test]
    fn the_multi_runtime_image_matches_the_runtimes_images() {
        let defaults = |dockerfile: &str| -> BTreeMap<String, String> {
            dockerfile
                .lines()
                .filter_map(|l| l.trim().strip_prefix("ARG "))
                .filter_map(|a| a.split_once('='))
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect()
        };
        let multi = defaults(MULTI.dockerfile);
        for def in RUNTIME_IMAGES {
            for (key, value) in defaults(def.dockerfile) {
                assert_eq!(multi.get(&key), Some(&value), "{key} of {}", def.tag);
            }
            let runtime = def.runtime.unwrap();
            let cmd = serde_json::to_string(adapter_command(runtime)).unwrap();
            assert!(
                def.dockerfile
                    .contains(&format!("CMD {}", cmd.replace(',', ", "))),
                "{runtime}: {cmd}"
            );
            for line in def.dockerfile.lines().map(str::trim) {
                if let Some(package) = line.strip_prefix('"').and_then(|l| l.split('@').nth(1)) {
                    assert!(MULTI.dockerfile.contains(package), "{package}");
                }
            }
            // Every setting of the runtime's image is the multi-runtime image's too.
            for setting in def
                .dockerfile
                .lines()
                .map(str::trim)
                .filter(|l| l.contains('=') && l.chars().next().is_some_and(char::is_uppercase))
                .filter(|l| !l.starts_with("ARG ") && !l.starts_with("LABEL "))
            {
                let setting = setting.trim_start_matches("ENV ").trim_end_matches(" \\");
                assert!(MULTI.dockerfile.contains(setting), "{setting}");
            }
        }
        assert!(MULTI.multi && MULTI.on_base() && MULTI.runtime.is_none());
        assert!(MULTI.dockerfile.contains("FROM tinfirm-runner-base:local"));
        assert!(MULTI.dockerfile.contains("CMD []"));
        assert!(MULTI.dockerfile.contains("USER 1000:1000"));
        assert!(MULTI.dockerfile.contains(VERSION_LABEL));
        for (path, _) in MULTI.files {
            assert!(MULTI.dockerfile.contains(&format!(" {path} ")), "{path}");
        }
        // pi's adapter, and the runtimes' lockfiles, the same bytes as their own images'.
        assert_eq!(MULTI.files[0], PI.files[0]);
        for def in [&CLAUDE, &CODEX, &PI, &OPENCODE] {
            let dir = def
                .tag
                .trim_start_matches("tinfirm-runner-")
                .trim_end_matches(":local");
            for (path, bytes) in def.files.iter().filter(|(p, _)| !p.ends_with(".mjs")) {
                let multi_path = format!("{dir}/{path}");
                let found = MULTI.files.iter().find(|(p, _)| *p == multi_path);
                assert_eq!(found.map(|(_, b)| *b), Some(*bytes), "{multi_path}");
            }
        }
        assert!(!BASE.on_base() && CLAUDE.on_base());
    }

    #[test]
    fn build_context_is_a_tar() {
        let tar = build_context("FROM scratch\n", &[("x.txt", b"hi")]).unwrap();
        let mut archive = tar::Archive::new(tar.as_slice());
        let names: Vec<String> = archive
            .entries()
            .unwrap()
            .map(|e| e.unwrap().path().unwrap().to_string_lossy().into_owned())
            .collect();
        assert_eq!(names, vec!["Dockerfile", "x.txt"]);
    }
}
