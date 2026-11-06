# ADR-0095: The sandbox bridge

- Status: Accepted
- Date: 2026-10-16
- Accepted by: the owner, 2026-10-27, with the fixes of the gaps it left open ([ADR-0101](0101-closing-the-elastic-runners-gaps.md))
- Amended by: [ADR-0100](0100-elastic-runners-in-pro.md): The sandbox bridge is part of elastic runners, Pro's and Enterprise's (`Feature.ELASTIC_RUNNERS`); its code is under `ee/`, the runner host's under `ee/runner/`.
- Amended by: [ADR-0101](0101-closing-the-elastic-runners-gaps.md): A restarted host takes back its runs on the bridge (R36): it keeps each run's session key and the listener's identity on disk, the bridge keeps a sandbox whose host is gone for 15 minutes, `reclaim` stops the agent, and the run ends with its work pushed and its session saved. The `hello` names the bridge's build and its last frame (`build`, `txSeq`).
- Sources: [ADR-0002](0002-rust-runner-host-and-bridge.md) (the bridge as planned), [ADR-0006](0006-runner-hosts-and-isolation-tiers.md), [ADR-0007](0007-credentials-stay-in-deployment.md), [ADR-0025](0025-kata-sandbox-runtime.md); the [Kubernetes runner analysis](../reviews/2026-10-15-kubernetes-runner-analysis.md) §3.4, §3.5 and §9 step 1; [threat model](../threat-model.md) 6.3, 6.6, R28, R36; the runner README's "v0 security shortcuts"
- Changes: the runner host's link to the sandbox, behind `run --sandbox-bridge`. The runner protocol between the server and the host does not change.

## Context

- ADR-0002 planned a bridge in the sandbox: a static binary that starts the runtime's ACP adapter and pipes its stdin and stdout to the runner host, buffers output for up to 25 s while the host reconnects, and stops the agent when the host has been gone for 25 s. The host attaches to the container's stdio through Docker instead (a v0 shortcut).
- That shortcut ties a run to Docker on the same machine: stdio comes through Docker attach, and the files through bind mounts of the host's directories. The executors of the analysis (Kubernetes pods, a VM per run) have neither.
- The run's credentials (an API key, a subscription token, a one-run token) are in the container's environment, where `docker inspect` shows them to whoever holds the Docker socket. A pod's environment would be in etcd, in its events and in audit logs.
- The platform tools and the git proxy are served over plain HTTP on a listener of the host, guarded only by a secret in the URL (R28).

## Decision

**One authenticated stream per run, from the sandbox to its host.**

- The bridge is the runner host binary itself, started as `tinfirm-runner-host bridge`. The release binaries for Linux are static (musl), so the same file runs in any Linux sandbox of its architecture. The Docker executor mounts it read-only at `/opt/tinfirm/tinfirm-runner-host`, so the images do not change when the host is upgraded. `run --sandbox-bridge` refuses a binary that is not a static Linux executable of the sandbox's architecture, and `TINFIRM_RUNNER_BRIDGE_BINARY` names another one (for a host on macOS, or a build that is not static). The VM executor puts it into the VM's image ([ADR-0096](0096-a-vm-per-run.md)), and the Kubernetes executor will put it into its images.
- The host runs one bridge listener, on the address of the git proxy (the sandbox network's gateway). Each run registers there and gets a **ticket** of 32 random bytes.
- The sandbox gets, in its environment, only the listener's address (`TINFIRM_BRIDGE_ADDR`), the run id, the ticket and the SHA-256 of the listener's certificate (`TINFIRM_BRIDGE_PIN`). None of these is a credential of the run.

**TLS, pinned.**

- The listener holds a self-signed ECDSA certificate that it creates when it starts. Its private key is only in the host's memory.
- The bridge speaks TLS 1.3 only, and accepts the one certificate whose SHA-256 is its pin. The handshake's signature is still checked.
- The TCP direction does not decide the TLS roles. The VM executor ([ADR-0096](0096-a-vm-per-run.md)) lets the host dial the sandbox, and the host stays the TLS server.

**The handshake.**

- The bridge sends `hello` with the run id and the ticket. The host answers `welcome` with a **session key** of 32 random bytes, or `refused` with a code, and closes.
- A ticket works once. A reconnect presents the session key, which exists only in the memory of the two processes, so a ticket read later from the container's settings opens nothing.
- Tickets, session keys and pins are compared by their HMAC-SHA256 tags under a random key of the process, so the time of a comparison tells nothing about them, whatever the compiler makes of it.

**Frames.** Inside TLS, each frame is:

| Field | Size | Meaning |
|---|---|---|
| length | 4 bytes, big endian | The bytes after this field, at most 1 MiB + 13 |
| kind | 1 byte | `CONTROL`, `DATA`, `EOF`, `ACK`, `PING`, `PONG` or `CREDIT` |
| stream | 4 bytes | 0 for control; 1 stdin (host to sandbox), 2 stdout and 3 stderr (sandbox to host); 16 and above, one per connection of a tunnel, numbered by the sandbox (below) |
| seq | 8 bytes | The frame's number in its direction, from 1; 0 for frames that are not numbered (`ACK`, `PING`, `PONG`, the handshake) |
| payload | the rest | JSON for `CONTROL`, at most 64 KiB of bytes for `DATA`, the acknowledged number for `ACK`, 4 bytes for `CREDIT` |

- `CONTROL` messages, host to sandbox:
  - `start`: the adapter's argv, its environment and its working directory, exactly once. The run's credentials travel here, never in the container's or the pod's settings.
  - `signal`: a signal for the adapter's process group.
  - `stop`: SIGTERM, then SIGKILL after a grace period.
- `CONTROL` messages, sandbox to host: `started`, `exited` (the exit code or signal), and `error`.
- For the tunnels: `tunnels` (host to sandbox), `listening` and `open` (sandbox to host), and `reset` (either way).
- For the files and the host's git steps: `put`, `get`, `exec` and `close` (host to sandbox), answered with `done`, `exec_done` or `failed`. Every request of the host's has an id, and its answer names it.

**No data is lost across a reconnect.**

- Each side keeps the numbered frames that its peer has not acknowledged: up to 8 MiB from the bridge, 4 MiB from the host.
- `ACK` carries the highest number received in order. A reconnect's `hello` and `welcome` each carry that number, and each side sends again what follows it; a frame received twice is dropped.
- A sender whose buffer is full stops reading its source. The adapter's writes then block, and nothing is dropped.
- The receiver acknowledges only what it has handed on to its consumer, and reads at most 8 MiB ahead of it; a consumer that stalls leaves the next frame waiting in the link and the connection unread, so the peer's sender runs out of credit and is then held by TCP, and nothing piles up in the receiving process. The connection's comings and goings are bounded the same way: a connection is attached only with room for its two events among 64 not yet taken, and one that finds none is dropped unread, so a peer that connects again and again while the consumer stalls piles up nothing either.
- `PING` every 5 s on an idle link; a link that receives nothing for 15 s is closed and the bridge reconnects.

**The 25-second rule (ADR-0002).**

- When the bridge has had no link to the host for 25 s, it stops the agent: SIGINT to the adapter's process group, SIGTERM after 5 s, SIGKILL after 5 s more. Then it exits.
- When the host has had no link for 30 s, it ends the run's output, so the run ends as when the agent's process is gone.
- The bridge sends signals with the `kill` utility, as `drain` does, so that the runner keeps no unsafe code.

**Tunnels to the host's listeners** (R28).

- With the bridge, the git proxy and the platform tools, the model proxy and the egress proxy listen on the host's loopback, where no sandbox reaches them but through its own bridge. Only the bridge's listener stays where the sandboxes reach the host (the sandbox network's gateway). Where the sandbox shares the host's network (the local test executor, Docker's `host` network), the listeners take 127.0.0.2.
- Before `start`, the host sends `tunnels`: for each listener the run uses, a number and an address in the sandbox, 127.0.0.1 on the listener's own port. The bridge listens there and answers `listening`, or `error`; the run fails when it cannot. Because the ports are the same, every URL the sandbox gets (`origin`, the MCP endpoint, the model's base URL, the proxy variables) names its own loopback, and the entrypoint's proxy variables for the agent's own dockerd are right before the bridge starts.
- Each connection to a tunnel becomes a stream: the bridge sends `open` with a new stream number (16 and up, never used twice) and the tunnel's number. The host connects the stream to that tunnel's listener and to nothing else, so the sandbox never names an address of the host. The listeners' own checks (the URL secrets, the proxy's credential and rules) do not change.
- Each direction of a stream has a window of 128 KiB. The receiver sends `CREDIT` as it writes the bytes on, and a sender waits for it, so one slow reader holds back its own stream and never the link. A peer that sends past the window gets its stream reset. `EOF` closes one direction; `reset` ends both.
- At most 128 streams are open per run, and 16 tunnels, so what a host holds for one sandbox's streams is bounded (16 MiB). The host refuses an `open` on an unknown tunnel or a used number with `reset`.
- The streams are numbered frames of the link, so a connection survives a reconnect of the bridge without loss.

**Files instead of mounts** (the analysis' §3.5).

- With the bridge, the sandbox gets none of the host's directories as mounts: not the workspace, the session home, the skills, the attachments or `/output`. A Codex ChatGPT login on Docker stays a mount, because its token is refreshed in place.
- A directory travels as a tar archive on a stream of the host's (numbers from 2³¹; the sandbox's tunnels stay below). `put` unpacks an archive of the host's into a directory of the sandbox, as it is. `get` packs paths of a directory of the sandbox, never following a symbolic link. On the host, what comes back is unpacked as directories and regular files only, at relative paths without `..`, into a directory that was empty, within caps on the files and the bytes; links, devices and files over the per-file cap are left out by name.
- Before `start` the sandbox gets the workspace (the host's clones, whose `origin` is the run's tunnel to the git proxy), the session home without its caches (`~/.cache`, `~/.npm`, `~/.m2/repository` and the like, which the package mirrors serve), the skills, the attachments and an empty `/output`.
- The platform tools read a file of the agent's with `get` of one path: a regular file of at most 25 MiB, or the error the agent got before.
- **The host never runs git in a `.git` that the agent wrote.** After the adapter has ended, the bridge stays, and the host runs its git steps in the sandbox with `exec`: the WIP commit of what the agent left uncommitted, then a bundle of `HEAD` without the commits the host's clone has. The host fetches the bundle into its own clone, which the agent never reached, with every object checked (`fetch.fsckObjects`), checks that it holds the `HEAD` the sandbox named, and moves the task branch there. The ahead count, the credential scan of the outgoing commits and the push with a lease then run on that clone as before.
- Then the session home (without the same paths) and `/output` come back, and the host sends `close`: the bridge answers and ends, and with it the sandbox. Without a word from the host for 10 minutes after the adapter, the bridge ends by itself.
- The base image has `/attachments` and `/output`, owned by the agent, for the bridge to fill.

**What does not change in this step.**

- The entrypoint keeps reading its settings from the container's environment: `TINFIRM_SANDBOX_*`, and the proxy variables that it writes into the agent's own dockerd. Those stay there. Every other variable of the run moves into `start`.
- The session home stays with the runner host, between turns (D3 of the analysis: session bundles in S3-compatible storage later).

## Consequences

- With `--sandbox-bridge`, `docker inspect` no longer shows the run's credentials: the container holds the bootstrap settings and the image's settings only (threat model 6.6, partly).
- With `--sandbox-bridge`, a sandbox reaches the platform tools and the git proxy only through its own authenticated bridge, not over plain HTTP on a listener that every sandbox reaches (R28 closed for such hosts). The secrets in the URLs stay, as a second check.
- The tunnels listen on the sandbox's loopback, so a container of the agent's own Docker reaches them only on Docker's `host` network. Listening on that dockerd's gateway as well is left for later.
- With `--sandbox-bridge`, the host's git does not read what an agent planted in `.git` (hooks, configuration, alternates, replace refs, forged remote-tracking refs): only objects come back, checked, into a clone of the host's. Without the flag, the host sanitizes the workspace's `.git` and runs git with safe settings, as before.
- A run copies its workspace into the sandbox and takes its commits, its session folders and `/output` back, where a mount cost nothing. For a large repository that is seconds per run; its caches never travel.
- The local test executor's "sandbox" shares the host's network, so with the bridge each of its runs gets a loopback address of its own (127.x.y.z, Linux), and the URLs name it. The end-to-end tests run once more with every run over the bridge (`TINFIRM_TEST_SANDBOX_BRIDGE`).
- The bridge runs as the agent's user, in the agent's sandbox, so the agent can stop or replace it. It is a transport, not a guard: everything it sends is checked by the host as it was before (S6 of the analysis). An agent that kills the bridge ends its own run.
- A host restart still loses its runs (R36): the session key dies with the host's memory, and the bridge stops the agent after 25 s. With the bridge in place, taking runs back after a restart becomes possible later. (Since ADR-0101 a restarted host takes them back: the work and the session are saved, and the run ends `INFRA`.)
- The entrypoint's messages before the bridge starts (dockerd's failures) are no longer attached. When a bridge does not connect, the host reads the container's last log lines and puts them into the run's log.
- A new parser of untrusted input runs in the host: the frames. Their sizes are bounded, a frame out of order closes the link, and the tests cover truncated and oversized frames.
- One new dependency: `rcgen`, for the certificate.

## Alternatives considered

- **A separate `tinfirm-bridge` binary.** Not chosen for now: one more release asset, and a version pairing for every host. The host binary carries the bridge as a subcommand, and a separate binary can follow if its size matters.
- **The bridge baked into the runner images.** Not chosen for Docker: every new host binary would change every image, and ADR-0062 would rebuild them all at each upgrade. A read-only mount needs no rebuild. The Kubernetes and VM executors have to bake it in, because their images are built elsewhere.
- **Kubernetes attach, or Docker attach, kept.** Rejected for the new executors (analysis §7): stdio through an API server for runs of up to 24 hours, lost on any reconnect, and no way for files and listeners.
- **Plain TCP with the ticket only.** Rejected: between nodes the stream carries prompts, code and credentials, and a ticket in clear could be replayed by whoever sees it.
- **The host's certificate from a CA.** Not needed: the bridge learns the pin from the host that starts it, which is a stronger binding than a CA the sandbox would have to trust.
