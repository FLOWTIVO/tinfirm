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
| `crates/tinfirm-runner-host` | The `tinfirm-runner-host` binary (and the library the tests drive) |
| `images/` | Dockerfiles of the runner images, embedded into the binary; the recipe of the sandbox VMs' image is `ee/runner/images/vm` |

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
([Runner images](#rebuilds-after-an-upgrade)).

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
tag=v0.1.0                          # or rolling
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
([ADR-0100](../docs/adr/0100-elastic-runners-in-pro.md)): the sandbox bridge, a VM per run, ready
VMs and session bundles. They are Pro's and Enterprise's: a server whose licence does not hold
them refuses a host started with `--sandbox-bridge` or `--executor vm` (`EDITION_REQUIRED`, and
the host stops), and hands out no session bundle. A host with Docker, as below, runs in every
edition.

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
   there instead.
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
   budget (runner 0.2.0 or later). A personal host's card lists the subscriptions it reported;
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

**Always on**, since runner host **0.2.0** (`tinfirm-runner-host --version`, and
`hello.runnerVersion` for the server): no option turns it off. The server cannot tell from the
protocol whether a host proxies, so no host may be able to put a gateway's key into the sandbox
([ADR-0059](../docs/adr/0059-litellm-budgets-and-the-model-proxy.md), decision 5), and the server
sends runs paid by a budget only to hosts of 0.2.0 or later
([package 2](../docs/reviews/2026-10-12-package-2-contract.md) §7; the host's page in the web app says
when a host is too old). `--model-proxy`
(`TINFIRM_RUNNER_MODEL_PROXY`), the opt-in of 0.1.0, is still accepted by 0.2.0 and 0.3.0 so that
service files keep working: it does nothing, not even with `0` or `false`, and `run` logs a warning
at start. It goes in a later release, which will refuse it as an unknown option, so remove it.

**The host's own token, since 0.3.0.** The gateway of a Tinfirm server is the server's model relay,
and the run's key is a one-run token. So that a copied run token is worth nothing away from this
host, the proxy also sends this host's runner token (the one of `login`) in the header
`x-tinfirm-runner-token`, and the relay takes the run's token only with the token of the host the
run was assigned to ([package 2](../docs/reviews/2026-10-12-package-2-contract.md), open point 6).
The proxy sends it only to a gateway at the **origin of this host's server URL** (scheme, host and
port of `login --server`, `wss` counting as `https`), where the WebSocket already takes it; any other
gateway never gets it, the sandbox never sees it, and a header of that name from the sandbox is
dropped like every other credential. A server's relay therefore needs the host logged in with the
server's public URL (`TINFIRM_PUBLIC_URL`): a relay at another origin gets no host token, the run's
log says so, and the server refuses the model requests of a host of 0.3.0 or later without it
(`401`). Hosts of 0.2.x send none; the server refuses them too, unless its operator turns
`TINFIRM_MODEL_RELAY_REQUIRE_HOST_TOKEN` off while they are upgraded.

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
- never logs the key or the host's token, and never passes them back: a gateway answer that contains either is cut off (`502` when it is in a header).

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
| `run --sandbox-runtime <name>` | `TINFIRM_RUNNER_SANDBOX_RUNTIME` | The Docker runtime of every sandbox, for example `kata`. Default: Docker's default (runc). `run` refuses a runtime that Docker does not know. The tier follows the name: `kata…` is `microvm`, `runsc…` is `gvisor`, anything else is `container` |
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
   `runner host starting` shows `sandbox_runtime`, `isolation_tier`, `sandbox_docker` and
   the names of the extra variables.

## Sandbox bridge

With `run --sandbox-bridge` (`TINFIRM_RUNNER_SANDBOX_BRIDGE`), the agent's adapter runs over the
sandbox bridge ([ADR-0095](../docs/adr/0095-sandbox-bridge.md)) instead of Docker attach. It is opt-in
for now, and Pro's and Enterprise's ([ADR-0100](../docs/adr/0100-elastic-runners-in-pro.md)): the host
lists `sandbox.bridge` in its `hello`, which needs protocol 2, and a server whose licence does not
hold elastic runners refuses it with `EDITION_REQUIRED`. Its code is under `ee/runner/`.

- **How it runs.** The container runs this host's binary, mounted read-only at
  `/opt/tinfirm/tinfirm-runner-host`, as `bridge`; the image's entrypoint still runs first. The bridge
  connects to this host's bridge listener (on the git proxy's address, on a port the system picks)
  over TLS 1.3, pinned to a certificate that the host creates when it starts. It presents the run's
  one-time ticket, and starts the image's command when the host sends `start`.
- **The run's credentials are not in the container's settings.** `docker inspect` shows only:
  - the bridge's own settings (`TINFIRM_BRIDGE_ADDR`, `_RUN`, the one-time `_TICKET`, `_PIN`);
  - the entrypoint's (`TINFIRM_SANDBOX_*`);
  - the proxy variables of the agent's own dockerd.

  Every other variable of the run, the credential among them, reaches the adapter in `start`.
- **The host's listeners are on loopback, and the sandbox reaches them through the bridge.** The git
  proxy and the platform tools, the model proxy and the egress proxy listen on 127.0.0.1 of this
  host, where no sandbox reaches them; only the bridge listener stays on the sandbox network's
  gateway. Before the agent starts, the bridge listens on the sandbox's own 127.0.0.1 at each
  listener's port and carries every connection there to that listener over the bridge (a tunnel), so
  `origin`, the MCP endpoint, the model's base URL and the proxy variables name the sandbox's
  loopback. The listeners' own checks do not change. With `TINFIRM_RUNNER_SANDBOX_NETWORK=host` the
  listeners take 127.0.0.2, and `TINFIRM_RUNNER_GIT_PROXY_BIND` and `_HOST` set where the bridge
  listener goes.
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
    `HEAD`. It fetches the bundle into its own clone, which the agent never reached, with every
    object checked, and pushes from there with the usual checks. The host never runs git in a `.git`
    that the agent wrote.
  - The session home and `/output` come back, as directories and regular files only (links and
    devices are left out), at most 2 GiB and 4 GiB; then the sandbox goes.
  - The images need `/attachments` and `/output` writable by the agent: the images of this version
    have them, and `run` rebuilds older ones ([Rebuilds after an upgrade](#rebuilds-after-an-upgrade)).
- **A lost connection resumes without losing output.** The adapter's output, the tunnels'
  connections and the files are kept until the host acknowledges them. When the bridge has had no connection for
  25 s, it stops the agent (SIGINT, then SIGTERM, then SIGKILL), and keeps the sandbox and its files
  for 15 minutes more, for this host after a restart (below). The host ends the run after 30 s
  without the bridge; a bridge that it no longer knows is refused, and ends at once.
- **What the host holds for a sandbox is bounded.** Each side of the link acknowledges only
  what it has handed on to its reader, and reads at most 8 MiB ahead of it; the adapter's
  output waits for the run's loop in a queue of 256 chunks. A run whose host falls behind, or
  a sandbox that floods, is held back through the bridge's credit and then TCP: the adapter's
  writes block in the sandbox, nothing is dropped, and nothing piles up in the host. A bridge
  that connects again and again while the run's loop has not taken the earlier connections'
  events (64 at most) is dropped without an answer, and tries again later.
- **A bridge that does not connect** within 180 s, or a container that ends before its bridge does,
  fails the run with `INFRA`. The container's last log lines (the entrypoint's) are in the run's log.
- **Requirements.**
  - The binary must be a static Linux executable for the sandboxes' architecture, as the release
    binaries for Linux are; `run` refuses any other.
  - `TINFIRM_RUNNER_BRIDGE_BINARY` names another binary, for example on macOS with Docker Desktop:
    the Linux release binary of the same version, in a directory that Docker Desktop shares.
  - A host firewall must admit the bridge's port from the sandbox network, as it must for the model
    proxy's listeners.
- **A restart of this host loses no work** ([ADR-0101](../docs/adr/0101-closing-the-elastic-runners-gaps.md),
  threat model R36). While a run's sandbox lives, this host keeps a record of it in its data
  directory (`runs/<run id>.json`, 0600): the assignment, the sandbox, the bridge's session key, the
  repositories as cloned and the agent's session; the bridge listener keeps its identity and port in
  `runs/bridge.json`. A `run` that starts after a crash, a `kill -9` or an upgrade finds the records
  and takes each run back before it connects: the run is in its `hello`, so Tinfirm keeps it; the
  listener starts with the same identity on the same port, and the sandbox's bridge comes back to
  it. The agent that the earlier process started stops, its work comes back and is pushed, its
  session is saved, and the run ends failed with `INFRA`, saying what was saved; the next message
  goes on from there. A sandbox that does not come back within 2 minutes is removed, and its work
  is lost.
  - The turn does not go on in place: the agent is stopped. Runs without the bridge, and a Docker
    host's runs when the machine itself restarts (Docker stops the containers), are lost.
  - The records hold the run's credentials (the repositories' tokens, a model access key) and the
    listener's private key while the sandbox lives, as the config directory holds the host token.
  - `run` must start with the same data directory and, for Docker, the same sandbox network, within
    the 15 minutes; `run --sandbox-bridge` must stay on.

## A VM per run

With `run --executor vm` (`TINFIRM_RUNNER_EXECUTOR=vm`), every run gets a fresh VM from a template
of a VM driver, and the VM is deleted when the run ends, never reused
([ADR-0096](../docs/adr/0096-a-vm-per-run.md)). This host needs no Docker and holds no cloud
credential, and a VM costs only while its run runs, or while it waits ready for one
([Ready VMs](#ready-vms)). Every agent runs over the [sandbox bridge](#sandbox-bridge), so a VM per
run is Pro's and Enterprise's too ([ADR-0100](../docs/adr/0100-elastic-runners-in-pro.md)).

- **How a run goes.**
  1. The driver creates a VM from the template, for no run in particular: its user data names none.
  2. Once the driver reports it running, this host dials the sandbox bridge in the VM, which listens
     on `--vm-port`. The VM's firewall lets in only this host, to that port, and lets nothing out.
  3. The bridge probes the VM's network from inside (below). The VM is ready now.
  4. A run takes a ready VM, or the next one that comes up. Its files go in, and the agent starts with
     its credentials, over the bridge, as in [Sandbox bridge](#sandbox-bridge).
  5. After the agent, the work comes back over the bridge, and the driver deletes the VM.
- **The user data holds no credential.** It holds the bridge's settings (the VM's id, its one-time
  ticket, this host's certificate pin), the runner image of a runtime, the sandbox's own settings
  (`--sandbox-env`), and the URL of the VM's own egress proxy listener. Anything in the VM can read it
  from the metadata service.
- **Always on:** the sandbox bridge, and the [egress proxy](#egress-proxy), which is the VM's only way
  to the network (`--egress-allow` works as with Docker). The tier is `microvm`.
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
  VM, and refuses to start when it finds a problem, or when the account may not have
  `--max-concurrent-runs` more VMs.
- **Leftovers.** After each connection to the server, this host lists its VMs and deletes those it does
  not hold: after a crash of this host, or a delete that failed. Each VM has the labels `tinfirm-host`
  (this host's id) and `tinfirm-vm` (the VM's own id); the run that takes it is in this host's log.
- **A VM that does not come up** (connected) within `--vm-start-timeout`, or that the provider fails,
  fails its run with `INFRA` and is deleted.
- **The VM image's build.** The VM image holds its own copy of this binary, which runs the bridge and
  built the runner images there. When a VM's bridge connects, it names that build (the version and a
  digest of the images' definitions, such as `0.3.0+images.1a2b3c4d5e6f`), and this host compares it
  with its own: `--vm-image-version warn` logs the difference once for each build, `refuse` deletes
  the VM and fails its run. Rebuild the VM image with this host's binary after every upgrade; this
  host's build is in its start's log line (`runner_build`).

| Option | Environment | Default | Meaning |
|---|---|---|---|
| `--executor docker\|vm` | `TINFIRM_RUNNER_EXECUTOR` | `docker` | Containers of this machine's Docker, or a VM per run |
| `--vm-driver COMMAND` | `TINFIRM_RUNNER_VM_DRIVER` | | `digitalocean`, this binary's own driver, or the command line of another (split at white space) |
| `--vm-template NAME` | `TINFIRM_RUNNER_VM_TEMPLATE` | | The driver's template: image, size, network and firewall |
| `--vm-port PORT` | `TINFIRM_RUNNER_VM_PORT` | `7443` | Where the bridge listens in the VM |
| `--vm-start-timeout SECONDS` | `TINFIRM_RUNNER_VM_START_TIMEOUT` | `900` | From the create to the bridge's first connection (30 to 3600) |
| `--vm-probe CHECK` | `TINFIRM_RUNNER_VM_PROBE` (one per line) | | `tcp:HOST:PORT`, `dns:NAME` or `http://HOST[:PORT]/PATH`, repeatable |
| `--vm-image-version warn\|refuse` | `TINFIRM_RUNNER_VM_IMAGE_VERSION` | `warn` | What to do with a VM whose image another build of the runner host made |

### Ready VMs

A VM takes the provider's boot, its own start and its dockerd before a run can begin: minutes. Ready
VMs wait for runs with all of that done, connected and probed, so a run that takes one starts in
seconds ([ADR-0098](../docs/adr/0098-ready-vms.md); the
[analysis](../docs/reviews/2026-10-15-kubernetes-runner-analysis.md#63-ready-sandboxes-a-start-in-seconds),
§6.3).

- **How many.** An owner or admin sets the schedule in Tinfirm, on this host's card (Runner hosts ›
  Ready sandboxes; [ADR-0099](../docs/adr/0099-pool-settings.md)): a count when no window applies, and
  weekly windows in a time zone, such as working days 08:00 to 18:00 with 2 ready. Until then the count
  is 0. The server evaluates the schedule and sends the count now and its next change in
  `host.config.pool`; this host follows the change on time by itself, and starts filling a window
  early by its cold start (the 90th percentile of its last 20, 5 minutes before the first, at most
  `--pool-lead-time-max`). `--pool-headroom N` keeps one ready VM per N busy ones. The operator's
  `--pool-max-ready` is the ceiling of both, and 0 turns ready VMs off. Ready VMs count against
  `--max-concurrent-runs` (and the server's lower value, if any): with all slots busy, none waits.
  While this host drains, after 15 minutes without the server, and when it stops, it keeps none.
- **What Tinfirm sees.** This host reports its ready, starting and busy VMs, its cold start and the
  runs of the last 24 hours that took a ready VM or started cold (`host.pool.status`), at most every 10
  seconds; the card shows them.
- **What a ready VM holds.** Nothing of a run: no prompt, no file, no credential. Its egress proxy
  listener, which its container's settings name, refuses every destination until a run takes the VM
  and binds its rules to it.
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
| `--pool-max-ready N` | `TINFIRM_RUNNER_POOL_MAX_READY` | `4` | The most ready VMs Tinfirm may ask for; 0 turns them off |
| `--pool-runtime claude\|codex\|pi\|opencode\|any` | `TINFIRM_RUNNER_POOL_RUNTIME` | `claude` | The runtime whose image the ready VMs run; `any`: the image with every runtime, for every run |
| `--pool-headroom BUSY` | `TINFIRM_RUNNER_POOL_HEADROOM` | `0` | One more ready VM per this many busy ones; 0 is off |
| `--pool-max-age MINUTES` | `TINFIRM_RUNNER_POOL_MAX_AGE` | `360` | A ready VM older than this is replaced (10 to 10080) |
| `--pool-cool-down MINUTES` | `TINFIRM_RUNNER_POOL_COOL_DOWN` | `10` | Ready VMs above the count for this long go (0 to 1440) |
| `--pool-lead-time-max MINUTES` | `TINFIRM_RUNNER_POOL_LEAD_TIME_MAX` | `30` | The longest a window starts filling before its time (0 to 240) |

**A driver** is a program that this host starts and talks to with JSON-RPC 2.0 on stdin and stdout,
one message per line: `hello`, `verify`, `create`, `describe`, `delete` and `list`
([ADR-0096](../docs/adr/0096-a-vm-per-run.md) has the methods). It holds the provider's credential, and
deletes only VMs that carry this host's label. You can write one for another provider; a driver must
keep the contract C1 to C7 of the
[analysis](../docs/reviews/2026-10-15-kubernetes-runner-analysis.md#62-any-provider-one-contract-a-driver-per-provider).

### In Kubernetes

The Helm chart runs one host as a pod with `runner.enabled` ([ADR-0101](../docs/adr/0101-closing-the-elastic-runners-gaps.md),
decision 8; [the operator guide](../docs-site/content/operator-guide/helm.md#the-runner-pod)): a company host
of one declared company, with `--executor vm --vm-driver digitalocean`, which the server registers
itself from `TINFIRM_COMPANIES_<KEY>_RUNNER_HOST_NAME` and `_TOKEN`. The pod runs the image
`ghcr.io/flowtivo/tinfirm-runner-host` (the last stage of `deploy/release/runner-musl.Dockerfile`:
this binary, git, the CA certificates and `kill`, user 10001), with `run --server <public URL>
--token-file <the mounted Secret>`, its data directory on a volume, so that a restarted pod takes its
runs back, and a `preStop` that drains it. The VM image, the VPC, the firewall and the API token are
the same as below; the firewall lets in the cluster's addresses.

### DigitalOcean

`--vm-driver digitalocean` creates a Droplet per run.

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
   packer build -var binary=./tinfirm-runner-host -var version=0.3.0 -var region=fra1 \
     ee/runner/images/vm/digitalocean.pkr.hcl
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
`tinfirm-sandbox`, `tinfirm-host:<this host's id>` and `tinfirm-vm:<the VM's id>` (a VM is created
for no run: [Ready VMs](#ready-vms)); this host's log names the run that takes it. It deletes a Droplet
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
| `doctor` | Checks the setup (directories and their modes, git, Docker, images, credentials, server); shows Docker's runtimes, the names of the recorded image build arguments, each image that `run` would rebuild, and the number of mirrors and their total size |
| `mirrors list` | Lists the repository mirrors: key, size, last use, last gc, repository URL |
| `mirrors prune [--older-than-days N] [--dry-run]` | Deletes idle mirrors and collects garbage in the others, like `run` does every 6 hours; refused while `run` is active (`--dry-run` only shows what it would do) |
| `run [--max-concurrent-runs N] [--protocol-version 1\|2] [--server <url> --token-file <file>] [--no-image-rebuild] [--sandbox-runtime <name>] [--sandbox-docker] [--sandbox-env KEY=VALUE]… [--sandbox-bridge] [--executor docker\|vm --vm-driver <command> --vm-template <name>]` | Connects and serves. N (1–16, default 2; up to 1000 with `--executor vm` and protocol 2) is the host's [ceiling](#capacity-ceilings-and-tinfirm-settings); other values are refused. `--protocol-version` (or `TINFIRM_RUNNER_PROTOCOL_VERSION`) picks the [runner protocol](#protocol-versions) for this start. Before it connects, it [rebuilds the images](#rebuilds-after-an-upgrade) that its binary would build otherwise, unless `--no-image-rebuild` (or `TINFIRM_RUNNER_NO_IMAGE_REBUILD`). `--upload-max-bytes` (or `TINFIRM_RUNNER_UPLOAD_MAX_BYTES`, default 26214400, the server's default `TINFIRM_ATTACHMENT_MAX_FILE_SIZE` of 25 MiB, at most 100 MiB) is the largest file an upload of the agent may have; a larger one is refused on its size before it is read, and at most 3 uploads read, scan and send a file at once. The sandbox options: [Kata Containers and Docker in the sandbox](#kata-containers-and-docker-in-the-sandbox); the VM executor's: [A VM per run](#a-vm-per-run). `--server` with `--token-file` (`TINFIRM_RUNNER_SERVER`, `TINFIRM_RUNNER_TOKEN_FILE`) stands in for `login`, for a host whose settings come from its environment ([In Kubernetes](#in-kubernetes)): the URL is checked as `login` checks it, and the token is read from the file once. Writes its PID into `run.lock` |
| `drain [--no-wait]` | Asks the `run` of this config directory to [drain](#drain-and-upgrades): sends SIGHUP to the PID in `run.lock` and waits until `run` has exited (`--no-wait` returns after the signal). Exits 0, also when no `run` is active ("Nothing to drain") |

Global options: `--config-dir`, `--data-dir` (or `TINFIRM_RUNNER_CONFIG_DIR`,
`TINFIRM_RUNNER_DATA_DIR`) and `--log <filter>` (`TINFIRM_RUNNER_LOG`, default `info`).
Logs are JSON lines on stderr and never contain credential values.

## Protocol versions

The host speaks one runner protocol version per connection and names it in
`hello.protocolVersion`:

| Version | Schema | What it is |
|---|---|---|
| 1 | [`protocol/runner-v1.schema.json`](../protocol/runner-v1.schema.json) | The frames and tools before the restart: `run.ask`, `run.repository_request`, `run.task_create` and their acks; the tools `ask_human`, `finish_run`, `request_repository`, `create_task`, `upload_attachment`, `list_repositories`. For a server from before protocol v2: the current server accepts a v1 host but sends it no runs. |
| 2 | [`protocol/runner-v2.schema.json`](../protocol/runner-v2.schema.json) | The protocol of the current server ([docs/PLATFORM.md §11.1](../docs/PLATFORM.md#111-the-runner)): one generic `run.request` / `run.request.ack` pair for every platform request (kinds `ASK`, `ACCESS`, `ARTIFACT`, `NETWORK`, `TOOL`), the server-executed tools of `run.assign.tools`, the writable [`/output`](#output-protocol-v2) folder and `run.finished.artifacts`, `run.finished.resumed` and `resumeOutcome`, the cache writes apart from the input in the usage (`cacheWriteTokens`), `run.assign.workItem` and `job` in place of `task` (the current server fills both with the conversation), `microvm` among the isolation tiers, and `welcome.protocolVersion`. The tools are renamed: `ask`, `finish`, `request_access`, `save_artifact`, `list_repositories`; `create_task` is gone. The default. |

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
- **Isolation tiers.** With version 2 `hello.isolationTiers` names the tier of the sandbox
  runtime: `microvm` for a `kata…` runtime, `gvisor` for `runsc…`, else `container`. With
  version 1 the host reports `container` as before: `microvm` is an additive change of the
  v1 schema that a server from before protocol v2 refuses.
- **Optional features.** A v2 host lists in `hello.features` what it implements beyond v2, and
  the server sends a feature's frames only to a host that lists it. This host lists `run.steer`
  ([Steering](#steering-protocol-v2)), `session.bundle`
  ([Session bundles](#session-bundles-protocol-v2)) and, with Docker, `run.skills`
  ([Skills](#skills-protocol-v2)); a v1 host lists nothing.
- **Per-kind waits.** A `run.request` of kind `ASK`, `ACCESS`, `NETWORK` or `ARTIFACT` waits 30 s for
  its ack (as the v1 pairs do); a `TOOL` request waits 65 s, the server's tool timeout
  (60 s by default) plus 5 s. A request that still waits when the connection is lost is sent
  again, with the same `requestId`, after the next `welcome`; exactly one ack of the
  request's kind answers it, and a late or repeated ack is ignored.

Switching a fleet: [docs/operations/runner-protocol-v2.md](../docs/operations/runner-protocol-v2.md).

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

The relay forwards each request to the company's LiteLLM (through its model port when LiteLLM is
bundled, [ADR-0069](../docs/adr/0069-company-model-gateway.md)) with the paying principal's key,
which never leaves the server, and streams the answer back. LiteLLM holds the company's provider
keys, the models each key may use and each key's budget: admins set budgets on the **Model access**
page, and the **Spend** page shows what was spent. Setting up LiteLLM, its providers and its
models: [docs/operations/litellm.md](../docs/operations/litellm.md).

Nothing of this is stored on this machine, and no `credential` command is needed. On this host a
run with `modelAccess` always goes through the [model proxy](#model-proxy): the runtime gets the
run's listener and a dummy key (`tinfirm-model-proxy-dummy-key`), and the proxy sends the run's
token, with this host's own token since 0.3.0, to the relay; neither reaches the sandbox. The
server sends runs paid by a budget only to hosts of runner 0.2.0 or later: a company host takes
any member's, a personal host its owner's whose accounts are all the owner's own. A host that
takes them must reach the server's public URL over https.

### What the runtime gets

The sandbox gets the model access as each runtime's CLI reads it (`src/runtime_env.rs`). For a
run of the relay, "the gateway" in this table is the run's listener and "the key" the dummy key:

| Format | Runtime | Sandbox environment |
|---|---|---|
| `anthropic` | Claude Code | `ANTHROPIC_API_KEY`; with a gateway `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN` (the key, sent as a bearer token) and `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1` |
| `openai` | Codex | `OPENAI_API_KEY`, `DEFAULT_AUTH_REQUEST`; with a gateway also `OPENAI_BASE_URL`, and in `CODEX_CONFIG`, where Codex reads its base URL, the gateway as a provider of its own without WebSockets (`model_provider = "tinfirm"`, `base_url` the gateway, `env_key = "OPENAI_API_KEY"`, `wire_api = "responses"`, `supports_websockets = false`), and `web_search = "disabled"`, since the model relay refuses a request whose tools the provider runs itself unless the company allows web search: with its built-in OpenAI provider Codex first tries the Responses API over a WebSocket, which LiteLLM is not known to serve and the [model proxy](#model-proxy) refuses, for about 6 s per session, and writes a fallback warning into the agent's reply |
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
| `NPM_REGISTRY` | claude, codex, pi, opencode | `https://registry.npmjs.org/` | The npm registry for the ACP adapters and the CLIs. claude, codex, pi and opencode install exactly the packages of their committed `package-lock.json` with `npm ci --ignore-scripts` (integrity-checked; OpenCode's one install script, which links its native binary, runs by name afterwards). To move a version, change the `ARG` and the image's `package.json`, then run `npm install --package-lock-only --ignore-scripts` with npm 11 in its directory |
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
The build checks the bundled version and links it to `/usr/local/bin/claude`. Auto-update is
off (`DISABLE_AUTOUPDATER=1`). The image label `dev.tinfirm.runtime-version` carries the
pinned versions, which the host reports to the server in `hello`.

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
     (creating it on first use), with that
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
   `session/new`), `session/prompt`. Both session requests get `cwd: "/workspace"` and the
   run's [platform tools](#platform-tools) in `mcpServers`. Permission requests are
   auto-approved (`allow_once`). When the run does not resume a session, the host logs why
   and, under protocol v2, reports it in `run.finished.resumeOutcome`: `FIRST_RUN` (no
   previous session), `NOT_SUPPORTED` (the agent has no `session/load`), `NO_SESSION` (the
   session home is new for this run, as on a host that did not run the previous turn, or
   the agent does not know the id) or `LOAD_FAILED` (any other error, whose message goes into
   a `warn` LOG event).
   Under protocol v2, the person's messages for the run that came before the prompt
   ([Steering](#steering-protocol-v2)) are appended to it, and those that come while it runs
   go to the agent's steering.
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
   during the run". Never a plain force. A read-only repository (`workspaces[].readOnly`,
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
  needs it. The first run starts it, on a random port. With Docker Engine on Linux it listens on the gateway IP of the sandbox
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
- If the proxy cannot start, the run goes on with a warning, without fetch and without the
  platform tools; a run whose model access is a gateway fails instead, because its model
  proxy has no address to listen on.

### Platform tools

Every run gets the Tinfirm platform tools over MCP (docs/archive/v1/CONVENTIONS.md §15.6; under
[protocol v2](#protocol-versions) the renamed set of
[Platform tools under protocol v2](#platform-tools-under-protocol-v2)). They are served by
the host process, not by the sandbox, at `http://<proxy host>:<port>/<mcp secret>/mcp` on
the git proxy's listener, and passed to the agent in ACP `session/new` and `session/load` as
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
