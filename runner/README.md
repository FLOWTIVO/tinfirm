# Tinfirm runner host

The runner host connects out to a Tinfirm server over a WebSocket, receives runs, and runs
each one in its own Docker container: it clones the repositories of the work, starts the
agent's ACP adapter in the container, streams the agent's progress to the server, serves the
agent's platform tools, and pushes the run's branch (`agent/<key>`) of each changed repository
when the agent is done. The agent is Claude Code, Codex, [pi](https://pi.dev) or [OpenCode](https://opencode.ai)
([ADR-0041](../docs/adr/0041-pi-and-opencode-runtimes.md)). Subscription credentials (Claude
Pro/Max, ChatGPT Plus/Pro) stay on this machine, and a run paid by a budget reaches its model
through the Tinfirm server's model relay, so no model key of the company reaches this machine
([Model access](#model-access)). With [Kata Containers](#kata-containers-and-docker-in-the-sandbox) as
the runtime, every sandbox is a microVM, and the agent can have its own Docker in it. With
[`--executor vm`](#a-vm-per-run), every run gets a fresh VM at a cloud provider instead, and this
host needs no Docker.

The contract is [`docs/CONVENTIONS.md` §14.10](../docs/CONVENTIONS.md#1410-runner-protocol-v2) (protocol v2) and
the v1 rules it keeps, [`docs/archive/v1/CONVENTIONS.md`](../docs/archive/v1/CONVENTIONS.md) §15 (transport, the host
binary, the sandbox, the run flow, the v1 platform tools, capacity settings),
[ADR-0016](../docs/adr/0016-runner-capacity-settings.md) (capacity settings and drain),
[ADR-0025](../docs/adr/0025-kata-sandbox-runtime.md) (Kata runtime and Docker in the
sandbox) and the protocol schemas
[`protocol/runner-v1.schema.json`](../protocol/runner-v1.schema.json) and
[`protocol/runner-v2.schema.json`](../protocol/runner-v2.schema.json). How the server maps a
conversation onto a run is [docs/PLATFORM.md §11.1](../docs/PLATFORM.md#111-the-runner), and the
server's side of runs [CONVENTIONS §15.6](../docs/CONVENTIONS.md#156-runs-and-turns). The host speaks
one of the two protocols per connection ([Protocol versions](#protocol-versions)).

| Path | Contents |
|---|---|
| `crates/tinfirm-protocol` | Message types of the runner protocol v1 and v2, with schema tests and samples |
| `crates/tinfirm-bridge` | The [sandbox bridge](#sandbox-bridge): one pinned TLS stream per run between this host and its sandbox |
| `crates/tinfirm-runner-host` | The `tinfirm-runner-host` binary (and the library the tests drive) |
| `crates/tinfirm-node-agent` | The `tinfirm-node-agent` binary of a [sandbox node](#sandbox-nodes): an unprivileged listener and a root helper that make and delete jailed Firecracker VMs ([its README](crates/tinfirm-node-agent/README.md)). It links no other crate of the workspace |
| `images/` | Dockerfiles of the runner images, embedded into the binary, and the recipe of the sandbox VMs' image (`images/vm`) |

All of it is MIT. The binary also compiles in the paid code of elastic runners, from
`ee/runner/` (the Tinfirm Enterprise licence; [ADR-0100](../docs/adr/0100-elastic-runners-in-pro.md),
[ADR-0114](../docs/adr/0114-the-secure-sandbox-is-in-the-core.md)): DigitalOcean's VM driver and its
Packer template, [ready VMs](#ready-vms) and [session bundles](#session-bundles-protocol-v2), with
their tests.

A maintainer's map of the host's code (its processes, its modules, and the stages of a run from
its assignment to its report) is [`crates/tinfirm-runner-host/MAP.md`](crates/tinfirm-runner-host/MAP.md).

## Install

Requirements: Docker (Docker Desktop, or Docker Engine on Linux) and `git` on the host. A
ready-made binary comes with every release ([Install from a release](#install-from-a-release)).
To build it from source instead, you need Rust stable (1.89 or newer):

```sh
cd runner
cargo install --path crates/tinfirm-runner-host
tinfirm-runner-host --help
```

## Install from a release

Every versioned release, and the prerelease `rolling` (the newest commit on `main` that
passed CI), carries the runner host binary. Only the runner HOST is published: the runner
images, which contain Claude Code, never are;
`images build` builds them on
your machine, and `run` rebuilds them when a new binary brings other Dockerfiles
([Runner images](#rebuilds-after-an-upgrade)). Nor is the VM image of
[sandbox nodes](#sandbox-nodes), which holds the runner images: you build it.

| Asset | Host |
|---|---|
| `tinfirm-runner-host-<version>-x86_64-unknown-linux-musl.tar.gz` | Linux x86_64. Static (musl): any distribution. |
| `tinfirm-runner-host-<version>-aarch64-unknown-linux-musl.tar.gz` | Linux arm64. Static (musl): any distribution. |
| `tinfirm-runner-host-<version>-aarch64-apple-darwin.tar.gz` | macOS on Apple silicon |
| `tinfirm-runner-host-<version>-x86_64-pc-windows-msvc.zip` | Windows. Planned: with the Windows port of the runner host. |

`<version>` is `x.y.z` for the release `vx.y.z`, and `rolling-<short sha>` for `rolling`.
`SHA256SUMS` lists every asset, `SHA256SUMS.sig` is its signature, and `REVISION` names the
commit. A checksum beside the files proves nothing against whoever can replace them, so check
the signature of `SHA256SUMS` before you trust it: with FLOWTIVO's release key
[`deploy/compose/auto-deploy/cosign.pub`](../deploy/compose/auto-deploy/cosign.pub) (the same as
`docs-site/content/cosign.pub`) for a release, the rolling key
[`cosign-rolling.pub`](../deploy/compose/auto-deploy/cosign-rolling.pub) beside it for `rolling`,
taken from your checkout of the repository, never from the release. Download the
assets from the release's page on GitHub, or with the GitHub CLI (`gh auth login`):

```sh
tag=v1.0.0                          # or rolling, the development channel
target=x86_64-unknown-linux-musl    # see the table
key=deploy/compose/auto-deploy/cosign.pub   # for rolling: deploy/compose/auto-deploy/cosign-rolling.pub
gh release download "$tag" -R FLOWTIVO/tinfirm -p SHA256SUMS -p SHA256SUMS.sig \
  -p "tinfirm-runner-host-*-$target.tar.gz"
openssl base64 -d -A -in SHA256SUMS.sig -out SHA256SUMS.der
openssl dgst -sha256 -verify "$key" -signature SHA256SUMS.der SHA256SUMS   # must say Verified OK
grep -- "-$target.tar.gz\$" SHA256SUMS | sha256sum -c -     # macOS: shasum -a 256 -c -
tar -xzf tinfirm-runner-host-*-"$target".tar.gz
mkdir -p ~/.local/bin
install -m 755 tinfirm-runner-host-*-"$target"/tinfirm-runner-host ~/.local/bin/
tinfirm-runner-host --version
```

The archive also holds `LICENSE` (MIT), `ee/LICENSE` and `THIRD-PARTY-LICENSES.txt`, the licences
of the crates the binary is built with ([ADR-0082](../docs/adr/0082-licence-notices-and-dependency-licences.md)).
The binary holds elastic runners' code too, under the Tinfirm Enterprise licence
([ADR-0100](../docs/adr/0100-elastic-runners-in-pro.md),
[ADR-0114](../docs/adr/0114-the-secure-sandbox-is-in-the-core.md)): DigitalOcean's VM driver, the
driver of a pod per run in a Kubernetes cluster, ready VMs and session bundles. They are Pro's and
Enterprise's: a server whose licence does not hold them refuses a host started with
`--vm-driver digitalocean`, `--vm-driver pod` or `--pool-max-ready` above 0
(`EDITION_REQUIRED`, and the host stops), and hands out no session bundle. The sandbox bridge and
a VM per run with a driver of your own are in every edition, as is a host with Docker, as below.

The same release also carries the node agent of a sandbox node
([ADR-0112](../docs/adr/0112-no-credential-on-a-sandbox-machine.md)):
`tinfirm-node-agent-<version>-x86_64-unknown-linux-musl.tar.gz` and
`tinfirm-node-agent-<version>-aarch64-unknown-linux-musl.tar.gz`, static. It runs on the sandbox
nodes of a control host whose VMs run on your own machines, not on a runner host; install it from
the same release as the runner host, which checks the agent's release when it connects. Download
and verify it as above, with `tinfirm-node-agent` in place of `tinfirm-runner-host` and a Linux
target; its archive holds the binary, its licence and the licences of its crates. It runs jailed
Firecracker VMs, and has run them on one KVM node; its service units are in
`crates/tinfirm-node-agent/units/`, and the role that sets up a node is planned
([Sandbox nodes](#sandbox-nodes);
[docs-site, runner hosts](../docs-site/content/operator-guide/runner-hosts.md)).

Then continue with the [Quickstart](#quickstart). To upgrade, install the
new binary the same way and drain ([Drain and upgrades](#drain-and-upgrades)). A Compose host
that follows `rolling` with auto-deploy does this by itself: it installs the new binary when
its checksum changes and runs `systemctl reload` on the runner unit
([docs/operations/auto-deploy.md](../docs/operations/auto-deploy.md)). The macOS binary is not
signed; a copy downloaded with a browser needs `xattr -d com.apple.quarantine` first.

## Quickstart

A runner host is a company host or a personal host, chosen when it is registered
([docs/PLATFORM.md §11.1](../docs/PLATFORM.md#111-the-runner)). The binary and the steps are the same; what
the server sends it differs:

| | Company host | Personal host |
|---|---|---|
| Registered by | OWNER or ADMIN, on **Runner hosts** → **Register a runner host** | Any member, for themselves, on **Accounts** → **Runner hosts** → **Register a runner host** |
| Companies | The one it was registered in | Every company its owner belongs to on this Tinfirm, over this one connection (ADR-0092): the slots are shared, and `welcome` names the host's id and one of its companies |
| Runs | Any member's turns paid by a budget, through the server's model relay ([Model access](#model-access)) | Only its owner's turns: those paid by a subscription on this host, and those paid by the owner's budget whose accounts are all the owner's own |
| Credentials on this machine | None. A credential it reports in `hello` is refused with the error `PERSONAL_HOST_CREDENTIAL_ONLY` (a `server error` warning in the log) and not stored | The owner's subscriptions (Claude, ChatGPT) and API keys: the owner's model access, which serves only the owner's work |

1. **Register the host in the web app** (the table says where). Copy the host token (`tfr_…`);
   it is shown once, with the commands of the next steps.
2. **Log in** with the server URL and the token:
   ```sh
   tinfirm-runner-host login --server https://tinfirm.example.com --token tfr_…
   ```
   Leave out `--token` to type it without echo, or pipe it on stdin: on the command line it
   shows in the process list and the shell's history, and `login` warns about it. Plain `http://` is accepted only for
   `localhost` (local development). Use the server's public URL (`TINFIRM_PUBLIC_URL`): the
   model proxy sends this host's own token only to that origin ([Model proxy](#model-proxy)).
3. **Personal host only: add a Claude subscription.** Skip steps 3 and 4 on a company host.
   This runs Claude Code's own sign-in (`claude auth login`) with `CLAUDE_CONFIG_DIR` set to
   a dedicated directory under the data dir; your own `~/.claude` is not touched. Sign in with
   the Claude account this host should use; without a browser on this machine, open the
   printed URL elsewhere and paste the code it shows ([ADR-0085](../docs/adr/0085-claude-login-on-the-runner-host.md)):
   ```sh
   tinfirm-runner-host credential add claude --label claude-main   # --pool 3 by default
   ```
   It uses the `claude` CLI of this machine, or the one in the runner image
   `tinfirm-runner-claude:local` (built in step 5) when there is none, and asks the same
   Claude Code whether it is signed in (`claude auth status`). The login stays in that
   directory as Claude Code's own `.credentials.json`, which Claude Code renews; the host reads
   nothing from it and holds no token. When the login expires, run the command again. As for a
   Codex login, the sandbox's user (UID 1000) reads and renews the file through the mount, so
   the host's user must be able to share it: run the host as UID 1000 (a personal host; the
   Ansible role, for company hosts, which hold no logins, uses another uid).
   A host upgraded from a `setup-token` credential (`CLAUDE_OAUTH_TOKEN`, before ADR-0085)
   skips the old file with a warning and `doctor` names it: `credential remove <label>`, then
   add the login as above. Until the host is upgraded, the server ignores that credential in
   its hello (`UNKNOWN_CREDENTIAL_KIND`) and the host's other credentials serve. Upgrade the
   server before the hosts: a server from before this change refuses the hello of an upgraded
   host that reports a login, so a server rolled back takes its hosts back with it.
4. **Personal host only: add a Codex subscription** (ChatGPT Plus/Pro). This runs `codex login` with
   `CODEX_HOME` set to a dedicated directory under the data dir; your own `~/.codex` is not
   touched. `--pool N` (1–16, default 3) is the ceiling of runs at once, as for every
   credential: the runs share the one login, which Codex renews in place and re-reads
   before each renewal, so one renewal serves them all. Keep no copy of the login on
   another machine: ChatGPT refresh tokens are single-use, and two copies break each other.
   ```sh
   tinfirm-runner-host credential add codex --label codex-main     # --device-auth without a browser
   ```
   If the Codex CLI is not installed, the command explains how to place an `auth.json`
   there instead. A run's sandbox can write to that directory, so the host reads `auth.json`
   (for the redaction of its tokens, `doctor`, and the check of each run) only as a regular file
   of at most 256 KiB, never through a link, a FIFO or a device; anything else is a problem that
   `doctor` and the run name, and adding the login again repairs it.
5. **Build the runner images** (a few minutes the first time):
   ```sh
   tinfirm-runner-host images build            # --runtime claude|codex|pi|opencode|all|multi, --no-cache
   ```
   Once is enough: after an upgrade, `run` rebuilds these images itself
   ([Rebuilds after an upgrade](#rebuilds-after-an-upgrade)).
6. **Check the setup** (Docker, images, credential presence and format, server connection;
   no model calls):
   ```sh
   tinfirm-runner-host doctor
   ```
   On a company host `doctor` warns that there are no credentials; that is expected.
7. **Run**:
   ```sh
   tinfirm-runner-host run                     # --max-concurrent-runs 2 by default
   ```
   The host appears online on its page in the web app. A company host takes turns paid by a
   budget. A personal host's card lists the subscriptions it reported;
   its owner picks one under **Who pays** in **My settings** of an agent, and a trigger's
   author in the trigger's settings. Agents name no credential, and there are no tasks: work
   starts as a conversation (**Conversations** → **New conversation**) or from a trigger.
   `Ctrl-C` (or SIGTERM) cancels the active runs, pushes their work in progress,
   reports them as cancelled, and exits. A second `Ctrl-C` exits at once. SIGHUP, or
   `tinfirm-runner-host drain` from another terminal, lets the active runs finish instead
   ([Drain and upgrades](#drain-and-upgrades)). Only one `run` process per config directory
   can run (`run.lock`); `doctor` skips its server check while `run` is active, because a
   second connection would replace the first.

On a personal host, API keys work the same way: `credential add anthropic-api-key --label …`
(Claude Code, pi and OpenCode) and `credential add openai-api-key --label …` (Codex, pi and
OpenCode). pi and OpenCode take only API keys, never a subscription. Other providers (Amazon
Bedrock, Gemini, OpenRouter, …) have no credential on this machine: a company adds them to its
LiteLLM, and runs paid by a budget reach them through the server's relay
([Model access](#model-access)). `credential list` shows metadata only;
`credential remove <label>` deletes a credential. `run` reads the credentials and their
pools when it starts, because the server learns them from the `hello` frame: an added
credential or a changed pool takes effect at the next start (drain or restart `run`); a
removed one is refused at once.

## Egress proxy

By default a sandbox can connect to any host. With `--egress-proxy` it can reach only the
runner host: it runs on an internal Docker network (`tinfirm-sandbox-egress`, created for you), and a
proxy of the host, one listener per run, lets a connection out only to an allowlist
([ADR-0044](../docs/adr/0044-sandbox-egress-proxy.md)):

- the agent's **model**: the provider's API (`api.anthropic.com`, `api.openai.com`, Bedrock in the credential's region, …); a gateway is not on it, since the [model proxy](#model-proxy) reaches it for the sandbox;
- the **package mirrors** of `--sandbox-env` (`TINFIRM_SANDBOX_MAVEN_MIRROR`, `_NPM_REGISTRY`, `_PIP_INDEX`, `_REGISTRY_MIRRORS`);
- your **`--egress-allow`** entries: `HOST[:PORT]` (443 unless given, `:*` for any), `*.example.com` (subdomains only), or an IP.

```sh
tinfirm-runner-host run --egress-proxy \
  --egress-allow registry.npmjs.org --egress-allow '*.maven.apache.org' \
  --egress-allow-private --egress-allow nexus.corp.example:8443
```

Loopback and link-local addresses (cloud metadata) are always refused; private ones only with
`--egress-allow-private`, which you need when an allowed host (a registry), or the gateway
that the model proxy reaches (the Tinfirm server's relay), is inside your network. The sandbox's tools use `HTTP_PROXY`/`HTTPS_PROXY` (and `JAVA_TOOL_OPTIONS`
for the JVM); a tool that ignores them has no network. `--sandbox-env` may not set the proxy
variables, and an upstream corporate proxy is not supported yet. A run's log lists what the proxy refused
(`403`, header `x-tinfirm-egress: denied`), so you can see what to allow. The proxy does not read
traffic: it limits where data can go, not what it is.

**Its two halves.** A sandbox speaks HTTP to the proxy: `CONNECT host:port` for a tunnel, or a
request with an absolute `http://` URL. The part that parses this (the request, the
`Proxy-Authorization` check and the copying of a tunnel's bytes) is one half; the decision is
the other: whether the destination is on the run's allowlist, where its name resolves, whether that
address is refused, the limit of 256 open connections per run (plain requests count too), and
the connection itself. With `--sandbox-bridge` or a [VM per run](#a-vm-per-run), the parsing
half runs in the sandbox's [front process](#the-reader-and-the-front), confined, which cannot make a
connection: it asks `run` for each one by name and port, and `run` decides, connects and hands
it the connected socket, or answers with the refusal (`400`, `403`, `502` or `503`) that the
front gives the sandbox. `run` logs each refusal, and a front that asks for more than the wire allows
(more than 512 open connections, a name over 253 bytes or not printable, port 0, an answer it
did not wait for) is ended, with every listener it serves. Without the bridge both halves run in `run`. A
destination with a user name (`user@host`) is refused with `400` either way.

**Requirements.** Docker Engine on Linux. With Docker Desktop (or any engine in a VM) `run`
refuses `--egress-proxy`: the sandboxes would reach this machine as `host.docker.internal`, which an
internal network cannot route to.

**Testcontainers with `--sandbox-docker`.** The agent's own Docker pulls images through the proxy
(`daemon.json` gets the proxy settings), so the registries must be allowed. For Docker Hub:
`--egress-allow registry-1.docker.io --egress-allow auth.docker.io --egress-allow production.cloudfront.docker.com`
(or a registry mirror of `--sandbox-env`). Containers it starts have no direct egress either.

**Services on this machine.** The sandbox network is internal, so nothing leaves the machine except through the proxy, but a sandbox can still connect to any service that listens on the network's gateway address, which is an address of this machine. Services bound to all interfaces (SSH, a metrics exporter, a Docker API on TCP) are reachable from a sandbox that ignores its proxy settings. Bind such services to specific interfaces, or drop input from the sandbox bridge in the host firewall except for the runner host's own listeners ([ADR-0044](../docs/adr/0044-sandbox-egress-proxy.md)).

### Public Suffix List

Under protocol v2 the server sends the host the [Public Suffix List](https://publicsuffix.org)
(`psl.update`, on connect when the host's version differs and whenever the server's list
changes; the host answers nothing). The runner embeds no list: the host verifies the frame
(SHA-256, at most 4 MiB unpacked, the shape of the list), uses it and keeps it in
`public_suffix_list.dat` in its data directory (mode 0600), reads it again at start and names its
SHA-256 in `hello`. A frame that is not valid is logged and the list the host holds stays. It is
used to refuse **wildcards over a public suffix** (`*.github.io`, `*.com.sg`), which would reach every
site of a shared service ([ADR-0044](../docs/adr/0044-sandbox-egress-proxy.md)): `request_network_access`
refuses one at once and tells the agent to name the exact host (a wildcard over one tenant,
`*.tenant.github.io`, is fine); a run's `allowedHosts` entry of that kind is dropped with a warning in
the run log, and the run goes on; your own `--egress-allow` wildcards stay, with a warning in the host's log
at start. Without a list none of these checks happen.

## Model proxy

The key of a **gateway** never enters the sandbox, where a prompt-injected agent could read it
(threat model 7.13, and R25 for a host's own keys;
[platform redesign, Part 7](../docs/reviews/2026-10-12-platform-redesign.md)): a run whose
`modelAccess` has a base URL, in the `anthropic` or `openai` format, gets a listener of this host
that holds the key, and its runtime gets that listener and a dummy key. Every run the Tinfirm
server pays from a budget is such a run: its gateway is the server's model relay and its key a
one-run token ([Model access](#model-access)). Model access without a base URL, which the server
never sends, is refused; this host's own credentials keep the key in the sandbox's environment. The runner protocol did not change for the proxy.

**Always on**: no option turns it off. The always-on proxy is part of runner protocol 2, so no
host of protocol 2 can put a gateway's key into the sandbox
([ADR-0059](../docs/adr/0059-litellm-budgets-and-the-model-proxy.md), decision 5;
[ADR-0109](../docs/adr/0109-runner-version-is-the-release-version.md)), and the server sends runs
paid by a budget to any host of protocol 2, whatever its version. `--model-proxy`
(`TINFIRM_RUNNER_MODEL_PROXY`), a former opt-in, is still accepted so that
service files keep working: it does nothing, not even with `0` or `false`, and `run` logs a warning
at start. It goes in a later release, which will refuse it as an unknown option, so remove it.

**The host's own token.** The gateway of a Tinfirm server is the server's model relay,
and the run's key is a one-run token. So that a copied run token is worth nothing away from this
host, the proxy also sends this host's runner token (the one of `login`) in the header
`x-tinfirm-runner-token`, and the relay takes the run's token only with the token of the host the
run was assigned to ([package 2](../docs/reviews/2026-10-12-package-2-contract.md), open point 6).
The proxy sends it only to a gateway at the **origin of this host's server URL** (scheme, host and
port of `login --server`, `wss` counting as `https`), where the WebSocket already takes it; any other
gateway never gets it, the sandbox never sees it, and a header of that name from the sandbox is
dropped like every other credential. A server's relay therefore needs the host logged in with the
server's public URL (`TINFIRM_PUBLIC_URL`): a relay at another origin gets no host token, the run's
log says so, and the server refuses the run's model requests without it (`401`). Sending it is part
of runner protocol 2 ([Compatibility with the server](#compatibility-with-the-server)).

```sh
tinfirm-runner-host run                          # a public gateway
tinfirm-runner-host run --egress-allow-private   # a gateway inside your network
```

**What the sandbox sees.** The base URL `http://<host>:<port>/<run secret>`, followed by the path of
the gateway's base URL (the relay's `/api/runner/v1/model/anthropic` or `/api/runner/v1/model/openai/v1`),
where `<host>` is where the sandbox reaches the git proxy and `<port>` is the run's own; the run
secret is masked in every log. The key is `tinfirm-model-proxy-dummy-key`. Both go into the
variables of each runtime as in the table of [Model access](#model-access) (`ANTHROPIC_BASE_URL` and
`ANTHROPIC_AUTH_TOKEN`, Codex's `OPENAI_API_KEY` and the `base_url` of its gateway provider in
`CODEX_CONFIG`, pi's `TINFIRM_MODEL_BASE_URL`, OpenCode's `baseURL`).

**What the host does.** The listener starts with the run and stops when the run ends; afterwards
nothing answers on its port. For a request to `/<run secret>/<path>` it:

- answers `404` to any other secret, and forwards only the model API: the gateway's base path followed by one of these, byte for byte (no `..`, no percent-escapes, no sub-paths); any other path is `404`, another method `405`;

  | Format | Path after the base path | Method |
  |---|---|---|
  | `anthropic` | `/v1/messages`, `/v1/messages/count_tokens` | `POST` |
  | `anthropic` | `/v1/models` | `GET` |
  | `openai` | `/responses`, `/chat/completions` | `POST` |
  | `openai` | `/models` | `GET` |

- drops every credential header of the sandbox (`Authorization`, `x-api-key`, `api-key`, `Proxy-Authorization`, `x-litellm-api-key`, `x-goog-api-key`, cookies, `x-tinfirm-runner-token`), the hop-by-hop and forwarding headers, sets `Authorization: Bearer <key>` and, for this host's server only, `x-tinfirm-runner-token: <host token>`, and keeps the rest (`anthropic-version`, `anthropic-beta`, the query);
- connects to the base URL's origin itself, over https with the system's root certificates, to an address it resolved and checked as the [egress proxy](#egress-proxy) does: loopback, link-local (cloud metadata), reserved and this machine's own addresses are refused, private ones unless `--egress-allow-private`, and loopback and this machine's addresses only for the gateway of `--model-proxy-local-gateway` ([below](#a-gateway-on-this-machine)). It follows no redirect (`502`);
- streams the answer back as it arrives (SSE), with no total timeout, only an idle one of 10 minutes between two parts; refuses a WebSocket upgrade (`501`); takes request bodies up to 64 MiB and 64 connections per run at once (more wait);
- takes a request head of at most 64 headers, each name at most 64 bytes and each value at most 8 KiB, 24 KiB in all (`431` above), and a query of at most 4 KiB (`414`); header values pass byte for byte, UTF-8 text included, and only the bytes HTTP forbids (CR, LF, NUL and the other control bytes) are refused; passes back the first 100 of the answer's headers, byte for byte, and drops the rest;
- never logs the key or the host's token, and never passes them back: a gateway answer that contains either is cut off (`502` when it is in a header).

**Its two halves.** The part that reads the sandbox's request (the run secret, the path rule,
the method, `Upgrade`, the bounds of the head) is one half; the other checks the request again
as it comes (an endpoint of the gateway's format, its method, every header one that HTTP allows,
the length within 64 MiB), attaches the key and the host's token, makes the request, and scans
and passes back the answer. With `--sandbox-bridge` or a [VM per run](#a-vm-per-run), the first
half runs in the sandbox's [front process](#the-reader-and-the-front), which never holds the key or the
host's token: it hands `run` the request's endpoint, method, query, headers and length, and the
body on a socket of its own; `run` gives the answer's status and headers back, and its body on
the same socket, part by part, so an SSE answer still arrives as it comes. A client that leaves,
before the answer, in its request body or in the answer, ends the request to the gateway too.
Without the bridge both halves run in `run`, with the same code and the same rules.

With `--egress-proxy` as well, the gateway leaves the run's allowlist (the sandbox no longer needs a
route to it), and `NO_PROXY` already names the listener's host. A run whose listener cannot start
fails (`INFRA`, "the model proxy cannot start: …") before its sandbox starts, rather than give the
sandbox the key: there is no fallback. Subscriptions keep this host's own credential handling.

**Where it listens, checked at start.** The listeners bind the address of the
[git proxy](#git-in-the-sandbox), each on a port the system picks. Because a gateway's run fails
without its listener, `run` checks that address when it starts (it binds a port there and lets it
go), and where no listener could serve a sandbox it refuses to start, naming what to change, so
that you learn it then and not from the first gateway run:

| Setup | Listeners on | Sandboxes connect to | `run` |
|---|---|---|---|
| Docker Engine on Linux | the gateway of the sandbox network: `bridge` (`docker0`, usually `172.17.0.1`), `tinfirm-sandbox-egress` with `--egress-proxy`, or the network of `TINFIRM_RUNNER_SANDBOX_NETWORK` | that address | Starts. Refused when the network has no gateway, or its gateway is not an address of this machine (a `macvlan` network; rootless Docker, whose networks live in their own namespace): set the two variables below |
| `TINFIRM_RUNNER_SANDBOX_NETWORK=host` | `127.0.0.1` | `127.0.0.1` | Starts. A gateway listening on `127.0.0.1` itself is then refused ([below](#a-gateway-on-this-machine)) |
| `TINFIRM_RUNNER_SANDBOX_NETWORK=none` | nowhere | nothing: no network | **Refused**: leave the variable unset, or name a network |
| Docker Desktop, or an engine in a VM (macOS) | `127.0.0.1` | `host.docker.internal` | Starts |
| `TINFIRM_RUNNER_GIT_PROXY_BIND` (and `_HOST`) | that address (its port is the git proxy's only) | `TINFIRM_RUNNER_GIT_PROXY_HOST`, or the detected host | Starts. **Refused** when the address is unspecified (`0.0.0.0`, `::`) or not one of this machine's |

On Docker Engine on Linux (runc), a container on the default bridge, one on an internal network
(as with `--egress-proxy`) and one on the host network reach their run's listener. The automated
tests run the listener in-process and with the local test executor; Docker Desktop's
`host.docker.internal` is not verified, as for the git proxy.

What `run` cannot check is whether a sandbox gets through to that address. A host firewall that
admits from the sandbox bridge only the git proxy's fixed port blocks the model proxy's listeners,
whose ports the system picks: admit input from the bridge to its gateway address on every port, as
the egress proxy needs too, and bind this machine's other services to other interfaces
([Egress proxy](#egress-proxy)). An engine in a VM that does not forward `host.docker.internal`
to this machine blocks them too (set the two variables). A gateway's run then fails at its first model
request (connection refused, or a timeout), and the key stays on this host. A run whose git proxy
cannot start (its fixed port taken) fails too when its model access is a gateway, since the model
proxy takes the git proxy's address; other runs go on without fetch and the platform tools.

### A gateway on this machine

The model proxy refuses a gateway on loopback or on this machine's own addresses, so that a run's
base URL cannot point it at a service of this host. When the gateway does run here, name its origin with `--model-proxy-local-gateway`. With the
Tinfirm server as the model relay (ADR-0059), the gateway is the Tinfirm server itself, so this is
for a runner host on the same machine as the server, with the server's public https origin
(`TINFIRM_RUNNER_MODEL_PROXY_LOCAL_GATEWAY`); log the host in with that same origin too, so that
its own token goes with the relay's requests ([above](#model-proxy)):

```sh
tinfirm-runner-host run --model-proxy-local-gateway https://tinfirm.corp.example
```

- **One origin**: `https://`, the host and the port of the runs' base URL (the server's public URL), without a path (`https://tinfirm.corp.example`, not `…/api/runner/v1/model/anthropic`), a user name, a query or a fragment. `run` refuses anything else at start.
- **Only that gateway**: a run whose base URL has exactly this origin (scheme, host and port; the host's case and the default port 443 do not count) may reach the gateway on loopback and on this machine's own addresses, private ones too, without `--egress-allow-private`. On any other address its name resolves to, and for every other gateway, the rules above stay: another port or another name for the same gateway (`localhost` for `127.0.0.1`) is refused.
- **Never**: the model proxy's own listening address (the git proxy's: the sandbox network's gateway, or `127.0.0.1` with Docker Desktop and `TINFIRM_RUNNER_SANDBOX_NETWORK=host`, where the gateway must then listen on another address of this machine), and the addresses no gateway has (link-local and cloud metadata, reserved), whatever the option says.
- **https only**: the model proxy forwards to no other gateway, and over plain http whatever listens on the port first (another process of this machine, while the gateway restarts) would get the key, with no certificate to show for it. Put the gateway behind TLS with a certificate for the origin's host name that this machine's system roots trust (a public CA, or your CA in the system's trust store).

## Kata Containers and Docker in the sandbox

By default a sandbox is a container under Docker's default runtime (runc), which shares this
machine's kernel. On a Linux machine with KVM (bare metal, or a VM with nested
virtualisation), [Kata Containers](https://katacontainers.io) runs every sandbox as a microVM
with its own kernel instead, and the agent can then have its own Docker daemon for
Testcontainers, `docker compose` and image builds ([ADR-0025](../docs/adr/0025-kata-sandbox-runtime.md)).

| Option | Variable | Effect |
|---|---|---|
| `run --sandbox-runtime <name>` | `TINFIRM_RUNNER_SANDBOX_RUNTIME` | The Docker runtime of every sandbox, for example `kata`. Default: Docker's default (runc). `run` refuses a runtime that Docker does not know. The host reports `gvisor` for a `runsc…` name and `container` for any other, a `kata…` one included ([Isolation level](#isolation-level)) |
| `run --sandbox-docker` | `TINFIRM_RUNNER_SANDBOX_DOCKER` (`1`, `true`) | The agent gets its own dockerd in the sandbox (below) |
| `run --egress-proxy`, `--egress-allow HOST[:PORT]`, `--egress-allow-private` | `TINFIRM_RUNNER_EGRESS_PROXY`, `TINFIRM_RUNNER_EGRESS_ALLOW` (one entry per line), `TINFIRM_RUNNER_EGRESS_ALLOW_PRIVATE` | The sandboxes reach the network only through the host's egress proxy, to the agent's model, the mirrors of `--sandbox-env` and the allowed hosts ([below](#egress-proxy)) |
| `run --model-proxy-local-gateway ORIGIN`, `--egress-allow-private` | `TINFIRM_RUNNER_MODEL_PROXY_LOCAL_GATEWAY`, `TINFIRM_RUNNER_EGRESS_ALLOW_PRIVATE` | The [model proxy](#model-proxy), which keeps a gateway's key on this host, is always on and has no option of its own. These two only widen where the gateway may be: the one gateway on this machine, or a private address. `--model-proxy` (`TINFIRM_RUNNER_MODEL_PROXY`) is accepted by 0.2.0 and 0.3.0 and does nothing |
| `run --sandbox-env KEY=VALUE`, repeatable | `TINFIRM_RUNNER_SANDBOX_ENV`, one `KEY=VALUE` per line | Extra environment of every sandbox: the mirror settings below, `HTTPS_PROXY` and the like. **Never credentials**: the agent can read them, they show in `docker inspect`, and they are not redacted |
| `run --sandbox-bridge` | `TINFIRM_RUNNER_SANDBOX_BRIDGE` (`1`, `true`) | The agent's adapter runs over the [sandbox bridge](#sandbox-bridge) instead of Docker attach, and the run's credentials leave the container's settings |

**The rule:** `--sandbox-docker` needs a runtime whose name starts with `kata`, or `run`
refuses to start. The agent's Docker needs a container that starts as root with every
capability and no seccomp, AppArmor or SELinux profile; under runc that is root on this
machine. In a Kata VM it is root in that VM only.

The sandbox is never `--privileged`: Docker puts every host device into the spec of a
privileged container, and Kata hot-plugs those block devices into the VM, which would hand
the agent this machine's disks. Docker has no `privileged_without_host_devices` (that is a
containerd CRI and CRI-O option), so the runner host sets the capabilities and security
options itself and keeps Docker's default devices. With `--sandbox-docker` the container
gets:

- user `0:0`, `cap_add: ALL`, `seccomp=unconfined`, `apparmor=unconfined`, `label=disable`,
  empty `MaskedPaths` and `ReadonlyPaths` (what the CLI sends for `systempaths=unconfined`,
  which the Engine API itself rejects), a private cgroup namespace;
- the device cgroup rules `b 7:* rwm` and `c 10:237 rwm`: the VM's own loop devices, not
  this machine's;
- `TINFIRM_SANDBOX_DOCKER=1` and `TINFIRM_SANDBOX_AGENT_USER=1000:1000`;
- the same mounts, limits, git proxy and platform tools as any sandbox. The limits then also
  cover the agent's dockerd and its containers.

The image's entrypoint `tinfirm-sandbox-entrypoint` then makes `/sys` and the cgroup
filesystems writable, mounts an ext4 loop disk at `/var/lib/docker` (a sparse file in the
container's own filesystem, never under `/workspace` or `/home/agent`: under Kata that
filesystem is virtio-fs, which overlayfs cannot use as its upper layer), writes
`/etc/docker/daemon.json`, starts dockerd and waits for it, sets
`TESTCONTAINERS_HOST_OVERRIDE=localhost`, and runs the adapter as 1000:1000. If dockerd does
not answer in time, the run fails with dockerd's last log lines in its log.

**Mirror settings** (`--sandbox-env`), read by the entrypoint at the start of every run:

| Variable | Written to |
|---|---|
| `TINFIRM_SANDBOX_REGISTRY_MIRRORS` | `registry-mirrors` of the agent's dockerd; comma-separated URLs |
| `TINFIRM_SANDBOX_INSECURE_REGISTRIES` | `insecure-registries` of the agent's dockerd; comma-separated `host[:port]` or CIDR |
| `TINFIRM_SANDBOX_DOCKER_STORAGE_DRIVER` | `storage-driver` of the agent's dockerd (default: dockerd chooses) |
| `TINFIRM_SANDBOX_DOCKER_DISK` | Size of the `/var/lib/docker` disk (default `40G`, sparse); `off` keeps the data in the container's filesystem |
| `TINFIRM_SANDBOX_DOCKER_START_TIMEOUT` | Seconds to wait for dockerd (default 60) |
| `TINFIRM_SANDBOX_MAVEN_MIRROR` | `~/.m2/settings.xml`, a mirror of `TINFIRM_SANDBOX_MAVEN_MIRROR_OF` (default `central`); also `MVNW_REPOURL` for the Maven wrapper |
| `TINFIRM_SANDBOX_NPM_REGISTRY` | `~/.npmrc` |
| `TINFIRM_SANDBOX_PIP_INDEX` | `~/.config/pip/pip.conf` and `~/.config/uv/uv.toml` |

Values are checked, never escaped: a URL must be `http(s)://` and must not contain a user
name, password, quotes, spaces or markup characters. The files in the home carry a marker
line; a file the agent wrote itself stays as it is (with a warning in the run's log), and a
marked file whose variable is gone is removed. The Docker settings apply only with
`--sandbox-docker`. Gradle gets no mirror setting yet.

`run` refuses a `--sandbox-env` whose key the host sets itself (`TINFIRM_*` other than
`TINFIRM_SANDBOX_*`, `GIT_CONFIG*`, `HOME`, `PATH`, the credential, model and effort variables),
whose key looks like a credential (`TOKEN`, `SECRET`, `PASSWORD`, `KEY`, `AUTH`, …), whose
value has a control character or a URL with a user name or password, or that names a key
twice. The error never repeats the value.

**Install with Kata.** The Ansible role in [`deploy/ansible`](../deploy/ansible/README.md)
installs Docker Engine, the Kata static release with Cloud Hypervisor, the `kata` runtime, the
runner host as a systemd service with `--sandbox-runtime kata --sandbox-docker` and the
mirror settings, and builds the images. By hand, on a machine with `/dev/kvm`:

1. Install Docker Engine 23 or newer, and the Kata Containers static release
   (`kata-go-static-<version>-<arch>.tar.zst`) unpacked to `/` (it lives in `/opt/kata`).
2. Copy `/opt/kata/share/defaults/kata-containers/configuration-clh.toml` (Cloud Hypervisor)
   to `/etc/kata-containers/configuration.toml`, load `vhost`, `vhost_net` and `vhost_vsock`,
   and check with `/opt/kata/bin/kata-runtime check`.
3. Register the runtime in `/etc/docker/daemon.json` and restart Docker:
   ```json
   {
     "runtimes": {
       "kata": {
         "runtimeType": "/opt/kata/bin/containerd-shim-kata-v2",
         "options": { "ConfigPath": "/etc/kata-containers/configuration.toml" }
       }
     }
   }
   ```
4. `docker run --rm --runtime kata alpine uname -r` prints a kernel other than this
   machine's, and `doctor` lists `kata` under "docker runtimes".
5. Start `run --sandbox-runtime kata --sandbox-docker --sandbox-env …`. Its log line
   `runner host starting` shows `sandbox_runtime`, `sandbox_family` (`kata`), `sandbox_docker`
   and the names of the extra variables; the next line, the level of isolation, is `container`
   ([Isolation level](#isolation-level)).

## Isolation level

A host reports its level of isolation: the strongest wall between an agent's code and the
credentials ([ADR-0113](../docs/adr/0113-levels-of-isolation-and-placement.md)). With protocol 2 it
sends it as the one value of `hello.isolationTiers`, with `hello.hardened`, whether the machine that
runs its VMs is hardened beyond the jail (`yes`, `no`, or `none` when the host runs no machine of its
own for its VMs; left out for `no`), and `hello.isolationStated: true` when the level rests on your
statement.

| Level | The wall | Where the credentials are | What an escape from a sandbox reaches |
|---|---|---|---|
| `machine` | Another machine | On another machine than the VM | The machine that runs the VM, and the other runs there; with a provider's VM per run, the provider's machine, with nothing of Tinfirm's |
| `vm-jail` | A jail around each VM | On the same machine, out of a VMM's user's reach | Through the VMM, one jailed user that reads no credential; through a flaw of the kernel under the VMs, the machine with every credential |
| `container` | The container and the kernel | On the same machine, behind the kernel alone | This machine, which holds the host's token and every run's git token, and the other runs on it |

`gvisor` is a `container` host whose runtime is `runsc…`; the server places it as `container`.

The host derives its level from what it can see, and only takes your word where it cannot:

| This host | `isolationTiers` | `hardened` |
|---|---|---|
| Docker with runc, or with a `kata…` `--sandbox-runtime` | `container` | left out (`no`) |
| Docker with a `runsc…` `--sandbox-runtime` | `gvisor` | left out (`no`) |
| `--executor vm --vm-driver digitalocean` | `machine` | `none` |
| `--executor vm --vm-driver node`, every node over its local socket ([Sandbox nodes](#sandbox-nodes)) | `vm-jail` | `yes` when every node that offers `--vm-template` reports it, else left out (`no`) |
| `--executor vm --vm-driver node`, every node over TLS at an address that is not this host's own | `machine` | `yes` when every node that offers `--vm-template` reports it, else left out (`no`) |
| `--executor vm --vm-driver pod`, whose configuration states that the workers for runs hold nothing else | `machine`, with `"isolationStated": true` | `no` |
| `--executor vm` with a driver of your own and `--isolation-level` | What `--isolation-level` states, with `"isolationStated": true` | What `--isolation-hardened` states |
| `--executor vm` with a driver of your own and no `--isolation-level` | `container` | left out (`no`) |
| Protocol 1 | `container` | not sent |

- **Kata Containers.** A Kata host reports `container`: its sandboxes are VMs, but the VMM runs as
  root on the machine that holds the credentials, so an escape through it reaches them. It still
  runs every sandbox as a Kata VM through Docker, and still takes `--sandbox-docker`
  ([Kata Containers and Docker in the sandbox](#kata-containers-and-docker-in-the-sandbox)); only
  what it reports is `container`.
- **A driver of your own.** The host cannot see where your driver runs the VMs. State the level
  with `--isolation-level machine` (the VMs run on other machines than this host, which hold no
  credential) or `vm-jail` (each VM's VMM runs in a jail of its own on this machine), and
  `--isolation-hardened yes|no|none`. Without them the host reports `container`, and says so at
  its start and in `doctor`, with how to state the level: it is a VM host shown as a container
  host until you state its level.
- **A statement never overrides what the host can see:** `run` refuses `--isolation-level` with
  the Docker executor, with `--vm-driver digitalocean` and with `--vm-driver node`.
- **The pod per run** ([ADR-0115](../docs/adr/0115-sandbox-vms-in-a-kubernetes-cluster.md),
  decision 2) keeps every run's pod off the worker of this host's pod, but cannot see what else
  the workers for runs hold. Its configuration states it (`isolationStatement`, the Helm chart's
  `runner.vm.cluster.isolationStatement`, word for word "the workers for runs hold nothing
  else"); without that statement `run` does not start, and `--isolation-level` is refused, since
  the statement has its one place there. A worker whose pods use user namespaces cannot have the
  first line of hardening, so such a host reports `hardened: no`.
- **The node driver's hardening** is what the nodes report
  ([ADR-0113](../docs/adr/0113-levels-of-isolation-and-placement.md), decision 5): each node
  agent's helper checks every line of a hardened machine, and its `verify` carries the mark. The
  driver's `verify` says `yes` when every node that offers `--vm-template` says `yes`; `no` when one
  of them says `no`, answers without the mark (a node agent older than this host, which the
  driver's log names), or says `yes` without the lines it checked or beside a line that does not
  hold; and nothing ("not known") when none says `no` but one does not answer. This
  host takes the value of the `verify` at its start, where "not known" is `no`: it vouches for no
  node it did not hear from. Later, "not known" keeps the last value. Its log and `doctor` name each
  line that does not hold (`node node-1: io-uring: kernel.io_uring_disabled is 0, not 2`). A lower
  mark stops nothing: the host still starts and its nodes still take VMs. While it runs, the host
  asks the driver's `verify` every 5 minutes, and at each of its other health checks (a failed
  create, a run that comes while the driver can make no VM, and the recheck then), never two at
  once; the scheduled `verify` also counts for whether the driver can make a VM, as any other.
  Each hello reports the value the host knows when it sends it. A change reaches the server by a
  new hello, since the hello is the one frame that carries the mark: the host closes its
  connection (1000) and connects again at once. A loss (`yes` to `no`) is logged as a warning
  that names the lines and is sent at once; a gain (`no` to `yes`) once it held for two checks
  in a row, so that a node whose mark flaps does not move the server's placement with it. "Not
  known" changes nothing, there is at most one such reconnect per 5 minutes, and none while the
  connection is down: the next hello carries the value. Runs go on through the reconnect as
  through any other: the server keeps their leases, the new hello lists them, and their events
  follow on the new connection. Shown with one hardened node and a server (2026-11-22; the
  outputs are not in this repository): with `kernel.io_uring_disabled` set back to 0 by hand while
  the host ran, it logged that the machines that run its VMs are no longer hardened and that it
  reconnects, connected again at once, and the server's stored `hardened` went from `YES` to `NO`,
  the level staying `MACHINE`; a run right after succeeded and pushed. With the setting restored, a
  new hello brought `YES` again. A driver of your own is not asked: `--isolation-hardened` states
  it.
- **Where it shows.** `run` logs the level at its start, with what an escape from a sandbox reaches
  on such a host. `doctor` says the same for the settings in its environment
  (`TINFIRM_RUNNER_EXECUTOR`, `TINFIRM_RUNNER_VM_DRIVER`, `TINFIRM_RUNNER_ISOLATION_*`; it does not
  see `run`'s flags). The server stores the level and shows it on the host's page as reported,
  places runs by it, and checks neither value
  ([ADR-0113](../docs/adr/0113-levels-of-isolation-and-placement.md), decision 4).
- **Hosts and server take this release together.** `microvm` is no value any more: a server of
  this release refuses a hello that carries it, and a server of an earlier release refuses a host
  of this release that reports `machine` or `vm-jail`. A host that reports `container` or
  `gvisor` works with either; a host with a VM per run, and a Kata host of an earlier release
  (which sent `microvm`), is upgraded with the server
  ([ADR-0113](../docs/adr/0113-levels-of-isolation-and-placement.md), decision 2, which amends
  [ADR-0109](../docs/adr/0109-runner-version-is-the-release-version.md) for this one change).

| Option | Environment | Default | Meaning |
|---|---|---|---|
| `--isolation-level machine\|vm-jail` | `TINFIRM_RUNNER_ISOLATION_LEVEL` | | The level this host reports with a VM driver of your own; refused where the host derives its own |
| `--isolation-hardened yes\|no\|none` | `TINFIRM_RUNNER_ISOLATION_HARDENED` | `no` | Whether the machines that run those VMs are hardened; only with `--isolation-level` |

## Sandbox bridge

With `run --sandbox-bridge` (`TINFIRM_RUNNER_SANDBOX_BRIDGE`), the agent's adapter runs over the
sandbox bridge ([ADR-0095](../docs/adr/0095-sandbox-bridge.md)) instead of Docker attach. It is opt-in
for now, and in every edition ([ADR-0114](../docs/adr/0114-the-secure-sandbox-is-in-the-core.md)):
the host lists `sandbox.bridge` in its `hello`, which needs protocol 2. A server from before
ADR-0114 whose licence does not hold elastic runners refuses it with `EDITION_REQUIRED`. Its code is
`crates/tinfirm-bridge`, the host's modules `bridge.rs`, `sandbox_files.rs` and `takeback.rs`, and
the sandbox's [front](#the-reader-and-the-front) (`front/`), a confined process per sandbox that
holds its bridge.

- **How it runs.** The container runs this host's binary, mounted read-only at
  `/opt/tinfirm/tinfirm-runner-host`, as `bridge`; the image's entrypoint still runs first. Each
  sandbox has a bridge listener and a TLS identity of its own, which `run` makes for it and only the
  sandbox's [front](#the-reader-and-the-front) holds: the listener is on the git proxy's address (the
  sandbox network's gateway), on a port the system picks or one of `--sandbox-bridge-ports FROM-TO`
  (`TINFIRM_RUNNER_SANDBOX_BRIDGE_PORTS`, both included; a run that finds no free port in the range
  fails). The bridge connects there over TLS 1.3, pinned to its sandbox's own certificate, which no
  other sandbox's bridge pins. It presents the run's one-time ticket, which the front hands to `run`
  and `run` checks, and starts the image's command when the host sends `start`.
  - The front takes a connection only from the container's own address on the sandbox network,
    which `run` reads once the container has started (the local test executor's: loopback); any
    other is closed before its TLS handshake. Where every sandbox comes from one address (Docker
    Desktop, `TINFIRM_RUNNER_SANDBOX_NETWORK=host`, a listener address set with
    `TINFIRM_RUNNER_GIT_PROXY_BIND`), it takes any address, and `run` decides by the run's ticket
    alone; each front still holds only its own sandbox's key.
- **The run's credentials are not in the container's settings.** `docker inspect` shows only:
  - the bridge's own settings (`TINFIRM_BRIDGE_ADDR`, `_RUN`, the one-time `_TICKET`, and `_PIN`,
    the pin of its sandbox's own certificate);
  - the entrypoint's (`TINFIRM_SANDBOX_*`);
  - the proxy variables of the agent's own dockerd.

  Every other variable of the run, the credential among them, reaches the adapter in `start`.
- **The sandbox reaches its proxies only through the bridge.** This host binds no listener for a
  sandbox's git proxy and platform tools, model proxy or egress proxy; the bridge listener is the
  only port of this host's that is meant for a sandbox. Before the agent starts, the bridge listens on the sandbox's own
  127.0.0.1, at port 18761 (the git proxy and the platform tools), 18762 (the model proxy) and 18763
  (the egress proxy), and carries every connection there over the bridge (a tunnel) to that service
  in the sandbox's front, never to an address. So `origin`, the MCP endpoint, the model's base URL
  and the proxy variables name the sandbox's loopback. The front reads each request, and `run`
  checks it again with the rules it always applied and adds the credentials. A program of the image
  that holds one of the three ports fails the run at its start, with an error that names the
  service and the port. `TINFIRM_RUNNER_GIT_PROXY_BIND` and `_HOST` set where the bridge listeners
  go.
  - Each connection has a window of 128 KiB per direction, so one that stalls holds back no other;
    at most 128 are open per run.
  - A container of the agent's own Docker reaches the tunnels only on Docker's `host` network,
    because they listen on the sandbox's loopback.
- **No mounts: the files travel over the bridge.** The container gets none of this host's
  directories (only a Codex ChatGPT login stays mounted at `~/.codex`, because its token is refreshed
  in place).
  - Before the agent starts, the bridge receives the workspace (the host's clones), the session home
    without its caches (`~/.cache`, `~/.npm`, `~/.m2/repository`, `~/.gradle/caches` and the like:
    the mirrors serve them), the skills, the attachments and an empty `/output`.
  - The platform tools read the agent's files through the bridge.
  - After the agent, the host runs its git steps in the sandbox: the WIP commit, then a bundle of
    `HEAD`. A [reader](#the-reader-and-the-front) fetches the bundle into the host's own clone, which the agent
    never reached, with every object checked, and the host pushes from there with the usual checks.
    The host never runs git in a `.git` that the agent wrote. A bundle has 8 GiB at most. Every host-side git command runs with
    `core.packedGitWindowSize` set to 1 TiB, so git maps a whole pack at once: the fetch from a
    bundle of git 2.43 to 2.48 closes a file descriptor twice, and with git's default window of
    1 GiB the fetch of a bundle over 1 GiB could fail with `packfile … cannot be mapped: Bad file
    descriptor`.
  - The session home and `/output` come back, unpacked by a [reader](#the-reader-and-the-front) as directories
    and regular files only (links and devices are left out), at most 2 GiB and 4 GiB; then the
    sandbox goes.
  - The images need `/attachments` and `/output` writable by the agent: the images of this version
    have them, and `run` rebuilds older ones ([Rebuilds after an upgrade](#rebuilds-after-an-upgrade)).
- **A lost connection resumes without losing output.** The adapter's output, the tunnels'
  connections and the files are kept until the host acknowledges them. When the bridge has had no connection for
  25 s, it stops the agent (SIGINT, then SIGTERM, then SIGKILL), and keeps the sandbox and its files
  for 15 minutes more, for this host after a restart (below). The host ends the run after 30 s
  without the bridge; a bridge that it no longer knows is refused, and ends at once.
- **What the host holds for a sandbox is bounded.** Each side of the link (the bridge, and the
  sandbox's front) acknowledges only what it has handed on to its reader, and reads at most 8 MiB
  ahead of it; the adapter's output waits for the run's loop in a queue of 256 chunks. A run whose
  host falls behind, or a sandbox that floods, is held back through the bridge's credit and then
  TCP: the adapter's writes block in the sandbox, nothing is dropped, and nothing piles up in the
  host. A front has at most 8 connections in their handshake or waiting for `run`'s decision at
  once; a bridge that opens more has the others dropped without an answer, and tries again later.
- **What it costs.** The bridge through a front, against the bridge crate's host side in one
  process (release build, loopback, one machine): a handshake takes about 1.9 ms (1 ms in one
  process), a command's round trip about 1.85 ms (1.5 to 1.7 ms), and 1 GiB goes in at about
  400 MiB/s (440 to 465), comes out at about 375 MiB/s (445) and goes through a tunnel at about
  450 MiB/s (530). A front starts, with its bridge's listener, in about 3.3 ms, once per sandbox.
- **A bridge that does not connect** within 180 s, or a container that ends before its bridge does,
  fails the run with `INFRA`. The container's last log lines (the entrypoint's) are in the run's log.
- **Requirements.**
  - The binary must be a static Linux executable for the sandboxes' architecture, as the release
    binaries for Linux are; `run` refuses any other.
  - `TINFIRM_RUNNER_BRIDGE_BINARY` names another binary, for example on macOS with Docker Desktop:
    the Linux release binary of the same version, in a directory that Docker Desktop shares.
  - A host firewall between the sandbox network and this host must admit TCP to the bridge ports:
    a port per sandbox, from `--sandbox-bridge-ports`, or without it from the system's range for
    local ports (`net.ipv4.ip_local_port_range`). It admits no port of a proxy: the host binds
    none. The first start after an upgrade from a version before the fronts also opens the shared
    port that `runs/bridge.json` keeps, only while that version's runs are taken back (below). With
    a [VM per run](#a-vm-per-run) the control host dials each VM and listens on nothing for it.
- **A restart of this host loses no work** ([ADR-0101](../docs/adr/0101-closing-the-elastic-runners-gaps.md),
  threat model R36). From the start of a run's sandbox until the run's report is made, this host
  keeps a record of it in its data
  directory (`runs/<run id>.json`, 0600): the assignment, the sandbox, the bridge's session key, the
  repositories as cloned and the agent's session, and the sandbox's bridge (`bridge`: its TLS
  identity, and on Docker its port and its peer's address; a VM's dial target is the record's
  sandbox). A `run` that starts after a crash, a `kill -9` or an upgrade finds the records and
  takes each run back before it connects: the run is in its `hello`, so Tinfirm keeps it; a new
  front starts for each sandbox with the same identity, listening on the same port (a VM's dials
  it), and the sandbox's bridge comes back to it with the session's key. A record without `bridge`
  (one of the version before the fronts) is taken back through one shared listener in `run`,
  whose identity and port `runs/bridge.json` keeps; that is the only use of the shared listener and
  of that file. The agent that the earlier process started stops, its work comes back and is pushed, its
  session is saved, and the run ends failed with `INFRA`, saying what was saved; the next message
  goes on from there. A sandbox that does not come back within 2 minutes is removed, and its work
  is lost. Shown on a control host with a VM per run (2026-11-22; the outputs are not in this
  repository): `run` killed with `kill -9` 13 seconds into a run was started again by the service
  manager about 5 seconds later; the new process logged "taking back a run of the process before
  this one, through a new front", deleted the VM, and logged "run taken back and finished", and the
  server failed the run as infrastructure with "The runner host restarted during the run and took
  the run back: the agent was stopped, its work so far was pushed, its session was kept, so the next
  message goes on from it." A known limit: the record is written once the run's sandbox is ready
  (about 7 seconds after the run turned `RUNNING` on those machines), so a process that ends without
  a stop (a kill, a panic, a power loss) before that leaves none, its VM is deleted as a leftover,
  and the server marks the run `LOST` (`HOST_RESTARTED`). Since the agent of such a run never got a
  prompt, the server starts the turn again by itself, at most three times in a row
  ([ADR-0073](../docs/adr/0073-automatic-retries.md), decision 9); it does so only when nothing of the
  run past its preparation reached it, so a run that has no record for another reason (a sandbox
  without the bridge, a record that could not be written) and whose agent had started stays lost.
  On real machines on 2026-11-23, at `620b3f9`: a host killed before its run's record existed lost
  the run, and the server started the turn again after 1 minute, then after 2 and after 4, and
  after the third lost start left the turn to the person; a host killed after its agent had
  started took the run back and the server started nothing by itself.
  - The turn does not go on in place: the agent is stopped. Runs without the bridge, and a Docker
    host's runs when the machine itself restarts (Docker stops the containers), are lost.
  - **A restart in the run's own end.** Once the agent's turn has ended, the work comes back from
    the sandbox and the record says so before the sandbox stops, with what is known then: whether
    the agent's output leaked a credential value, and whether Tinfirm cancelled the run. The record
    then keeps the agent's outcome (its failure, the summary, the `finish` result, the usage, the
    session; every text redacted), and each file of `/output` as it is saved. A `run` that starts
    after a crash in that end finishes the run from its own disk: it saves the files of `/output`
    not saved yet, pushes after Tinfirm's answer (a branch already at the run's head counts as
    pushed), stores the session bundle, and reports the outcome the run had. After a leak nothing
    leaves the host, no new git token is asked for, and the run fails with `CREDENTIAL_LEAK`; a
    cancelled run ends cancelled. When the host cannot write its record's mark, the record goes
    before the sandbox stops, and a restart then loses the run instead of pushing it. An abort
    (Tinfirm gave the run to another execution) is looked at between the steps of the end: no
    further step begins and no report is made, while the step under way finishes. A
    crash before the outcome is in the record ends the run failed with `INFRA`, saying that the
    agent's turn had ended and what was saved. Such a record is of version 2: an earlier version
    of the host removes it, so the run is lost on a downgrade in that window. Before 2026-11-23
    the record went when the sandbox stopped, and a crash in the run's end lost the run with its
    work unpushed on the host.
  - **The report until it is delivered.** When the run's report (`run.finished`) is made, the host
    keeps it (`runs/<run id>.<epoch>.finished.json`, 0600: the frame as sent, no credential) in the
    record's place, and removes it once it was written on a connection that then stayed up for
    `TINFIRM_RUNNER_FINISHED_RETENTION` (3 minutes), or, on an orderly stop (SIGTERM, a drain),
    once it was written at all; Tinfirm assigning the run again removes it too. A `run` that
    starts after a crash, or after a stop while it was cut off from Tinfirm, finds the reports it
    never wrote or wrote shortly before and sends them again; Tinfirm ignores a report of a run it
    ended already. A record of a newer execution of the same run is taken back, and the older
    report goes unsent. The protocol has no acknowledgement of the report, so one that Tinfirm
    drops after it was written is still lost.
  - Of one `/output` file whose upload was under way at the crash, the next start's upload is
    refused as a duplicate name: the file is stored once and the run's report does not list it.
  - A record or report that the host cannot remove (a read-only or failing disk) stays: the next
    start finishes the run again and reports it a second time, which Tinfirm ignores. A panic in a
    run's task leaves its record and the credentials in it on disk until the host restarts; the
    next start finishes the run from the record.
  - On real machines, on 2026-11-23 at `620b3f9` (the outputs are not in this repository): a host
    killed during the push took the run back, pushed, and reported that the run succeeded, with
    the work on the branch once; a report that could not be delivered was sent once after the
    host was back; an orderly restart took nothing back and left no report; a cancelled run
    whose host was killed ended cancelled. Not shown there: the leak (the test's stand-in agent
    holds no credential to leak), and a kill between the end's other steps, which follow each
    other within about a second: the tests reach those, by stopping the host's process at each.
  - The records hold the run's credentials (the repositories' tokens, a model access key) and the
    bridge's private key until the run's report is made, as the config directory holds the host token.
    A record's git tokens are those the run holds now: when Tinfirm revokes a repository's token
    ([Revoked git tokens](#revoked-git-tokens-protocol-v2)), the host writes the record again,
    whole and 0600, without the token and with the repository in its `revoked` list (the
    server's reason and message). A host that takes the run back then neither uses that token
    nor pushes the repository.
  - **Redaction after a restart.** A token that the record no longer holds (a revoked one, a
    GitHub App's token that a renewal replaced) must still be redacted and found by the scans
    once the host restarts. So the record also keeps, in `redaction`, salted hashes of every
    token the run has held, live ones included: one path for all of them, written whenever a
    token enters the run. For each form in which the redactor matches a token (the raw value,
    its URL encoding, its base64 at each of the three byte alignments in both alphabets: 5 to
    8 forms of a GitHub token), it keeps the form's length, the top 12 bits of a rolling hash
    of the form (a polynomial hash modulo 2^64 whose base the record's salt gives) and
    SHA-256 over the record's salt (32 random bytes, one per record) and the form. A run taken
    back slides a window of each length over what it scans, and hashes a window with the salt
    only where the 12 bits match. Its events, the host's own log lines of the run, the scan of
    the outgoing commits before the push (`CREDENTIAL_LEAK`), the session bundle's scan and
    the `/output` scan find those tokens as they find the live ones. A token's `Basic` header
    holds its base64, which these forms match, so the headers are not kept. A record without
    `redaction` (an earlier version's) is read as before, and the previous version reads one
    with it, ignoring the field.
  - **A record whose hashes cannot be used** fails closed. Its hashes are read as untrusted
    and bounded before any work: a salt of 32 bytes in hex, at most 16,384 forms, each of 8 to
    49,152 bytes (the percent-encoding of the longest git token the host takes, 16,384 bytes,
    the longest the server keeps; an assignment with a longer one is refused and a renewal
    with one is ignored), a 12-bit tag and a SHA-256 in
    lowercase hex. A record that has `redaction` and breaks any of these pushes nothing of the
    run, stores no session bundle and saves no file of `/output`: the run ends failed with
    `INFRA`, "its record of the run is damaged", and its work stays on the host as a failed
    run's does (`keep_failed_workspaces`).
  - **What a scan costs.** The run's events and the host's log lines are redacted with every
    run's hashes, since they are small. The bulk scans of a run (its outgoing commits, its
    session bundle, its `/output`) use every credential by value and that run's own hashes
    alone, so ten runs taken back do not make each scan ten times as long. With ten runs'
    hashes loaded, a scan of 200 MiB took 17.6 to 18.3 s with every run's hashes and 1.75 to
    1.82 s with one run's (release build, 4 CPUs). What bounds a scan when an agent makes its
    commits large on purpose is the size of what comes back from the sandbox: at most 8 GiB of
    bundle per repository (the sandbox bridge's `BUNDLE_LIMITS`) and 20 repositories; at about
    110 MiB/s by hashes and 160 MiB/s by value, 8 GiB takes about 2 minutes to scan. The
    session bundle is 64 MiB unpacked at most, and an `/output` file 25 MiB by default.
  - **What the hashes give away.** Someone who reads the record learns, of a token that is no
    longer in it, its length and at most 12 bits per stored form from the rolling hashes: at
    most 60 to 96 bits for 5 to 8 forms, if all of them could be used together, which no method
    known to us does for forms in different encodings. The salted SHA-256 lets them test one
    guess at a time, for this record only. A GitHub token has 30 random characters (about 178
    bits; its last 6 are a checksum), so at least about 82 bits stay unknown even in that worst
    case, beyond any search; a token with less entropy (GitLab's `glpat-` tokens have 20
    characters, about 119 bits) keeps less in that worst case. The live tokens are in the
    record in plain text, as before. The cost: a scan of 200 MiB of pack-like bytes for ten
    tokens took 1.1 to 1.2 s by value and 1.6 to 1.8 s by their hashes alone (release build,
    4 CPUs), so a run taken back scans its commits about 2.5 times as long as before.
  - A host that took a run back uses none of the run's git tokens and pushes nothing before this
    process has been welcomed by the server. A server that lists `run.git.revoke` in
    `welcome.features` sends `welcome.done` last, after every revocation (and cancel) it keeps for
    the runs the `hello` listed, which the host has applied by then; the run waits for it, so a
    revocation that Tinfirm sent while the host was away comes first. A server that lists no such
    feature (one from before ADR-0116) keeps no revocation, and the run goes on as soon as it is
    welcomed. There is no time limit: without a connection, or without `welcome.done`, the run
    waits as long as it takes. Then it renews every token of the run that expires
    ([Git tokens that expire](#git-tokens-that-expire-protocol-v2)), an expired one included,
    before the push.
  - While the run waits, its agent is stopped and its sandbox is gone: the work, the session
    home and `/output` are on this host, and the record stays, marked `collected`, with how each
    repository came back. The mark is written before the sandbox is stopped; a host that cannot
    write it keeps the sandbox until the run ends, says so in the run's log, and a restart
    meanwhile takes the work from the sandbox again. The run's wall-clock limit no longer matters then, since nothing of the
    run executes. The host logs one warning when the wait begins ("a run taken back waits for the
    server's answer to the hello before it pushes"), the run's events say it once, and `doctor`
    counts such runs under "runs to take back". A cancel from the server is applied after the wait,
    and still pushes; an abort (a stale epoch) ends the wait and pushes and reports nothing. When
    the host stops (SIGTERM) during the wait, the run neither pushes nor reports, and the next
    `run` takes it back from the record and its own disk, without a sandbox; so does a `run` after
    a second crash. The record keeps a token's latest value and its expiry until the run ends.
  - `run` must start with the same data directory and, for Docker, the same sandbox network, within
    the 15 minutes; `run --sandbox-bridge` must stay on.
- **A front that ends during a run** (a crash, or a flaw that ended it) is replaced once: a new front
  takes the session back with the run's key, on the same port (a VM's dials it again), the agent
  is stopped, its work comes back and is saved, and the run fails with `INFRA`, saying what was
  saved and what was not. When the new front ends too, the run fails and says that its work is
  lost. The other runs and the host are not touched. Shown on a control host with a VM per run
  (2026-11-22; the outputs are not in this repository): a VM's front, killed during the run, was
  replaced in under a second, and the run ended with "a new front took the sandbox back, the agent
  was stopped, and its work so far was saved." During that run the front showed `NoNewPrivs 1`
  and `Seccomp 2` with 13 filters.

<a id="the-reader"></a>

### The reader and the front

`run` holds every run's git token, its own token to the server and the one-run model tokens. With
the bridge, what a hostile sandbox sends is parsed outside it, in processes that hold no credential
and confine themselves (path C of
[ADR-0112](../docs/adr/0112-no-credential-on-a-sandbox-machine.md)), so that a flaw in that parsing
needs a second flaw to reach a credential. Both are this binary, as hidden subcommands that `run`
starts and nobody runs by hand:

- the **reader** (`tinfirm-runner-host reader`; `reader.rs`, `confine.rs`), one process per
  archive or git bundle that comes back from a sandbox;
- the **front** (`tinfirm-runner-host front`; `front/`, `confine.rs`), one process per sandbox,
  which holds the sandbox's bridge and parses everything the sandbox sends while it runs: the
  bridge's TLS, frames and tunnels, and the HTTP of its proxies and platform tools.

The reader:

- **What it reads.** The session home and `/output` coming back, each repository's bundle (with
  the fetch into the host's clone, `fsck` and the checkout of the new `HEAD`), a session bundle
  being restored (with the bridge), and the one file the platform tools read from a sandbox. One
  process per archive or bundle, never shared between two runs.
- **What it is given.** In its argument, only that reading's directories: the incoming session home,
  the run's `/output`, or one bundle's scratch directory and that run's clone. The sandbox's bytes
  on its stdin, as they come over the bridge. An empty environment but `PATH`, no descriptor but
  stdin, stdout and stderr, and no token.
- **What it cannot do** (Linux 5.19 or later, with Landlock enabled), applied by itself before it
  reads a byte:
  - Landlock: it reads and writes only its directories (it executes nothing there), reads and
    executes the system's directories for git, and nothing else: not this host's config or data
    directory, its token, the take-back records, another run's directories, or `/proc` (so not
    `/proc/<pid>/mem` of `run`). Where the kernel has them, TCP (Linux 6.7) and signals and
    abstract sockets outside the reader (Linux 6.12) are refused too;
  - a seccomp filter: no socket of any kind, no `ptrace` or `process_vm_*`, no namespaces, no
    mounts, no `io_uring`, no `bpf`, no new session or process group, and it cannot make itself
    dumpable again. It also keeps the reader's signals inside its process group on every kernel,
    before Linux 6.12 too: a signal by pid only to its own group (0 or the group's negative id) or
    itself, or the signal 0 (a check that a process exists) to any; no `pidfd_open` or
    `pidfd_send_signal`, no `F_SETOWN` or `FIOSETOWN` (a `SIGIO` sent for it); and no
    `prlimit64`, `sched_set*`, `setpriority` or `ioprio_set` on another process (Landlock's scope
    does not cover a limit, and a CPU limit would end `run`). seccomp compares a pid with
    constants only, so a git in the reader cannot signal its own children by their pids either:
    its cleanup after an error is left to `run`'s kill of the group;
  - `no_new_privs`, and not dumpable; `run` itself is not dumpable either, so no process of its
    user reads its memory;
  - it runs in a process group of its own, which `run` kills when the reading is over or its time
    is up, with everything the reader started. An unpacking may write no file larger than its
    limit and use at most 1 GiB of memory.
- **What `run` takes back from it.** One line of JSON of a fixed form, at most 1 MiB (and, for the
  platform tools' file, that file's bytes). `run` treats the answer as hostile too: every field is
  bounded and checked, and once the reader is gone, `run` walks what it wrote, without following
  anything: only directories and regular files with one link each, within the limits, and the counts
  the reader claimed. A link, a hard link, a device or a file over its limit fails the reading and
  empties the directory. For a bundle, the reader must answer the `HEAD` the sandbox named; `run`
  then puts back the clone's `.git` configuration and reads `HEAD` again.
- **git after the import.** The clone then holds objects a sandbox made, so every git command `run`
  runs on it afterwards (the ahead count, `HEAD`, the credential scan's listings) runs in a reader
  too, read only. A host that takes a run back after a restart reads the run's clone that way from
  the start: the process before it may have made the import, also when the run's record does not
  say so yet. By default the push alone runs in `run`'s process tree, because it needs the run's
  git token. It reads objects that `fsck` checked, from a clone whose configuration `run` restored,
  to the run's branch alone. A flaw in git's reading of such objects in the push would still reach
  that run's token and the files of `run`'s user: this is what is left of path C for the pack. With
  `run --push-client host` a reader writes the pack too, and the token stays in `run`, which sends
  the pack unread ([The push](#the-push)); that client has been checked against a git server run for
  the test and, for a new branch, GitHub alone.

The front:

- **What it does.** `run` starts one with each sandbox (a VM's with its create, a ready VM's too),
  and it ends with the sandbox. It does the bridge's TLS and reads each connection's `hello`, which
  it hands to `run`; `run` decides with the ticket or the session key it keeps, and the front then
  holds the link, the adapter's stdio, the files going in and out and the tunnels. The tunnels end
  in its own services: the HTTP of the [git proxy](#git-in-the-sandbox) and the
  [platform tools](#platform-tools), of the [model proxy](#model-proxy) and of the
  [egress proxy](#egress-proxy). For each request it hands `run` a typed head within fixed bounds
  and the body on a socket of its own; `run` checks it again with the rules it always applied, adds
  the credential and makes the request. It makes no connection: `run` makes each and passes it the
  socket.
- **What it holds.** Only what belongs to its sandbox, which the sandbox holds anyway or which is
  useless anywhere else: the sandbox's TLS key, which no other sandbox's bridge pins; the bridge's
  ticket and session key (`run` decides each `hello` with its own copy, and no other front has this
  run's entry); the `start` environment, which is what the agent gets anyway (a dummy key behind a
  model gateway; a provider key or a login only where the sandbox holds it by design); the `exec`
  environment (`GIT_*` settings only); the route secrets of the git proxy, the platform tools and
  the model proxy, and the egress proxy's credential; and the archives that go into the sandbox
  (the host's clones, whose `.git/config` holds the route secret and no token, the session home,
  the skills and the attachments). It holds no git token, no model key, no host token, no other
  sandbox's identity, ticket or session key, and not the shared listener's key.
- **What it cannot do** (Linux 5.19 or later, with Landlock enabled), applied by itself before it
  builds its runtime: limits of 512 MiB of data, 1024 descriptors and no core file; not dumpable;
  Landlock with no directory at all (only `/dev/null` and `/dev/urandom`), and where the kernel has
  them no TCP `bind` or `connect` and no signal or abstract socket outside its domain; and the
  reader's seccomp filter, with on top of it no `execve`, no new process (threads only), no
  `connect`, `bind` or `listen`, and no signal outside its own process group, the signal 0
  included. A pair of Unix sockets stays allowed, which reaches no other process. It runs as
  `run`'s user, in a process group of its own, with an empty environment and no descriptor but its
  control channel on stdin, `/dev/null` on stdout and a pipe to `run` for its stderr.
- **How this is tested** (ADR-0112 decision 8, path C: `tests/path_c.rs`). Two runs over the bridge
  on one host use the git, model and egress proxies, with canaries for the host's token, each run's
  git token and the gateway's key. A probe that `run`'s own code starts as a sandbox's front, given
  copies of what `run` gave run 2's front, can read no file of the host's and nothing of `run`'s,
  run 1's front's or the sandboxes' processes under `/proc`, write nothing, signal, limit or open a
  pidfd of none of them, make no socket, and connect or bind nowhere (run 1's bridge and tunnels,
  any port of the test, an abstract socket): Landlock refuses the paths, the seccomp filter the
  rest. A tap on what `run` writes to its fronts (the frames, and the sockets `run` fills) finds no
  form of a canary over both runs, and does find one that the test makes `run` send. Stand-in
  agents, a fake server and fake upstreams on one machine, not real machines; `ptrace` and
  `process_vm_readv` are not tried themselves (no call without `unsafe` code), `/proc/<pid>/mem` is.

Both:

- **What they do not protect against.** A flaw of the kernel (Landlock, seccomp) from inside a
  reader or a front; and what `run` still parses itself: the agent's ACP messages (lines of up to
  8 MiB), the JSON of the platform tools' calls (1 MiB at most), the fronts' messages (closed,
  bounded types), the readers' answers and the walk of what they wrote, stderr and log text, the
  answers of the code host and of the model's gateway (not the sandbox's input, but a model's text
  passes through them), and on a personal Docker host a Codex login's `auth.json`, which the
  sandbox can write. Those are bounded and tested with a hostile peer; a flaw in one of them still
  reaches the host's credentials with no second flaw. By default the push, too, runs in `run`'s
  process tree with the run's token (above); with `--push-client host` it does not, and `run` parses
  the code host's answers to it instead ([The push](#the-push)).
- **Where they cannot be confined** (Linux before 5.19, Landlock not enabled at boot, macOS),
  every host that uses the bridge refuses to start: `--executor vm` and a Docker host with
  `--sandbox-bridge` alike, before any image is built. At its start `run` runs a reader's probe
  and a front's, and one refusal names each that is not confined: "this system cannot confine the
  reader of what sandboxes send back (…) nor the front process of a sandbox (…)". A front whose
  probe got through anything it must be refused (a host file, a socket, another process, a
  program), or that could not signal its own process group or run its runtime, counts as not
  confined too. `--allow-unconfined-reader` (`TINFIRM_RUNNER_ALLOW_UNCONFINED_READER`), one flag
  for both, starts it anyway, with a warning that names what it gives up:
  - of the reader: it still runs as a process of its own without a token, but a flaw in its
    reading of a sandbox's archive or git bundle reaches every file of the host's user (the host's
    token and stored credentials among them), and can end `run` and its runs with a signal; where
    seccomp is missing too, it reaches the network as well;
  - of the fronts: each still runs as a process of its own that holds no credential, but a flaw in
    its parsing of its sandbox's bridge (the TLS, the frames, the tunnels) or of the HTTP to the
    sandbox's proxies reaches every file of the host's user, the host's token and stored
    credentials among them; where seccomp is missing too, it also reaches the network, starts
    programs and can end `run` and its runs with a signal.

  A Docker host without the bridge uses no reader and no front, and checks neither.
- **`doctor`** runs a reader, which confines itself and tries to open the config and data
  directories, its parent's memory and a socket, and says `reader: confined: Landlock ABI 7 (files,
  TCP, signals and abstract sockets), seccomp filter, no_new_privs, not dumpable`, or why not. It
  runs a front's probe, which also tries to reach `doctor`'s process and to start a program, and
  says `front: confined: …`, or why not. Each line has the outcome `run` would give: a failure
  where `run` would refuse (`TINFIRM_RUNNER_SANDBOX_BRIDGE` or `TINFIRM_RUNNER_EXECUTOR=vm` set,
  without `TINFIRM_RUNNER_ALLOW_UNCONFINED_READER`), a warning where the flag accepts it, and a note
  where the bridge is off. `host memory` says whether `run` can make itself not dumpable, and
  `front wire` whether a front starts and answers (below).
- **The front's messages.** What `run` and a front say to each other:
  - **Its wire to `run`.** A front's stdin is one end of a pair of Unix sockets, its control
    channel; `run` holds the other. Each message is its length in 4 bytes, then JSON of one of a
    closed set of messages with no unknown field: at most 64 KiB from a front (a command's end,
    which carries its output, 192 KiB), 1 MiB from `run`.
    The front says it is ready, with its confinement, then answers `run`'s messages in order,
    and exits when `run` closes its end, so it ends with `run` even after `kill -9`.
    Descriptors go one way only: `run` sends them with `SCM_RIGHTS`, and reads its end with
    plain reads, so a descriptor that a front sends back is closed by the kernel. A front that
    sends anything else (a frame over its bound, bytes that are not one of the messages, a
    second `ready`, an answer to nothing `run` asked) is ended at once, its process group
    killed, and only its own handle says why; every other front goes on. So is a channel that
    ends inside a frame, a front that does not answer a request within 10 s, and one that does
    not take a message of `run`'s within 10 s: the channel carries small messages only, so a
    front that stops reading it is stuck or hostile. A sandbox's own bytes never go on this
    channel but each on a descriptor of its own, where a slow sandbox only slows its stream. A front that is
    not ready within 10 s, or exits, is reported with its exit status and the start of its
    stderr. A front's process group is signalled only until the front is reaped, under one lock
    with the reaping, so a group id given to another process since is never signalled; the host
    reaps every front, also after its handle is dropped.
    `doctor` also tries the wire, on a line of its own: `[ ok ] front wire: ready, a round
    trip, a descriptor passed`, or a failure with the reason when `run` would start fronts
    (`--sandbox-bridge` or `--executor vm`), since no run starts there without one.
  - **The bridge messages.** `run` passes the front the sandbox's TLS identity and the bridge's
    timing (`bridgeInit`), with the listener it bound for that sandbox, or, for a VM, with none:
    `run` then dials the VM's bridge and passes each connection (`dialed`), and the front says
    whether the session's link has a connection (`link`), so that `run` dials again only while it
    is down, at most once per retry interval, and only to the VM's target. `allowPeer` names the
    one address the front takes connections from. For each connection's `hello` the front sends
    `hello` (the run, the ticket or the session key, each at most 128 printable bytes, the
    sequence numbers and the bridge's build), at most 8 at once; `run` answers `admit` with a new
    session and its key, a resumption, a take-back after a frame number, or a refusal with its
    code, and the front says whether the link took the connection (`admitted`). Then `run` asks,
    each with its own id and a typed answer: `openTunnels` (each tunnel's address and the front's
    service it reaches), `start` with the adapter's stdin, stdout and stderr on three sockets,
    `signal`, `stop`, `stdinEnd` (`run` ended the adapter's stdin on purpose: a stdin socket that
    ends without it passes no end on), `exec` (a command and its output, at most 64 KiB each),
    `put` (a tar archive of `run`'s own on a socket pair, unpacked in the sandbox), `get` (the
    sandbox's files as a tar archive, written on a pipe whose other end is a reader's stdin, so
    `run` never holds the archive), `probe` (the network probe), `reclaim` and `close`. The front
    says when the adapter exited (`exited`) and when the session ended (`sessionEnded`). A front
    that sends a `hello` out of its bounds or a ninth at once, says `admitted` of a `hello` that
    `run` has not admitted, says `exited` or `sessionEnded` twice, or says `link` after its
    session ended, breaks the wire and is ended.
  - **The egress messages.** `run` tells the front the credential the sandbox's egress proxy must
    ask for, which the sandbox reaches through its tunnel; the front sends `connect` with a request number that only grows, a host
    name or address and a port, and `closed` once that connection ends; `run` answers with
    `connected` and the socket, or `refused` with a status, a message and whether the
    destination is off the allowlist (only then does the client get `x-tinfirm-egress:
    denied`). `run` holds a connection's place in the 256 until its `closed` comes, or the
    front ends; a connection whose client left while `run` connected is closed by the front at
    once, with its `closed`.
  - **The model messages.** `run` tells the front the run secret, the format and the base path of
    the sandbox's model proxy, which the sandbox reaches through its tunnel. For each request the front sends `request` with a request
    number that only grows (shared with `connect`), the endpoint, method, query, headers and
    length, within the bounds above (a header value travels as a string whose characters are
    its bytes, U+0000 to U+00FF, and its bounds count those bytes); `run` answers with `body` and one end of a new pair of
    Unix sockets, on which the front writes the request's body and then says `bodyEnd`; then
    `response` with the answer's status and headers, the answer's body on the same socket, and
    `end`, which says whether that body is whole. The front finishes its client's answer only on
    a whole `end`, and cuts it off otherwise. A front gives a request up with `abort` when its
    client leaves or its body breaks, and `run` then ends it and the request to the gateway. At
    most 64 requests are open at `run` per front (more wait in the front); a front that opens
    more, sends a head out of its bounds (a header with CR, LF or NUL, more than 64 headers, a
    query that is not one), reuses a request number, ends a body twice, or aborts a request that
    never was, is ended. A front that closes a request's socket and does not say within 10 s
    whether the body is whole has that request ended. When a front ends, `run` ends all its
    requests. A sandbox has one listener of each proxy in its life, and its front serves each
    once: `run` refuses a second one itself, so a front never hears of it.
  - **The git messages.** `run` tells the front the bounds of the sandbox's git proxy and
    platform tools (open connections, the time for a request's headers, the idle time of a
    connection), which the sandbox reaches through its tunnel, then the
    run's routes as they are (`gitRoutes`: each repository route's number, secret, repository
    path and branch, and the platform tools' number and secret), again whenever they change,
    each followed by a round trip so that a request after them finds them. For each fetch or
    tool call the front sends `request` as for the model proxy, with the route's number in
    place of its path and secret, and the same sockets, answers and ends follow. A route the
    run does not have, a request its route does not take (another method, another query, a
    push), a body over its bound or not of its declared length, a header with CR, LF or NUL,
    and a browser's request to the tools are refused by `run` as by the shared listener of a host
    without the bridge; a
    front that opens more than 64 of these requests at once is ended.

### The push

By default `run` pushes a run's work with `git push` (step 8 of [What a run does](#what-a-run-does)):
git runs in `run`'s process tree with the run's git token in its environment, and its
`pack-objects` reads the objects that a sandbox made. `run --push-client host`
(`TINFIRM_RUNNER_PUSH_CLIENT=host`) pushes with the runner host's own receive-pack client instead
(`git_push.rs`), so that no process that reads a sandbox's objects holds the token. **This client has
been checked against two servers alone:** a git smart-HTTP server run for the test, and GitHub for a
new branch ([On real machines](#on-real-machines)). It was not checked against GitLab, Gitea or Azure
DevOps, so `git push` stays the default, and `host` is for testing it. With `--push-client host`, for a repository
over https:

1. `run` reads the run's token at that request, from the run's holder of git tokens, as the git
   proxy does ([Revoked git tokens](#revoked-git-tokens-protocol-v2)). It sends
   `GET <repository>/info/refs?service=git-receive-pack` (protocol v0, as git 2.43 pushes over
   HTTP) and reads the answer as pkt-lines alone, each bounded and checked: at most 32 MiB,
   262,144 refs, 65,520 bytes a line and 4096 bytes a ref name. The first line must be
   `# service=git-receive-pack`. A ref name with a control character, a space or DEL, an HTML page,
   a dumb server's answer, an encoded answer, a status other than 200, and a redirect each fail the
   push. The `shallow` lines of a shallow repository, after the refs, are checked (at most
   65,536) and dropped, as git's own push drops them. No redirect is followed, so the token goes to the repository's own URL alone, as with the
   [git proxy](#git-in-the-sandbox).
2. It compares the branch's tip there with the lease (the tip at clone time, or none) before it
   sends anything. A branch that moved, was created or was deleted during the run is refused as
   git refuses it, `[rejected] (stale info)`, and nothing reaches the repository. A branch that is
   at HEAD already is up to date, and nothing is sent.
3. A process without any credential writes the pack. With the bridge (`--sandbox-bridge`,
   `--executor vm`) it is a [reader](#the-reader-and-the-front), confined as for its other git
   commands: it reads the clone, writes nothing, and its stdout is the pack. Without the bridge it is
   `git pack-objects` as a child of `run` without the token in its environment, like `run`'s other
   git commands on the workspace. It packs the objects reachable from HEAD and from none of at most
   257 tips: the lease's, and the first 256 that the repository lists (`HEAD` first, then branches,
   then tags) and that the clone had at clone time. The pack is a full one, never a thin pack. A
   repository with more refs still gets a correct pack, only perhaps a larger one.
4. `run` reads the token again and POSTs `<old> <new> refs/heads/<branch>`, where `old` is the lease
   (the zero id for a new branch), so that the repository checks it again when it updates the
   branch. Then follows the pack as the writer writes it: unread, in chunks of 64 KiB, never held
   whole. A request up to 1 MiB goes with a `Content-Length`, once the writer has ended well; a
   larger one in chunks (`Transfer-Encoding: chunked`), as git does with its `http.postBuffer`. A
   receive-pack takes a pack once its checksum, at its end, has come, without waiting for the end
   of the request, so `run` holds the most recent chunk back and sends the last one only once the
   writer has ended well. A writer that fails, or does not end within 30 s of its output's end,
   never has its pack's end sent: the request is broken off, and the repository never has a whole
   pack to take. The push has one deadline of 60 minutes, from the first reading of the token to
   the report (the ref discovery, the check of the clone's objects, packing and sending), and the
   ref discovery 120 s of it; when it passes, the writer's process group is killed and the push
   fails.
5. It reads the report-status, out of the side-band when the repository has `side-band-64k`: at
   most 16 MiB in all, of which 1 MiB of report. The newest 16 KiB of the repository's messages
   are kept (its errors come last), and of a progress meter only its last state. A fatal message
   (band 3), an `unpack` line that is not `ok`, an `ng` line, and a status for another ref or for
   none each fail the push.

The outcome is worded as `git push --porcelain` words it, so a run reports the same reasons as with
`git push` ("the remote branch changed during the run …", "the push was rejected …", "the
repository refused the push", "git push failed"), with the repository's own messages as
`remote: …` lines. A repository of another scheme (`file://`, for tests and local development) is
pushed by git, which needs no token for it, and the host's log says so at each such push. The runner host makes no shallow clone (the mirror
fetches every branch and tag, and `run` deletes a `shallow` file that an agent plants), so a pack
never needs `--shallow`.

Where it differs from `git push`, which an operator can meet:

- **No proxy.** It connects to the repository's host directly: neither `HTTPS_PROXY` nor git's
  `http.proxy` applies. A host that reaches its code host only through a proxy cannot use it.
- **Its own trust.** It trusts the system's root certificates, or those of `SSL_CERT_FILE` and
  `SSL_CERT_DIR` when they are set, as the git proxy does; not git's `http.sslCAInfo` or
  `http.sslVerify`.
- **No git configuration.** Nothing of the host user's git configuration applies: no `insteadOf`
  rewrite, no `http.extraHeader`, no `push.followTags` or other `push.*` setting.
- **Larger packs.** It never sends a thin pack, so a push can be larger than git's.
- **Limits that can refuse a push** that `git push` makes: a repository whose ref advertisement
  is over 32 MiB or 262,144 refs, and a push that takes more than 60 minutes. Switch back to
  `--push-client git` for such a repository.
- **The log.** When it fails, the host's log says so in fixed words; what the repository and the
  pack's writer said reaches only the run's events, redacted as everything there.

What it does not close: `run` parses the code host's answers itself, bounded as above; and a
`git pack-objects` that a hostile repository took over can send any bytes to the run's repository
as the pack, under the run's token. That can add objects there; the branch still moves only to the
HEAD that `run` checked, and only from the lease. Tested in `tests/git_push.rs` (against
`git http-backend` and hostile fake hosts), `tests/reader.rs` (the reader's pack), the parsers' unit
tests with seeded random input, and runs in `tests/local_runs.rs`. `TINFIRM_TEST_PUSH_CLIENT=host`
sets this client on every test host, which uses it for the repositories that a test serves over
HTTP; most tests use `file://` repositories, which git pushes, and the log says so.

#### On real machines

On 2026-11-22, at `cdf8209`, with the client switched on at a control host and real runs (the
outputs are not in this repository), against a git smart-HTTP server run for the test, which
recorded every request, and against GitHub for a new branch. What passed:

- The ref discovery: 200, `Content-Type` exactly `application/x-git-receive-pack-advertisement`,
  and the server's capabilities (`report-status report-status-v2 delete-refs side-band-64k quiet
  atomic ofs-delta object-format=sha1`). The User-Agent `git/2.0 (tinfirm-runner-host <version>)`
  was accepted.
- A new branch, at GitHub and at the test's server. An update after a rebase, with the lease (a
  forced update from the leased value).
- A stale lease (the branch moved during the run): "the remote branch changed during the run …
  nothing was pushed", with one GET and no POST. An empty push (HEAD is already the tip): one GET,
  no POST.
- A pack under 1 MiB went with a `Content-Length`, and one of 125 MiB in chunks (13519 of them), with
  no 411 and no 413.
- A refusal by a server hook: "the repository refused the push: remote: …" with the hook's own
  text. A wrong or expired token: 401, "git push failed: … returned error: 401". A redirect (a 302
  that the test's server made): refused, "follows no redirect, so that the token goes to the
  repository's own URL alone".
- More than 256 refs (313 advertised): the push went through with a correct pack.

Not shown, or in part:

- Every point but the new branch ran against the test's own server alone, and at GitHub only a new
  branch was pushed. GitLab, Gitea and Azure DevOps were not reached (no account).
- A branch that moves between the client's two requests (the second half of the stale lease).
- A push to a SHA-256 repository: the test's server advertised `object-format=sha256`, but no run
  pushed to such a repository.
- A repository with no ref at all: a run cannot clone an empty repository, so only "the branch does
  not exist yet" was shown, by the new branch.
- A shallow repository; the time and memory of a 1 GiB push against `git push` (not measured: the
  test's server was the slow part; the large pack went in 64 KiB chunks); an HTTPS proxy in between
  (a proxy set for the whole host made the run's clone fail first); and a pack writer that fails
  after writing a whole pack, on a real host.

So `git push` stays the default.

## A VM per run

With `run --executor vm` (`TINFIRM_RUNNER_EXECUTOR=vm`), every run gets a fresh VM from a template
of a VM driver, and the VM is deleted when the run ends, never reused
([ADR-0096](../docs/adr/0096-a-vm-per-run.md)). This host needs no Docker and holds no cloud
credential, and a VM costs only while its run runs, or, with [ready VMs](#ready-vms), while it
waits for one. Every agent runs over the [sandbox bridge](#sandbox-bridge).

- **Editions** ([ADR-0114](../docs/adr/0114-the-secure-sandbox-is-in-the-core.md)). The VM executor,
  the driver protocol and the VM image's recipe are in every edition: with a driver of your own
  (below), a Community server takes the host. DigitalOcean's driver (`--vm-driver digitalocean`), the
  pod per run (`--vm-driver pod`) and ready VMs are Pro's and Enterprise's (elastic runners): such a
  host lists `sandbox.elastic` or `sandbox.pool` in its `hello`, and a server whose licence does not
  hold elastic runners refuses it with `EDITION_REQUIRED`.
- **How a run goes.** Without ready VMs, every run starts cold:
  1. The run asks for a VM, and the driver creates one from the template. Its user data names no
     run.
  2. Once the driver reports it running, this host dials the sandbox bridge in the VM, which listens
     on `--vm-port`, and passes the connection to the VM's [front](#the-reader-and-the-front),
     which holds the VM's bridge from its create; it dials again only while the bridge's link is
     down. The VM's firewall lets in only this host, to that port, and lets nothing out; this host
     listens on nothing for its VMs.
     A driver that names a `port` in `describe` is dialed at that port of the VM's address instead:
     a sandbox node publishes each VM's bridge on a port of its own address and forwards it to
     `--vm-port` in the VM ([ADR-0112](../docs/adr/0112-no-credential-on-a-sandbox-machine.md)).
  3. The bridge probes the VM's network from inside (below). The VM is up now.
  4. The run takes the VM. Its files go in, and the agent starts with its credentials, over the
     bridge, as in [Sandbox bridge](#sandbox-bridge).
  5. After the agent, the work comes back over the bridge, and the driver deletes the VM.
- **The user data holds no credential.** It holds the bridge's settings (the VM's id, its one-time
  ticket, the pin of the VM's own bridge certificate, which its front holds, and the port to listen
  on), the runner image of a runtime, the sandbox's own settings (`--sandbox-env`), and the URL of
  the VM's egress proxy, `127.0.0.1:18763` on the VM's loopback, which the bridge's tunnel carries to
  its front; no address of this host's. Anything in the VM can read it
  from the metadata service. In the VM, `vm-boot` reads it from the source that the kernel command
  line names (`tinfirm.user-data=disk:/dev/vdb`: a sandbox node's VM gets it on a small read-only
  disk, ADR-0112), or else from DigitalOcean's metadata service; one VM image serves both.
- **Always on:** the sandbox bridge, and the [egress proxy](#egress-proxy), which is the VM's only way
  to the network (`--egress-allow` works as with Docker). The level it reports depends on the
  driver ([Isolation level](#isolation-level)).
- **No images on this host**: the VM's image holds the runner images, built when the image is
  built (below), and `run` rebuilds none.
- **`--sandbox-docker` needs no Kata here**: the VM is the boundary, and the agent is root in it, as
  in a Kata VM. `--sandbox-runtime` is refused. A Codex ChatGPT login cannot reach a VM (it is a mount);
  such a run fails.
- **The probe.** Before any file or credential enters the VM, the bridge tries `1.1.1.1:443`,
  `8.8.8.8:53`, `[2606:4700:4700::1111]:443`, a DNS answer for `example.com`, and the credential paths of
  the AWS, GCP and Azure metadata services. A VM that reaches any of them is deleted, and its run fails
  with `INFRA`. `--vm-probe` adds checks of your own: your database, your other networks.
- **Checked at start.** `run` asks the driver to verify the template against the contract of a sandbox
  VM, and refuses to start when it finds a problem, or when the driver has too little room for
  `--max-concurrent-runs` VMs. The driver's room (`maxVms`) is what is left: the nodes' free slots
  with `--vm-driver node`, the account's remaining quota with a cloud's driver. The VMs that already
  carry this host's `tinfirm-host` label count as this host's own room: after a crash or a restart
  they are the runs it takes back and the leftovers it deletes, so a host sized to its nodes starts
  again with them in place. Only when the room is short does `run` list them (`list`), and the
  refusal says both numbers: "--max-concurrent-runs is 4, but the VM driver `node` has room for 1
  more VM(s), and this host holds 2 VM(s) of its own". A host that has never been welcomed has no
  VMs of its own. A driver that names no `maxVms` is not checked. With `--vm-driver node` the
  count is where the room is: only this host's VMs on the nodes that offer `--vm-template` count,
  since only those nodes' free slots are in `maxVms` (a VM's id names its node). A node that does
  not answer is in neither number; a node that reports a problem refuses the start before.
- **Leftovers.** After each connection to the server, this host lists its VMs and deletes those it does
  not hold: after a crash of this host, or a delete that failed. Each VM has the labels `tinfirm-host`
  (this host's id) and `tinfirm-vm` (the VM's own id); the run that takes it is in this host's log.
- **A delete that fails** (its node is down or reboots, the driver does not answer) is tried 3 times
  over a few seconds, and then again on its own, without a restart of this host: after 15 s, then
  after twice the wait before, up to 5 minutes, and every 5 minutes from then on until the delete
  succeeds. Only the VMs whose delete this host asked for are tried, never one that a run, a ready
  VM or a run taken back holds. The log has "the sandbox VM is left; the host tries its delete
  again later", then "sandbox VM deleted, after a delete that had failed" once the node answers.
  The first 12 failed tries (about 40 minutes) are warnings; the twelfth says "the sandbox VM is
  still left; the host keeps trying its delete", and the tries after it are in the debug log only.
  The cleanup at the next connection or start deletes such a VM too. Shown on machines
  (2026-11-22) with a node rebooted during a run: the host logged a first and a second failed
  delete (no connection in time, then the connection refused), then that the VM was deleted once
  the node answered, without a restart of its process; `doctor` then said "room for 2 more VM(s)".
- **A VM that ends during its run** (its VMM dies, its node reboots) ends the run's bridge session
  without the adapter's exit status once the bridge has been gone for 30 s. This host then asks the
  driver's `describe` once, for at most 25 s: when the VM is gone or failed, or the driver cannot
  describe it, the run fails with `INFRA`, "The sandbox VM ended during the run (…)", not as the
  agent's fault. An adapter that exits by itself is reported as before, without that question.
  Shown on machines (2026-11-22) with the VMM killed during a run on a sandbox node: the run failed
  as `INFRA` after 47 seconds, "The sandbox VM ended during the run (the provider reports the VM
  failed); the agent's session in it was lost."
- **A VM that does not come up** (connected) within `--vm-start-timeout`, or that the provider fails,
  fails its run with `INFRA` and is deleted.
- **A driver that can make no VM.** After a `create` that fails, this host asks the driver's
  `verify`. Only an answer with problems and no room (`maxVms` 0), such as a node whose firewall
  table is missing, stops it from taking runs: it rejects each new run with `NO_FREE_SLOT` and a
  reason that names the cause ("the VM driver can make no VM now (…)"). This host asks `verify`
  again before a rejection (at most every 5 s) and on its own every 30 s, and takes runs again once
  the answer is clean or a create succeeds. With `--vm-driver node` it also asks every 5 minutes
  while it takes runs, for the nodes' hardening ([Isolation level](#isolation-level)); such an
  answer with problems and no room stops it from taking runs too, before a run's create fails. A
  clean answer after a failed create leaves the host as it was: the failure was the VM's own. A `verify` that fails or does not answer changes nothing.
  A run's admission, and the report of the run whose create failed, wait at most 5 s for `verify`;
  a slower answer is used when it comes. Its log has one warning when it stops taking runs and one
  line when it takes them again. With a server that lists the feature `host.health`
  ([ADR-0117](../docs/adr/0117-a-host-reports-that-it-can-take-no-run.md)) the host also tells
  the server: `host.health` with the problem `VM_DRIVER_NO_VM` and the cause when the state
  begins, again when the cause changes and after each reconnect while it holds, and once without
  a problem when it ends; at most one frame every 5 s. The server then sends it no run, the host's
  card says **Takes no runs** and why, and the waiting runs start as soon as the host takes runs
  again. With an older server the host sends nothing: the runs wait in the server's queue with
  the rejection's reason, and the server tries each again later, waiting longer after each
  rejection by the same host (up to 5 minutes by default).
- **The VM image's build.** The VM image holds its own copy of this binary, which runs the bridge and
  built the runner images there. When a VM's bridge connects, it names that build (the version and a
  digest of the images' definitions, such as `1.0.0+images.1a2b3c4d5e6f`), and this host compares it
  with its own: `--vm-image-version warn` logs the difference once for each build, `refuse` deletes
  the VM and fails its run. Rebuild the VM image with this host's binary after every upgrade; this
  host's build is in its start's log line (`runner_build`).

| Option | Environment | Default | Meaning |
|---|---|---|---|
| `--executor docker\|vm` | `TINFIRM_RUNNER_EXECUTOR` | `docker` | Containers of this machine's Docker, or a VM per run |
| `--vm-driver COMMAND` | `TINFIRM_RUNNER_VM_DRIVER` | | `digitalocean`, this binary's own driver (Pro and Enterprise), `node`, its driver for [sandbox nodes](#sandbox-nodes) (every edition), `pod`, its driver of a pod per run in the Kubernetes cluster this host runs in (Pro and Enterprise; its configuration in `TINFIRM_POD_DRIVER_CONFIG`, the Helm chart's `runner.vm.driver: pod`), or the command line of your own (split at white space) |
| `--vm-template NAME` | `TINFIRM_RUNNER_VM_TEMPLATE` | | The driver's template: image, size, network and firewall |
| `--vm-port PORT` | `TINFIRM_RUNNER_VM_PORT` | `7443` | Where the bridge listens in the VM |
| `--vm-start-timeout SECONDS` | `TINFIRM_RUNNER_VM_START_TIMEOUT` | `900` | From the create to the bridge's first connection (30 to 3600) |
| `--vm-probe CHECK` | `TINFIRM_RUNNER_VM_PROBE` (one per line) | | `tcp:HOST:PORT`, `dns:NAME` or `http://HOST[:PORT]/PATH`, repeatable |
| `--vm-image-version warn\|refuse` | `TINFIRM_RUNNER_VM_IMAGE_VERSION` | `warn` | What to do with a VM whose image another build of the runner host made |
| `--isolation-level machine\|vm-jail` | `TINFIRM_RUNNER_ISOLATION_LEVEL` | | With a driver of your own: the level this host reports ([Isolation level](#isolation-level)) |
| `--isolation-hardened yes\|no\|none` | `TINFIRM_RUNNER_ISOLATION_HARDENED` | `no` | With `--isolation-level`: whether the machines that run the VMs are hardened |

### Ready VMs

A VM takes the provider's boot, its own start and its dockerd before a run can begin: minutes. Ready
VMs wait for runs with all of that done, connected and probed, so a run that takes one starts in
seconds. They are Pro's and Enterprise's, and off by default: `--pool-max-ready` above 0 turns them
on, and the host then lists `sandbox.pool`, which a server refuses without elastic runners
([ADR-0114](../docs/adr/0114-the-secure-sandbox-is-in-the-core.md)). Their code is
`ee/runner/tinfirm-runner-host/src/ready_vms.rs`; it keeps VMs that the core's pool (`pool.rs`)
creates, probes, hands to runs and deletes ([ADR-0098](../docs/adr/0098-ready-vms.md); the
[analysis](../docs/reviews/2026-10-15-kubernetes-runner-analysis.md#63-ready-sandboxes-a-start-in-seconds),
§6.3).

- **How many.** An owner or admin sets the schedule in Tinfirm, on this host's card (Runner hosts ›
  Ready sandboxes; [ADR-0099](../docs/adr/0099-pool-settings.md)): a count when no window applies, and
  weekly windows in a time zone, such as working days 08:00 to 18:00 with 2 ready. Until then the count
  is 0. The server evaluates the schedule and sends the count now and its next change in
  `host.config.pool`; this host follows the change on time by itself, and starts filling a window
  early by its cold start (the 90th percentile of its last 20, 5 minutes before the first, at most
  `--pool-lead-time-max`). `--pool-headroom N` keeps one ready VM per N busy ones. The operator's
  `--pool-max-ready` is the ceiling of both, and 0, the default, turns ready VMs off. Ready VMs
  count against `--max-concurrent-runs` (and the server's lower value, if any): with all slots busy, none waits.
  While this host drains, after 15 minutes without the server, and when it stops, it keeps none.
- **What Tinfirm sees.** This host reports its ready, starting and busy VMs, its cold start and the
  runs of the last 24 hours that took a ready VM or started cold (`host.pool.status`), at most every 10
  seconds; the card shows them.
- **What a ready VM holds.** Nothing of a run: no prompt, no file, no credential. Its front holds
  its bridge's session while it waits. Its egress proxy, which its settings name and its front
  serves through its tunnel, refuses every destination until a run takes the VM and binds its rules
  to it. A ready VM has no record: after a restart, this host deletes it as a leftover and makes a
  new one, with a new front.
- **One shape.** The ready VMs run the runner image of `--pool-runtime` with this host's sandbox
  settings. A run of another runtime gets a VM created for it (a cold start); when this host's slots
  are full, a ready VM goes to make room. With `--pool-runtime any`, every run starts from the image
  with every runtime (`tinfirm-runner-multi:local`, which the VM image's recipe builds), and the host
  names the run's adapter when it starts it, so the ready VMs serve every runtime
  ([ADR-0101](../docs/adr/0101-closing-the-elastic-runners-gaps.md)).
- **Taken, never returned.** Every claim starts a replacement at once. The VM goes with its run, like
  any other.
- **Kept fresh.** A ready VM older than `--pool-max-age` is replaced, and ready VMs above the count for
  `--pool-cool-down` are deleted, the oldest first. A VM that waited longer than a minute is probed
  again when a run takes it.
- **A start that fails.** A run that waits for a VM fails with `INFRA` when that VM cannot be created,
  as without ready VMs. After a failed create, this host starts no ready VM of its own for 30 s,
  doubling up to 10 minutes, so a provider that refuses (a quota, an outage) is not asked every few
  seconds.
- **In the log.** Each run says whether it took a ready VM or started cold, and how long it waited.

| Option | Environment | Default | Meaning |
|---|---|---|---|
| `--pool-max-ready N` | `TINFIRM_RUNNER_POOL_MAX_READY` | `0` | The most ready VMs Tinfirm may ask for; 0 turns them off. Above 0, the server needs elastic runners |
| `--pool-runtime claude\|codex\|pi\|opencode\|any` | `TINFIRM_RUNNER_POOL_RUNTIME` | `claude` | The runtime whose image the ready VMs run; `any`: the image with every runtime, for every run |
| `--pool-headroom BUSY` | `TINFIRM_RUNNER_POOL_HEADROOM` | `0` | One more ready VM per this many busy ones; 0 is off |
| `--pool-max-age MINUTES` | `TINFIRM_RUNNER_POOL_MAX_AGE` | `360` | A ready VM older than this is replaced (10 to 10080) |
| `--pool-cool-down MINUTES` | `TINFIRM_RUNNER_POOL_COOL_DOWN` | `10` | Ready VMs above the count for this long go (0 to 1440) |
| `--pool-lead-time-max MINUTES` | `TINFIRM_RUNNER_POOL_LEAD_TIME_MAX` | `30` | The longest a window starts filling before its time (0 to 240) |

**A driver** is a program that this host starts and talks to with JSON-RPC 2.0 on stdin and stdout,
one message per line: `hello`, `verify`, `create`, `describe`, `delete` and `list`
([ADR-0096](../docs/adr/0096-a-vm-per-run.md) has the methods). It holds the provider's credential, and
deletes only VMs that carry this host's label. The protocol is version 1. `describe` (and each VM of
`list`) answers `id`, `name`, `state` (`starting`, `running`, `gone` or `failed`), `address`,
`labels`, and an optional `port`
([ADR-0112](../docs/adr/0112-no-credential-on-a-sandbox-machine.md)): with a `port`, this host dials the
VM's bridge at `address:port`; without one, at `address:--vm-port`. The bridge in the VM listens on
`--vm-port` either way. A run's take-back record keeps the port, so that a restarted host dials the
same; a record without one, such as one written before the field existed, means `--vm-port`. A
request is a JSON object with `jsonrpc` `"2.0"`, a `method` and an `id` (a number or a string); the
protocol has no notifications and no batches. `verify` answers `problems`, `maxVms` and `checked`,
and two optional fields that this host reads only from `vm-driver node`: `hardened` (`yes` or `no`,
left out when the driver cannot tell; any other value reads as `no`) and `notHardened`, a list of
why, for the log ([Isolation level](#isolation-level)). A driver of your own may leave both out: its
hardening is what `--isolation-hardened` states. A method that fails is answered with an error of code
-32000 and a message for this host's log, an unknown method too. A line that is not JSON is
answered with -32700 and `id` `null`, JSON that is no request with -32600 (with its `id` when it has
one), and an empty line with nothing. This binary's drivers (`vm-driver digitalocean` and `vm-driver
node`) do so, and exit non-zero when their input ended after such lines and no request. One whose
input cannot be read (a broken pipe, not its end) sends the answers already under way and exits
non-zero with the read error. This host reads only the answers to its own ids. You can write one for another provider or
your own hypervisor, in any edition: such a host lists no paid feature. A driver must keep the contract C1 to
C7 of the
[analysis](../docs/reviews/2026-10-15-kubernetes-runner-analysis.md#62-any-provider-one-contract-a-driver-per-provider).

### Sandbox nodes

`--vm-driver node` is this binary's second built-in driver
([ADR-0112](../docs/adr/0112-no-credential-on-a-sandbox-machine.md), decision 4). This host is then a
**control host**: it holds the credentials and runs no agent code, and the VMs run on **sandbox
nodes**, machines that hold nothing, each with the node agent (`crates/tinfirm-node-agent`). The
driver relays `verify`, `create`, `describe`, `delete` and `list` to each node agent's listener, over
TLS with pinned certificates both ways, or over the listener's local socket when the agent runs on
this host's own machine (a single machine, which is set aside and not built: the node agent refuses
a control host on its own machine).

**Not ready for use yet.** The node agent's execution (its jail, network, disks and VMM) is built.
VMs were made, described, listed and deleted on one KVM node through this driver over TLS, driven by
a script in this host's place ([the evidence](../docs/reviews/2026-11-19-node-agent-execution/README.md);
[the agent's state](crates/tinfirm-node-agent/README.md#what-is-built-and-what-is-planned)). The
Ansible plays that set up a node and a control host (`deploy/ansible/README.md`) ran on machines
once, on 2026-11-20 (Ubuntu 24.04), with runs of this host's tests' stand-in agent through a node;
on the same day, conversations with real runtimes (Claude Code and PI, with a real model) ran
through a node, in a Firecracker VM, with the sandbox's front confined (Landlock and seccomp).
The node's hardening is built: the Ansible role's tasks that set it up, the node agent's checks of
it, and this host's reading of them. On 2026-11-22, on one node (Ubuntu 24.04, kernel 7.0), the
play, the 13 checks, the unit that sets kexec, lockdown and module loading at each boot, and the
VMMs' AppArmor profile in both modes held, with nothing logged by the profile, driven through the
driver's `verify` and `doctor` from a control host with no server (the outputs are not in this
repository). Later that day, with a server and the VMMs' profile in enforce mode, a real
conversation (Claude Code with a real model, from its start to its push), a run of the tests'
stand-in agent and a make that failed after its VMM started ran through a hardened node with no
`DENIED` line; the server stored this host's level `machine` with `hardened` `yes`, and a line set
back by hand reached it as `no` by a new hello ([Isolation level](#isolation-level)). Not shown: a
new VM going to a hardened node first (there was one node), and the failing line in this host's
warning: the warning carries it in its field `not_hardened`, but the round's notes have the
message without its fields (`doctor` named the line).
Tinfirm publishes no VM image: the image holds Claude Code when built with every runtime, and
Tinfirm does not redistribute it, so you build the image with `images/vm/local-disk.sh` and this
host's own binary ([ADR-0112](../docs/adr/0112-no-credential-on-a-sandbox-machine.md), decision 4).
A CI job that builds and boots the image is planned. The Helm chart's runner pod has not run
against a node. Here, the driver is tested against node agents with their fake backend.

- **Editions.** In the core, in every edition: the driver lists no paid feature
  ([ADR-0114](../docs/adr/0114-the-secure-sandbox-is-in-the-core.md)).
- **Its level of isolation** ([Isolation level](#isolation-level)). This host reads the driver's
  configuration at its start (the same file: it names it to the driver with `--config`) and reports
  `vm-jail` when every node is reached over its local socket, and `machine` when every node is reached
  over TLS at an address that is not this host's own; hardened `yes` when every node that offers
  `--vm-template` reports it ([Isolation level](#isolation-level)). An agent that leaves its VM
  through the VMM is then one jailed user that reads no credential, and a flaw of the kernel under the
  VMs (KVM) reaches the machine with every credential (`vm-jail`), or the node, which holds no
  credential, and the other runs on it (`machine`). A configuration that cannot be read, that mixes
  sockets and TLS, or that reaches this host's own address over TLS stops `run` at its start, and
  `--isolation-level` is refused. Conversations with real runtimes have run through a node once
  (above): this is what such a host reports at its start, and `vm-jail` waits for the single
  machine.
- **Its configuration**, `/etc/tinfirm/node-driver.json` (or `TINFIRM_NODE_DRIVER_CONFIG`):

  ```json
  {
    "nodes": [
      {
        "name": "node-1",
        "address": "10.0.0.20:7700",
        "nodeSha256": "the SHA-256 of the node's certificate, 64 hex digits",
        "certificate": "/etc/tinfirm/node-driver/client.crt",
        "key": "/etc/tinfirm/node-driver/client.key",
        "templates": ["sandbox-4c8g"]
      }
    ],
    "peerBuild": "warn"
  }
  ```

  A node is reached either at `address` (`HOST:PORT`, the listener's TLS port) with the node's
  certificate pinned by `nodeSha256` and this host's client certificate and key (DER; the key must be
  this host's user's alone, 0600, or the driver refuses to start), or at `socket`, the listener's local
  socket, with no certificate. All the nodes are reached one way: sockets and TLS at once are refused,
  since a runner host has one isolation level. `templates` names the templates of the node agent's own
  configuration that the node offers; `--vm-template` must be one of them.
- **The certificates.** `tinfirm-runner-host node identity --cert FILE --key FILE` writes a new key
  (PKCS #8 DER, mode 0600) and its self-signed certificate (DER), and prints the certificate's SHA-256
  in 64 hex digits: this host's client certificate, whose SHA-256 goes into the node agent's
  `clientSha256`. Neither file may exist, and neither is followed if it is a symbolic link.
  `tinfirm-runner-host node fingerprint --cert FILE` prints the SHA-256 of a certificate, such as the
  node's for `nodeSha256`. `tinfirm-node-agent identity` and `fingerprint` do the same with the same
  files: a certificate made by either binary works with the other, and the control host needs no node
  agent's binary for its own.
- **Placement.** A new VM goes to a node that reports itself hardened before one that does not, then to
  the node with the most free slots, by each node's `verify`, among the nodes that offer the template,
  answer and report no problem; the first in the file's order on a tie.
  When no node can take it, the `create`'s error names each node's cause: its problems (cut to 400
  bytes), that it did not answer, or that it has no free slot. Only when every such node is healthy
  and full is it "no node of the driver has a free slot".
- **Capacity.** `verify`'s `maxVms` is the sum of the free slots of the nodes that answer with no
  problem. A node that does not answer is not counted and is named in the start's log; a node that
  reports a problem, or a template that no node offers, refuses the start. The start check adds
  this host's own VMs on the nodes that answer to that sum ([Checked at start](#a-vm-per-run)).
- **Ids.** A VM's id is `<node>:<the agent's id>`, so the driver, which this host starts again at will,
  finds the node of each.
- **A node that is down.** `describe` and `delete` of its VMs fail (never `gone`), and the run that
  waits on one ends as `INFRA` when its timeouts expire. `list` lists the VMs of the nodes that answer:
  this host deletes only leftovers it is shown, so a node that does not answer keeps its VMs until it
  answers again. A delete that failed while it was down is tried again on its own
  ([A delete that fails](#a-vm-per-run)), and a run whose VM it took down ends as `INFRA`.
- **The port.** Each node publishes each VM's bridge on a port of its own address, which `describe`
  names (above); the VM's bridge listens on `--vm-port` inside it, and its user data comes on a small
  read-only disk that `vm-boot` reads.
- **Builds.** The driver and the agent exchange their build ids when they connect; another release
  is a warning (`peerBuild: warn`) or refused (`refuse`). The images' digest after `+` is not
  compared. A connection that was lost (an agent that was restarted) is made again at the next call.

```sh
tinfirm-runner-host run --executor vm --vm-driver node --vm-template sandbox-4c8g
```

### In Kubernetes

The Helm chart runs one host as a pod with `runner.enabled` ([ADR-0101](../docs/adr/0101-closing-the-elastic-runners-gaps.md),
decision 8; [the operator guide](../docs-site/content/operator-guide/helm.md#the-runner-pod)): a company host
of one declared company, which the server registers itself from
`TINFIRM_COMPANIES_<KEY>_RUNNER_HOST_NAME` and `_TOKEN`. It runs `--executor vm` with the driver
`runner.vm.driver`: `digitalocean` (the default, Pro's and Enterprise's), `node`, or `pod`, a pod
per run in the same cluster ([the operator guide's page](../docs-site/content/operator-guide/kubernetes-pod-per-run.md);
its image's recipe is `ee/runner/images/vm/pod-image.sh`), and keeps no ready VMs unless
`runner.pool.maxReady` is above 0. The pod runs the image
`ghcr.io/flowtivo/tinfirm-runner-host` (the last stage of `deploy/release/runner-musl.Dockerfile`:
this binary, git, the CA certificates and `kill`, user 10001), with `run --server <public URL>
--token-file <the mounted Secret>`, its data directory on a volume, so that a restarted pod takes its
runs back, and a `preStop` that drains it. The VM image, the VPC, the firewall and the API token are
the same as below; the firewall lets in the cluster's addresses.

### DigitalOcean

`--vm-driver digitalocean` creates a Droplet per run. The driver and its Packer template are Pro's and
Enterprise's (`ee/runner/`); the VM image's recipe that the template runs is `runner/images/vm`.

1. **A VPC of the sandboxes' own** in the region, not the region's default VPC. Peer it with the VPC
   of this host (for example a DOKS cluster's), so that this host reaches the Droplets on their VPC
   addresses.
2. **A Cloud Firewall on the tag `tinfirm-sandbox`**, and no other firewall on that tag:
   - inbound: TCP 7443 (`--vm-port`) from this host only, for example the cluster's tag `k8s:<cluster
     id>` or this host's addresses, never from anywhere, and never from the sandboxes themselves:
     not their VPC's range or any address in it, and not the tag `tinfirm-sandbox` or their
     `tinfirm-…:…` tags, from which one sandbox would reach another's bridge;
   - outbound: UDP 67 (DHCP) only, to the platform's DHCP server: IPv4 link-local addresses
     (`169.254.0.0/16`), the broadcast address (`255.255.255.255`) or the sandboxes' VPC's own
     range only. Never `0.0.0.0/0` or `::/0`, from which an agent could send datagrams to any
     host; never another private range, such as `10.0.0.0/8`, which reaches the VPCs peered with
     the sandboxes' (among them the VPC of this host); and never a tag, a Droplet or a cluster,
     which names no address.
3. **The snapshot.** Build it with Packer, from a static Linux release binary of this version of the
   runner host:

   ```sh
   export DIGITALOCEAN_TOKEN=...   # may create Droplets and snapshots; not the driver's token
   packer init ee/runner/images/vm/digitalocean.pkr.hcl
   packer build -var binary=./tinfirm-runner-host -var version=1.0.0 -var region=fra1 \
     ee/runner/images/vm/digitalocean.pkr.hcl   # from the repository's root
   ```

   `region` has no default: build in a region where the sandboxes run. A snapshot is only in the
   regions it was built in or copied to, and `verify` refuses a template whose `image` is not in its
   `region`. For sandboxes in several regions, copy it to the others in the same build, for example
   `-var 'snapshot_regions=["nyc3","sgp1"]'`, and name the same image in each region's template.

   It installs Docker Engine, the binary, the runner images (built in place: `-var
   image_build_args='--build-arg NPM_REGISTRY=…'` for mirrors) and the unit that runs `vm-boot` at
   boot, and leaves no SSH server or key and no DigitalOcean agent. The snapshot stays private to the
   team. Build it again for each release.
4. **An API token for the driver** with the narrowest scopes it needs: to create, read and delete
   Droplets, to create and read tags, and to read the account, images, snapshots, VPCs and firewalls.
   Put it in a file that only this host's user reads.
5. **The driver's configuration**, `/etc/tinfirm/digitalocean.json` (or `TINFIRM_DO_CONFIG`):

   ```json
   {
     "tokenFile": "/run/secrets/digitalocean-token",
     "connect": "private",
     "templates": {
       "sandbox-4c8g": {
         "region": "fra1",
         "size": "s-4vcpu-8gb",
         "image": "123456789",
         "vpc": "VPC_UUID",
         "firewall": "FIREWALL_ID",
         "bridgePort": 7443
       }
     }
   }
   ```

   `connect` is `private` (the VPC addresses) or `public` (the Droplets' public addresses, which the
   firewall then opens to this host's alone). The token can also come from `DIGITALOCEAN_TOKEN_FILE`
   or `DIGITALOCEAN_TOKEN`; a file keeps it out of this host's environment.
6. **Run:**

   ```sh
   tinfirm-runner-host run --executor vm --vm-driver digitalocean --vm-template sandbox-4c8g \
       --max-concurrent-runs 4
   ```

At start the driver checks, and `run` refuses to start on any finding:

- the account is active, and its Droplet limit, less the Droplets that are not sandboxes, covers
  `--max-concurrent-runs`;
- the image exists, is private and is in the region;
- the VPC is in the region and is not its default;
- the firewall exists, is on `tinfirm-sandbox` and alone there, lets in only TCP to the bridge's port,
  not from anywhere and not from the sandboxes' own VPC or tags, and lets out nothing but DHCP, and that only to IPv4 link-local
  addresses, the broadcast address or the VPC's own range.

Before each create it reads the firewall and the VPC's range again (at most once a minute). It gives
a Droplet no SSH key, no IPv6, no monitoring, no backups and no DigitalOcean agent, and tags it
`tinfirm-sandbox`, `tinfirm-host:<this host's id>` and `tinfirm-vm:<the VM's id>` (a VM's user data
names no run); this host's log names the run that takes it. It deletes a Droplet
only by its id, after reading it: it must carry `tinfirm-sandbox` and this host's tag, and never a
cluster's tag (`k8s…`). It never deletes by tag. Every create and delete is in this host's log with
the Droplet's id.

The token can still delete any Droplet of the team: DigitalOcean's scopes are per resource type, so the
guards above are the driver's code
([threat model](../docs/threat-model.md) R57; a team of the sandboxes' own is
follow-up work).

**Not yet:** a check on a real account (the boot time, the metadata service and DHCP under the
firewall, the probe from a Droplet) and the nightly conformance test.

## Commands

| Command | Does |
|---|---|
| `login --server <url> [--token <token>]` | Stores the server URL and the host token |
| `credential add claude --label <l> [--pool 3]` | Runs `claude auth login` into the credential's own `CLAUDE_CONFIG_DIR` ([ADR-0085](../docs/adr/0085-claude-login-on-the-runner-host.md)); the host holds no token. The pool (1–16) is the credential's [ceiling](#capacity-ceilings-and-tinfirm-settings): its runs share the one login, which Claude Code renews in place |
| `credential add codex --label <l> [--device-auth] [--pool N]` | `codex login` into a dedicated `CODEX_HOME`; `--pool` 1–16, default 3 |
| `credential add anthropic-api-key\|openai-api-key --label <l> [--pool 3]` | Stores an API key; the pool (1–16) is a ceiling |
| `credential list`, `credential remove <label>` | Manage credentials |
| `images build [--runtime claude\|codex\|pi\|opencode\|all\|multi] [--no-cache] [--build-arg KEY=VALUE]…` | Builds the runner images locally, with the [build arguments](#image-build-arguments) of a host without internet access, and records the arguments and runtimes in `images.json` for [the rebuilds of `run`](#rebuilds-after-an-upgrade) |
| `doctor [--max-concurrent-runs N] [run's sandbox and VM options]` | Checks the setup as `run` would start with the same options and environment (`TINFIRM_RUNNER_*`): the ceiling N (default 2) as `run` takes it for the executor (at most 16 with Docker), directories and their modes, git, credentials, server, [the reader and the front](#the-reader-and-the-front) (their confinement, and the front's wire), and the level of isolation. With the Docker executor, Docker, its runtimes and the runner images: the names of the recorded image build arguments and each image that `run` would rebuild. With `--executor vm`, no Docker (it says "not used"), but what `run --executor vm` checks at its start: the driver's options; with `--vm-driver node`, its configuration and that every client key is this user's alone (0600); then the driver's `hello` and a `verify` of `--vm-template`, with room for N VMs (default 2), this host's own VMs counted as at `run`'s start ([Checked at start](#a-vm-per-run)); with `--vm-driver node`, the nodes' hardening, with each line that does not hold, which the level of isolation then reports. Also the number of mirrors and their total size, and the runs that the next `run` takes back, with those whose work waits on this host for the server |
| `mirrors list` | Lists the repository mirrors: key, size, last use, last gc, repository URL |
| `mirrors prune [--older-than-days N] [--dry-run]` | Deletes idle mirrors and collects garbage in the others, like `run` does every 6 hours; refused while `run` is active (`--dry-run` only shows what it would do) |
| `run [--max-concurrent-runs N] [--protocol-version 1\|2] [--server <url> --token-file <file>] [--no-image-rebuild] [--push-client git\|host] [--sandbox-runtime <name>] [--sandbox-docker] [--sandbox-env KEY=VALUE]… [--sandbox-bridge] [--executor docker\|vm --vm-driver <command> --vm-template <name>]` | Connects and serves. N (1–16, default 2; up to 1000 with `--executor vm` and protocol 2) is the host's [ceiling](#capacity-ceilings-and-tinfirm-settings); other values are refused. `--protocol-version` (or `TINFIRM_RUNNER_PROTOCOL_VERSION`) picks the [runner protocol](#protocol-versions) for this start. Before it connects, it [rebuilds the images](#rebuilds-after-an-upgrade) that its binary would build otherwise, unless `--no-image-rebuild` (or `TINFIRM_RUNNER_NO_IMAGE_REBUILD`). `--upload-max-bytes` (or `TINFIRM_RUNNER_UPLOAD_MAX_BYTES`, default 26214400, the server's default `TINFIRM_ATTACHMENT_MAX_FILE_SIZE` of 25 MiB, at most 100 MiB) is the largest file an upload of the agent may have; a larger one is refused on its size before it is read, and at most 3 uploads read, scan and send a file at once. The sandbox options: [Kata Containers and Docker in the sandbox](#kata-containers-and-docker-in-the-sandbox); the VM executor's: [A VM per run](#a-vm-per-run). `--server` with `--token-file` (`TINFIRM_RUNNER_SERVER`, `TINFIRM_RUNNER_TOKEN_FILE`) stands in for `login`, for a host whose settings come from its environment ([In Kubernetes](#in-kubernetes)): the URL is checked as `login` checks it, and the token is read from the file once. Writes its PID into `run.lock` |
| `drain [--no-wait]` | Asks the `run` of this config directory to [drain](#drain-and-upgrades): sends SIGHUP to the PID in `run.lock` and waits until `run` has exited (`--no-wait` returns after the signal). Exits 0, also when no `run` is active ("Nothing to drain") |
| `node identity --cert <file> --key <file>`, `node fingerprint --cert <file>` | The TLS identity of a control host for [sandbox nodes](#sandbox-nodes): writes a new key (0600) and certificate, which must not exist, and prints its SHA-256; prints a certificate's SHA-256. The files of `tinfirm-node-agent identity` |

Global options: `--config-dir`, `--data-dir` (or `TINFIRM_RUNNER_CONFIG_DIR`,
`TINFIRM_RUNNER_DATA_DIR`) and `--log <filter>` (`TINFIRM_RUNNER_LOG`, default `info`).
Logs are JSON lines on stderr and never contain credential values.

## Protocol versions

The host speaks one runner protocol version per connection and names it in
`hello.protocolVersion`:

| Version | Schema | What it is |
|---|---|---|
| 1 | [`protocol/runner-v1.schema.json`](../protocol/runner-v1.schema.json) | The frames and tools before the restart: `run.ask`, `run.repository_request`, `run.task_create` and their acks; the tools `ask_human`, `finish_run`, `request_repository`, `create_task`, `upload_attachment`, `list_repositories`. For a server from before protocol v2: the current server accepts a v1 host but sends it no runs. |
| 2 | [`protocol/runner-v2.schema.json`](../protocol/runner-v2.schema.json) | The protocol of the current server ([docs/PLATFORM.md §11.1](../docs/PLATFORM.md#111-the-runner)): one generic `run.request` / `run.request.ack` pair for every platform request (kinds `ASK`, `ACCESS`, `ARTIFACT`, `NETWORK`, `TOOL`), the server-executed tools of `run.assign.tools`, the writable [`/output`](#output-protocol-v2) folder and `run.finished.artifacts`, `run.finished.resumed` and `resumeOutcome`, the cache writes apart from the input in the usage (`cacheWriteTokens`), `run.assign.workItem` and `job` in place of `task` (the current server fills both with the conversation), the level of isolation in `hello.isolationTiers` (`machine`, `vm-jail`, `container` or `gvisor`) with `hello.hardened` and `hello.isolationStated`, and `welcome.protocolVersion`. The tools are renamed: `ask`, `finish`, `request_access`, `save_artifact`, `list_repositories`; `create_task` is gone. The default. |

- **Choosing.** `config.json` holds `protocolVersion` (2 when the field is missing, as in a
  file written before this version); `run --protocol-version 1` (or
  `TINFIRM_RUNNER_PROTOCOL_VERSION=1`) overrides it for that start. Everything else (epochs,
  leases, events, heartbeats, capacity, reconnects) is the same in both versions. The default
  is 2 since the server accepts both versions (the restart's package 8); a server from before
  it speaks v1 only and refuses a v2 host, which then exits with the error below: start it
  with `--protocol-version 1` for such a server, or set `"protocolVersion": 1` in
  `config.json`. The current server assigns runs to v2 hosts only: a v1 host connects but takes
  no turns, and the server answers its v1 requests `REJECTED` / `RUN_NOT_ACTIVE`.
- **No silent downgrade.** A v2 server answers `hello` with `welcome.protocolVersion` (the
  host's version). A host started with version 2 that gets a `welcome` without the field, or
  with another version, sends `error` `UNSUPPORTED_PROTOCOL`, closes the connection with 1002
  and exits non-zero, naming the mismatch and the `--protocol-version` to use instead; it
  never goes on in v1 within a session. A v1 host ignores the field. A server that does not
  know the host's version refuses the `hello` with close code 1002, which is fatal too.
- **Frames of the other version** (a `run.ask.ack` for a v2 host, a `run.request.ack` for a
  v1 host) are answered with `error` `UNKNOWN_TYPE`, as the schemas demand. The host reads a
  `run.assign` of either shape (`task` or `workItem`), whatever version it speaks.
- **Isolation tiers.** With version 2 `hello.isolationTiers` holds the host's one level of
  isolation ([Isolation level](#isolation-level)): `machine`, `vm-jail`, `container`, or
  `gvisor` for a `runsc…` runtime. With version 1 the host reports `container` as before, and
  no `hardened` or `isolationStated`. A host of this release never sends `microvm`.
- **Optional features.** A v2 host lists in `hello.features` what it implements beyond v2, and
  the server sends a feature's frames only to a host that lists it. This host lists `run.steer`
  ([Steering](#steering-protocol-v2)), `session.bundle`
  ([Session bundles](#session-bundles-protocol-v2)), `run.git.revoke`
  ([Revoked git tokens](#revoked-git-tokens-protocol-v2)), `run.git.refresh`
  ([Git tokens that expire](#git-tokens-that-expire-protocol-v2)), with Docker or a VM per run,
  `run.skills` ([Skills](#skills-protocol-v2)), and with a VM per run `host.health`
  ([A driver that can make no VM](#a-vm-per-run)); a v1 host lists nothing. A feature that the host
  sends frames for, as `host.health`, is used only with a server that lists it in
  `welcome.features`.
- **Per-kind waits.** A `run.request` of kind `ASK`, `ACCESS`, `NETWORK`, `ARTIFACT` or `GIT_TOKEN` waits 30 s for
  its ack (as the v1 pairs do); a `TOOL` request waits 65 s, the server's tool timeout
  (60 s by default) plus 5 s. A request that still waits when the connection is lost is sent
  again, with the same `requestId`, after the next `welcome`; exactly one ack of the
  request's kind answers it, and a late or repeated ack is ignored.

Switching a fleet: [docs/operations/runner-protocol-v2.md](../docs/operations/runner-protocol-v2.md).

## Compatibility with the server

Three numbers, each moved by something else
([ADR-0109](../docs/adr/0109-runner-version-is-the-release-version.md)):

| Number | Where | What moves it | What the server does with it |
|---|---|---|---|
| The product version | `--version`, `hello.runnerVersion`, the release assets (`tinfirm-runner-host-<version>-<target>`) and the image (`ghcr.io/flowtivo/tinfirm-runner-host:v<version>`) | Every release: the workspace's `version` in `Cargo.toml` is the release's (`0.1.0-SNAPSHOT` between releases) | Stores and shows it on the host's page; decides nothing from it |
| The protocol version | `hello.protocolVersion` | A breaking change of the wire | Accepts 1 and 2 ([Protocol versions](#protocol-versions)); gives turns to hosts of 2 only |
| The features | `hello.features` | A new optional capability within protocol 2, whatever release ships it | Sends a feature's frames only to a host that lists it |

**This server** accepts protocol versions 1 and 2, and runs conversations on hosts of protocol 2.
It uses the features `run.steer`, `run.skills`, `session.bundle`, `run.git.revoke`, `run.git.refresh`,
`host.health`, `sandbox.bridge`, `sandbox.pool` and `sandbox.elastic` (the last two only with elastic runners, Pro and Enterprise,
[ADR-0114](../docs/adr/0114-the-secure-sandbox-is-in-the-core.md)). A host that lists none of them
still takes turns; it only misses what they add.

**Every host of protocol 2** has the always-on [model proxy](#model-proxy) and sends its own
token to the server's model relay (`X-Tinfirm-Runner-Token`). These are part of protocol 2, not
features: the server sends a run paid by a budget to any host of protocol 2, and the relay
refuses a model request without the host's token.

So **any runner host release that speaks a protocol the server supports works with it**: upgrade
hosts and server in either order, with one exception. **The level of isolation** changed the
values of `hello.isolationTiers` in this release ([Isolation level](#isolation-level)): a server
of this release refuses `microvm`, which a host of an earlier release with a VM per run or Kata
sends, and a server of an earlier release refuses `machine` and `vm-jail`, which a host of this
release with a VM per run sends. Such hosts and the server take this release together
([ADR-0113](../docs/adr/0113-levels-of-isolation-and-placement.md), decision 2, which amends
ADR-0109 for this one change); a host that reports `container` or `gvisor` works with either. A build from `main` or the `rolling` prerelease reports the
development version (`0.1.0-SNAPSHOT`) in `hello`; its `--version` adds what it was published
as, such as `0.1.0-SNAPSHOT (rolling-abc1234)` (`TINFIRM_RUNNER_BUILD` at build time).

## Directories

- **Config:** `$XDG_CONFIG_HOME/tinfirm-runner` (default `~/.config/tinfirm-runner`, also on
  macOS), mode 0700: `config.json` (server URL, host token and the
  [protocol version](#protocol-versions)), `credentials/<label>.json`
  and `state.json`, all mode 0600, and `run.lock`, which the active `run` holds (a file lock)
  and into which it writes its PID (decimal, then a newline; empty when no `run` holds it).
- **Data:** `$XDG_DATA_HOME/tinfirm-runner` (default `~/.local/share/tinfirm-runner`), mode
  0700:
  - `workspaces/<runId>`: the workspace of a running run, mounted at `/workspace`, with one
    clone per repository in `workspaces/<runId>/<dir>`;
  - `outputs/<runId>`: under protocol v2, the run's [`/output`](#output-protocol-v2), mounted
    writable there, empty when the run starts and deleted when the run ends (a failed run's
    goes into its kept workspace as `output`, see `failed-workspaces/`). Never inside the
    workspace or another directory the sandbox writes to: the host opens the directory before
    the sandbox starts and reads it back only while its path still names that directory, so
    nothing the agent does to `/workspace` moves what the host reads;
  - `mirrors/<key>.git`: a bare mirror per repository URL and principal (`<key>` is the first
    32 hex characters of SHA-256 over the URL and the SHA-256 of the git credentials the run
    fetches with, or over the URL alone for a public repository), which every run fetches
    before it clones from it. Two accounts or credentials never share a mirror, so objects
    that one fetched (a branch deleted since) never reach another's workspace; a rotated
    token starts a new mirror, and the old one is deleted when idle.
    Never mounted into a sandbox; mode 0700 (`doctor` warns otherwise). Its
    `tinfirm-mirror.json` records the repository URL (without userinfo, query or fragment,
    so never a credential) and when the mirror was created, last used by a run and last
    garbage-collected. Idle mirrors are deleted and the others garbage-collected
    ([Disk maintenance](#disk-maintenance));
  - `attachments/<runId>`: the files of a running run (`run.assign.attachments`: on the current
    server, its conversation's files), mounted read-only at `/attachments` and deleted when the
    run ends;
  - `failed-workspaces/`: the last 5 workspaces of failed runs, for inspection, each with its
    run's `/output` moved in as `output` (unless the agent left something at that name);
  - `homes/<agentId>/<workItemId>`: the agent's home (`/home/agent`), which keeps the runtime's
    session files so that a follow-up run can resume with `session/load`. `<workItemId>` is
    `run.assign.workItem.id` (`task.id` from a v1 server): on the current server the
    conversation's id, so each conversation has a home of its own. Next to it, outside the
    mount, `homes/<agentId>/<workItemId>.last-used` holds the time of the last run
    that used it, `homes/<agentId>/<workItemId>.skills` the mount points of
    [skills](#skills-protocol-v2) that the host created in the home for its last run, and
    `homes/<agentId>/<workItemId>.bundle` the SHA-256 of the
    [session bundle](#session-bundles-protocol-v2) its session folders were last stored as or
    restored from. Idle homes are deleted ([Disk maintenance](#disk-maintenance));
  - `skills/<sha256>/<name>`: a [skill](#skills-protocol-v2) unpacked from the zip with that
    SHA-256, mounted read-only into the runs that have it; next to it,
    `skills/<sha256>/<name>.last-used` holds the time of the last run that used it. Idle
    skills are deleted ([Disk maintenance](#disk-maintenance));
  - `codex-homes/<label>`: the dedicated `CODEX_HOME` of a Codex credential;
  - `public_suffix_list.dat` and `public_suffix_list.dat.fetched`: the [Public Suffix List](#public-suffix-list) the server sent, and when the server fetched it, so that a restart still refuses an older list (mode 0600).

## Model access

Each `run.assign` names who pays for the run's model in exactly one of two fields
([docs/PLATFORM.md §11.2](../docs/PLATFORM.md#112-model-access),
[ADR-0059](../docs/adr/0059-litellm-budgets-and-the-model-proxy.md)):

- **`credentialLabel`**: a credential of this host, added with `credential add`. The server sends
  it only to a personal host, for its owner's own work, when the payer chose that subscription
  (**Who pays** in a person's settings of an agent, or in a trigger's settings).
- **`modelAccess`**: the payer's budget, a LiteLLM key that the server keeps. The server holds no
  provider key and sends none: it sends its own model relay and a token for this run alone.

| `modelAccess` field | What the server sends |
|---|---|
| `format` | `openai` for Codex; `anthropic` for Claude Code, pi and OpenCode (LiteLLM translates to the model's provider) |
| `baseUrl` | The relay on the server's public URL: `<TINFIRM_PUBLIC_URL>/api/runner/v1/model/anthropic`, or `…/api/runner/v1/model/openai/v1` |
| `apiKey` | A one-run token, which the relay accepts only while the run is `RUNNING` on the host it was assigned to, with its epoch, and which is revoked when the run ends |

The relay forwards each request to the company's LiteLLM with the paying principal's key, which
never leaves the server, and streams the answer back. LiteLLM holds the company's provider
keys, the models each key may use and each key's budget: admins set budgets on the **Model access**
page, and the **Spend** page shows what was spent. Setting up LiteLLM, its providers and its
models: [docs/operations/litellm.md](../docs/operations/litellm.md).

Nothing of this is stored on this machine, and no `credential` command is needed. On this host a
run with `modelAccess` always goes through the [model proxy](#model-proxy): the runtime gets the
run's listener and a dummy key (`tinfirm-model-proxy-dummy-key`), and the proxy sends the run's
token, with this host's own token, to the relay; neither reaches the sandbox. The
server sends runs paid by a budget to any host of protocol 2: a company host takes
any member's, a personal host its owner's whose accounts are all the owner's own. A host that
takes them must reach the server's public URL over https.

### What the runtime gets

The sandbox gets the model access as each runtime's CLI reads it (`src/runtime_env.rs`). For a
run of the relay, "the gateway" in this table is the run's listener and "the key" the dummy key:

| Format | Runtime | Sandbox environment |
|---|---|---|
| `anthropic` | Claude Code | `ANTHROPIC_API_KEY`; with a gateway `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN` (the key, sent as a bearer token) and `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`; in every run, gateway or not, in `session/new` and `session/load` the `_meta` `{"claudeCode": {"options": {"disallowedTools": ["WebSearch"]}}}`, which `claude-agent-acp` passes to the Agent SDK: Claude Code's WebSearch is Anthropic's server-side `web_search` tool, which the model relay always refuses ([ADR-0108](../docs/adr/0108-no-provider-run-tools.md)); WebFetch fetches in the sandbox, through the egress proxy, and stays |
| `openai` | Codex | `OPENAI_API_KEY`, `DEFAULT_AUTH_REQUEST`; with a gateway also `OPENAI_BASE_URL`, and in `CODEX_CONFIG`, where Codex reads its base URL, the gateway as a provider of its own without WebSockets (`model_provider = "tinfirm"`, `base_url` the gateway, `env_key = "OPENAI_API_KEY"`, `wire_api = "responses"`, `supports_websockets = false`); in every run, gateway or not, a ChatGPT login too, `web_search = "disabled"` in `CODEX_CONFIG`, since no Tinfirm run may have the provider run a tool and the model relay always refuses one (ADR-0108). Without WebSockets because with its built-in OpenAI provider Codex first tries the Responses API over a WebSocket, which LiteLLM is not known to serve and the [model proxy](#model-proxy) refuses, for about 6 s per session, and writes a fallback warning into the agent's reply |
| `bedrock` | Claude Code | `CLAUDE_CODE_USE_BEDROCK=1`, `AWS_REGION`, `AWS_DEFAULT_REGION`, `AWS_BEARER_TOKEN_BEDROCK` (a Bedrock API key) or `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1` |
| every format | pi | The provider's variable (`ANTHROPIC_API_KEY`, or `ANTHROPIC_AUTH_TOKEN` for a gateway; `OPENAI_API_KEY`; the AWS variables; `GEMINI_API_KEY`; `OPENROUTER_API_KEY`; `MISTRAL_API_KEY`; `GROQ_API_KEY`; `XAI_API_KEY`; `DEEPSEEK_API_KEY`), `TINFIRM_MODEL_PROVIDER` (pi's provider ID), `TINFIRM_MODEL` (the agent's model), and for a gateway `TINFIRM_MODEL_BASE_URL` and `PI_OFFLINE=1` |
| every format | OpenCode | The provider's variable (as for pi; for `google` also `GOOGLE_GENERATIVE_AI_API_KEY`), and `OPENCODE_CONFIG_CONTENT`: only the provider enabled, the agent's model as `provider/model`, no updates or sharing, and a gateway's `baseURL` with `Authorization: Bearer {env:…}` |

The agent's model (`run.assign.agent.model`) goes to Claude Code as `ANTHROPIC_MODEL` and to
Codex as `model` in `CODEX_CONFIG`. For pi and OpenCode it is a model of the credential's
provider, with or without the provider in front (`claude-sonnet-4-5` or
`anthropic/claude-sonnet-4-5`); an OpenRouter model keeps its own slash
(`anthropic/claude-sonnet-4.5`). Through the relay it is a model name of the company's LiteLLM,
which the payer's key must allow. An API-key credential of this machine works the same way for pi
and OpenCode, as an `anthropic` or `openai` key without a gateway.

No runtime asks the model's provider to run a tool itself, in any run (ADR-0108): Claude Code's
WebSearch and Codex's web search are off whatever reaches the model, a gateway, a key of this
host, a login or Bedrock, as the table says. Without a gateway no relay would refuse them, so
the runtime's switch is the only guard there. OpenCode 2.0.29 and pi
1.0.4 send no provider-run tool by default: OpenCode's own web search calls its search
providers (Exa, Tavily, ...) from the sandbox, through the egress proxy, and pi has none, so
neither needs a switch. Agents search the web through a search MCP server the company registers,
which the Tinfirm server calls like any other system.

The agent's effort (`run.assign.agent.effort`, protocol v2: `LOW` … `MAX`; absent is the
runtime's default) goes to Claude Code as `CLAUDE_CODE_EFFORT_LEVEL`, to Codex as
`model_reasoning_effort` in `CODEX_CONFIG` and to pi as `TINFIRM_THINKING_LEVEL`, which
`tinfirm-pi-acp` passes as `--thinking`; each uses the nearest level its model offers. OpenCode
offers its levels per model as the session's thought-level config option, so the host sets that
option over ACP (`session/set_config_option`) to the level, or else the nearest one offered
(the lower one when two are as near), and logs what it set. Both keep a session's level when it
resumes: without an effort, the host returns a resumed OpenCode session's option to `default`,
and `tinfirm-pi-acp` starts a resumed pi session at pi's own default (`medium`). This applies to
every run, one paid by a subscription too.

A run with `modelAccess` takes a slot of the host but of no credential pool. When the payer's
budget is spent, LiteLLM refuses the requests: the run fails with `USAGE_LIMIT`, the host reports
the reset time when it knows one but sends no `credential.status`, and the server marks the
payer's budget spent and holds their turns until it resets or an admin raises it. A key the
gateway refuses (a 401, or a 403 for Claude Code) fails the run with `LOGIN_EXPIRED`. The token is
redacted like every credential, in that run.

### Runner-side compatibility the server does not use

`modelAccess` in the protocol can also carry a provider's own API key without a base URL
(`anthropic`, `openai`), a `bedrock` key with its region, and the `google`, `openrouter`,
`mistral`, `groq`, `xai` and `deepseek` keys of pi and OpenCode. Such a key would be in the
sandbox's environment, since the model proxy serves only a base URL, and the Tinfirm server never
sends one: it keeps no provider key. So `run` refuses model access without a base URL, whatever
its format, and an assignment whose format does not serve the agent's runtime, that has a base URL
outside `anthropic` and `openai`, or a region or access key ID outside `bedrock`
(`INVALID_ASSIGNMENT`). A personal host's own API keys (`credential add`) still go into the
sandbox's environment.

## Capacity: ceilings and Tinfirm settings

Two limits must each have a free slot before a run starts: the slots of the host and, for a
run with `credentialLabel`, the pool of its credential (agents have no run limit of their own).
For both, the machine owner sets **ceilings** on this machine; Tinfirm can set a lower number of
host slots ([ADR-0016](../docs/adr/0016-runner-capacity-settings.md); the rules: archived v1
CONVENTIONS §14.8, §15.7):

| Limit | Ceiling (on this machine) | Setting in Tinfirm |
|---|---|---|
| Host slots | `run --max-concurrent-runs N`, 1–16, default 2; up to 1000 for a VM per run (`--executor vm`, protocol 2) | **Runs at a time** on the host's card (`desiredMaxConcurrentRuns`): OWNER and ADMIN set it for a company host on **Runner hosts**, the owner for a personal host under **Accounts** → **Runner hosts** |
| Credential pool | `credential add … --pool N`, 1–16, default 3 | None: the server sends `host.config` with an empty `credentials` list, so each pool is its ceiling |

- `run` reads the ceilings when it starts and reports them in `hello`. Changing one needs a
  new start of `run` ([drain](#drain-and-upgrades) or restart).
- The server sends the settings in `host.config`, right after `welcome` and after every
  change, and the host applies them at once, without a restart. It uses
  `min(setting, ceiling)`, or the ceiling without a setting, and confirms the values with
  `host.config.applied`. A setting above the ceiling applies up to the ceiling: Tinfirm can
  lower use, never raise it above what the owner allows. The host also applies a credential's
  `desiredPoolSize` in `host.config.credentials`, which the protocol allows and the current
  server never sends.
- More slots take the next run. Fewer slots never cancel or pause a running run; the host
  refuses new runs until fewer run.
- Each `host.config` is the full state with a version. The host ignores an older version
  than the last one it applied on the same connection, and forgets the version when it
  reconnects. The settings live only in the host's memory; until the first `host.config` the
  ceilings apply (the server assigns nothing before the host has answered one).

To give room, start the host with generous ceilings (for example
`run --max-concurrent-runs 8`) and lower the number of runs at a time in Tinfirm.

## Drain and upgrades

SIGTERM and `Ctrl-C` cancel the active runs (they push their work in progress and report
`CANCELLED`). A **drain** lets them finish instead:

1. `run` gets SIGHUP, from `tinfirm-runner-host drain`, `kill -HUP <pid>` or systemd.
2. It tells the server that it drains (`host.config.applied` with `draining: true` and 0
   slots), and refuses every new run with the code `DRAINING`. The UI shows the host as
   draining, and queued runs wait for another host or for the restart.
3. The active runs go on as usual, with events, heartbeats, platform tools and reconnects,
   until they finish.
4. When no run is active and the server has every message (after reconnects if needed),
   `run` closes the connection with 1000 and exits with status 0.

A second SIGHUP changes nothing. SIGTERM or `Ctrl-C` during a drain cancels the runs that
are still active. `drain` finds `run` through the PID in `run.lock` and returns once the
process has ended; it refuses to signal anything when `run.lock` is held without a PID (by
`mirrors prune` or `doctor`, or by a `run` from before `drain` existed). A drain can take as long as the longest run (`maxWallClockSeconds`: the
agent's time limit of a turn, up to 1440 minutes, else 60 minutes by default). Closing the terminal of a foreground `run` sends SIGHUP too, so it
drains.

An upgrade without cancelled runs, by hand:

```sh
cargo install --path crates/tinfirm-runner-host      # the new binary
tinfirm-runner-host drain && tinfirm-runner-host run  # waits for the old `run` to exit
```

The new `run` [rebuilds the runner images](#rebuilds-after-an-upgrade) whose Dockerfiles the
new binary changed before it connects; no `images build` is needed.

With systemd, `systemctl reload` drains and systemd starts the new binary once `run` has
exited; `systemctl stop` and `restart` send SIGTERM, which still cancels the active runs:

```ini
[Unit]
Description=Tinfirm runner host
Wants=network-online.target
After=network-online.target docker.service

[Service]
User=tinfirm
ExecStart=/usr/local/bin/tinfirm-runner-host run --max-concurrent-runs 4
# Drain: the active runs finish, then `run` exits 0 ...
ExecReload=/bin/kill -HUP $MAINPID
# ... and systemd starts it again, with the binary that is installed now.
Restart=always
RestartSec=5
# SIGTERM cancels the runs and pushes their work in progress; give it time.
TimeoutStopSec=180

[Install]
WantedBy=multi-user.target
```

## Disk maintenance

Mirrors, session homes and cached skills would otherwise grow forever. `run` makes a maintenance pass one
minute after startup and then every 6 hours, and logs a JSON `info` event for every
deletion and gc (mirror key, repository URL without credentials, freed bytes).

- **Mirrors**, one at a time, each under its lock. Maintenance takes the lock only when no
  run holds it or waits for it; a mirror in use is skipped until the next pass.
  - A mirror that no run used for longer than `TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS`
    (default 30) is deleted: renamed to `mirrors/.tmp-delete-…` first, then removed, so a
    crash never leaves a half-deleted mirror under its own name (startup removes the
    leftovers). The next run on that repository creates the mirror again.
  - The other mirrors get `git gc --prune=now` (at the lowest CPU priority, with the host's
    hardened git settings: no hooks, no credential helpers, no replace objects) when it is
    due: 7 days after the last gc (or the creation) if a run fetched the mirror since, or at
    once when `git count-objects -v` shows 1000 loose objects or 20 packs. Pruning at once
    is safe because no workspace links to a mirror and the lock keeps fetches and clones
    out. A run that needs the mirror while its gc runs waits for the gc, and so does the
    shutdown of `run` (a second `Ctrl-C` exits at once).
  - With `TINFIRM_RUNNER_MIRROR_MAX_BYTES` (bytes, or a number with `K`, `M`, `G` or `T`,
    powers of 1024, e.g. `50G`; unset is no cap), the least recently used mirrors are
    deleted until the total is under the cap. A mirror in use is never deleted.
- **Session homes** that no run used for longer than `TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS`
  (default 30) are deleted the same way, never the home of an active run. A later
  follow-up run of that conversation gets a new, empty home: `session/load` fails there, so the
  host starts a new session with the full prompt.
- **Skills** in the cache (`skills/<sha256>/<name>`) that no run used for longer than
  `TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS` (default 14) are deleted the same way, never one
  that an active run mounts. A later run with that skill downloads it again.
- Mirrors, homes and skills from before this bookkeeping existed get their idle time counted
  from the first pass that sees them.

`mirrors list` shows the mirrors; `mirrors prune` makes the mirror part of a pass while
`run` is stopped (`--older-than-days N` overrides the idle limit, `--dry-run` only shows
what it would delete and collect). `doctor` shows the number of mirrors and their size.

## Runner images

| Image | Contents | Command |
|---|---|---|
| `tinfirm-runner-base:local` | `node:26.13.0-trixie-slim` (Debian 13) with Node 26, git, build-essential, ripgrep, jq, curl…; Debian's OpenJDK 21 (`JAVA_HOME=/usr/lib/jvm/default-jdk`), Maven 3.9.16, Gradle 9.8.0, Python 3 with pip, venv and uv 0.12.22; Docker Engine 29.8.2 with containerd, buildx and compose; user `agent` (uid/gid 1000, `HOME=/home/agent`) | `bash` |
| `tinfirm-runner-claude:local` | `@agentclientprotocol/claude-agent-acp@0.87.0`, which pins `@anthropic-ai/claude-agent-sdk@0.3.287`, which ships **Claude Code 2.1.287** as its platform binary | `claude-agent-acp` |
| `tinfirm-runner-codex:local` | `@agentclientprotocol/codex-acp@1.13.1` and `@openai/codex@0.156.1` (`CODEX_PATH`) | `codex-acp` |
| `tinfirm-runner-pi:local` | `@earendil-works/pi-coding-agent@1.0.4` and `tinfirm-pi-acp`, Tinfirm's ACP adapter for pi ([below](#the-pi-adapter)) | `tinfirm-pi-acp` |
| `tinfirm-runner-opencode:local` | `@opencode/cli@2.0.29` (OpenCode 2, [ADR-0061](../docs/adr/0061-opencode-2.md)), OpenCode's own ACP agent, which starts OpenCode's server as a child on 127.0.0.1 | `opencode acp` |
| `tinfirm-runner-multi:local` | Every runtime and adapter above, at the same versions, for ready VMs that serve every runtime (`run --pool-runtime any`, [ADR-0101](../docs/adr/0101-closing-the-elastic-runners-gaps.md)); `images build --runtime multi` builds it, `all` does not | none: the host names the run's adapter |

Every image's entrypoint is `tinfirm-sandbox-entrypoint` (POSIX sh, from the base image). It
adds a passwd entry for a random UID, starts dockerd when `TINFIRM_SANDBOX_DOCKER=1`, writes
the mirror settings of [`--sandbox-env`](#kata-containers-and-docker-in-the-sandbox) into the
agent's home, and execs the command, the ACP adapter. It writes only to stderr, which the
host shows as log lines of the run; stdout carries ACP, one JSON message per line. The host
takes a line of up to 8 MiB on stdout: a longer one ends the run as an agent that stopped
speaking ACP (`PROTOCOL_ERROR`, with the cause in the run's log). The complete lines the host
holds at once, from the splitter through the ACP client's queues to the run's loop, are bounded
too: 16 MiB of them (each line counting 512 bytes over its length), a line waiting for its
share before it is queued and giving it back as the loop takes it, so an agent whose messages
are not yet taken waits on its own pipe rather than growing the host. A stderr line is kept up
to 64 KiB; the rest of a longer one is dropped, and the line ends with `…[truncated N bytes]`.
The host reads the sandbox's output at the pace of the run's loop, holding at most 1024
chunks of it: an agent that writes faster waits on its own pipe (or, over the bridge, in the
sandbox), never in the host's memory without bound.

The images run under a random UID with GID 0 as well (OpenShift style): `/home/agent` and
`/workspace` are writable by group 0 (`chmod g=u`), and so is `/etc/passwd`, for the
entrypoint's entry. To add JDK 25, build with
`--build-arg JDK_PACKAGES="openjdk-21-jdk-headless openjdk-25-jdk-headless"` when your apt
sources carry `openjdk-25-jdk-headless` for Debian 13 (check with
`apt-cache policy openjdk-25-jdk-headless` in the base image), and add
`--build-arg JDK_DEFAULT=25` to make it `JAVA_HOME` and the `java` on `PATH`. Every JDK is in
`/usr/lib/jvm/java-<version>-openjdk-<arch>`, where Maven toolchains and Gradle find it.

### Image build arguments

`images build --build-arg KEY=VALUE` sets an `ARG` of the Dockerfiles, and only for the
images that declare it. An unknown name is refused before Docker is called. The arguments
stay in the image's history, so **never credentials**; a URL with a user name or password
is refused. For a host without internet access:

| Argument | Image | Default | Used for |
|---|---|---|---|
| `APT_MIRROR` | base | empty: `http://deb.debian.org` | Replaces `http://deb.debian.org` in Debian's apt sources; the mirror serves `/debian` and `/debian-security` below it. Use `http://`: `ca-certificates` is not installed yet, and apt checks the signatures |
| `DOCKER_APT_URL` | base | `https://download.docker.com/linux/debian` | Docker's apt repository for Debian, or a mirror of it. The packages are verified with Docker's release key, which is in the build context |
| `PIP_INDEX_URL` | base | `https://pypi.org/simple` | The package index for uv (checked against pinned hashes) |
| `MAVEN_REPOSITORY_URL` | base | `https://repo.maven.apache.org/maven2` | A repository with Maven Central's layout; Maven's distribution is downloaded from it (checked against a pinned SHA-512) |
| `GRADLE_DOWNLOAD_URL` | base | `https://services.gradle.org/distributions` | Where `gradle-<version>-bin.zip` is (checked against a pinned SHA-256) |
| `NPM_REGISTRY` | claude, codex, pi, opencode | `https://registry.npmjs.org/` | The npm registry for the ACP adapters and the CLIs. claude, codex, pi and opencode install exactly the packages of their committed `package-lock.json` with `npm ci --ignore-scripts` (each tarball checked against the lockfile's SHA-512, then `tinfirm-npm-check`; OpenCode's one install script, which links its native binary, runs by name afterwards), so a mirror cannot change what is installed. To move a version, change the `ARG` and the image's `package.json`, then run `npm install --package-lock-only --ignore-scripts` with npm 11 in its directory; for Claude Code, [Updating Claude Code](#updating-claude-code) |
| `JDK_PACKAGES`, `JDK_DEFAULT` | base | `openjdk-21-jdk-headless`, `21` | The JDKs and the default one (above) |

The base image's `FROM` image (`node:…-trixie-slim`, pinned by digest) comes from Docker Hub: give this
machine's dockerd a registry mirror (`registry-mirrors` in `/etc/docker/daemon.json`). The
other `ARG`s pin versions and checksums (`DOCKER_CE_VERSION`, `MAVEN_VERSION`,
`MAVEN_SHA512`, …); change them together.

```sh
tinfirm-runner-host images build \
  --build-arg APT_MIRROR=http://nexus.internal/repository \
  --build-arg DOCKER_APT_URL=https://nexus.internal/repository/docker-debian \
  --build-arg NPM_REGISTRY=https://nexus.internal/repository/npm/ \
  --build-arg PIP_INDEX_URL=https://nexus.internal/repository/pypi/simple \
  --build-arg MAVEN_REPOSITORY_URL=https://nexus.internal/repository/maven-central \
  --build-arg GRADLE_DOWNLOAD_URL=https://nexus.internal/repository/gradle-distributions
```

Claude Code is not installed separately: the Agent SDK's platform package (for example
`@anthropic-ai/claude-agent-sdk-linux-arm64`) contains the native `claude` binary, and the
adapter pins the SDK version exactly, so the adapter version fixes the Claude Code version.
The build checks the binary's SHA-256 and then its version, and links it to
`/usr/local/bin/claude`. Auto-update is off (`DISABLE_AUTOUPDATER=1`). The image label
`dev.tinfirm.runtime-version` carries the pinned versions, which the host reports to the
server in `hello`.

What the build checks before a runtime runs (threat model Q16):

1. `npm ci` checks each tarball against the SHA-512 `integrity` of the committed
   `package-lock.json`, and stops on a mismatch (`EINTEGRITY`).
2. `tinfirm-npm-check` (from the base image) checks that every package the lockfile locks for
   this platform is installed at its locked version. npm only warns (`seems to be corrupted`)
   and skips an **optional** package whose download fails its integrity check, and the native
   binaries of Claude Code, Codex and OpenCode are optional platform packages.
3. For Claude Code, the `claude` and `multi` images then compare the binary's SHA-256 with the
   entry for `CLAUDE_CODE_VERSION` and the platform (`linux-x64`, `linux-arm64`) in
   [`images/claude/claude-code.sha256`](images/claude/claude-code.sha256). Those values come
   from Anthropic's release manifest, whose signature is verified with Anthropic's release
   key, so they do not depend on the npm registry. A missing entry or a mismatch stops the
   build with an `ERROR:` line naming both values.

Each failure ends the build with an `ERROR: … (Q16)` line, and the host keeps the images it
had. Do not work around it with another mirror before you know why the download differs.

### Updating Claude Code

The Claude Code version moves with the adapter, `@agentclientprotocol/claude-agent-acp`,
which pins the Agent SDK, whose platform packages hold the binary. Moving it is a deliberate
change in one pull request:

1. Set the adapter's version in `images/claude/package.json` and `CLAUDE_AGENT_ACP_VERSION`
   in `images/claude/Dockerfile` and `images/multi/Dockerfile`; run
   `npm install --package-lock-only --ignore-scripts` with npm 11 in `images/claude`.
2. Find the Claude Code version that the new SDK bundles (`claude --version` of the binary in
   its platform package, step 4; the build checks it too) and set `CLAUDE_CODE_VERSION` in
   both Dockerfiles.
3. Verify Anthropic's manifest of that version and take its checksums
   ([Anthropic's instructions](https://code.claude.com/docs/en/setup#binary-integrity-and-code-signing)):

   ```sh
   VERSION=2.1.287
   export GNUPGHOME="$(mktemp -d)"
   curl -fsSL https://downloads.claude.ai/keys/claude-code.asc | gpg --import
   gpg --fingerprint security@anthropic.com   # must be 31DD DE24 DDFA B679 F42D 7BD2 BAA9 29FF 1A7E CACE
   REPO=https://downloads.claude.ai/claude-code-releases
   curl -fsSLO "$REPO/$VERSION/manifest.json"
   curl -fsSLO "$REPO/$VERSION/manifest.json.sig"
   gpg --verify manifest.json.sig manifest.json  # Good signature, primary key fingerprint as above
   jq -r '.platforms["linux-x64","linux-arm64"].checksum' manifest.json
   ```

4. Check that the locked npm tarballs hold the same binary, so the SDK and the manifest agree:

   ```sh
   SDK=0.3.287
   for arch in x64 arm64; do
     curl -fsSL -o "sdk-$arch.tgz" \
       "https://registry.npmjs.org/@anthropic-ai/claude-agent-sdk-linux-$arch/-/claude-agent-sdk-linux-$arch-$SDK.tgz"
     echo "sha512-$(openssl dgst -sha512 -binary "sdk-$arch.tgz" | base64 -w0)"  # = the lockfile's integrity
     tar -xzOf "sdk-$arch.tgz" package/claude | sha256sum                       # = the manifest's checksum
   done
   ```

5. Add a line `<sha256>  <version>  linux-x64` and one for `linux-arm64` to
   `images/claude/claude-code.sha256`, with the day you checked them in its comment, and remove
   the old version's lines. The tests (`cargo test -p tinfirm-runner-host images`) fail
   until both Dockerfiles' `CLAUDE_CODE_VERSION` has both entries.
6. Build the image once (`tinfirm-runner-host images build --runtime claude`): the log line
   `Claude Code <version> linux-<arch>: SHA-256 …, as pinned` shows the check passed.

Dependabot does not update the runtimes' lockfiles (`.github/dependabot.yml` covers only
the images' `FROM` lines). Were it set up for `images/claude`, its pull request would change
the lockfile alone: the tests would fail on the adapter's version, and the build on the
binary's checksum, until steps 1 to 5 are done by hand. The checksum file is the deliberate
step.

**Images with Claude Code are built on this machine and are never pushed to any registry.**
`images build` only builds and tags locally, and so do the pi and OpenCode
images.

### Rebuilds after an upgrade

`run` keeps the images of this host those of its binary
([ADR-0062](../docs/adr/0062-runner-host-rebuilds-its-images.md)), so an upgrade needs no
`images build`:

- Every image carries the label `dev.tinfirm.image-spec`, the SHA-256 of its Dockerfile, the
  files it copies, the build arguments it declares and, for a runtime image, the base's digest.
- `images build` records its build arguments (the last build's) and runtimes (every runtime
  built so far, and the image with every runtime once it was built) in `images.json` in the
  config directory, before it builds.
- `run`, after it took `run.lock` and before it connects, rebuilds each runtime image whose
  label is not the digest its binary would build, and each missing image of a recorded
  runtime, with the recorded build arguments; the base first when it is not current. An
  image that `TINFIRM_RUNNER_IMAGE_*` replaces, or a runtime that nobody built here, is left
  alone. The log says which images it rebuilds and why; the build output is at `debug`.
- An image that cannot be built (a registry or mirror is down) is logged as an error, and
  `run` goes on with the image there is, if any; the next start, or `images build`, tries
  again. When the base fails, no runtime image is built on the old one.
- SIGTERM, SIGINT or SIGHUP during the rebuild ends `run` at once: no run is active yet.
- `run --no-image-rebuild` (`TINFIRM_RUNNER_NO_IMAGE_REBUILD=1`) keeps the images as they
  are. `doctor` shows the names of the recorded build arguments and each image that `run` would
  rebuild.

A rebuild from Docker's cache takes seconds; a new base takes minutes, during which the host
is offline, as during a drain. Images built before the label exist are rebuilt once. A host
that used `--build-arg` before `images.json` existed has none recorded: run `images build`
once with the same arguments, or the first rebuild uses the defaults.

### The pi adapter

pi speaks ACP only through `pi-acp`, which passes no MCP servers to pi, reports no tokens and
ends a failed model request as a finished turn; `tinfirm-pi-acp`
([`images/pi/tinfirm-pi-acp.mjs`](images/pi/tinfirm-pi-acp.mjs),
[ADR-0041](../docs/adr/0041-pi-and-opencode-runtimes.md)) is Tinfirm's adapter over pi's
documented JSON protocol, `pi --mode rpc`:

- `session/new` and `session/load` write the run's MCP servers (the platform tools) to
  `~/.pi/agent/mcp.json` with `exposure: direct`, so the model sees `mcp__tinfirm__finish` and the
  rest like built-in tools, and start `pi --mode rpc --no-approve --session-id <id>`. The session
  lives in the agent's home, so a follow-up run resumes it; `--no-approve` ignores a repository's
  `.pi/` settings, extensions and MCP servers, which would wait for a trust decision.
- A gateway's base URL (`TINFIRM_MODEL_BASE_URL`) goes to `~/.pi/agent/models.json` as the
  provider's `baseUrl`, and is removed again when a later run has none.
- `session/prompt` maps pi's events (text and thinking deltas, tool executions, retries) to
  `session/update` until `agent_settled`, and answers with the turn's tokens from pi's session
  statistics; a `usage_update` carries the context and the cost. A refused key ends the prompt with
  `-32000` (`LOGIN_EXPIRED`), any other failed request with `-32603` and pi's message.
- `_session/steering` gives the running turn a message with pi's `steer`, which pi delivers after
  the turn's current tool calls; it answers `injected` once pi took the message from its steering
  queue, and `promptRequired` when no turn runs or the turn ended with the message still queued
  (it then clears pi's queue). `initialize` advertises it (`_meta.steering.supported`).
- `session/cancel` clears pi's steering queue and sends pi's `abort`; pi's stderr goes to the
  run's log.

Its tests run with `node --test runner/images/pi/test/` against a fake pi, and as part of
`cargo test` (`images::tests::the_pi_adapter_passes_its_tests`).

## Steering (protocol v2)

A person's message written while a run works reaches the agent's running turn, as in Claude Code
and Codex, instead of waiting for the next turn ([ADR-0060](../docs/adr/0060-steering-a-running-turn.md)).
The host lists the feature `run.steer` in `hello.features`; the server then sends `run.steer`
(the run, its epoch, a `steerId` and the text it rendered) and the host answers each one with
exactly one `run.steer.ack`, a control message that goes out before the run's `run.finished`:

| When the steer arrives | What the host does | Ack |
|---|---|---|
| Before the agent's prompt is sent (the run clones, starts its sandbox, opens its session) | Appends the text to the prompt | `DELIVERED` once the prompt is sent |
| While the prompt runs, the agent advertises steering (`initialize`'s `_meta.steering.supported`: claude-agent-acp, codex-acp, Tinfirm's pi adapter) | Sends the ACP extension request `_session/steering` with `idleBehavior: promptRequired`; the agent takes the text after its current step, before its next model request | `DELIVERED` for `injected`; `REFUSED` `NOT_RUNNING` for `promptRequired` or `startedNewTurn`; `NOT_SUPPORTED` for an unknown method; `FAILED` for another error |
| While the prompt runs, the agent has no steering (OpenCode) | Nothing | `REFUSED` `NOT_SUPPORTED` |
| After the prompt ended or a stop was asked, for another epoch, or for a run the host does not run | Nothing | `REFUSED` `NOT_RUNNING` |

A `steerId` the host answered gets the same ack again. Answers to `_session/steering` that are
still on their way when the prompt ends count for 0.5 s more; a refused message waits for the
next turn on the server. The run's log says what became of each message.

## Skills (protocol v2)

An agent's skills (Agent Skills format: a folder with a `SKILL.md`, and any scripts, references
or templates) come from its company's library at the version the agent pins
([ADR-0064](../docs/adr/0064-skills-library.md); the contract,
[§8](../docs/reviews/2026-10-15-skills-contract.md)). A host with Docker lists the feature
`run.skills` in `hello.features`; the server sends `run.assign.skills` only to such a host, and
the local test executor, which has no mounts, does not list it (a host that does not list it
rejects a run with skills with `INVALID_ASSIGNMENT`). Each skill names its `skillId`, `version`,
`name`, and the `sha256` and `sizeBytes` of its zip; at most 20, unique names, else the run is
rejected with `INVALID_ASSIGNMENT`.

After the attachments, for each skill the host:

1. Uses `skills/<sha256>/<name>` when it is there: no download.
2. Otherwise downloads the zip from
   `GET /api/runner/v1/runs/{runId}/skills/{skillId}/versions/{version}?epoch=` with the host
   token, checks its size and SHA-256 while it reads, and unpacks it into a temporary directory
   in `skills/`, renamed into place when complete. Unpacking refuses an entry that is a link
   or a device, an absolute path, `..`, a backslash, a path of more than 1,024 bytes or 8
   segments, more than 200 files, and more than 5 MiB in all, counted as the bytes come out
   rather than as the zip claims. Files are written `0644` and directories `0755`.
3. Creates the mount point in the session home through plain directories only, each created
   `0755` when missing and never a link or a file: a home that an earlier run left with a link
   on the way fails the run. Docker would otherwise create the mount point itself, owned by
   root, and the runtime could not write its own files in `~/.claude`. Before that it removes
   the empty mount points it created for the previous run of the home (listed in
   `homes/<agentId>/<workItemId>.skills`), so a skill the agent no longer has leaves nothing behind.
4. Mounts the skill read-only where the runtime reads a user's skills:

   | Runtime | Mount point |
   |---|---|
   | Claude Code | `/home/agent/.claude/skills/<name>` |
   | Codex, OpenCode, pi | `/home/agent/.agents/skills/<name>` |

   OpenCode also reads `~/.claude/skills`; the host sets `OPENCODE_DISABLE_CLAUDE_CODE_SKILLS=1`
   for it, which OpenCode 1 reads and OpenCode 2 ignores (both read `~/.agents/skills`). A LOG
   event names the skills and versions the run got.

A skill the host cannot provide (the download, the size or hash, the unpack, the mount point)
fails the run with `INFRA` before the agent starts, and the message names the skill. The server
checked and scanned every version for credentials when it was uploaded.

## Session bundles (protocol v2)

A session bundle carries a conversation's session to whichever company host runs its next turn
([ADR-0097](../docs/adr/0097-session-bundles.md)): another machine, or a VM per run, which keeps
no home at all. The host lists `session.bundle` in `hello.features`. The server sends
`run.assign.sessionBundle` only to a company host, and only while it keeps bundles: a store is
configured (`TINFIRM_SESSION_BUNDLES_STORE`) and its licence holds elastic runners, Pro's and
Enterprise's ([ADR-0100](../docs/adr/0100-elastic-runners-in-pro.md)); without them the host keeps
its homes on its own machine. A personal host never gets one.

At the end of a run whose assignment has `sessionBundle`, and that reported a session:

1. The host packs the runtime's session folders of the home, and nothing else, as a gzip tar
   of at most 64 MiB before and 32 MiB after compression:

   | Runtime | Folders |
   |---|---|
   | Claude Code | `~/.claude/projects`, `~/.claude/todos` |
   | Codex | `~/.codex/sessions` |
   | pi | `~/.pi/agent/sessions` |
   | OpenCode | `~/.local/share/opencode`, without its `log` and `bin` |

2. It does not upload a bundle that holds a credential value of the run (the model key, the git
   token, a subscription token), or one of a run that leaked a credential.
3. It uploads it to `POST /api/runner/v1/runs/{runId}/session-bundle?epoch=&sessionId=` with the
   host token. The server scans it for the company's vault secrets and the credential formats,
   seals it with a key of its own, and keeps the newest per conversation and agent.
4. `run.finished.sessionBundle` says what became of it: `STORED`, `TOO_LARGE`, `REFUSED`,
   `FAILED` or `NONE`. Without `STORED`, the server drops the conversation's earlier bundle,
   which no longer holds the session as it is.

Before the sandbox of a run whose `sessionBundle` names a `sha256` and `sizeBytes` starts, the
host:

1. Leaves the home as it is when `homes/<agentId>/<workItemId>.bundle` names that bundle: the home
   holds it already, or a later state of it from this host's own runs.
2. Otherwise downloads it from `GET` on the same path, checks its size and SHA-256, and unpacks
   it into a directory of its own beside the home with the rules of `/output`: directories and
   regular files only, at relative paths without `..`, at most 100,000 files and 64 MiB. Links
   and devices are left out.
3. Moves each of the runtime's session folders of it into the home, in place of the home's own.
   Anything else in the bundle is left out, and a `warn` LOG event names it.

A bundle that cannot be stored or restored is a log line, never a failed run: the turn starts a
new session (`resumeOutcome` `NO_SESSION`).

## What a run does

1. Accepts the run if the host does not drain; for a run with `credentialLabel`, knows the
   credential (one it started with, still in the store), the credential serves the runtime and
   is neither invalid nor in cooldown; `run.assign.workspaces` is valid (at most 20 repositories, each `dir` a valid
   and unique directory name `^[a-z0-9]+(-[a-z0-9]+)*$` of at most 64 characters, HTTPS
   repository URLs and `agent/…` branches), the image is there, no other run of the same
   work item (`workItem.id`: on the current server, the same conversation) is still active on
   this host, and the host and the credential's pool have a free
   slot (their [effective values](#capacity-ceilings-and-tinfirm-settings)). Otherwise it
   sends `run.rejected` with a reason and a `code`: `DRAINING`, `STALE_EPOCH`,
   `UNKNOWN_CREDENTIAL`, `CREDENTIAL_UNAVAILABLE`, `INVALID_ASSIGNMENT`, `IMAGE_MISSING`,
   `DOCKER_UNAVAILABLE`, `TASK_RUN_ACTIVE`, `NO_FREE_SLOT` or `POOL_FULL`. The last three
   are checked while the run takes its slot, so two assignments cannot both take the last
   one. The image check comes after that, in the run's own task, and so does the wait for
   the stale copy of a run assigned again with a newer epoch (the new execution starts once
   the old one has stopped): the connection never waits for Docker or for another run.
2. For each repository, in order (a run can have none):
   - fetches the host's mirror of the repository URL for that repository's credentials
     (creating it on first use; a token minted for the run names its credential's mirror,
     [below](#git-tokens-that-expire-protocol-v2)), with that
     repository's git token passed per command as `http.extraHeader` (through `GIT_CONFIG_*`
     environment variables, so it is neither in any `.git/config` nor in the process list).
     Any fetch failure, a 401 or 403 included, fails the run with `GIT_ERROR` before the
     container starts: a run whose token no longer works never gets stale content;
   - clones `/workspace/<dir>` from the mirror as a plain local copy (`--no-hardlinks`, no
     alternates, no network), sets `origin` to the repository URL, and checks out
     `agent/<KEY>` from `origin` or creates it from the default branch;
   - points the workspace's `origin` at the host's git proxy, so the agent can `git fetch`
     (see [Git in the sandbox](#git-in-the-sandbox)).
3. Downloads the run's files (`run.assign.attachments`, ADR-0014: on the current server, its
   conversation's files) from
   `GET /api/runner/v1/runs/{runId}/attachments/{id}?epoch=` with the host token, and checks
   each file's size and SHA-256. A file deleted meanwhile (404) is skipped with a LOG event;
   any other failure fails the run with `INFRA` before the agent starts. File names that are
   not a single plain path segment reject the run. The server has already scanned the files
   for credentials. Then, under protocol v2, it provides the run's
   [skills](#skills-protocol-v2) (`run.assign.skills`) from its cache, downloading those it
   does not hold, and creates their mount points in the session home; a skill it cannot
   provide fails the run with `INFRA`.
4. Starts the container `tinfirm-run-<runId>` under the `--sandbox-runtime`: user 1000:1000,
   all capabilities dropped, `no-new-privileges` (with `--sandbox-docker`, root and the
   settings of [Docker in the sandbox](#kata-containers-and-docker-in-the-sandbox) instead),
   4 GiB memory, 2 CPUs, 1024 pids, an init process, labels
   `dev.tinfirm.run-id` and `dev.tinfirm.runner-host`. Mounts: the workspace at
   `/workspace` (the agent's working directory, with the repositories in it), the session
   home at `/home/agent`, the attachments read-only at `/attachments` (also in
   `TINFIRM_ATTACHMENTS_DIR`) when the run has any, under protocol v2 the empty
   [`/output`](#output-protocol-v2) folder writable (also in `TINFIRM_OUTPUT_DIR`, with
   `TINFIRM_WORK_ITEM_KEY` and `TINFIRM_JOB_ID` next to `TINFIRM_RUN_ID` and
   `TINFIRM_TASK_KEY`; both keys hold `workItem.key`, on the current server the conversation's
   key, and the job id is the conversation's id), each [skill](#skills-protocol-v2) read-only at the runtime's skill
   directory (`/home/agent/.claude/skills/<name>`, or `/home/agent/.agents/skills/<name>`),
   and for Codex ChatGPT credentials the
   credential's `CODEX_HOME` at `/home/agent/.codex`, for a Claude login its
   `.credentials.json` at `/home/agent/.claude/.credentials.json` (read-write, over a plain
   empty file the host made there); never a mirror. An API key goes in as
   `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` (with pi's provider for
   pi, or OpenCode's configuration); a run with `modelAccess` gets the variables of
   [Model access](#model-access) instead, which for the server's relay hold the run's
   [model proxy](#model-proxy) and a dummy key, never the run's token.
   Git gets `safe.directory` for each `/workspace/<dir>`, and the `--sandbox-env` entries
   follow.
   Git credentials and the host token never enter the container; the agent fetches through
   the git proxy. Docker gets 600 s to remove a leftover container of the run and to create
   this one (other Docker calls 120 s): a daemon still deleting a large container, such as an
   earlier run's Docker disk, can hold a create for minutes. A create that takes longer fails
   the run with `INFRA` and says that the Docker daemon is busy or stuck.
5. Speaks ACP over the container's stdio (Docker attach): `initialize` without fs or
   terminal capabilities, `session/load` of the previous session (falls back to
   `session/new`), `session/prompt`. Both session requests get `cwd: "/workspace"`, the
   run's [platform tools](#platform-tools) in `mcpServers`, and for Claude Code through a
   gateway its disallowed WebSearch in `_meta` ([What the runtime gets](#what-the-runtime-gets)). Permission requests are
   auto-approved (`allow_once`). When the run does not resume a session, the host logs why
   and, under protocol v2, reports it in `run.finished.resumeOutcome`: `FIRST_RUN` (no
   previous session), `NOT_SUPPORTED` (the agent has no `session/load`), `NO_SESSION` (the
   session home is new for this run, as on a host that did not run the previous turn, or
   the agent does not know the id) or `LOAD_FAILED` (any other error, whose message goes into
   a `warn` LOG event).
   Under protocol v2, the person's messages for the run that came before the prompt
   ([Steering](#steering-protocol-v2)) are appended to it, and those that come while it runs
   go to the agent's steering. `session/prompt` carries one text block: the assignment's
   `prompt` (or its `resumePrompt`, below) as the server rendered it. Nothing else of the
   assignment goes to the agent as text, `workItem.title` included.
6. Streams events to the server: text coalesced every 250 ms, tool calls with inputs and
   outputs truncated to the schema limits, plans, cumulative usage, rate limits. At
   `maxWallClockSeconds` (the agent's time limit of a turn, else the server's
   `TINFIRM_RUN_MAX_WALL_CLOCK`) the host stops the run (`session/cancel`) with `TIME_LIMIT`
   under protocol v2 (`GUARD_STOPPED` under v1) and the message `the run reached its time
   limit (60 min)`; its work is pushed as for any stop and its session kept, so the server
   lets a retry go on from where it stopped. Guards stop the run (`session/cancel`, then
   `GUARD_STOPPED`) after more than `maxTurns` tool calls, after the same tool call 5 times in
   a row, or when the agent sends no ACP message at all for
   `TINFIRM_RUNNER_INACTIVITY_TIMEOUT` seconds (default 1200; a long tool call that still
   reports progress keeps the run alive). A model refusal
   (`stopReason: refusal`) finishes `FAILED` / `MODEL_REFUSED` and never pauses the
   credential.
7. Under protocol v2, before the git finalize: saves the files of `/output` as artifacts of
   the job, on the current server documents of the conversation
   ([`/output`](#output-protocol-v2)); `run.finished.artifacts` lists them.
8. For each repository: restores the clone-time `.git/config` (so `origin` is the real
   repository again), commits leftovers as `WIP: uncommitted changes from run <id>`, counts
   the commits ahead of the default branch, and scans the outgoing commits for credential
   values. Then, only if neither the agent's output nor any repository contained a
   credential, it pushes `HEAD:refs/heads/agent/<KEY>` to each repository that is ahead of
   its default branch, naming the repository URL explicitly, with a lease:
   `--force-with-lease=refs/heads/agent/<KEY>:<tip>`, where `<tip>` is the branch's tip in
   the mirror at clone time (empty when the branch did not exist then, which requires that
   it still does not). So the agent may rebase its branch onto the default branch, but when
   someone else pushed to the branch, created it or deleted it during the run, the push is
   refused: nothing is pushed or overwritten, and the reason is "the remote branch changed
   during the run". Never a plain force. `run --push-client host` pushes with the runner host's
   own client and the same lease, without the token in git's process tree, and has been checked
   against a git server run for the test and, for a new branch, GitHub alone
   ([The push](#the-push)). A read-only repository (`workspaces[].readOnly`,
   ADR-0017) is never pushed, and its commits are not scanned. A failed push does not stop the
   others; the run then fails with `GIT_ERROR` and a `failureMessage` that names the
   repositories. `run.finished` carries one git result per cloned repository (in
   `workspaces` order, `[]` without repositories), the summary (the agent's last message),
   the session id and the usage; under protocol v2 also `resumed` and `resumeOutcome`
   (step 5). GIT events carry the repository's `componentId` and `dir`.

Usage is cumulative for the whole run. `totalTokens` is exact; `inputTokens` counts the input
neither read from nor written to the prompt cache, `cachedInputTokens` the cache reads and
`cacheWriteTokens` the cache writes, so the four parts add up to the total (0 cache writes for
Codex, which reports none). Protocol v1 has no `cacheWriteTokens`: a v1 host counts the cache
writes in `inputTokens`, and the three parts add up to the total. codex-acp reports the split
only for the last model request of a turn (codex-acp #447), so for Codex the host sums the
per-request totals and splits the earlier requests like the last one; such totals carry
`estimated: true`. OpenCode 2's turns are exact; its cost is the session's, so a run that
resumed a session reports none. OpenCode 1, in an image not rebuilt yet, reports only the last
request of each turn, so its totals are a lower bound, marked `estimated`; the host tells the
two apart by `initialize`'s `agentInfo.version` ([ADR-0061](../docs/adr/0061-opencode-2.md)).
pi's turns are exact. A follow-up run sends `resumePrompt` only when
`session/load` of the previous session succeeded; otherwise it sends the full `prompt`.

Usage limits (`_meta["_claude/rateLimit"]` with status `rejected`, Codex
`usageLimitExceeded`, the CLI's limit message, or a model gateway's budget error) put the
credential in cooldown until the reset time and fail the run with `USAGE_LIMIT`; for a run
with `modelAccess` the host keeps no cooldown, and the server holds the payer's turns until the
budget resets ([Model access](#model-access)). Every credential value on the host (raw,
base64 at any alignment, URL-encoded) is replaced with `[REDACTED]` before anything leaves
the host; if the agent's own output contains one, or a commit to be pushed in any
repository does, the run stops with `CREDENTIAL_LEAK` and no repository is pushed.

If the connection drops, runs keep going; events are buffered (up to 10 000 per run) and
flushed after the reconnect, with the last 500 frames of each run written again. The backoff
grows from 1 s to 30 s, but stays at most 5 s while the host holds runs (runs that execute,
or whose end the server may not have yet), so they are back well within the server's
reconnect grace. The protocol has no ack of `run.finished`, so a finished run stays in
`hello.activeRuns`, and its end is written again after every reconnect, until that end was
written on a connection that then stayed up for `TINFIRM_RUNNER_FINISHED_RETENTION` seconds
(default 180). Time without a connection does not count: when the server goes down right
after the kernel took a `run.finished` its process never read, the host still lists the run
when the server is back, however long that takes, and the server ends the run with the
host's outcome instead of losing it (`HOST_RESTARTED`) and retrying a turn whose commits are
pushed. Keep the value above the server's reconnect grace (`TINFIRM_RUNNER_RECONNECT_GRACE`,
2 minutes) and the host's 45-s silence limit. Every frame stays far below the server's
1 MiB limit. On startup the host removes containers with its label that belong to no live
run. Close code 4401 (bad or revoked token), 4409 (another connection for this host
replaced this one), 1002 (unsupported protocol version) and 1008 (hello refused) are fatal:
the host stops its runs without pushing, does not reconnect, and exits non-zero.

`run.heartbeat` renews the leases of the active runs every `heartbeatIntervalSeconds` from
`welcome`, and at least three times per `leaseTtlSeconds`. The server also renews all of a
host's `RUNNING` runs on any frame from the host, keeps them for a 2-minute grace after a
disconnect or a server restart, and resumes a run it marked `LOST` after its lease expired
when the same host reports it again (in `hello`, `run.heartbeat` or `run.finished`;
docs/archive/v1/CONVENTIONS.md §15.1; the server's side:
[CONVENTIONS §15.6](../docs/CONVENTIONS.md#156-runs-and-turns)). Heartbeats never wait behind other traffic, also on a slow link: one
goes out right after `welcome`, before the events a reconnect sends again, and later ones go
out between two frames as soon as they are due. The host writes buffered frames in batches
(256 KiB or 250 ms) and reads the server's messages between two batches, keeps the TCP send
buffer small (256 KiB) so that a backlog waits in its own buffer, where heartbeats overtake
it, and handles server messages without waiting (Docker calls run in the background; `hello`
waits at most 5 s for Docker to report the runtime images). A write that does not finish
within 45 s ends the connection, and the host reconnects.

### Git in the sandbox

The agent can `git fetch` (and git's partial clone could fetch missing blobs) without any git
credentials in the sandbox. While the agent runs, the `origin` of each repository is
`http://<proxy host>:<port>/<route secret>/<repository path>`: a fetch-only proxy in the host
process, with one route and one route secret per repository.

- It forwards only `GET …/info/refs?service=git-upload-pack` and `POST …/git-upload-pack`
  to the route's repository, and adds that repository's `Authorization` header on the way
  out. The client's own `Authorization` and other headers are dropped.
- `git push` gets 403 with the reason "pushing from the sandbox is not allowed; the runner
  host pushes agent/<KEY> when the run ends". Other paths, and unknown secrets, get 404.
  Redirects are not followed.
- A route secret is 32 random bytes. It leads to one repository of the run only, and the
  host removes it when the run ends. Everything that leaves the host has it replaced with
  `[REDACTED]`, but it is not a credential: `git fetch` prints the URL, and that is no
  `CREDENTIAL_LEAK`.
- One listener serves all runs, and also the [platform tools](#platform-tools), so every run
  needs it. The first run starts it, on a random port. With `--sandbox-bridge` or a
  [VM per run](#a-vm-per-run) this host binds no listener for it: the sandbox reaches it through a
  tunnel of its bridge, at port 18761 of its own loopback, to its
  [front process](#the-reader-and-the-front): the front reads the sandbox's requests (the route
  secret, the path and the method, the headers within the bounds below, the body's framing)
  and hands `run` the route's number, the request's head and its body; `run` checks it again,
  reads the route's token, makes the request and passes the answer back. The front never
  holds a token. The rules and limits are the same either way.
- A request's head is at most 64 headers, each name at most 64 bytes and each value at most
  8 KiB, 24 KiB in all (`431` above), with a query of at most 4 KiB (`414`). With Docker Engine on Linux it listens on the gateway IP of the sandbox
  network (`docker0`, usually `172.17.0.1`). With Docker Desktop (and on macOS in general)
  it listens on `127.0.0.1`, which containers reach as `host.docker.internal`.
- `TINFIRM_RUNNER_GIT_PROXY_BIND` sets the listen address (an IP, optionally with a port)
  and `TINFIRM_RUNNER_GIT_PROXY_HOST` the host name or IP that the sandbox uses. Use them
  for rootless Docker or a VM-based engine that does not forward `host.docker.internal` to
  this machine, or to get a fixed port for a host firewall (ufw drops traffic from
  `docker0` unless you allow it; the [model proxy](#model-proxy)'s listeners on the same
  address take ports the system picks, so the firewall must admit those too). The address
  must be one of this machine's, and not `0.0.0.0` or `::`, or `run` refuses to start: the
  model proxy, which is always on, listens there. With `TINFIRM_RUNNER_SANDBOX_NETWORK=none`
  there is no proxy, and `run` refuses to start for the same reason.
- If the proxy cannot start, the run goes on with a
  warning, without fetch and without the platform tools; a run whose model access is a gateway fails instead, because its model
  proxy has no address to listen on. With the bridge, a sandbox whose front cannot start does not
  start either, and its run fails.
- A route reads its repository's token from the run's holder at each request, never a copy
  taken when the run started. Once Tinfirm has revoked the token
  ([below](#revoked-git-tokens-protocol-v2)), each new request gets 403 with Tinfirm's message
  as plain text, which git shows the agent (`fatal: unable to access …: The requested URL
  returned error: 403`, after the message). A request already forwarded finishes. With the
  bridge it is `run`, not the front, that reads the token at each request, so a revocation
  between two requests of the same sandbox refuses the second.

### Revoked git tokens (protocol v2)

Under protocol v2 the host lists the feature `run.git.revoke` in `hello.features`
([ADR-0116](../docs/adr/0116-short-lived-and-revocable-git-tokens.md)). Tinfirm sends
`run.git.revoke` when the run may no longer use a repository's git token: the conversation's
account was disconnected or needs a new sign-in, a credential was disabled, a grant or a lending
was withdrawn, a holder was suspended, the system was paused or removed, and so on. The frame names
the repositories (`componentIds`), a reason code and a message; it has no ack, and Tinfirm sends
it again at every `hello` that lists the run, until the run ends, followed by `welcome.done`,
which says that every such frame of the runs the `hello` listed has been sent.

One holder per run keeps each repository's token; the host's git, the git proxy's routes and the
take-back record read it there when they use it. For a frame of the run's current epoch, for each
repository it names:

| What | After the revocation |
|---|---|
| The holder | Drops the token, and keeps the reason and the message |
| The git proxy | Answers each new fetch with 403 and the message ([Git in the sandbox](#git-in-the-sandbox)). A fetch already under way finishes |
| A clone not yet made | `cannot clone <repository>: <message>`, `GIT_ERROR` |
| The mirror fetch before the push | Not attempted: the credential scan compares with the refs at clone time |
| The push | Not attempted. A repository with commits ahead fails the run with `GIT_ERROR` and `git failed in <repository>: not pushed: <message>`, its git result `pushed: false` with the commits counted; its commits stay only in a kept failed workspace. A repository without commits fails nothing |
| The take-back record | Written again without the token ([Sandbox bridge](#sandbox-bridge)) |
| The redaction | The token stays on the run's list until the run ends: what was revealed stays redacted, and the agent printing it is still `CREDENTIAL_LEAK` |
| The run's events | A `LOG` event at `warn` with the message |

Once the holder has dropped the tokens and the take-back record is written again (a record that
is being made when the frame comes is made first, then written again without the token), the
host confirms the frame with `run.git.revoked` (`runId`, `epoch`, `componentIds`): it names every
repository of the frame that the run has, those revoked already included, so a frame that comes
twice is confirmed twice. It goes in the run's order with its events, so a confirmation written
while the host is disconnected, or lost with the connection, goes again after the next
`welcome`. A record that cannot be written leaves the frame unconfirmed; Tinfirm sends it again at
the next `hello`. A host that restarts between a revocation and its confirmation takes the run
back without the token, and confirms the frame that Tinfirm sends again at its `hello`.

The other repositories go on as before, and so does the agent: it works on without git for the
repositories revoked. A frame for another epoch, for a run the host does not hold, or one that
names none of the run's repositories, changes nothing, and is confirmed by nothing. A repository
revoked already keeps its first reason. A
cancelled run whose token was revoked ends `CANCELLED` as before, with the repository not pushed
and the reason in the log. A host of protocol 1 knows no such frame (`error` `UNKNOWN_TYPE`), and
Tinfirm sends it only to a host that lists the feature: such a run keeps its token until it ends.

### Git tokens that expire (protocol v2)

Under protocol v2 the host also lists `run.git.refresh`
([ADR-0116](../docs/adr/0116-short-lived-and-revocable-git-tokens.md)). A token that Tinfirm mints
for one run, such as a GitHub App's installation token, comes with `git.expiresAt` (at most an
hour away) and `git.mirrorKey`; any other token has neither and lives until the run ends.

- **When the host renews.** With a `run.request` of kind `GIT_TOKEN` naming every repository that
  is due: while the run works, when a token has 10 minutes or less left (the host looks every
  15 s); before the push at the run's end, when it has 20 minutes or less left; and for a run
  taken back after a restart, every token that expires, once the server has answered the
  `hello` whole (after `welcome.done`, however long that takes; a server without
  `run.git.revoke` in `welcome.features` never sent `expiresAt`, so such a run has none to
  renew). It asks at most
  once a minute per repository, and only while connected.
- **The answer.** DONE: each renewed token goes into the run's holder (so the git proxy's routes,
  the host's git and the take-back record use it from then on) and onto the run's redaction list,
  where the old one stays. A repository in `refused` with `GIT_REVOKED`, or a REJECTED
  `GIT_REVOKED` for the whole request, is revoked with the server's message, as by
  `run.git.revoke` ([above](#revoked-git-tokens-protocol-v2)). `GIT_HELD` (a kill switch holds the
  run's systems), `GIT_UNAVAILABLE`, an unknown code, no answer within 30 s or no connection: the
  token is kept, and the host asks again later.
- **A token that expires without a new one** cannot be used: the git proxy answers 403 with "the
  git token expired and Tinfirm could not renew it", which is also logged once as a warning, and
  the repository is not pushed (`GIT_ERROR`, `not pushed: …`). A renewal that comes later makes
  it usable again; nothing is revoked.
- **The mirror.** A token with `mirrorKey` names its mirror by the credential's key, not by the
  token, so the runs of one credential share a mirror per repository and two credentials still
  never do. Such a mirror is a `<key>.git` like any other, and maintenance deletes it when idle.
- `expiresAt` and `mirrorKey` come together; an assignment with one alone, or a key that is not 64
  lowercase hex characters, is rejected with `INVALID_ASSIGNMENT`.

### Platform tools

Every run gets the Tinfirm platform tools over MCP (docs/archive/v1/CONVENTIONS.md §15.6; under
[protocol v2](#protocol-versions) the renamed set of
[Platform tools under protocol v2](#platform-tools-under-protocol-v2)). They are served by
the host process, not by the sandbox, at `http://<proxy host>:<port>/<mcp secret>/mcp` on
the git proxy's listener (with the bridge, the sandbox's tunnel to its front, at port 18761
of its own loopback: the front reads the HTTP and hands `run` the body, at most 1 MiB, whose JSON
`run` reads and answers), and passed to the agent in ACP `session/new` and `session/load` as
`mcpServers: [{"type": "http", "name": "tinfirm", "url": …, "headers": []}]`. With protocol
v1 the six tools are the following; they serve a server from before protocol v2, since the
current server sends runs only to v2 hosts, and their texts speak of that server's tasks:

- `list_repositories`: the task's repositories (`name`, `path` `/workspace/<dir>`,
  `repoUrl`, `branch`, `defaultBranch`, `readOnly`) and the other repositories of the project that the
  agent can request (`name`, `repoUrl`, `pendingRequest`), from `run.assign`.
- `request_repository(name, reason)`: trims both (empty values and names over 100
  characters are an error result, a reason is cut to 2000 characters), answers a repository
  that the run already has with its path, and otherwise sends `run.repository_request` with
  a new `requestId` (the name matched to `requestableRepositories`, exactly first, then
  ignoring case, and the text redacted). It waits up to 30 s for the server's
  `run.repository_request.ack` and returns its `message` unchanged: `PENDING` as a normal
  result, `REJECTED` as an error result. Without a server connection, or without an answer in
  time, the result is an error that asks the agent to name the repository in its summary.
  After a reconnect within the 30 s the host sends the same frame again. The run is not held
  open: after a pending request the agent finishes its turn, and the server wakes it when a
  member decides.
- `create_task(title, description, repositories, assignee)` (v1 only): for work outside the
  task. Trims the values and cuts the redacted title to 200 and the
  description to 50 000 characters; an empty title, a value of the wrong type, a repository
  name or assignee over 100 characters, a blank assignee or more than 20 names are an error
  result, and nothing is sent.
  A repository of the run may be named by its `dir`; duplicate names (ignoring case) count
  once, and without `repositories` the server takes the run's task's. It sends
  `run.task_create` with a new `requestId` and waits for `run.task_create.ack` like
  `request_repository`: `CREATED` is a normal result, `REJECTED` an error result. The current
  server answers every v1 request `REJECTED` (`RUN_NOT_ACTIVE`): a v1 host has no runs there,
  and protocol v2 has no such tool.
- `upload_attachment(path, fileName, note)` ([ADR-0021](../docs/adr/0021-agent-attachments.md)):
  attaches a file the agent made to the task. `path` is absolute in the sandbox or relative
  to `/workspace`, and must lie under `/workspace` or `/home/agent`; `..` is refused. The host
  reads the file from the run's workspace or session home without following a symlink out of
  it (the path is opened with `O_NOFOLLOW`, resolved again, and must be the same file beneath
  the directory), refuses anything but a regular file and anything over 100 MiB, and refuses a
  file that contains a credential or a masked value in any encoding, so it never leaves the
  host. `fileName` (default: the file's own name) must be a plain file name; `note` is
  redacted and cut to 2000 characters. The host posts the file as `multipart/form-data` to
  `POST /api/runner/v1/runs/{runId}/attachments?epoch={epoch}` with its host token and returns
  the server's `message`: `STORED` as a normal result, `REJECTED` (a duplicate name, a limit,
  a credential found by the server's scan) as an error result. The server stores it with the
  task's files (archived v1 CONVENTIONS §15.6).
- `ask_human(question, options)` (archived v1 CONVENTIONS §15.6): asks a
  person. Trims the question (empty is an error result) and cuts the redacted text to 2000
  characters; `options` are at most 10 short answers of 1 to 100 characters, duplicates
  (ignoring case) count once, and anything else is an error result with nothing sent. It sends
  `run.ask` with a new `requestId` and waits for `run.ask.ack` like `request_repository`:
  `PENDING` (the question is posted on the task, and on its issue in the company's tracker) is
  a normal result that tells the agent to finish its turn; `REJECTED` an error result. The
  server wakes the agent with the answers once every open question is answered.
- `finish_run(outcome, summary, nextSteps, openQuestions)` (archived v1 CONVENTIONS §15.6): the agent's result,
  `COMPLETED` or `BLOCKED`. The host trims and redacts the texts, cuts the summary to 20 000 and
  each entry to 2000 characters, drops empty entries, and refuses more than 20 next steps or 10
  open questions, a value of the wrong type or a missing outcome or summary with an error
  result. It sends nothing then: it keeps the last call of the run, answers that the agent
  should stop, and `run.finished` carries it as `result`. `summary` stays the agent's last
  message.
- The transport is the part of MCP Streamable HTTP these tools need: `POST` with one
  JSON-RPC message (`initialize`, `notifications/initialized`, `ping`, `tools/list`,
  `tools/call`) answered with `application/json`, or `202` for notifications. There are no
  sessions and no server-sent events: `GET` is `405`. Other paths and unknown secrets are
  `404`; requests with an `Origin` header (browsers) are `403`.
- The MCP secret is 32 random bytes per run, masked like the git proxy's route secrets, and
  removed with the run. As every secret of the proxies, it is compared by its HMAC-SHA256 tag
  under a random key of the process, so the time of the answer tells nothing about it.
- An agent whose `initialize` result does not set `agentCapabilities.mcpCapabilities.http`,
  or a run without the listener (network `none`, or it failed to start), gets
  `mcpServers: []` and a `LOG` warning; the prompt then asks the agent to name the
  repository it needs in its summary.

### Platform tools under protocol v2

With [protocol v2](#protocol-versions) the MCP `tools/list` of a run is built per run: the
platform tools below, then the server-executed tools of `run.assign.tools[]`
(`{name, description, inputSchema}`, for example `jira_get` and `jira_search`), listed as the
server described them. Every request to the server is one `run.request {runId, epoch,
requestId, kind, payload}` frame, answered by one `run.request.ack {runId, requestId, status:
PENDING | DONE | REJECTED, payload?, code?, message?}`; the `requestId` is the idempotency key,
and the host returns the ack's `message` to the agent when the server gives one, or a text of
its own. The `instructions` and the descriptions speak of the work item, the job, the request,
documents under `/output` and questions that end the turn
([ADR-0036](../docs/adr/0036-questions-end-a-run-quietly.md)); never of tasks or backlogs. The
current server calls the work item and the job a conversation, and its own messages, which the
host passes on, use its words.

`run.assign.platformTools`, when present, names the platform tools the host may list in the
run (an allowlist of `ask`, `finish`, `request_access`, `request_network_access`,
`save_artifact`, `list_repositories`): the host lists a platform tool only when it is named
there and the host offers it (`request_network_access` still needs `--egress-proxy`);
`finish` and `list_repositories` are listed whatever the list says; a name the host does not
know is ignored; absent, every platform tool is listed. The `instructions` drop the sentences
of the tools left out and say what to do instead (decide within the request or finish
`BLOCKED`; work with what you have; `/output` only), and a call of a platform tool the run
does not list is an unknown tool (JSON-RPC `-32602`). The current server leaves the field
out, so every platform tool is listed, and checks each request itself.

- `list_repositories`: as in v1 (the requestable repositories are those `request_access`
  can ask for).
- `ask(question, options)`: the checks of `ask_human`; sends `run.request` kind `ASK` with
  `{question, options?}`. `PENDING` (the server stored a `QUESTION` decision, `payload.decisionId`)
  is a normal result that tells the agent to ask everything it needs, one question per call,
  and stop without a summary; `REJECTED` is an error result. The fallbacks without an answer
  are those of `ask_human`.
- `request_access(repository | tool, reason)`: exactly one of `repository` (a name from
  `list_repositories`; a repository of the workspace is refused at once) and `tool` (the
  host's description says a connection's name); sends kind `ACCESS` with
  `{repository?, tool?, reason}`. `PENDING` (an approval of the repository) is a normal result
  that tells the agent to stop when it has nothing else to do; `REJECTED` an error result;
  without an answer the agent names what it needs in its summary. The current server answers
  every `tool` request `REJECTED` / `TOOL_DENIED`: an agent's tools come from its definition.
- `request_network_access(host, reason)`: listed only with `--egress-proxy`. `host` is
  `HOST[:PORT]` (a name, `*.example.com` or an IP address; 443 unless given; no `:*`, scheme or
  path; refused at once otherwise); sends kind `NETWORK` with `{host, reason}`, the host in the
  form of the egress rule. `PENDING` (a decision) is a normal result that tells the agent to
  stop when it has nothing else to do; `REJECTED` an error result; the fallbacks without an
  answer are those of `request_access`. A member approves the host for the conversation or
  for the agent for good; it then arrives in `run.assign.allowedHosts` and is added to the run's egress
  rules with the model's hosts and `--egress-allow`. An entry that does not parse is logged
  as a warning in the run log and skipped.
- `save_artifact(path | url, title, note)`: a file (`path`, read and scanned like
  `upload_attachment`, named by `title` or its own name) is posted to
  `POST /api/runner/v1/runs/{runId}/attachments?epoch={epoch}&artifact=true&source=save_artifact`,
  which makes it a document of the run's conversation (a `DOCUMENT` output); a link (`url`,
  http(s) without a user name or password,
  at most 2000 characters; `title` at most 200, the URL when omitted) is sent as kind
  `ARTIFACT` with `{url, title}` and answered `DONE` with `payload.artifactId`. A link that
  holds a credential never leaves the host. Files the agent writes to `/output` are saved
  without a call ([`/output`](#output-protocol-v2)).
- `finish(outcome, summary, nextSteps, openQuestions)`: as `finish_run`; the answer says that
  Tinfirm posts the summary on the work item.
- A server-executed tool: a tool of a system, with the conversation's account on it, or one of
  the memory tools `remember`, `forget` and `recall` when the agent's memory is on
  ([ADR-0063](../docs/adr/0063-agent-memory.md)). The call's `arguments` (an object, every string
  redacted, at most 64 KiB as JSON) go as kind `TOOL` with `{tool, arguments}`; the server runs
  the call within its tool timeout and answers `DONE` with the MCP result
  `{content, structuredContent?, isError}`, which the host returns to the agent as sent;
  `PENDING` when the call waits for a member's approval (a tool whose mode for the agent is
  `ASK`, as writes are by default) or for its person to connect an account, which is a normal
  result with the server's message, or the host's own text, telling the agent to end its turn;
  or `REJECTED` (an unknown or denied tool, an account that is gone, a failed system, or a
  result above about 800 KiB: `RESULT_TOO_LARGE`, with a message that asks the agent to narrow
  the query), which is an error result with the server's message. Without an answer within 65 s the agent is
  told to narrow the query and try again. A tool of `run.assign.tools` with a name the schema forbids,
  one named like a platform tool, or a second one of a name is left out with a warning.
- The v1 names (`ask_human`, `finish_run`, `request_repository`, `create_task`,
  `upload_attachment`) are unknown tools under v2 (JSON-RPC `-32602`).

### `/output` (protocol v2)

A v2 run has a writable folder `/output` (the host directory `outputs/<runId>`, outside the
workspace, empty when the run starts; `TINFIRM_OUTPUT_DIR` names it in the sandbox) for the
documents it makes. The host opens that directory before the sandbox starts and keeps it
open. When the agent's turn has ended, before the git finalize and the cleanup of the
workspace, the host first checks that the path still names the directory it opened (not a
symlink, the same inode): an `/output` the agent replaced saves nothing, with one `warn` LOG
event (`/output was replaced during the run; nothing was saved`), and the run goes on. Then
it walks the folder (sorted by path, at most 32 levels deep, never through
a symlink) and saves each regular file as an artifact of the job, on the current server a
document of the conversation: it opens the file beneath
the folder (`secure_fs::open_beneath`: a symlink the agent planted leads nowhere, and a
folder that is itself a symlink is refused), refuses
one over 100 MiB, scans the content for credentials and masked values like an upload, posts
it to the attachments endpoint with `artifact=true&source=output` (`/output` is the run's
result channel, not a tool) and the path
relative to `/output` as the file name (`reports/q3.md`), and lists it in
`run.finished.artifacts[] {name, attachmentId,
sizeBytes}`. At most 200 files are saved. A file the scan refuses, one over the cap, one with
a name that is not a plain path segment, or one the server refuses is skipped and named in a
`LOG` event (`/output/env.txt was not saved: it holds a credential …`); the run goes on.
After a credential leak in the agent's output nothing of `/output` is saved, as nothing is
pushed. Under protocol v1 there is no `/output`.

## v0 security shortcuts

These are accepted risks ([threat model §7](../docs/threat-model.md#7-accepted-risks)). The last
column says what removes each one.

| Shortcut | Risk | Removed by |
|---|---|---|
| The sandbox network is open unless `--egress-proxy` is set | A prompt-injected agent can send data out | `--egress-proxy` ([ADR-0044](../docs/adr/0044-sandbox-egress-proxy.md)), then making it the default |
| The API keys of a personal host are passed into the sandbox env, and its logins are mounted there (a provider's own key in `modelAccess`, which the server never sends, is refused); a run paid by a budget holds only a one-run token, which stays on the host behind the [model proxy](#model-proxy) | Key exposure to the agent; no hard USD budget for a key of this machine | Paying with a budget through the server's relay ([ADR-0059](../docs/adr/0059-litellm-budgets-and-the-model-proxy.md)) |
| The Claude login's `.credentials.json` is mounted read-write ([ADR-0085](../docs/adr/0085-claude-login-on-the-runner-host.md)) | The agent can read the tokens; the host redacts their shape in what leaves the sandbox | Nothing planned: Claude Code renews the login itself, and the host must not hold the tokens |
| The host attaches to the container's stdio through Docker, unless `--sandbox-bridge` is set | Restarting the host process loses its runs, with the bridge too, since its session keys live in the host's memory; without the bridge the run's credentials are in the container's settings | The [sandbox bridge](#sandbox-bridge) (built, opt-in), then taking runs back after a restart |
| Tool permissions are auto-approved inside the sandbox (Codex runs in full-access mode) | The agent can run anything in its container | Policy-based approvals |
| The Codex Plus/Pro `auth.json` is mounted read-write, shared by the runs of its pool | The agent can read the refresh token | `chatgptAuthTokens` through the app-server adapter |
| The git token travels to the runner host in `run.assign` | Exposure on the host (never in the sandbox) | GitHub App with 1-hour scoped tokens |
| One server replica for the runner connections and SSE | No HA | LISTEN/NOTIFY fan-out |
| The platform MCP tools (`list_repositories`, `request_repository`, `create_task`, `upload_attachment`, `ask_human`, `finish_run`; under protocol v2 `ask`, `finish`, `request_access`, `request_network_access`, `save_artifact` and the server-executed tools) are served over plain HTTP on the git proxy listener, guarded only by the secret in the URL, unless `--sandbox-bridge` is set | A process that reaches the listener and learns a run's URL can request repositories or network hosts, create backlog tasks (v1), attach files or save artifacts, read the company's systems through the run's tools, ask members questions and set the run's result in that run's name (a member still decides) | The [sandbox bridge](#sandbox-bridge) (built, opt-in): the listener is on loopback, and each sandbox reaches it through its own bridge; then making it the default |

More things to know:

- **Docker socket access is root-equivalent** on the host. Run the host as a user you trust
  with that.
- **With `--sandbox-docker` the agent is root in its sandbox**, because it controls a Docker
  daemon there. Only a Kata VM may hold that (`run` refuses any other runtime), so root
  reaches that VM, not this machine
  ([ADR-0025](../docs/adr/0025-kata-sandbox-runtime.md)).
- **The agent controls the workspace, including `.git`.** Before the host runs git after the
  agent, it restores the `.git/config` written at clone time (which also points `origin`
  back at the real repository), removes hooks, alternates, grafts, `shallow`, commit-graphs,
  `commondir` and symlinks inside `.git`, and runs git with hooks, fsmonitor, credential
  helpers, signing, replace refs and commit-graphs disabled. The credential scan and the
  ahead count never trust the workspace's refs (the agent can point
  `refs/remotes/origin/*` anywhere): they exclude only commits reachable from the mirror's
  branches and tags, which the host controls, and the push names the repository URL instead
  of reading it from `.git/config`.
- **Linux hosts:** the container runs as uid 1000. If your uid is not 1000, the agent may not
  be able to write the bind-mounted workspace (`doctor` warns). Docker Desktop on macOS maps
  ownership and needs nothing.

## Development

```sh
cd runner
cargo test --workspace                          # unit, protocol schema, CLI and end-to-end tests
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --check
```

- The end-to-end tests run a fake server (tokio WebSocket) and a fake ACP agent
  (`crates/tinfirm-runner-host/tests/fake-agent/fake-agent.mjs`, plain Node) against local
  bare repositories (`file://`, or smart HTTP, below). The fake server answers `hello` like
  the real one: `welcome` (echoing the hello's protocol version), then `host.config`, and it
  waits for `host.config.applied`; it checks every frame against the schema of the hello's
  version. The fake agent also calls the platform tools through the MCP URL it gets in
  `session/new` or `session/load` (the `request-repo` scenario for v1, `platform-v2` for v2,
  which also writes files to `/output`). `HostSetup::v2()` and `AssignSpec::v2(..)` start a
  v2 host and send a v2 assignment. `--no-load-session` makes the fake agent offer no
  `session/load`, and `--fail-load-session` makes the load fail with an internal error
  (`local_executor_with`). `local_runs.rs` and `capacity.rs` (capacity settings, admission codes and
  drain) start the agent as a child process; `docker_runs.rs` builds the test image
  `tinfirm-runner-fake:test` and repeats the key scenarios in real containers, including the
  real binary receiving SIGTERM, SIGHUP and `drain`. The Docker tests skip themselves when
  Docker is not reachable, or with `TINFIRM_SKIP_DOCKER_TESTS=1`.
- `bridge_runs.rs` runs the agent over the [sandbox bridge](#sandbox-bridge) with the local test
  executor, and `vm_runs.rs` with the VM executor and the fake driver
  (`tests/fake-vm-driver/fake-driver.mjs`), whose "VMs" are this binary as `bridge` on a loopback
  address of their own; every run there starts cold. `TINFIRM_TEST_SANDBOX_BRIDGE=1` or
  `TINFIRM_TEST_VM_EXECUTOR=1` runs `local_runs.rs` again over the bridge or in such VMs. The paid
  features' tests are under `ee/runner/tinfirm-runner-host/tests` and run with the rest:
  `pool_runs.rs` (ready VMs) and `digitalocean_runs.rs` (DigitalOcean's driver and the feature
  `sandbox.elastic`).
- The git proxy tests (`tests/git_proxy.rs` and the `fetch` scenario in `local_runs.rs` and
  `docker_runs.rs`), the mirror tests (`tests/mirror.rs`) and the multi-repository runs serve
  repositories over smart HTTP with `git http-backend` and require the host's `Authorization`
  header, so every fetch from the agent has to go through the proxy.
- The connection's unit tests (`src/connection.rs`) measure the gaps between heartbeats
  while a 4 MiB backlog goes over a throttled in-memory link, and while a fake Docker API
  answers every call after 3 s.
- The maintenance tests (`tests/maintenance.rs`, and the session home scenario in
  `local_runs.rs`) use real mirrors and homes with explicit timestamps instead of waiting
  for days.
- `cargo test -p tinfirm-runner-host --test docker_runs -- --ignored real_images` smoke-tests
  the real runner images (after `images build`) with the sandbox network set to `none`: no
  request reaches a model vendor, and all credentials are fake. `-- --ignored real_pi_and_opencode`
  does the same for pi and OpenCode, with a provider's own keys: a gateway's run needs its model
  proxy, which `none` cuts off.
- `stale_images_are_rebuilt_and_a_failed_base_stops_the_runtime_images` in `docker_runs.rs`
  builds two small images in place of the base and a runtime image to check the rebuild of
  `run`: recorded images are built, only a changed image is rebuilt, and a base that fails
  leaves the runtime image as it was.
- The unit tests of `src/images.rs` run the base image's scripts with `sh`: `daemon.json` and
  the Maven, npm, pip and uv settings from the environment, refused values, and files the
  agent wrote itself. The part of the entrypoint that starts dockerd needs a Kata VM; check it
  with the steps in [`deploy/ansible/README.md`](../deploy/ansible/README.md#checks-after-the-first-install).
  `sh -n` and `shellcheck` cover the scripts' syntax.
- The unit tests of `src/sandbox.rs` check the container that `--sandbox-runtime` and
  `--sandbox-docker` produce, and `tests/cli.rs` the refusals of `run` and `images build`.
- Development-only environment variables: `TINFIRM_RUNNER_IMAGE_CLAUDE`,
  `TINFIRM_RUNNER_IMAGE_CODEX`, `TINFIRM_RUNNER_IMAGE_PI` and `TINFIRM_RUNNER_IMAGE_OPENCODE`
  override the runtime images,
  `TINFIRM_RUNNER_SANDBOX_NETWORK` sets the Docker network of the sandboxes (`run` refuses
  `none`, on which the always-on [model proxy](#model-proxy) cannot reach them), and
  `TINFIRM_RUNNER_ALLOW_INSECURE_REPOS=1` accepts `file://` and `http://` repositories.
