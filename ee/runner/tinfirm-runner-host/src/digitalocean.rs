//! `vm-driver digitalocean`: the VM driver for DigitalOcean (ADR-0096, the driver protocol of
//! crate::vm). A Droplet per run, from a template of its configuration: a private snapshot, a
//! size, a VPC of the sandboxes' own and a Cloud Firewall on the tag [`SANDBOX_TAG`] that
//! lets in only the runner, to the bridge's port, and lets nothing out but DHCP, to the
//! sandboxes' own networks.
//!
//! It holds the API token; the runner host holds none. Its guards (the Kubernetes runner
//! analysis §6.1, D8):
//!
//! - it creates only from its templates, and checks the firewall before each create;
//! - it deletes a Droplet only by its id, after reading it: it must carry [`SANDBOX_TAG`] and
//!   the host's labels, and never a tag of a cluster (`k8s…`); it never deletes by tag;
//! - it lists only by [`SANDBOX_TAG`];
//! - every create and delete is written to stderr, which the runner host logs.
//!
//! The configuration is a JSON file (`--config`, or `TINFIRM_DO_CONFIG`):
//!
//! ```json
//! {
//!   "tokenFile": "/run/secrets/digitalocean-token",
//!   "connect": "private",
//!   "templates": {
//!     "sandbox-4c8g": {
//!       "region": "fra1",
//!       "size": "s-4vcpu-8gb",
//!       "image": "123456789",
//!       "vpc": "VPC_UUID",
//!       "firewall": "FIREWALL_ID",
//!       "bridgePort": 7443
//!     }
//!   }
//! }
//! ```
//!
//! The token comes from `tokenFile`, or `DIGITALOCEAN_TOKEN_FILE`, or `DIGITALOCEAN_TOKEN`.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, anyhow, bail};
use http_body_util::{BodyExt, Full, Limited};
use hyper::body::Bytes;
use hyper::{Method, Request, StatusCode, header};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::sync::mpsc;

use crate::vm::{DRIVER_PROTOCOL, HOST_LABEL};

/// The tag of every sandbox Droplet: the firewall is on it, and the driver lists and deletes
/// only Droplets that carry it.
pub const SANDBOX_TAG: &str = "tinfirm-sandbox";
/// The default API.
pub const API_URL: &str = "https://api.digitalocean.com";
/// DigitalOcean's limit for user data.
const MAX_USER_DATA: usize = 64 * 1024;
/// The largest answer of the API that the driver reads.
const MAX_BODY: usize = 16 * 1024 * 1024;
const API_TIMEOUT: Duration = Duration::from_secs(60);
/// How long a firewall that passed its check is not read again before a create.
const FIREWALL_CHECK_TTL: Duration = Duration::from_secs(60);
/// Pages of 200 Droplets or firewalls at most: a bound, not a limit anyone reaches.
const MAX_PAGES: usize = 100;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Connect {
    /// The runner reaches the Droplets on their VPC addresses (the VPC is peered with its own).
    #[default]
    Private,
    /// On their public addresses, which the firewall opens to the runner's alone.
    Public,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Template {
    pub region: String,
    pub size: String,
    /// The snapshot's or custom image's id (never a public image).
    pub image: String,
    /// The sandboxes' own VPC.
    pub vpc: String,
    /// The Cloud Firewall on [`SANDBOX_TAG`].
    pub firewall: String,
    #[serde(default = "default_bridge_port")]
    pub bridge_port: u16,
}

fn default_bridge_port() -> u16 {
    crate::vm::DEFAULT_PORT
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ConfigFile {
    #[serde(default)]
    api_url: Option<String>,
    #[serde(default)]
    token_file: Option<PathBuf>,
    #[serde(default)]
    connect: Connect,
    templates: BTreeMap<String, Template>,
}

/// The driver's configuration.
pub struct Config {
    pub api: url::Url,
    pub token: tinfirm_protocol::Secret,
    pub connect: Connect,
    pub templates: BTreeMap<String, Template>,
}

impl Config {
    /// Reads the configuration file and the token.
    pub fn load(path: &Path) -> Result<Config> {
        let text = std::fs::read_to_string(path)
            .with_context(|| format!("cannot read {}", path.display()))?;
        let file: ConfigFile = serde_json::from_str(&text)
            .with_context(|| format!("{} is not a valid configuration", path.display()))?;
        let token = match file
            .token_file
            .or_else(|| std::env::var_os("DIGITALOCEAN_TOKEN_FILE").map(PathBuf::from))
        {
            Some(token_file) => std::fs::read_to_string(&token_file)
                .with_context(|| format!("cannot read the token file {}", token_file.display()))?,
            None => std::env::var("DIGITALOCEAN_TOKEN").context(
                "no API token: set tokenFile in the configuration, DIGITALOCEAN_TOKEN_FILE or DIGITALOCEAN_TOKEN",
            )?,
        };
        let token = token.trim().to_string();
        if token.is_empty() || token.chars().any(|c| c.is_whitespace() || c.is_control()) {
            bail!("the DigitalOcean API token is empty or not one word");
        }
        let api = url::Url::parse(file.api_url.as_deref().unwrap_or(API_URL))
            .context("apiUrl is not a URL")?;
        let loopback = match api.host() {
            Some(url::Host::Ipv4(ip)) => ip.is_loopback(),
            Some(url::Host::Ipv6(ip)) => ip.is_loopback(),
            Some(url::Host::Domain(name)) => name == "localhost",
            None => false,
        };
        if api.scheme() != "https" && !(api.scheme() == "http" && loopback) {
            bail!("apiUrl must be https (plain http only on loopback, for tests)");
        }
        if file.templates.is_empty() {
            bail!("the configuration has no templates");
        }
        for (name, template) in &file.templates {
            for (field, value) in [
                ("region", &template.region),
                ("size", &template.size),
                ("image", &template.image),
                ("vpc", &template.vpc),
                ("firewall", &template.firewall),
            ] {
                if value.trim().is_empty() || !value.chars().all(path_safe) {
                    bail!("template `{name}`: `{field}` is empty or not an id");
                }
            }
            if template.bridge_port == 0 {
                bail!("template `{name}`: bridgePort must not be 0");
            }
        }
        Ok(Config {
            api,
            token: tinfirm_protocol::Secret::new(token),
            connect: file.connect,
            templates: file.templates,
        })
    }
}

/// A character that may stand in an id that goes into an API path.
fn path_safe(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.')
}

/// The tag of a label: `KEY:VALUE`, with letters, digits, `-` and `_` (DigitalOcean's tags
/// also take `:`).
fn tag_of(key: &str, value: &str) -> Result<String> {
    let plain = |text: &str| {
        !text.is_empty()
            && text
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_'))
    };
    if !key.starts_with("tinfirm-") || !plain(key) || !plain(value) {
        bail!("the label `{key}` cannot be a tag (letters, digits, `-` and `_`)");
    }
    let tag = format!("{key}:{value}");
    if tag.len() > 255 {
        bail!("the label `{key}` is too long for a tag");
    }
    Ok(tag)
}

fn tags_of(droplet: &Value) -> Vec<String> {
    droplet["tags"]
        .as_array()
        .map(|tags| {
            tags.iter()
                .filter_map(Value::as_str)
                .map(String::from)
                .collect()
        })
        .unwrap_or_default()
}

/// The labels in a Droplet's tags: those of the form `tinfirm-…:VALUE`.
fn labels_of(tags: &[String]) -> BTreeMap<String, String> {
    tags.iter()
        .filter_map(|tag| tag.split_once(':'))
        .filter(|(key, _)| key.starts_with("tinfirm-"))
        .map(|(key, value)| (key.to_string(), value.to_string()))
        .collect()
}

fn id_of(droplet: &Value) -> String {
    match &droplet["id"] {
        Value::Number(n) => n.to_string(),
        Value::String(s) => s.clone(),
        _ => String::new(),
    }
}

/// A Droplet id: digits.
fn check_id(id: &str) -> Result<()> {
    if id.is_empty() || id.len() > 20 || !id.chars().all(|c| c.is_ascii_digit()) {
        bail!("`{id}` is not a Droplet id");
    }
    Ok(())
}

/// The driver: the API, its templates, and the firewalls it checked lately.
pub struct DigitalOcean {
    config: Config,
    firewalls_checked: Mutex<BTreeMap<String, Instant>>,
}

impl DigitalOcean {
    pub fn new(config: Config) -> DigitalOcean {
        DigitalOcean {
            config,
            firewalls_checked: Mutex::new(BTreeMap::new()),
        }
    }

    fn template(&self, name: &str) -> Result<&Template> {
        self.config
            .templates
            .get(name)
            .with_context(|| format!("no template `{name}` in the driver's configuration"))
    }

    /// One API request; the status and the JSON answer (`null` when it has none).
    async fn api(
        &self,
        method: Method,
        path: &str,
        body: Option<&Value>,
    ) -> Result<(StatusCode, Value)> {
        let url = self
            .config
            .api
            .join(path)
            .with_context(|| format!("cannot build the URL of {path}"))?;
        let mut attempt = 0;
        loop {
            attempt += 1;
            let request = Request::builder()
                .method(method.clone())
                .uri(crate::attachments::path_and_query(&url))
                .header(header::HOST, crate::attachments::authority(&url))
                .header(
                    header::AUTHORIZATION,
                    format!("Bearer {}", self.config.token.expose()),
                )
                .header(header::USER_AGENT, "tinfirm-runner-host vm-driver")
                .header(header::ACCEPT, "application/json")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Full::new(Bytes::from(
                    body.map(|b| serde_json::to_vec(b).expect("JSON serializes"))
                        .unwrap_or_default(),
                )))?;
            let response = crate::attachments::send(&url, request, API_TIMEOUT)
                .await
                .with_context(|| format!("{method} {path}"))?;
            let status = response.status();
            let bytes = Limited::new(response.into_body(), MAX_BODY)
                .collect()
                .await
                .map_err(|e| anyhow!("{method} {path}: {e}"))?
                .to_bytes();
            // A rate limit or a server error: a request that changed nothing (a create is
            // tried again only after a rate limit, which DigitalOcean answers before it acts).
            let again = status == StatusCode::TOO_MANY_REQUESTS
                || (status.is_server_error() && method != Method::POST);
            if again && attempt < 3 {
                tokio::time::sleep(Duration::from_secs(2 * attempt)).await;
                continue;
            }
            let value = if bytes.is_empty() {
                Value::Null
            } else {
                serde_json::from_slice(&bytes).unwrap_or(Value::Null)
            };
            return Ok((status, value));
        }
    }

    /// A request that must answer 2xx; its JSON answer.
    async fn api_ok(&self, method: Method, path: &str, body: Option<&Value>) -> Result<Value> {
        let (status, value) = self.api(method.clone(), path, body).await?;
        if !status.is_success() {
            bail!(
                "{method} {path}: HTTP {status}: {}",
                value["message"].as_str().unwrap_or("no message")
            );
        }
        Ok(value)
    }

    /// Every item of a paged list (`key`) at `path`, which has a query already.
    async fn all_pages(&self, path: &str, key: &str) -> Result<Vec<Value>> {
        let mut items = Vec::new();
        for page in 1..=MAX_PAGES {
            let value = self
                .api_ok(
                    Method::GET,
                    &format!("{path}&per_page=200&page={page}"),
                    None,
                )
                .await?;
            let batch = value[key].as_array().cloned().unwrap_or_default();
            let more = value["links"]["pages"]["next"].is_string() && !batch.is_empty();
            items.extend(batch);
            if !more {
                return Ok(items);
            }
        }
        bail!("{path}: more than {MAX_PAGES} pages")
    }

    pub async fn handle(&self, method: &str, params: Value) -> Result<Value> {
        match method {
            "hello" => Ok(json!({
                "protocol": DRIVER_PROTOCOL,
                "driver": "digitalocean",
                "version": env!("CARGO_PKG_VERSION"),
            })),
            "verify" => self.verify(str_param(&params, "template")?).await,
            "create" => {
                self.create(
                    str_param(&params, "template")?,
                    str_param(&params, "name")?,
                    str_param(&params, "userData")?,
                    &labels_param(&params)?,
                )
                .await
            }
            "describe" => self.describe(str_param(&params, "id")?).await,
            "delete" => {
                self.delete(str_param(&params, "id")?, &labels_param(&params)?)
                    .await
            }
            "list" => self.list(&labels_param(&params)?).await,
            other => bail!("no method `{other}`"),
        }
    }

    /// What breaks the contract of a sandbox VM in the template's setup, and how many more
    /// Droplets the account may have.
    pub async fn verify(&self, name: &str) -> Result<Value> {
        let template = self.template(name)?;
        let mut problems = Vec::new();
        let mut checked = Vec::new();

        let account = self.api_ok(Method::GET, "/v2/account", None).await?;
        let account = &account["account"];
        if account["status"] != "active" {
            problems.push(format!(
                "the account is {}, not active",
                account["status"].as_str().unwrap_or("of an unknown status")
            ));
        }
        let limit = account["droplet_limit"].as_u64();
        let total = self
            .api_ok(Method::GET, "/v2/droplets?per_page=1", None)
            .await?["meta"]["total"]
            .as_u64()
            .unwrap_or(0);
        let sandboxes = self
            .api_ok(
                Method::GET,
                &format!("/v2/droplets?tag_name={SANDBOX_TAG}&per_page=1"),
                None,
            )
            .await?["meta"]["total"]
            .as_u64()
            .unwrap_or(0);
        // The sandboxes count as free: those of this host are its leftovers, which it deletes.
        let max_vms = limit.map(|limit| limit.saturating_sub(total.saturating_sub(sandboxes)));
        checked.push(format!(
            "account: droplet limit {}, {total} Droplet(s), {sandboxes} of them sandboxes",
            limit.map_or_else(|| "unknown".to_string(), |l| l.to_string())
        ));

        match self
            .api(Method::GET, &format!("/v2/images/{}", template.image), None)
            .await?
        {
            (status, value) if status.is_success() => {
                let image = &value["image"];
                if image["public"] != false {
                    problems.push(format!(
                        "the image {} is public; sandboxes start only from a private snapshot or custom image of the team (D2)",
                        template.image
                    ));
                }
                if image["status"].as_str().is_some_and(|s| s != "available") {
                    problems.push(format!(
                        "the image {} is {}, not available",
                        template.image,
                        image["status"].as_str().unwrap_or("?")
                    ));
                }
                let in_region = image["regions"]
                    .as_array()
                    .is_some_and(|r| r.iter().any(|r| r == template.region.as_str()));
                if !in_region {
                    problems.push(format!(
                        "the image {} is not in {}",
                        template.image, template.region
                    ));
                }
                checked.push(format!(
                    "image {} ({}, {})",
                    template.image,
                    image["name"].as_str().unwrap_or("?"),
                    image["type"].as_str().unwrap_or("?")
                ));
            }
            (StatusCode::NOT_FOUND, _) => {
                problems.push(format!("there is no image {}", template.image))
            }
            (status, value) => bail!(
                "GET /v2/images/{}: HTTP {status}: {}",
                template.image,
                value["message"].as_str().unwrap_or("no message")
            ),
        }

        let vpc_range = match self
            .api(Method::GET, &format!("/v2/vpcs/{}", template.vpc), None)
            .await?
        {
            (status, value) if status.is_success() => {
                let vpc = &value["vpc"];
                if vpc["region"] != template.region.as_str() {
                    problems.push(format!(
                        "the VPC {} is in {}, not in {}",
                        template.vpc,
                        vpc["region"].as_str().unwrap_or("?"),
                        template.region
                    ));
                }
                if vpc["default"] == true {
                    problems.push(format!(
                        "the VPC {} is the region's default, which other Droplets share; the sandboxes need a VPC of their own (C6)",
                        template.vpc
                    ));
                }
                checked.push(format!(
                    "VPC {} ({})",
                    template.vpc,
                    vpc["ip_range"].as_str().unwrap_or("?")
                ));
                vpc["ip_range"].as_str().map(str::to_string)
            }
            (StatusCode::NOT_FOUND, _) => {
                problems.push(format!("there is no VPC {}", template.vpc));
                None
            }
            (status, value) => bail!(
                "GET /v2/vpcs/{}: HTTP {status}: {}",
                template.vpc,
                value["message"].as_str().unwrap_or("no message")
            ),
        };

        let firewall = self
            .firewall_problems(template, vpc_range.as_deref())
            .await?;
        if firewall.is_empty() {
            checked.push(format!(
                "firewall {} on the tag {SANDBOX_TAG}: in only tcp {} from the runner, out only DHCP",
                template.firewall, template.bridge_port
            ));
        }
        problems.extend(firewall);
        Ok(json!({ "problems": problems, "maxVms": max_vms, "checked": checked }))
    }

    /// What is wrong with the template's firewall, and with any other on [`SANDBOX_TAG`].
    /// `vpc_range` is the address range of the template's VPC, to which DHCP may go and from
    /// which no inbound source may come, since a sandbox would then reach another's bridge.
    /// Without it the check fails rather than skips those parts.
    async fn firewall_problems(
        &self,
        template: &Template,
        vpc_range: Option<&str>,
    ) -> Result<Vec<String>> {
        let mut problems = Vec::new();
        let vpc = vpc_range.and_then(ipv4_network);
        if vpc.is_none() {
            problems.push(format!(
                "the VPC {} has no IPv4 range ({}), so the firewall cannot be checked against it",
                template.vpc,
                vpc_range.unwrap_or("none")
            ));
        }
        let firewalls = self.all_pages("/v2/firewalls?", "firewalls").await?;
        let on_tag = |fw: &Value| {
            fw["tags"]
                .as_array()
                .is_some_and(|tags| tags.iter().any(|t| t == SANDBOX_TAG))
        };
        let Some(firewall) = firewalls
            .iter()
            .find(|fw| fw["id"] == template.firewall.as_str())
        else {
            problems.push(format!("there is no firewall {}", template.firewall));
            return Ok(problems);
        };
        for other in firewalls
            .iter()
            .filter(|fw| fw["id"] != template.firewall.as_str() && on_tag(fw))
        {
            problems.push(format!(
                "the firewall {} ({}) is on the tag {SANDBOX_TAG} too, and its rules add to the template's",
                other["id"].as_str().unwrap_or("?"),
                other["name"].as_str().unwrap_or("?")
            ));
        }
        if !on_tag(firewall) {
            problems.push(format!(
                "the firewall {} is not on the tag {SANDBOX_TAG}, so the sandboxes would not get it",
                template.firewall
            ));
        }
        if firewall["status"]
            .as_str()
            .is_some_and(|s| s != "succeeded")
        {
            problems.push(format!(
                "the firewall {} is {}, not in effect",
                template.firewall,
                firewall["status"].as_str().unwrap_or("?")
            ));
        }
        let port = template.bridge_port.to_string();
        let anywhere = |list: &Value| {
            list["addresses"]
                .as_array()
                .is_some_and(|a| a.iter().any(|a| a == "0.0.0.0/0" || a == "::/0"))
        };
        let inbound = firewall["inbound_rules"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        if inbound.is_empty() {
            problems.push(format!(
                "the firewall {} lets nothing in, so this host cannot reach the bridge on tcp {port}",
                template.firewall
            ));
        }
        for rule in &inbound {
            let (protocol, ports) = (
                rule["protocol"].as_str().unwrap_or("?"),
                rule["ports"].as_str().unwrap_or("?"),
            );
            if protocol != "tcp" || ports != port {
                problems.push(format!(
                    "an inbound rule lets {protocol} {ports} in; only tcp {port}, the bridge's, from the runner"
                ));
            }
            if anywhere(&rule["sources"]) {
                problems.push(format!(
                    "an inbound rule lets {protocol} {ports} in from anywhere; only from the runner"
                ));
            }
            // The sandboxes themselves are never the runner: from their tag or their VPC, one
            // sandbox would reach another's bridge.
            if rule["sources"]["tags"].as_array().is_some_and(|tags| {
                tags.iter()
                    .filter_map(Value::as_str)
                    .any(|t| t == SANDBOX_TAG || (t.starts_with("tinfirm-") && t.contains(':')))
            }) {
                problems.push(format!(
                    "an inbound rule lets {protocol} {ports} in from the sandboxes' own tags; only from the runner"
                ));
            }
            for source in rule["sources"]["addresses"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
            {
                if let (Some(source_net), Some(vpc)) = (ipv4_network(source), vpc)
                    && overlaps(source_net, vpc)
                {
                    problems.push(format!(
                        "an inbound rule lets {protocol} {ports} in from {source}, which covers the sandboxes' VPC {}; only from the runner",
                        vpc_range.unwrap_or("?")
                    ));
                }
            }
        }
        for rule in firewall["outbound_rules"].as_array().into_iter().flatten() {
            let (protocol, ports) = (
                rule["protocol"].as_str().unwrap_or("?"),
                rule["ports"].as_str().unwrap_or("?"),
            );
            if !(protocol == "udp" && (ports == "67" || ports == "67-68")) {
                problems.push(format!(
                    "an outbound rule lets {protocol} {ports} out; the sandboxes must reach nothing but DHCP (udp 67), and the network only through the egress proxy"
                ));
                continue;
            }
            // DHCP to anywhere would let an agent send datagrams to any host on udp 67, and to
            // a private range beyond the sandboxes' VPC to the VPCs peered with it,
            // a cluster's among them; a destination by tag, Droplet, load balancer or cluster
            // names no address to check.
            let destinations = &rule["destinations"];
            for selector in [
                "tags",
                "droplet_ids",
                "load_balancer_uids",
                "kubernetes_ids",
            ] {
                if destinations[selector]
                    .as_array()
                    .is_some_and(|ids| !ids.is_empty())
                {
                    problems.push(format!(
                        "an outbound rule lets udp {ports} out to {selector}; only to addresses, the platform's DHCP server's"
                    ));
                }
            }
            let addresses: Vec<&Value> = destinations["addresses"]
                .as_array()
                .into_iter()
                .flatten()
                .collect();
            if addresses.is_empty() {
                problems.push(format!(
                    "an outbound rule lets udp {ports} out without an address; name the platform's DHCP server"
                ));
            }
            for address in addresses {
                let address = address.as_str().unwrap_or("?");
                if !dhcp_destination(address, vpc_range) {
                    problems.push(format!(
                        "an outbound rule lets udp {ports} out to {address}; only to the sandboxes' own networks: IPv4 link-local, the broadcast address or the VPC's {}",
                        vpc_range.unwrap_or("range")
                    ));
                }
            }
        }
        Ok(problems)
    }

    /// Checks the template's firewall before a create, at most once a minute.
    async fn check_firewall(&self, template: &Template) -> Result<()> {
        // What the check finds depends on the VPC and the bridge's port too: templates that share
        // a firewall are checked each on their own.
        let key = format!(
            "{} {} {}",
            template.firewall, template.vpc, template.bridge_port
        );
        let fresh = crate::vm::lock(&self.firewalls_checked)
            .get(&key)
            .is_some_and(|at| at.elapsed() < FIREWALL_CHECK_TTL);
        if fresh {
            return Ok(());
        }
        let vpc = self
            .api_ok(Method::GET, &format!("/v2/vpcs/{}", template.vpc), None)
            .await?;
        let problems = self
            .firewall_problems(template, vpc["vpc"]["ip_range"].as_str())
            .await?;
        if !problems.is_empty() {
            crate::vm::lock(&self.firewalls_checked).remove(&key);
            bail!("refusing to create a sandbox: {}", problems.join("; "));
        }
        crate::vm::lock(&self.firewalls_checked).insert(key, Instant::now());
        Ok(())
    }

    pub async fn create(
        &self,
        template_name: &str,
        name: &str,
        user_data: &str,
        labels: &BTreeMap<String, String>,
    ) -> Result<Value> {
        let template = self.template(template_name)?;
        let valid_name = !name.is_empty()
            && name.len() <= 255
            && name
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '.'));
        if !valid_name {
            bail!("`{name}` is not a Droplet name");
        }
        if user_data.len() > MAX_USER_DATA {
            bail!("the user data is over DigitalOcean's 64 KiB");
        }
        if !labels.contains_key(HOST_LABEL) {
            bail!("a sandbox needs the label {HOST_LABEL}");
        }
        let mut tags = vec![SANDBOX_TAG.to_string()];
        for (key, value) in labels {
            tags.push(tag_of(key, value)?);
        }
        self.check_firewall(template).await?;
        let image: Value = match template.image.parse::<u64>() {
            Ok(id) => json!(id),
            Err(_) => json!(template.image),
        };
        let body = json!({
            "name": name,
            "region": template.region,
            "size": template.size,
            "image": image,
            "vpc_uuid": template.vpc,
            "tags": tags,
            "user_data": user_data,
            "ipv6": false,
            "monitoring": false,
            "backups": false,
            "with_droplet_agent": false,
        });
        let created = self
            .api_ok(Method::POST, "/v2/droplets", Some(&body))
            .await?;
        let id = id_of(&created["droplet"]);
        check_id(&id).context("the API answered the create without a Droplet id")?;
        eprintln!("created Droplet {id} ({name}) from the template {template_name}");
        Ok(json!({ "id": id }))
    }

    /// The Droplet `id`, which must be a sandbox; `None` when it is gone.
    async fn sandbox(&self, id: &str) -> Result<Option<Value>> {
        check_id(id)?;
        let (status, value) = self
            .api(Method::GET, &format!("/v2/droplets/{id}"), None)
            .await?;
        match status {
            StatusCode::NOT_FOUND => Ok(None),
            status if status.is_success() => {
                let droplet = value["droplet"].clone();
                let tags = tags_of(&droplet);
                if !tags.iter().any(|t| t == SANDBOX_TAG) {
                    bail!("the Droplet {id} is not a sandbox (no tag {SANDBOX_TAG})");
                }
                if let Some(cluster) = tags.iter().find(|t| t.starts_with("k8s")) {
                    bail!("the Droplet {id} carries the cluster's tag {cluster}: never a sandbox");
                }
                Ok(Some(droplet))
            }
            status => bail!(
                "GET /v2/droplets/{id}: HTTP {status}: {}",
                value["message"].as_str().unwrap_or("no message")
            ),
        }
    }

    fn view(&self, droplet: &Value) -> Value {
        let tags = tags_of(droplet);
        let state = match droplet["status"].as_str() {
            Some("new") => "starting",
            Some("active") => "running",
            Some("archive") => "gone",
            _ => "failed",
        };
        let kind = match self.config.connect {
            Connect::Private => "private",
            Connect::Public => "public",
        };
        let address = droplet["networks"]["v4"]
            .as_array()
            .into_iter()
            .flatten()
            .find(|n| n["type"] == kind)
            .and_then(|n| n["ip_address"].as_str());
        json!({
            "id": id_of(droplet),
            "name": droplet["name"],
            "state": state,
            "address": address,
            "labels": labels_of(&tags),
        })
    }

    pub async fn describe(&self, id: &str) -> Result<Value> {
        Ok(match self.sandbox(id).await? {
            Some(droplet) => self.view(&droplet),
            None => json!({ "id": id, "state": "gone" }),
        })
    }

    /// Deletes the Droplet `id` after reading it: a sandbox, with each of `labels`, which name
    /// the host it belongs to.
    pub async fn delete(&self, id: &str, labels: &BTreeMap<String, String>) -> Result<Value> {
        // Without the host's label every sandbox would pass the check below.
        if !labels.get(HOST_LABEL).is_some_and(|host| !host.is_empty()) {
            bail!("a delete must name the host's label {HOST_LABEL}: not this host's to delete");
        }
        let Some(droplet) = self.sandbox(id).await? else {
            return Ok(json!({}));
        };
        let tags = tags_of(&droplet);
        for (key, value) in labels {
            let tag = tag_of(key, value)?;
            if !tags.contains(&tag) {
                bail!("the Droplet {id} does not carry {tag}: not this host's to delete");
            }
        }
        let (status, value) = self
            .api(Method::DELETE, &format!("/v2/droplets/{id}"), None)
            .await?;
        if !(status.is_success() || status == StatusCode::NOT_FOUND) {
            bail!(
                "DELETE /v2/droplets/{id}: HTTP {status}: {}",
                value["message"].as_str().unwrap_or("no message")
            );
        }
        eprintln!(
            "deleted Droplet {id} ({})",
            droplet["name"].as_str().unwrap_or("?")
        );
        Ok(json!({}))
    }

    /// The sandboxes with each of `labels`.
    pub async fn list(&self, labels: &BTreeMap<String, String>) -> Result<Value> {
        let wanted: Vec<String> = labels
            .iter()
            .map(|(k, v)| tag_of(k, v))
            .collect::<Result<_>>()?;
        let droplets = self
            .all_pages(&format!("/v2/droplets?tag_name={SANDBOX_TAG}"), "droplets")
            .await?;
        let vms: Vec<Value> = droplets
            .iter()
            .filter(|d| {
                let tags = tags_of(d);
                !tags.iter().any(|t| t.starts_with("k8s"))
                    && wanted.iter().all(|w| tags.contains(w))
            })
            .map(|d| self.view(d))
            .collect();
        Ok(json!({ "vms": vms }))
    }
}

fn str_param<'a>(params: &'a Value, key: &str) -> Result<&'a str> {
    params[key]
        .as_str()
        .with_context(|| format!("the parameter `{key}` is missing"))
}

fn labels_param(params: &Value) -> Result<BTreeMap<String, String>> {
    match &params["labels"] {
        Value::Null => Ok(BTreeMap::new()),
        labels => serde_json::from_value(labels.clone()).context("`labels` is not a map of text"),
    }
}

/// Serves the driver protocol on `input` and `output` until `input` ends: each request in a
/// task of its own, each answer on a line of its own.
pub async fn serve<R, W>(driver: Arc<DigitalOcean>, input: R, mut output: W) -> Result<()>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let (tx, mut rx) = mpsc::unbounded_channel::<Value>();
    let writer = tokio::spawn(async move {
        while let Some(answer) = rx.recv().await {
            let mut line = serde_json::to_vec(&answer).expect("answers serialize");
            line.push(b'\n');
            if output.write_all(&line).await.is_err() || output.flush().await.is_err() {
                break;
            }
        }
    });
    let mut lines = BufReader::new(input).lines();
    while let Some(line) = lines.next_line().await? {
        let Ok(request) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        let id = request["id"].clone();
        let method = request["method"].as_str().unwrap_or_default().to_string();
        let params = request["params"].clone();
        let (driver, tx) = (driver.clone(), tx.clone());
        tokio::spawn(async move {
            let answer = match driver.handle(&method, params).await {
                Ok(result) => json!({"jsonrpc": "2.0", "id": id, "result": result}),
                Err(e) => {
                    eprintln!("{method} failed: {e:#}");
                    json!({"jsonrpc": "2.0", "id": id, "error": {"code": -32000, "message": format!("{e:#}")}})
                }
            };
            let _ = tx.send(answer);
        });
    }
    drop(tx);
    let _ = writer.await;
    Ok(())
}

/// `vm-driver digitalocean`: serves the driver protocol on stdin and stdout.
pub async fn run(config_path: &Path) -> Result<()> {
    let config = Config::load(config_path)?;
    serve(
        Arc::new(DigitalOcean::new(config)),
        tokio::io::stdin(),
        tokio::io::stdout(),
    )
    .await
}

/// Whether a DHCP rule's destination (an address or a CIDR) reaches no host outside the
/// sandboxes' own networks: IPv4 link-local, the broadcast address, or the sandboxes' VPC
/// (`vpc_range`). Another private range is not one: it may reach the VPCs peered with the
/// sandboxes', a cluster's among them. DHCPv6 is not on udp 67, so an IPv6 destination is never
/// one.
fn dhcp_destination(cidr: &str, vpc_range: Option<&str>) -> bool {
    let Some(destination) = ipv4_network(cidr) else {
        return false;
    };
    let own = [
        ipv4_network("169.254.0.0/16"),
        ipv4_network("255.255.255.255/32"),
        vpc_range.and_then(ipv4_network),
    ];
    own.into_iter().flatten().any(|(network, prefix)| {
        let mask = u32::MAX.checked_shl(32 - u32::from(prefix)).unwrap_or(0);
        destination.1 >= prefix && destination.0 & mask == network & mask
    })
}

/// An IPv4 address or CIDR as its address and prefix length; an address alone is a /32.
fn ipv4_network(cidr: &str) -> Option<(u32, u8)> {
    let (address, prefix) = cidr.split_once('/').unwrap_or((cidr, "32"));
    let address = address.parse::<std::net::Ipv4Addr>().ok()?;
    let prefix = prefix.parse::<u8>().ok().filter(|p| *p <= 32)?;
    Some((u32::from(address), prefix))
}

/// Whether two IPv4 networks, as [`ipv4_network`] gives them, share an address.
fn overlaps((a, a_prefix): (u32, u8), (b, b_prefix): (u32, u8)) -> bool {
    let mask = u32::MAX
        .checked_shl(32 - u32::from(a_prefix.min(b_prefix)))
        .unwrap_or(0);
    a & mask == b & mask
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::convert::Infallible;
    use std::sync::atomic::{AtomicU64, Ordering};

    use http_body_util::BodyExt;
    use hyper::body::Incoming;
    use hyper_util::rt::TokioIo;

    const TOKEN: &str = "dop_v1_TEST-ONLY-fake-token";
    const HOST: &str = "0192f1a4-0000-7000-8000-00000000aaaa";

    /// A request the fake API got: method, path with query, and body.
    type Seen = (String, String, Value);

    /// The fake API's state: what it answers, and what it was asked.
    struct Fake {
        account: Value,
        image: Option<Value>,
        vpc: Option<Value>,
        firewalls: Vec<Value>,
        droplets: BTreeMap<String, Value>,
        seen: Vec<Seen>,
        next: AtomicU64,
    }

    fn good_firewall() -> Value {
        json!({
            "id": "fw-1", "name": "tinfirm-sandboxes", "status": "succeeded",
            "tags": [SANDBOX_TAG],
            "inbound_rules": [{"protocol": "tcp", "ports": "7443", "sources": {"tags": ["k8s:cluster-1"], "addresses": ["10.110.0.0/20"]}}],
            "outbound_rules": [{"protocol": "udp", "ports": "67", "destinations": {"addresses": ["169.254.169.254/32", "255.255.255.255"]}}]
        })
    }

    fn fake() -> Fake {
        Fake {
            account: json!({"account": {"status": "active", "droplet_limit": 25}}),
            image: Some(
                json!({"image": {"id": 123, "name": "tinfirm-sandbox-0.3.0", "type": "snapshot", "public": false, "status": "available", "regions": ["fra1", "ams3"]}}),
            ),
            vpc: Some(
                json!({"vpc": {"id": "vpc-1", "region": "fra1", "ip_range": "10.120.0.0/20", "default": false}}),
            ),
            firewalls: vec![good_firewall()],
            droplets: BTreeMap::new(),
            seen: Vec::new(),
            next: AtomicU64::new(1000),
        }
    }

    fn droplet(id: u64, tags: &[&str], status: &str) -> Value {
        json!({
            "id": id, "name": format!("tinfirm-run-{id}"), "status": status, "tags": tags,
            "networks": {"v4": [
                {"ip_address": "164.90.1.2", "type": "public"},
                {"ip_address": "10.120.0.7", "type": "private"}
            ]}
        })
    }

    fn answer(fake: &mut Fake, method: &str, path: &str, body: Value) -> (u16, Value) {
        fake.seen
            .push((method.to_string(), path.to_string(), body.clone()));
        let route = path.split('?').next().unwrap_or_default();
        let query = path.split_once('?').map(|(_, q)| q).unwrap_or_default();
        let page = query
            .split('&')
            .find_map(|kv| kv.strip_prefix("page="))
            .and_then(|p| p.parse::<usize>().ok())
            .unwrap_or(1);
        let tagged = query.contains(&format!("tag_name={SANDBOX_TAG}"));
        match (method, route) {
            ("GET", "/v2/account") => (200, fake.account.clone()),
            ("GET", "/v2/droplets") => {
                let all: Vec<Value> = fake
                    .droplets
                    .values()
                    .filter(|d| !tagged || tags_of(d).iter().any(|t| t == SANDBOX_TAG))
                    .cloned()
                    .collect();
                if query.contains("per_page=1&") || query.ends_with("per_page=1") {
                    return (
                        200,
                        json!({"droplets": all.iter().take(1).collect::<Vec<_>>(), "meta": {"total": all.len()}}),
                    );
                }
                // Pages of two, to see the paging.
                let chunk: Vec<Value> = all.iter().skip((page - 1) * 2).take(2).cloned().collect();
                let next = (page * 2 < all.len())
                    .then(|| format!("https://api/v2/droplets?page={}", page + 1));
                (
                    200,
                    json!({"droplets": chunk, "links": {"pages": {"next": next}}, "meta": {"total": all.len()}}),
                )
            }
            ("POST", "/v2/droplets") => {
                let id = fake.next.fetch_add(1, Ordering::Relaxed);
                let tags: Vec<&str> = body["tags"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|t| t.as_str().unwrap())
                    .collect();
                let mut created = droplet(id, &tags, "new");
                created["name"] = body["name"].clone();
                fake.droplets.insert(id.to_string(), created.clone());
                (202, json!({"droplet": created}))
            }
            ("GET", "/v2/firewalls") => (200, json!({"firewalls": fake.firewalls, "links": {}})),
            ("GET", r) if r.starts_with("/v2/images/") => match &fake.image {
                Some(image) => (200, image.clone()),
                None => (
                    404,
                    json!({"id": "not_found", "message": "The resource you were accessing could not be found."}),
                ),
            },
            ("GET", r) if r.starts_with("/v2/vpcs/") => match &fake.vpc {
                Some(vpc) => (200, vpc.clone()),
                None => (404, json!({"id": "not_found", "message": "not found"})),
            },
            ("GET", r) if r.starts_with("/v2/droplets/") => {
                match fake.droplets.get(&r["/v2/droplets/".len()..]) {
                    Some(d) => (200, json!({"droplet": d})),
                    None => (404, json!({"id": "not_found", "message": "not found"})),
                }
            }
            ("DELETE", r) if r.starts_with("/v2/droplets/") => {
                match fake.droplets.remove(&r["/v2/droplets/".len()..]) {
                    Some(_) => (204, Value::Null),
                    None => (404, json!({"id": "not_found", "message": "not found"})),
                }
            }
            _ => (404, json!({"id": "not_found", "message": "no route"})),
        }
    }

    /// Serves the fake API on loopback; returns the driver for it and the state.
    async fn start(fake: Fake) -> (DigitalOcean, Arc<Mutex<Fake>>) {
        let state = Arc::new(Mutex::new(fake));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let served = state.clone();
        tokio::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                let state = served.clone();
                tokio::spawn(async move {
                    let service = hyper::service::service_fn(move |req: Request<Incoming>| {
                        let state = state.clone();
                        async move {
                            let auth = req.headers().get(header::AUTHORIZATION).cloned();
                            let method = req.method().to_string();
                            let path = req.uri().to_string();
                            let bytes = req.into_body().collect().await.unwrap().to_bytes();
                            let body: Value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
                            let (status, value) = if auth.as_ref().and_then(|a| a.to_str().ok())
                                != Some(&format!("Bearer {TOKEN}"))
                            {
                                (
                                    401,
                                    json!({"id": "unauthorized", "message": "Unable to authenticate you"}),
                                )
                            } else {
                                answer(&mut crate::vm::lock(&state), &method, &path, body)
                            };
                            let body = if value.is_null() {
                                Vec::new()
                            } else {
                                serde_json::to_vec(&value).unwrap()
                            };
                            Ok::<_, Infallible>(
                                hyper::Response::builder()
                                    .status(status)
                                    .body(Full::new(Bytes::from(body)))
                                    .unwrap(),
                            )
                        }
                    });
                    let _ = hyper::server::conn::http1::Builder::new()
                        .serve_connection(TokioIo::new(stream), service)
                        .await;
                });
            }
        });
        let template = Template {
            region: "fra1".into(),
            size: "s-4vcpu-8gb".into(),
            image: "123".into(),
            vpc: "vpc-1".into(),
            firewall: "fw-1".into(),
            bridge_port: 7443,
        };
        let config = Config {
            api: url::Url::parse(&format!("http://{addr}")).unwrap(),
            token: tinfirm_protocol::Secret::new(TOKEN),
            connect: Connect::Private,
            templates: BTreeMap::from([("sandbox-4c8g".to_string(), template)]),
        };
        (DigitalOcean::new(config), state)
    }

    fn labels(run: &str) -> BTreeMap<String, String> {
        BTreeMap::from([
            ("tinfirm-host".to_string(), HOST.to_string()),
            ("tinfirm-run".to_string(), run.to_string()),
        ])
    }

    fn host_only() -> BTreeMap<String, String> {
        BTreeMap::from([("tinfirm-host".to_string(), HOST.to_string())])
    }

    fn problems(verified: &Value) -> Vec<String> {
        serde_json::from_value(verified["problems"].clone()).unwrap()
    }

    #[tokio::test]
    async fn a_setup_that_keeps_the_contract_passes_with_the_droplets_left() {
        let mut state = fake();
        // A cluster node and a leftover sandbox: the node counts, the sandbox does not.
        state
            .droplets
            .insert("1".into(), droplet(1, &["k8s:cluster-1", "k8s"], "active"));
        state
            .droplets
            .insert("2".into(), droplet(2, &[SANDBOX_TAG], "active"));
        let (driver, _) = start(state).await;
        let verified = driver.verify("sandbox-4c8g").await.unwrap();
        assert_eq!(problems(&verified), Vec::<String>::new());
        assert_eq!(verified["maxVms"], 24);
        let checked = verified["checked"].to_string();
        assert!(checked.contains("droplet limit 25"), "{checked}");
        assert!(checked.contains("firewall fw-1 on the tag"), "{checked}");
        assert!(driver.verify("other").await.is_err());
    }

    #[tokio::test]
    async fn a_setup_that_breaks_the_contract_is_reported_point_by_point() {
        let mut state = fake();
        state.account = json!({"account": {"status": "locked", "droplet_limit": 10}});
        state.image = Some(
            json!({"image": {"id": 123, "public": true, "status": "available", "regions": ["nyc3"]}}),
        );
        state.vpc = Some(
            json!({"vpc": {"id": "vpc-1", "region": "fra1", "ip_range": "10.120.0.0/20", "default": true}}),
        );
        let mut firewall = good_firewall();
        firewall["inbound_rules"] = json!([
            {"protocol": "tcp", "ports": "7443", "sources": {"addresses": ["0.0.0.0/0"]}},
            {"protocol": "tcp", "ports": "22", "sources": {"addresses": ["10.0.0.0/8"]}}
        ]);
        firewall["outbound_rules"] = json!([
            {"protocol": "udp", "ports": "67", "destinations": {"addresses": ["0.0.0.0/0", "10.120.0.0/20", "10.110.0.0/20", "203.0.113.9/32", "::/0"]}},
            {"protocol": "udp", "ports": "67-68", "destinations": {"tags": ["web"], "droplet_ids": [42]}},
            {"protocol": "tcp", "ports": "0", "destinations": {"addresses": ["0.0.0.0/0", "::/0"]}}
        ]);
        let mut other = good_firewall();
        other["id"] = json!("fw-2");
        other["name"] = json!("allow-web");
        state.firewalls = vec![firewall, other];
        let (driver, _) = start(state).await;
        let found = problems(&driver.verify("sandbox-4c8g").await.unwrap());
        let all = found.join("\n");
        for expected in [
            "the account is locked",
            "the image 123 is public",
            "is not in fra1",
            "the region's default",
            "lets tcp 7443 in from anywhere",
            "lets tcp 22 in",
            "an outbound rule lets tcp 0 out",
            "lets udp 67 out to 0.0.0.0/0",
            // The cluster's VPC, peered with the sandboxes': private, but not theirs.
            "lets udp 67 out to 10.110.0.0/20; only to the sandboxes' own networks: IPv4 link-local, the broadcast address or the VPC's 10.120.0.0/20",
            "lets udp 67 out to 203.0.113.9/32",
            "lets udp 67 out to ::/0",
            "lets udp 67-68 out to tags",
            "lets udp 67-68 out to droplet_ids",
            "lets udp 67-68 out without an address",
            "fw-2 (allow-web) is on the tag",
        ] {
            assert!(all.contains(expected), "no `{expected}` in:\n{all}");
        }
        // DHCP to the sandboxes' own VPC is fine.
        assert!(!all.contains("out to 10.120.0.0/20"), "{all}");
    }

    #[tokio::test]
    async fn missing_parts_and_a_firewall_off_the_tag_are_problems() {
        let mut state = fake();
        state.image = None;
        state.vpc = None;
        let mut firewall = good_firewall();
        firewall["tags"] = json!([]);
        state.firewalls = vec![firewall];
        let (driver, _) = start(state).await;
        let all = problems(&driver.verify("sandbox-4c8g").await.unwrap()).join("\n");
        assert!(all.contains("there is no image 123"), "{all}");
        assert!(all.contains("there is no VPC vpc-1"), "{all}");
        // Without the VPC's range the firewall's check fails rather than skips it.
        assert!(all.contains("the VPC vpc-1 has no IPv4 range"), "{all}");
        assert!(all.contains("not on the tag tinfirm-sandbox"), "{all}");
        let (driver, state) = start(Fake {
            firewalls: Vec::new(),
            ..fake()
        })
        .await;
        let all = problems(&driver.verify("sandbox-4c8g").await.unwrap()).join("\n");
        assert!(all.contains("there is no firewall fw-1"), "{all}");
        // A create checks the firewall first, and creates nothing without it.
        let error = driver
            .create("sandbox-4c8g", "tinfirm-run-x", "{}", &labels("r1"))
            .await
            .unwrap_err();
        assert!(
            format!("{error:#}").contains("refusing to create"),
            "{error:#}"
        );
        assert!(crate::vm::lock(&state).droplets.is_empty());
    }

    #[tokio::test]
    async fn a_create_tags_the_droplet_and_gives_it_nothing_but_its_user_data() {
        let (driver, state) = start(fake()).await;
        let created = driver
            .create(
                "sandbox-4c8g",
                "tinfirm-run-1",
                r#"{"tinfirm":1}"#,
                &labels("run-1"),
            )
            .await
            .unwrap();
        let id = created["id"].as_str().unwrap().to_string();
        let seen = crate::vm::lock(&state).seen.clone();
        let (_, _, body) = seen.iter().find(|(m, _, _)| m == "POST").unwrap();
        assert_eq!(
            body,
            &json!({
                "name": "tinfirm-run-1",
                "region": "fra1",
                "size": "s-4vcpu-8gb",
                "image": 123,
                "vpc_uuid": "vpc-1",
                "tags": [SANDBOX_TAG, format!("tinfirm-host:{HOST}"), "tinfirm-run:run-1"],
                "user_data": r#"{"tinfirm":1}"#,
                "ipv6": false,
                "monitoring": false,
                "backups": false,
                "with_droplet_agent": false,
            })
        );
        // Starting, then running at its VPC address, with its labels back.
        let vm = driver.describe(&id).await.unwrap();
        assert_eq!(vm["state"], "starting");
        crate::vm::lock(&state).droplets.get_mut(&id).unwrap()["status"] = json!("active");
        let vm = driver.describe(&id).await.unwrap();
        assert_eq!(vm["state"], "running");
        assert_eq!(vm["address"], "10.120.0.7");
        assert_eq!(vm["labels"], json!(labels("run-1")));
        // Bad names, labels and oversized user data never reach the API.
        for (name, data, labels) in [
            ("bad name", "{}", labels("r")),
            (
                "ok",
                "{}",
                BTreeMap::from([("tinfirm-host".to_string(), "a b".to_string())]),
            ),
            ("ok", "{}", BTreeMap::new()),
            ("ok", &"x".repeat(MAX_USER_DATA + 1), labels("r")),
        ] {
            assert!(
                driver
                    .create("sandbox-4c8g", name, data, &labels)
                    .await
                    .is_err(),
                "{name}"
            );
        }
        assert_eq!(crate::vm::lock(&state).droplets.len(), 1);
        assert!(
            driver
                .create("nope", "ok", "{}", &labels("r"))
                .await
                .is_err()
        );
    }

    /// The check before each create: DHCP to a private range wider than the sandboxes' VPC
    /// reaches the cluster's VPC, which is peered with it.
    #[tokio::test]
    async fn a_create_refuses_a_firewall_that_lets_dhcp_out_beyond_the_sandboxes_vpc() {
        let mut state = fake();
        state.firewalls[0]["outbound_rules"] = json!([
            {"protocol": "udp", "ports": "67", "destinations": {"addresses": ["10.0.0.0/8"]}}
        ]);
        let (driver, state) = start(state).await;
        let refused = driver
            .create("sandbox-4c8g", "tinfirm-run-1", "{}", &labels("run-1"))
            .await
            .unwrap_err()
            .to_string();
        assert!(
            refused.contains("lets udp 67 out to 10.0.0.0/8"),
            "{refused}"
        );
        assert!(crate::vm::lock(&state).droplets.is_empty());
        // Narrowed to the VPC's own range, the next create reads the firewall again and goes.
        crate::vm::lock(&state).firewalls[0]["outbound_rules"] = json!([
            {"protocol": "udp", "ports": "67", "destinations": {"addresses": ["10.120.0.0/20", "255.255.255.255"]}}
        ]);
        driver
            .create("sandbox-4c8g", "tinfirm-run-1", "{}", &labels("run-1"))
            .await
            .unwrap();
        assert_eq!(crate::vm::lock(&state).droplets.len(), 1);
    }

    #[tokio::test]
    async fn the_sandboxes_themselves_are_never_an_inbound_source() {
        let mut state = fake();
        let mut firewall = good_firewall();
        firewall["inbound_rules"] = json!([
            {"protocol": "tcp", "ports": "7443", "sources": {"addresses": ["10.0.0.0/8"]}},
            {"protocol": "tcp", "ports": "7443", "sources": {"addresses": ["10.120.0.9/32"]}},
            {"protocol": "tcp", "ports": "7443", "sources": {"tags": [SANDBOX_TAG]}},
            {"protocol": "tcp", "ports": "7443", "sources": {"tags": ["tinfirm-run:r1"]}}
        ]);
        state.firewalls = vec![firewall];
        let (driver, state) = start(state).await;
        let all = problems(&driver.verify("sandbox-4c8g").await.unwrap()).join("\n");
        for expected in [
            "from 10.0.0.0/8, which covers the sandboxes' VPC 10.120.0.0/20",
            "from 10.120.0.9/32, which covers the sandboxes' VPC",
            "from the sandboxes' own tags",
        ] {
            assert!(all.contains(expected), "no `{expected}` in:\n{all}");
        }
        assert_eq!(
            all.matches("from the sandboxes' own tags").count(),
            2,
            "{all}"
        );
        // The runner's own VPC, peered, is fine (the good firewall's 10.110.0.0/20).
        let (good, _) = start(fake()).await;
        assert!(problems(&good.verify("sandbox-4c8g").await.unwrap()).is_empty());
        // A create reads the VPC and refuses the same firewall.
        let error = driver
            .create("sandbox-4c8g", "tinfirm-run-x", "{}", &labels("r1"))
            .await
            .unwrap_err();
        assert!(
            format!("{error:#}").contains("covers the sandboxes' VPC"),
            "{error:#}"
        );
        assert!(crate::vm::lock(&state).droplets.is_empty());
    }

    #[tokio::test]
    async fn only_this_hosts_sandboxes_are_deleted_and_never_a_clusters_droplet() {
        let mut state = fake();
        let ours = format!("tinfirm-host:{HOST}");
        state
            .droplets
            .insert("10".into(), droplet(10, &[SANDBOX_TAG, &ours], "active"));
        state.droplets.insert(
            "11".into(),
            droplet(11, &[SANDBOX_TAG, "tinfirm-host:other"], "active"),
        );
        state
            .droplets
            .insert("12".into(), droplet(12, &["k8s:cluster-1"], "active"));
        state.droplets.insert(
            "13".into(),
            droplet(13, &[SANDBOX_TAG, &ours, "k8s:cluster-1"], "active"),
        );
        let (driver, state) = start(state).await;
        for (id, expected) in [
            ("11", "not this host's to delete"),
            ("12", "is not a sandbox"),
            ("13", "carries the cluster's tag"),
            ("12; DELETE", "is not a Droplet id"),
        ] {
            let error = driver.delete(id, &host_only()).await.unwrap_err();
            assert!(format!("{error:#}").contains(expected), "{id}: {error:#}");
        }
        // A delete that names no host could delete any sandbox: refused before any call.
        for labels in [
            BTreeMap::new(),
            BTreeMap::from([(HOST_LABEL.to_string(), String::new())]),
        ] {
            let error = driver.delete("11", &labels).await.unwrap_err();
            assert!(
                format!("{error:#}").contains("must name the host's label"),
                "{error:#}"
            );
        }
        driver.delete("10", &host_only()).await.unwrap();
        // Gone already: no error, and nothing more to delete.
        driver.delete("10", &host_only()).await.unwrap();
        let state = crate::vm::lock(&state);
        let deletes: Vec<&Seen> = state
            .seen
            .iter()
            .filter(|(m, _, _)| m == "DELETE")
            .collect();
        assert_eq!(deletes.len(), 1, "{deletes:?}");
        assert_eq!(deletes[0].1, "/v2/droplets/10");
        assert_eq!(
            state.droplets.keys().cloned().collect::<Vec<_>>(),
            ["11", "12", "13"]
        );
        // Never by tag.
        assert!(
            state
                .seen
                .iter()
                .all(|(m, p, _)| m != "DELETE" || !p.contains("tag"))
        );
    }

    #[tokio::test]
    async fn list_pages_through_the_sandboxes_and_keeps_those_with_the_labels() {
        let mut state = fake();
        let ours = format!("tinfirm-host:{HOST}");
        for id in 20..25u64 {
            state
                .droplets
                .insert(id.to_string(), droplet(id, &[SANDBOX_TAG, &ours], "active"));
        }
        state.droplets.insert(
            "30".into(),
            droplet(30, &[SANDBOX_TAG, "tinfirm-host:other"], "active"),
        );
        state
            .droplets
            .insert("31".into(), droplet(31, &["k8s:cluster-1"], "active"));
        let (driver, state) = start(state).await;
        let listed = driver.list(&host_only()).await.unwrap();
        let ids: Vec<&str> = listed["vms"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v["id"].as_str().unwrap())
            .collect();
        assert_eq!(ids, ["20", "21", "22", "23", "24"]);
        let seen = crate::vm::lock(&state).seen.clone();
        let pages: Vec<&String> = seen
            .iter()
            .filter(|(m, p, _)| m == "GET" && p.starts_with("/v2/droplets?"))
            .map(|(_, p, _)| p)
            .collect();
        assert_eq!(pages.len(), 3, "{pages:?}");
        assert!(
            pages
                .iter()
                .all(|p| p.contains(&format!("tag_name={SANDBOX_TAG}")))
        );
    }

    #[tokio::test]
    async fn the_protocol_is_served_on_stdio_and_a_wrong_token_is_an_error() {
        let (driver, _) = start(fake()).await;
        let (mut client, server) = tokio::io::duplex(64 * 1024);
        let (input, output) = tokio::io::split(server);
        let served = tokio::spawn(serve(Arc::new(driver), input, output));
        client
            .write_all(b"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{\"protocol\":1}}\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"describe\",\"params\":{\"id\":\"999\"}}\n{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"fly\",\"params\":{}}\n")
            .await
            .unwrap();
        let mut lines = BufReader::new(&mut client).lines();
        let mut answers = BTreeMap::new();
        while answers.len() < 3 {
            let line = lines.next_line().await.unwrap().unwrap();
            let answer: Value = serde_json::from_str(&line).unwrap();
            answers.insert(answer["id"].as_u64().unwrap(), answer);
        }
        assert_eq!(answers[&1]["result"]["driver"], "digitalocean");
        assert_eq!(answers[&1]["result"]["protocol"], DRIVER_PROTOCOL);
        assert_eq!(answers[&2]["result"], json!({"id": "999", "state": "gone"}));
        assert!(
            answers[&3]["error"]["message"]
                .as_str()
                .unwrap()
                .contains("no method")
        );
        drop(lines);
        drop(client);
        served.await.unwrap().unwrap();

        let (mut driver, _) = start(fake()).await;
        driver.config.token = tinfirm_protocol::Secret::new("dop_v1_wrong");
        let error = driver.verify("sandbox-4c8g").await.unwrap_err();
        assert!(format!("{error:#}").contains("401"), "{error:#}");
    }

    #[test]
    fn the_configuration_is_checked() {
        let dir = tempfile::tempdir().unwrap();
        let token = dir.path().join("token");
        std::fs::write(&token, format!("{TOKEN}\n")).unwrap();
        let write = |text: String| {
            let path = dir.path().join("config.json");
            std::fs::write(&path, text).unwrap();
            path
        };
        let template = r#"{"region":"fra1","size":"s-4vcpu-8gb","image":"123","vpc":"vpc-1","firewall":"fw-1"}"#;
        let config = Config::load(&write(format!(
            r#"{{"tokenFile":"{}","templates":{{"sandbox-4c8g":{template}}}}}"#,
            token.display()
        )))
        .unwrap();
        assert_eq!(config.token.expose(), TOKEN);
        assert_eq!(config.api.as_str(), "https://api.digitalocean.com/");
        assert_eq!(config.connect, Connect::Private);
        assert_eq!(config.templates["sandbox-4c8g"].bridge_port, 7443);
        for (text, expected) in [
            (
                format!(r#"{{"tokenFile":"{}","templates":{{}}}}"#, token.display()),
                "no templates",
            ),
            (
                format!(
                    r#"{{"tokenFile":"{}","apiUrl":"http://api.example","templates":{{"t":{template}}}}}"#,
                    token.display()
                ),
                "must be https",
            ),
            (
                format!(
                    r#"{{"tokenFile":"{}","templates":{{"t":{}}}}}"#,
                    token.display(),
                    template.replace("vpc-1", "../x")
                ),
                "`vpc` is empty or not an id",
            ),
            (
                format!(
                    r#"{{"tokenFile":"{}","templates":{{"t":{template}}},"extra":1}}"#,
                    token.display()
                ),
                "not a valid configuration",
            ),
            (
                r#"{"tokenFile":"/nonexistent/token","templates":{}}"#.to_string(),
                "cannot read the token file",
            ),
        ] {
            let error = Config::load(&write(text)).err().expect("an error");
            assert!(
                format!("{error:#}").contains(expected),
                "{expected}: {error:#}"
            );
        }
    }

    #[test]
    fn labels_and_tags_map_both_ways() {
        assert_eq!(
            tag_of("tinfirm-host", HOST).unwrap(),
            format!("tinfirm-host:{HOST}")
        );
        for (key, value) in [
            ("host", "x"),
            ("tinfirm-host", "a:b"),
            ("tinfirm-host", ""),
            ("tinfirm host", "x"),
        ] {
            assert!(tag_of(key, value).is_err(), "{key}={value}");
        }
        let tags = vec![
            SANDBOX_TAG.to_string(),
            format!("tinfirm-host:{HOST}"),
            "k8s:x".to_string(),
            "team:a".to_string(),
        ];
        assert_eq!(labels_of(&tags), host_only());
    }

    #[test]
    fn dhcp_reaches_only_the_sandboxes_own_networks() {
        let vpc = Some("10.120.0.0/20");
        for inside in [
            "169.254.169.254/32",
            "169.254.0.0/16",
            "10.120.0.0/20",
            "10.120.4.0/24",
            "10.120.0.2",
            "255.255.255.255/32",
            "255.255.255.255",
        ] {
            assert!(dhcp_destination(inside, vpc), "{inside}");
        }
        for outside in [
            "0.0.0.0/0",
            "8.8.8.8/32",
            // Private, but beyond the sandboxes' VPC: the cluster's peered VPC and the rest.
            "10.110.0.0/20",
            "10.0.0.0/8",
            "10.120.0.0/19",
            "10.120.16.1",
            "172.16.0.0/12",
            "192.168.1.1",
            "169.254.0.0/15",
            "255.255.255.255/31",
            "::/0",
            "fe80::/10",
            "10.0.0.0/33",
            "not-an-address",
        ] {
            assert!(!dhcp_destination(outside, vpc), "{outside}");
        }
        // Without the VPC's range, only link-local and the broadcast address.
        assert!(!dhcp_destination("10.120.0.0/20", None));
        assert!(dhcp_destination("169.254.169.254", None));
        assert!(!dhcp_destination("10.120.0.0/20", Some("not-a-range")));
    }
}
