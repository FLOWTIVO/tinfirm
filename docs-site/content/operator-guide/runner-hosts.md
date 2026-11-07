# Install and run runner hosts

This page is for the people who run the machines on which agents work. A runner host is a machine with Docker that connects out to the Tinfirm server, takes runs, and runs each one in a sandbox of its own: it clones the repositories of the work, starts the agent in a container, streams its progress to the server, and pushes the agent's branch when the turn ends. Here you install one, run it as a service, choose how strongly its sandboxes are isolated, limit their network, and keep it upgraded and tidy.

Every command and setting is listed in [Runner host commands and settings](../reference/runner-host.md). Registering a host and setting its runs at a time in the app: [Company runner hosts](../admin-guide/runner-hosts.md).

## Company hosts and personal hosts

A host is a company host or a personal host, chosen when it is registered and never changed. The binary and the steps are the same; what the server sends it differs.

| | Company host | Personal host |
|---|---|---|
| Registered by | An Owner or Admin, on **Runner hosts**, **Register a runner host** | Any member, for themselves, on **Accounts**, **Runner hosts** |
| Companies | The one it was registered in | Every company its owner belongs to on this Tinfirm, over one connection: one registration serves them all, with shared slots, and follows the owner's memberships |
| Runs | Any member's turns paid by a budget, through the server's model relay | Only its owner's turns: those paid by a subscription on this host, and those paid by the owner's budget when every account the conversation uses is the owner's own |
| Credentials on the machine | None. A credential it reports is refused and not stored. | The owner's subscriptions (Claude, ChatGPT) and API keys, which serve only the owner's work |

## What a host needs

- Docker: Docker Engine on Linux, or Docker Desktop. Access to the Docker socket is root-equivalent: run the host as a user you trust with that.
- `git`.
- An outbound connection to the server's public URL over https. A host needs no inbound port.
- Disk for the runner images, the repository mirrors and the workspaces.
- On Linux, the agent runs as uid 1000. Without `--sandbox-bridge` the workspaces are directories of the host's user: run the host as uid 1000, or the agent may not write them (`doctor` warns). With the bridge they travel over it, and a company host's user needs no uid of the sandbox's.

Each Tinfirm release carries the binary for these platforms on its [GitHub Release](https://github.com/FLOWTIVO/tinfirm/releases), with `SHA256SUMS`, which lists every archive, its signature `SHA256SUMS.sig`, and `REVISION`, which names the commit. The current release is v1.0.0, whose archives are `tinfirm-runner-host-1.0.0-<platform>`. The prerelease `rolling` carries the same files for every commit of the development branch: a development channel, not a release.

| Archive | Platform |
|---|---|
| `tinfirm-runner-host-<version>-x86_64-unknown-linux-musl.tar.gz` | Linux x86_64, static: any distribution |
| `tinfirm-runner-host-<version>-aarch64-unknown-linux-musl.tar.gz` | Linux arm64, static |
| `tinfirm-runner-host-<version>-aarch64-apple-darwin.tar.gz` | macOS on Apple silicon |
| `tinfirm-runner-host-<version>-x86_64-pc-windows-msvc.zip` | Windows: planned |

`<version>` is `x.y.z` for a release `vx.y.z`, such as `1.0.0`, and `rolling-<short sha>` for `rolling`. Only the runner host is published, as these archives and as the image `ghcr.io/flowtivo/tinfirm-runner-host` (`:v1.0.0` for v1.0.0); the runner images, which contain Claude Code, are built on your machine and never pushed to a registry.

On Kubernetes, the Helm chart can run a company host for you, as a pod whose sandboxes are VMs, with no machine of its own and no registration in the app: [The runner pod](helm.md#the-runner-pod).

## Install

1. Download the archive for your platform, `SHA256SUMS` and `SHA256SUMS.sig` from a Tinfirm release, then check the signature of `SHA256SUMS` with FLOWTIVO's public key `cosign.pub` ([Verify your images](verify-images.md); for `rolling`, the rolling key `deploy/compose/auto-deploy/cosign-rolling.pub` of the repository), the archive against `SHA256SUMS`, and install it. For v1.0.0 on Linux x86_64:

    ```sh
    version=1.0.0
    target=x86_64-unknown-linux-musl
    base=https://github.com/FLOWTIVO/tinfirm/releases/download/v$version
    curl -fsSL -O "$base/SHA256SUMS" -O "$base/SHA256SUMS.sig" \
      -O "$base/tinfirm-runner-host-$version-$target.tar.gz"
    openssl base64 -d -A -in SHA256SUMS.sig -out SHA256SUMS.der
    openssl dgst -sha256 -verify cosign.pub -signature SHA256SUMS.der SHA256SUMS
    grep -- "-$target.tar.gz\$" SHA256SUMS | sha256sum -c -     # macOS: shasum -a 256 -c -
    tar -xzf tinfirm-runner-host-*-"$target".tar.gz
    mkdir -p ~/.local/bin
    install -m 755 tinfirm-runner-host-*-"$target"/tinfirm-runner-host ~/.local/bin/
    tinfirm-runner-host --version
    ```

    The macOS binary is not signed: a copy downloaded with a browser needs `xattr -d com.apple.quarantine` first. The archive also holds the licence of the binary and of the crates it is built with. From a checkout of Tinfirm's source, `make runner-install` builds and installs it instead, with Rust 1.89 or newer.

2. **Register the host** in the web app (the table above says where). The dialog shows the host's token, `tfr_…`, once, with the commands of the next steps.

3. **Log in** with the server's public URL (`TINFIRM_PUBLIC_URL`) and the token:

    ```sh
    tinfirm-runner-host login --server https://tinfirm.example.com --token tfr_…
    ```

    Leave out `--token` to type it without echo, or pipe it on standard input: on the command line it shows in the process list and the shell's history, and `login` warns about it. Plain `http://` is accepted only for `localhost`. Use the public URL and no other address of the server: the host sends its own token with model requests only to that origin.

4. **Personal host only:** add a subscription ([below](#subscriptions-on-a-personal-host)).

5. **Build the runner images.** The first build takes a few minutes; after an upgrade, `run` rebuilds what changed by itself.

    ```sh
    tinfirm-runner-host images build
    ```

6. **Check the setup**: directories and their modes, git, Docker and its runtimes, the images, the credentials, and the connection to the server, without calling a model. On a company host it warns that there are no credentials; that is expected.

    ```sh
    tinfirm-runner-host doctor
    ```

7. **Run**:

    ```sh
    tinfirm-runner-host run --max-concurrent-runs 4
    ```

    The host shows **Online** on its page in the web app. `Ctrl-C` or SIGTERM cancels the active runs, pushes their work in progress and exits; a second `Ctrl-C` exits at once. SIGHUP, or `tinfirm-runner-host drain` from another terminal, lets them finish instead ([Drain and upgrade](#drain-and-upgrade)). Only one `run` can serve a config directory at a time.

### Run it as a service

With systemd, `systemctl reload` drains and systemd starts the binary installed now once `run` has exited; `stop` and `restart` send SIGTERM, which cancels the active runs:

```ini title="/etc/systemd/system/tinfirm-runner-host.service"
[Unit]
Description=Tinfirm runner host
Wants=network-online.target
After=network-online.target docker.service

[Service]
User=tinfirm
ExecStart=/usr/local/bin/tinfirm-runner-host run --max-concurrent-runs 4
# Drain: the active runs finish, then `run` exits 0, and systemd starts it again.
ExecReload=/bin/kill -HUP $MAINPID
Restart=always
RestartSec=5
# SIGTERM cancels the runs and pushes their work in progress; give it time.
TimeoutStopSec=180

[Install]
WantedBy=multi-user.target
```

Settings that are not options of the unit's command go in its environment (`Environment=` or an `EnvironmentFile=`): the reference lists every `TINFIRM_RUNNER_*` variable. For Linux VMs with Kata Containers, the Ansible role in `deploy/ansible` of Tinfirm's source installs Docker, Kata, the binary and this unit, logs in and builds the images.

**A company host is a tier-0 asset.** It receives the git token and the prompt of every conversation it runs, so harden it like the server. The Ansible role does by default, and a unit you write yourself should too:

- `--egress-proxy` and `--sandbox-bridge` on the command line: the sandboxes reach only what the egress proxy allows, and the host's listeners only through each sandbox's own bridge;
- the service sandboxed by systemd: `NoNewPrivileges=yes`, `ProtectSystem=strict`, `ProtectHome=yes`, `PrivateTmp=yes`, and `ReadWritePaths=` the host's config and data directories (outside `/home`);
- a service user whose uid is not 1000, the sandbox's, when the bridge is on;
- every download checked: the release's `SHA256SUMS` against its signature `SHA256SUMS.sig` and FLOWTIVO's public key (`tinfirm_runner_host_public_key`, the key shipped with the role; `tinfirm_runner_host_allow_unsigned` admits a release without a signature), then the archive against `SHA256SUMS`, Kata's tarball against its SHA-256, Docker's repository key by its fingerprint.

The role's variables turn each of them off (`tinfirm_runner_host_egress_proxy`, `tinfirm_runner_host_sandbox_bridge`, `tinfirm_runner_host_uid`); `deploy/ansible/README.md` lists them.

## What a run does

1. **Accepts or refuses it.** The host refuses a run while it drains, when it or the credential's pool has no free slot, when an image is missing, or when the same conversation still has a run on it; the server then tries again.
2. **Clones the repositories.** It fetches its own mirror of each repository with that repository's token, then clones a plain copy into the run's workspace and checks out the branch `agent/<key>`. A fetch that fails, a `401` or `403` included, fails the run before the agent starts.
3. **Starts the sandbox** with the conversation's files, the agent's [skills](../user-guide/skills.md) and the agent's session from the previous turn, if the host has it, and speaks to the agent over its standard input and output. Skills are downloaded once per version, checked against their hash, and mounted read-only.
4. **Streams the work** to the server: text, tool calls, usage. The agent fetches through the host's fetch-only git proxy: it can never push, and git credentials never enter the sandbox.
5. **Stops the run** at the turn's time limit (the agent's, up to 1,440 minutes, else the server's default of 60), after more tool calls than the server allows, after the same tool call 5 times in a row, or after 20 minutes without any message from the agent (`TINFIRM_RUNNER_INACTIVITY_TIMEOUT`).
6. **Pushes the result.** It saves the files of `/output` as documents of the conversation, commits what the agent left uncommitted, scans the outgoing commits and the agent's output for credentials, and pushes `agent/<key>` to each repository it changed, never with a plain force: when someone else changed that branch during the run, the push is refused. A credential found anywhere stops the run with nothing pushed.

Every credential the host itself holds (its own token, the turn's git tokens, a subscription's login), raw, base64 or URL-encoded, is replaced with `[REDACTED]` before anything leaves the host; when the agent's output or a commit to push contains one, the turn stops with `CREDENTIAL_LEAK` and nothing is pushed. A secret the host does not know, such as one written in a repository, is not recognised: keep secrets out of prompts and repositories. When the connection drops, runs keep going, and their events are sent after the host reconnects.

**Skills need a current host.** A host delivers an agent's skills only when it runs with Docker and speaks runner protocol 2, the default; it tells the server so when it connects. The server sends a turn whose agent has skills only to such a host, never without its skills: while none of the connected hosts that may run it delivers them, the turn waits with **No host for its skills**. Upgrade older hosts. The server sends no turns at all to a host that speaks protocol 1.

## The sandbox

Each run gets a container `tinfirm-run-<run id>` with these limits, which are fixed:

| Limit | Value |
|---|---|
| Memory | 4 GiB, no swap |
| CPUs | 2 |
| Processes | 1,024 |
| User | 1000:1000, every capability dropped, `no-new-privileges` |

It mounts the workspace at `/workspace`, the agent's home at `/home/agent` (kept per conversation, so that the next turn resumes the session), the conversation's files read-only at `/attachments`, an empty writable `/output`, and the agent's skills read-only. It never mounts a mirror, and a sandbox is never `--privileged`.

### Isolation

By default a sandbox is a container under Docker's default runtime, runc, which shares the machine's kernel. `--sandbox-runtime <name>` picks another runtime that Docker knows; the host reports the tier by the runtime's name:

| Tier | Runtime | What it is |
|---|---|---|
| `container` | runc, Docker's default | A container on the machine's kernel |
| `gvisor` | a runtime whose name starts with `runsc` | gVisor's user-space kernel |
| `microvm` | a runtime whose name starts with `kata` | A Kata Containers microVM with its own kernel; needs Linux with KVM |

**Docker in the sandbox.** With `--sandbox-docker`, the agent gets its own Docker daemon in its sandbox, for Testcontainers, `docker compose` and image builds. That needs a container that is root with every capability, so `run` refuses it unless the runtime's name starts with `kata`: root then reaches the microVM, not the machine. The agent's Docker keeps its data on a sparse disk of 40 GB by default, and the sandbox's limits cover it and its containers.

**Install Kata by hand**, on a machine with `/dev/kvm`:

1. Install Docker Engine 23 or newer, and the Kata Containers static release unpacked to `/` (it lives in `/opt/kata`).
2. Copy `/opt/kata/share/defaults/kata-containers/configuration-clh.toml` (Cloud Hypervisor) to `/etc/kata-containers/configuration.toml`, load the modules `vhost`, `vhost_net` and `vhost_vsock`, and check with `/opt/kata/bin/kata-runtime check`.
3. Register the runtime in `/etc/docker/daemon.json` and restart Docker:

    ```json title="/etc/docker/daemon.json"
    {
      "runtimes": {
        "kata": {
          "runtimeType": "/opt/kata/bin/containerd-shim-kata-v2",
          "options": { "ConfigPath": "/etc/kata-containers/configuration.toml" }
        }
      }
    }
    ```

4. `docker run --rm --runtime kata alpine uname -r` prints a kernel other than the machine's, and `doctor` lists `kata` among Docker's runtimes.
5. Start `run --sandbox-runtime kata --sandbox-docker`. Its log line `runner host starting` shows the runtime and the isolation tier.

**Package mirrors.** `--sandbox-env` sets environment variables in every sandbox, such as the `TINFIRM_SANDBOX_*` mirrors for Maven, npm, pip and the agent's Docker, which the sandbox writes into the agent's settings at each start. Never put a credential there: the agent can read it, and it shows in `docker inspect`. The host refuses names that look like credentials, URLs with a user name or password, and variables it sets itself.

## The egress proxy

Without `--egress-proxy` a sandbox can connect to any host; the Ansible role turns the proxy on by default. With `--egress-proxy`, sandboxes run on an internal Docker network, `tinfirm-sandbox-egress`, and reach the network only through a proxy of the host, which lets a connection out only to:

- the agent's model provider, for a credential of a personal host (a budget's model requests go through the [model proxy](#the-model-proxy) instead);
- the package mirrors set with `--sandbox-env`;
- your `--egress-allow` entries: `HOST[:PORT]` (443 unless given, `:*` for every port), `*.example.com` for its subdomains, or an IP address;
- the hosts that a person approved for the conversation or the agent, when the agent asked for network access.

```sh
tinfirm-runner-host run --egress-proxy \
  --egress-allow registry.npmjs.org --egress-allow '*.maven.apache.org' \
  --egress-allow-private --egress-allow nexus.internal.example.com:8443
```

Loopback and link-local addresses, cloud metadata among them, are always refused; private addresses only with `--egress-allow-private`. A wildcard over a public suffix, such as `*.github.io`, is refused. A run's log lists what the proxy refused. The proxy limits where data can go, not what it is.

- It needs Docker Engine on Linux: with Docker Desktop, or any engine in a VM, `run` refuses it.
- Tools in the sandbox use `HTTP_PROXY` and `HTTPS_PROXY`; a tool that ignores them has no network. An upstream corporate proxy is not supported yet.
- With `--sandbox-docker`, the agent's Docker pulls through the proxy: allow your registry, or Docker Hub's hosts (`registry-1.docker.io`, `auth.docker.io`, `production.cloudfront.docker.com`).
- A sandbox can still connect to any service of the machine that listens on the sandbox network's gateway address: see [Firewalls](#firewalls).

## The model proxy

A run paid by a budget gets the address of the server's model relay and a token for that run alone. The host never puts that token into the sandbox: it starts a listener for the run that holds it, and the agent gets the listener's address and a dummy key. The listener forwards only the model API's paths, adds the token and the host's own token, and refuses everything else. This is always on in every runner host that speaks runner protocol 2, so the server sends runs paid by a budget to any of them. `--model-proxy` is a former option that does nothing; remove it from service files.

The listeners bind the address of the host's git proxy, each on a port the system picks:

| Setup | Listeners on | Sandboxes connect to |
|---|---|---|
| Docker Engine on Linux | The gateway of the sandbox network (`docker0`, usually `172.17.0.1`, or `tinfirm-sandbox-egress`) | That address |
| Docker Desktop, or an engine in a VM | `127.0.0.1` | `host.docker.internal` |
| `TINFIRM_RUNNER_GIT_PROXY_BIND` and `_HOST` set | That address | The host name you set |

`run` checks at its start that a listener can serve the sandboxes there, and refuses to start, naming what to change, when it cannot: for example with rootless Docker or a `macvlan` network, set the two variables. It cannot check a firewall: a run then fails at its first model request.

The relay must be reachable over https. When the server's public address is inside your network, start the host with `--egress-allow-private`. When the host runs on the same machine as the server, name the server's public https origin with `--model-proxy-local-gateway https://tinfirm.example.com`, and log the host in at that same origin.

## Subscriptions on a personal host

A member's own subscription pays for their own work on their own host. Add it on the host, then restart `run` (or drain): the host reports its credentials when it connects.

```sh
tinfirm-runner-host credential add claude --label claude-max       # Claude Pro or Max
tinfirm-runner-host credential add codex --label chatgpt           # ChatGPT Plus or Pro, for Codex
```

- `credential add claude` runs Claude Code's own sign-in into a directory of the host, not your `~/.claude`. Without a browser on the machine, open the printed address elsewhere and paste the code. The login stays there, Claude Code renews it, and each run that pays with it mounts it; the host holds no token. When it expires, run the command again.
- `credential add codex` runs `codex login` into a directory of its own; `--device-auth` works without a browser. Keep no copy of the login on another machine: ChatGPT's refresh tokens are single-use, and two copies break each other.
- `credential add anthropic-api-key` and `openai-api-key` store an API key, typed without echo. pi and OpenCode take only API keys.
- `--pool N`, 1 to 16 and 3 by default, is the most runs at once with that credential; the runs share the login.
- The sandbox user, uid 1000, reads and renews the logins: run a personal host as uid 1000.

The member then picks the subscription under **Who pays** in **My settings** of an agent: [My settings for an agent](../user-guide/my-settings.md). A trigger may pay with its author's subscription only where the company allows it in **Settings**. `credential list` shows the credentials' metadata, and `credential remove <label>` deletes one, which the server stops using at once.

## Capacity: ceilings and Runs at a time

Two limits must each have a free slot before a run starts: the host's slots and, for a subscription's run, the credential's pool.

| Limit | Ceiling, on the machine | In Tinfirm |
|---|---|---|
| Host slots | `run --max-concurrent-runs N`, 1 to 16, 2 by default | **Runs at a time** on the host's card: Owners and Admins for a company host, its owner for a personal one |
| Credential pool | `credential add … --pool N`, 1 to 16, 3 by default | None: each pool is its ceiling |

The host uses the lower of **Runs at a time** and its ceiling, or the ceiling when nothing is set: Tinfirm can lower use, never raise it. A change in Tinfirm applies at once, without a restart, and fewer slots never stop a running turn: the host takes no new one until fewer run. A new ceiling needs a new start of `run`. Start hosts with generous ceilings, such as `--max-concurrent-runs 8`, and lower the number in Tinfirm. Size the machine for its ceiling: each run may use 4 GiB of memory and 2 CPUs.

## Drain and upgrade

A **drain** lets the active runs finish instead of cancelling them:

1. `run` gets SIGHUP: from `tinfirm-runner-host drain`, `kill -HUP`, or `systemctl reload`.
2. It tells the server, which shows the host as **Draining** and sends its queued turns to another host or keeps them waiting; the host refuses every new run.
3. The active runs finish as usual. Then `run` closes the connection and exits with status 0.

A drain can last as long as the longest turn. SIGTERM or `Ctrl-C` during a drain cancels what is still running. Closing the terminal of a foreground `run` drains it too.

To **upgrade**, install the new binary in place of the old, then drain: `systemctl reload tinfirm-runner-host`, or by hand `tinfirm-runner-host drain && tinfirm-runner-host run`. The new `run` rebuilds the images whose Dockerfiles the new binary changed, with the build arguments `images build` recorded, before it connects: a rebuild from Docker's cache takes seconds, a new base image minutes, during which the host is offline. An image that cannot be built is logged, and `run` goes on with the one it has. `run --no-image-rebuild` keeps the images as they are. Upgrade the server before its hosts.

## Disk maintenance

`run` tidies its data directory one minute after it starts and then every 6 hours, and logs each deletion:

| What | Deleted when | Setting |
|---|---|---|
| Repository mirrors | Unused for 30 days; the least recently used first while over a size cap | `TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS`, `TINFIRM_RUNNER_MIRROR_MAX_BYTES` (such as `50G`; no cap by default) |
| Agents' homes, one per conversation | Unused for 30 days. A later turn of that conversation starts a new session with the full prompt. | `TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS` |
| Cached skills | Unused for 14 days | `TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS` |
| Workspaces of failed runs | Beyond the last 5 | None |

Mirrors in use are never deleted; the others are garbage-collected when due. `mirrors list` shows the mirrors and their size, and `mirrors prune` tidies them while `run` is stopped (`--dry-run` shows what it would do).

## Firewalls

- **Inbound:** a host needs no inbound port.
- **From the sandboxes:** they reach the host's git proxy, its platform tools and, for runs paid by a budget, the model proxy's listeners on the gateway address of the sandbox network, the latter on ports the system picks. With ufw or firewalld, allow traffic from `docker0` (and `tinfirm-sandbox-egress` with the egress proxy) to that address on every port. A rule for one fixed port leaves the model proxy closed, and runs paid by a budget fail.
- **Other services of the machine**, such as SSH or a metrics exporter, answer a sandbox when they listen on all interfaces. Bind them to other interfaces, or drop input from the sandbox bridges except for the runner host's own listeners.
- **Outbound:** the server's public URL over https, the git hosts of the company's repositories, and, for building images, Docker Hub and the package registries, or your mirrors of them ([Image build arguments](../reference/runner-host.md#image-build-arguments)).
