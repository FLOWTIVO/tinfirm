# Runner host commands and settings

Every command and option of `tinfirm-runner-host`, every `TINFIRM_RUNNER_*` environment variable, the host's directories, and the settings of the sandboxes. How to install and run a host: [Install and run runner hosts](../operator-guide/runner-hosts.md).

## Global options

These go with any command.

| Option | Variable | Default | Meaning |
|---|---|---|---|
| `--config-dir <dir>` | `TINFIRM_RUNNER_CONFIG_DIR` | `$XDG_CONFIG_HOME/tinfirm-runner`, else `~/.config/tinfirm-runner` (also on macOS) | The [config directory](#directories) |
| `--data-dir <dir>` | `TINFIRM_RUNNER_DATA_DIR` | `$XDG_DATA_HOME/tinfirm-runner`, else `~/.local/share/tinfirm-runner` | The [data directory](#directories) |
| `--log <filter>` | `TINFIRM_RUNNER_LOG` | `info` | The log filter, such as `debug` or `info,tinfirm_runner_host=debug`. Logs are JSON lines on stderr and never hold a credential. |
| `--version`, `--help` | | | The binary's version, and help for any command |

## Commands

### login

```sh
tinfirm-runner-host login --server <url> [--token <token>]
```

Stores the server URL and the host token (`tfr_…`) shown at registration in `config.json`. Without `--token`, it asks for the token without echo, or reads one line from standard input; prefer that, since a token on the command line shows in the process list and the shell's history (`login` warns when it gets one). `--server` takes `https://`; plain `http://` only for `localhost`, `127.0.0.1`, `::1` and names under `.localhost`. Use the server's public URL: the model proxy sends the host's own token only to that origin.

### credential

Personal hosts only: a company host's credentials are refused by the server.

| Command | Does |
|---|---|
| `credential add claude --label <label> [--pool <n>]` | A Claude Pro or Max login: runs Claude Code's own sign-in (`claude auth login`) into a directory of the host, with this machine's `claude`, else the one in the runner image. The host holds no token. |
| `credential add codex --label <label> [--device-auth] [--pool <n>]` | A ChatGPT Plus or Pro login for Codex: runs `codex login` into a directory of its own. `--device-auth` uses the device-code flow, for a machine without a browser. |
| `credential add anthropic-api-key --label <label> [--pool <n>]` | An Anthropic API key, typed without echo, for Claude Code, pi and OpenCode |
| `credential add openai-api-key --label <label> [--pool <n>]` | An OpenAI API key, for Codex, pi and OpenCode |
| `credential list` | The credentials' metadata, never a secret |
| `credential remove <label>` | Deletes a credential, its login directory included |

- `--label`: lowercase letters, digits and dashes, at most 63, starting with a letter or digit. It is the name the owner sees under **Who pays**.
- `--pool`: the most runs at once with this credential, 1 to 16, default 3. A ceiling: Tinfirm never raises it.
- `run` reads the credentials when it starts: an added credential or a changed pool takes effect at its next start. A removed one is refused at once.

### images build

```sh
tinfirm-runner-host images build [--runtime claude|codex|pi|opencode|all|multi] [--no-cache] [--build-arg KEY=VALUE]…
```

Builds `tinfirm-runner-base:local` and the runtime images from the Dockerfiles in the binary, and records the build arguments and the runtimes in `images.json`, so that `run` rebuilds the same images, with the same arguments, after an upgrade.

| Option | Default | Meaning |
|---|---|---|
| `--runtime` | `all` | The runtime images to build: `claude`, `codex`, `pi`, `opencode` or `all`; `multi`, the image with every runtime, for ready VMs that serve every runtime (Pro and Enterprise) |
| `--no-cache` | off | Build without Docker's cache |
| `--build-arg KEY=VALUE` | none | Repeatable. An argument of the Dockerfiles ([below](#image-build-arguments)); an unknown name is refused. Never a credential: the values stay in the image's history. |

### doctor

```sh
tinfirm-runner-host doctor
```

Checks the setup without calling a model: the directories and their modes, git, Docker and its runtimes, the images and which ones `run` would rebuild, the recorded build arguments, the credentials' presence and format, the mirrors' number and size, and the connection to the server. It skips the server check while `run` is active, since a second connection would replace the first. It exits non-zero when a check fails.

### mirrors

| Command | Does |
|---|---|
| `mirrors list` | The repository mirrors: key, size, last use, last garbage collection, repository URL |
| `mirrors prune [--older-than-days <n>] [--dry-run]` | Deletes idle mirrors and garbage-collects the others, as `run` does every 6 hours. `--older-than-days` overrides `TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS`; `--dry-run` only shows what it would do. Refused while `run` is active. |

### run

```sh
tinfirm-runner-host run [options]
```

Connects to the server and runs agents. Before it connects, it rebuilds the images that its binary would build otherwise, and writes its process id into `run.lock`: one `run` per config directory.

| Option | Variable | Default | Meaning |
|---|---|---|---|
| `--max-concurrent-runs <n>` | | `2` | The host's ceiling of runs at once, 1 to 16. **Runs at a time** in Tinfirm can lower it, never raise it. |
| `--protocol-version <1\|2>` | `TINFIRM_RUNNER_PROTOCOL_VERSION` | `protocolVersion` of `config.json`, else `2` | The runner protocol. The current server sends runs to version 2 hosts only; 1 is for a server from before version 2. |
| `--server <url>` | `TINFIRM_RUNNER_SERVER` | `config.json` | With `--token-file`, in place of `login`: the server's URL, https only, for a host whose settings come from its environment, such as the Helm chart's runner pod |
| `--token-file <file>` | `TINFIRM_RUNNER_TOKEN_FILE` | `config.json` | With `--server`: the file that holds the host's token, such as a mounted Secret. Read once, at the start. |
| `--no-image-rebuild` | `TINFIRM_RUNNER_NO_IMAGE_REBUILD` | off | Keep the images as they are, for hosts whose images are built otherwise |
| `--upload-max-bytes <n>` | `TINFIRM_RUNNER_UPLOAD_MAX_BYTES` | `26214400` (25 MiB) | The largest file an agent's upload may have (`save_artifact`, a file of `/output`), at most 100 MiB. A larger file is refused on its size before it is read, and the host reads, scans and sends at most 3 files at once. Set it to the server's `TINFIRM_ATTACHMENT_MAX_FILE_SIZE` when that differs from its default. |
| `--sandbox-runtime <name>` | `TINFIRM_RUNNER_SANDBOX_RUNTIME` | Docker's default (runc) | The Docker runtime of every sandbox, such as `kata`. Refused when Docker does not know it. |
| `--sandbox-docker` | `TINFIRM_RUNNER_SANDBOX_DOCKER` | off | The agent's own Docker daemon in the sandbox. Needs a runtime whose name starts with `kata`. |
| `--sandbox-env KEY=VALUE` | `TINFIRM_RUNNER_SANDBOX_ENV`, one per line | none | Repeatable. More environment of every sandbox, such as the [sandbox settings](#sandbox-settings). Never credentials. |
| `--egress-proxy` | `TINFIRM_RUNNER_EGRESS_PROXY` | off | Sandboxes reach the network only through the host's egress proxy. Needs Docker Engine on Linux. |
| `--egress-allow HOST[:PORT]` | `TINFIRM_RUNNER_EGRESS_ALLOW`, one per line | none | Repeatable, with `--egress-proxy`. A host sandboxes may reach: port 443 unless given, `:*` for every port; `*.example.com` for its subdomains; or an IP address. |
| `--egress-allow-private` | `TINFIRM_RUNNER_EGRESS_ALLOW_PRIVATE` | off | The model relay that the model proxy reaches, and the allowed hosts, may have private addresses (RFC 1918, `100.64.0.0/10`, `fc00::/7`). Loopback and link-local addresses stay refused. |
| `--model-proxy-local-gateway <origin>` | `TINFIRM_RUNNER_MODEL_PROXY_LOCAL_GATEWAY` | none | The one gateway on this machine, such as the Tinfirm server on the same host: its https origin, `https://HOST[:PORT]`, without a path. Runs whose gateway has exactly this origin may reach it on this machine's addresses. |
| `--model-proxy[=<bool>]` | `TINFIRM_RUNNER_MODEL_PROXY` | | Deprecated, no effect: the model proxy is always on. Logs a warning; a later release refuses it. |

Boolean variables take `true` or `1`, and `false` or `0`.

### drain

```sh
tinfirm-runner-host drain [--no-wait]
```

Asks the `run` of this config directory to drain: it sends SIGHUP to the process id in `run.lock`, then waits until `run` has exited. `--no-wait` returns right after the signal. It exits 0, also when no `run` is active (**Nothing to drain**).

## Signals and exits

| Event | What `run` does |
|---|---|
| SIGTERM, SIGINT (`Ctrl-C`) | Cancels the active runs, pushes their work in progress, reports them cancelled, and exits. A second one exits at once. |
| SIGHUP, `drain`, `systemctl reload` | Drains: takes no new run, lets the active ones finish, then exits with status 0. A second SIGHUP changes nothing. |
| SIGKILL, a crash, an upgrade that kills `run` | With elastic runners (Pro and Enterprise): the next `run` on the same data directory takes back the runs whose sandboxes still live. It stops their agents, pushes their work, saves their sessions and reports them failed (`INFRA`), so the next message goes on from there. Without them, the runs are lost. |
| The server closes with 4401 (bad or revoked token), 4409 (another connection of this host replaced this one), 1002 (unsupported protocol version) or 1008 (the server refused the host) | Stops its runs without pushing, does not reconnect, and exits non-zero |
| Any other lost connection | Keeps its runs going and reconnects, after 1 second growing to 30, at most 5 while it holds runs |

## Environment variables

Besides the variables of the options above:

| Variable | Default | Meaning |
|---|---|---|
| `TINFIRM_RUNNER_INACTIVITY_TIMEOUT` | `1200` | Seconds without any message from the agent after which a run is stopped. A long tool call that reports progress keeps it alive. |
| `TINFIRM_RUNNER_FINISHED_RETENTION` | `180` | Seconds a finished run is still reported after each reconnect, so that the server never misses its end. Keep it above the server's reconnect grace of 2 minutes. |
| `TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS` | `30` | Days without use after which a repository mirror is deleted |
| `TINFIRM_RUNNER_MIRROR_MAX_BYTES` | no cap | The most bytes of all mirrors, such as `50G` (`K`, `M`, `G`, `T` are powers of 1024). The least recently used are deleted beyond it. |
| `TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS` | `30` | Days without use after which an agent's home of a conversation is deleted |
| `TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS` | `14` | Days without use after which a cached skill is deleted |
| `TINFIRM_RUNNER_GIT_PROXY_BIND` | the sandbox network's gateway, or `127.0.0.1` with Docker Desktop | The address the git proxy and the model proxy's listeners bind, an IP with an optional port. Must be an address of this machine, never `0.0.0.0` or `::`. |
| `TINFIRM_RUNNER_GIT_PROXY_HOST` | the detected address, or `host.docker.internal` with Docker Desktop | The host name or IP at which sandboxes reach those listeners |
| `TINFIRM_RUNNER_SANDBOX_NETWORK` | Docker's `bridge`, or `tinfirm-sandbox-egress` with the egress proxy | The Docker network of the sandboxes. `host` binds the listeners on `127.0.0.1`; `none` is refused. |
| `TINFIRM_RUNNER_IMAGE_CLAUDE`, `_CODEX`, `_PI`, `_OPENCODE` | the built images | For development: another image for a runtime. `run` does not rebuild it. |
| `TINFIRM_RUNNER_ALLOW_INSECURE_REPOS` | off | For development: `1` accepts `file://` and `http://` repositories |

## Directories

The **config directory** (mode 0700):

| Path | Contents |
|---|---|
| `config.json` | The server URL, the host token and the protocol version (mode 0600) |
| `credentials/<label>.json` | A credential's label, kind and pool, and an API key's value (mode 0600). A login's secret stays in its own directory. |
| `state.json` | The host's and its company's ids from the last connection (mode 0600) |
| `images.json` | The build arguments and runtimes of the last `images build` |
| `run.lock` | Held by the active `run`, with its process id |

The **data directory** (mode 0700):

| Path | Contents |
|---|---|
| `workspaces/<run id>` | A running run's workspace, mounted at `/workspace`, with one clone per repository and its `output` folder |
| `mirrors/<key>.git` | A bare mirror per repository URL and git account or credential, which each run fetches before cloning. Two accounts never share a mirror. Never mounted into a sandbox. |
| `attachments/<run id>` | A running run's files, mounted read-only at `/attachments`, deleted when it ends |
| `failed-workspaces/` | The workspaces of the last 5 failed runs, for inspection |
| `homes/<agent id>/<conversation id>` | The agent's home of a conversation, mounted at `/home/agent`, which keeps its session for the next turn |
| `skills/<sha256>/<name>` | A cached skill, mounted read-only into the runs that use it |
| `claude-logins/<label>` | A Claude login's own Claude Code configuration |
| `codex-homes/<label>` | A Codex login's own `CODEX_HOME` |
| `public_suffix_list.dat` | The Public Suffix List the server sent, which the egress proxy uses |
| `runs/` | With elastic runners: a record of each run whose sandbox lives, with its credentials, and the sandbox bridge's identity (mode 0600), so that a restarted `run` takes the runs back. A record goes with its sandbox. |

## The sandbox

Each run is a container `tinfirm-run-<run id>`:

| Setting | Value |
|---|---|
| Memory | 4 GiB, no swap |
| CPUs | 2 |
| Processes | 1,024 |
| User | 1000:1000, every capability dropped, `no-new-privileges`; with `--sandbox-docker`, root with the settings Docker in a microVM needs |
| Mounts | `/workspace`, `/home/agent`, `/attachments` (read-only), `/output`, the agent's skills (read-only) |
| Isolation tier | `microvm` for a runtime named `kata…`, `gvisor` for `runsc…`, else `container` |

### Sandbox settings

Set with `--sandbox-env`, read by the sandbox at the start of each run. A URL must be `http://` or `https://`, without a user name, password, quotes, spaces or markup.

| Variable | Sets |
|---|---|
| `TINFIRM_SANDBOX_MAVEN_MIRROR` | A Maven mirror in `~/.m2/settings.xml`, and `MVNW_REPOURL` for the Maven wrapper |
| `TINFIRM_SANDBOX_MAVEN_MIRROR_OF` | What that mirror replaces (default `central`) |
| `TINFIRM_SANDBOX_NPM_REGISTRY` | The npm registry in `~/.npmrc` |
| `TINFIRM_SANDBOX_PIP_INDEX` | The package index of pip and uv |
| `TINFIRM_SANDBOX_REGISTRY_MIRRORS` | With `--sandbox-docker`: the agent's Docker's registry mirrors, comma-separated URLs |
| `TINFIRM_SANDBOX_INSECURE_REGISTRIES` | With `--sandbox-docker`: its insecure registries, comma-separated `host[:port]` or CIDR |
| `TINFIRM_SANDBOX_DOCKER_STORAGE_DRIVER` | With `--sandbox-docker`: its storage driver (default: Docker's choice) |
| `TINFIRM_SANDBOX_DOCKER_DISK` | With `--sandbox-docker`: the size of its sparse data disk (default `40G`); `off` keeps the data in the container |
| `TINFIRM_SANDBOX_DOCKER_START_TIMEOUT` | With `--sandbox-docker`: seconds to wait for its daemon (default `60`) |

`--sandbox-env` refuses a name the host sets itself (other `TINFIRM_*` variables, `HOME`, `PATH`, `GIT_CONFIG*`, the model, credential and effort variables of the runtimes), a name that looks like a credential (with `TOKEN`, `SECRET`, `PASSWORD`, `KEY`, `AUTH` and the like), a value with a control character or a URL with a user name or password, and a name given twice. The error never repeats the value. A file in the agent's home that the agent wrote itself is left as it is.

The host itself sets in every sandbox, among others: `TINFIRM_RUN_ID`; `TINFIRM_WORK_ITEM_KEY` and `TINFIRM_TASK_KEY`, the conversation's key; `TINFIRM_JOB_ID`, the conversation's id; `TINFIRM_ATTACHMENTS_DIR` and `TINFIRM_OUTPUT_DIR`; and, with `--sandbox-docker`, `TINFIRM_SANDBOX_DOCKER=1` and `TINFIRM_SANDBOX_AGENT_USER`.

## Image build arguments

For a host without internet access, `images build --build-arg` points the image builds at your mirrors:

| Argument | Image | Default | Used for |
|---|---|---|---|
| `APT_MIRROR` | base | `http://deb.debian.org` | Debian's apt sources; the mirror serves `/debian` and `/debian-security`. Use `http://`: apt checks the signatures. |
| `DOCKER_APT_URL` | base | `https://download.docker.com/linux/debian` | Docker's apt repository for Debian |
| `PIP_INDEX_URL` | base | `https://pypi.org/simple` | The package index for uv |
| `MAVEN_REPOSITORY_URL` | base | `https://repo.maven.apache.org/maven2` | Maven's distribution |
| `GRADLE_DOWNLOAD_URL` | base | `https://services.gradle.org/distributions` | Gradle's distribution |
| `NPM_REGISTRY` | claude, codex, pi, opencode | `https://registry.npmjs.org/` | The agents' adapters and CLIs |
| `JDK_PACKAGES`, `JDK_DEFAULT` | base | `openjdk-21-jdk-headless`, `21` | The JDKs, and the default one |

Downloads are checked against pinned checksums or signatures. The base image's own base comes from Docker Hub: give the machine's Docker a registry mirror (`registry-mirrors` in `/etc/docker/daemon.json`).

## Runner images

| Image | Holds |
|---|---|
| `tinfirm-runner-base:local` | Debian 13 with Node 26, git, build tools, OpenJDK 21, Maven, Gradle, Python with uv, and Docker Engine for `--sandbox-docker`; user `agent` (uid 1000) |
| `tinfirm-runner-claude:local` | Claude Code and its ACP adapter, pinned together |
| `tinfirm-runner-codex:local` | Codex and its ACP adapter |
| `tinfirm-runner-pi:local` | pi and Tinfirm's adapter for it |
| `tinfirm-runner-opencode:local` | OpenCode 2 |

The images are built and tagged on the machine only, and never pushed to a registry. Claude Code's auto-update is off in them.

## Why a host refuses a run

A host refuses a run it was sent with one of these codes; the server sends it again a few seconds later, to this host or another.

| Code | Why |
|---|---|
| `DRAINING` | The host drains |
| `NO_FREE_SLOT` | Every slot of the host is in use |
| `POOL_FULL` | The credential's pool is full |
| `TASK_RUN_ACTIVE` | A run of the same conversation is still active on the host |
| `IMAGE_MISSING` | The runtime's image is not built |
| `DOCKER_UNAVAILABLE` | Docker does not answer |
| `UNKNOWN_CREDENTIAL`, `CREDENTIAL_UNAVAILABLE` | The credential is not on the host, does not serve the runtime, or is invalid or cooling down after a usage limit |
| `INVALID_ASSIGNMENT` | The run is not valid for this host |
| `STALE_EPOCH` | The host already has a newer assignment of the same run |
