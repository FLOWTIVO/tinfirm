# A map of the runner host

This is the map to read before the code of `tinfirm-runner-host`. It says where things are and
how a run moves through the modules. What the host does, and the operator's settings, are in
[`runner/README.md`](../../README.md); this file does not repeat them. The paths below are
relative to this crate (`runner/crates/tinfirm-runner-host`). `ee/` means
`ee/runner/tinfirm-runner-host`, whose modules `src/lib.rs` compiles in with `#[path]`.

## The processes

Every process is this one binary, as a subcommand of `src/main.rs`.

| Process | Subcommand | Started by | What it holds |
|---|---|---|---|
| `run` | `run` | the operator (systemd, the chart) | every credential of the host; the connection to the server; every run's task (`host.rs`) |
| The reader | `reader` (hidden) | `run`, once per archive or git bundle that comes back from a sandbox (`reader.rs`, `Reader`) | no credential; it confines itself (`confine.rs`) before it reads |
| A front | `front` (hidden) | `run`, once per sandbox: `Shared::sandbox_front` (`host.rs`), called by `run::serving` for a Docker or local sandbox and by `Pool::boot` (`pool.rs`) for a VM; `BridgeKeep::register_again` (`sandbox.rs`) for a front that replaces one | no credential; the sandbox's bridge, its TLS and frames, and the HTTP of its proxies (`front/`) |
| A VM driver | `vm-driver digitalocean`, `node` or `pod` (hidden), or the operator's own program | `run`, with `--executor vm` (`vm.rs`, `Driver`) | the provider's credential, never a run's |
| `vm-pod` | `vm-pod` (hidden) | the pod of a run (`ee/src/kubernetes/vm_pod.rs`) | the run's VM in Firecracker |
| The bridge | `bridge` (hidden) | in the sandbox: the container's command, the local test executor, or `vm-boot` (`vm_boot.rs`) in a VM | the run's adapter, its stdio, its files; the bridge crate is `runner/crates/tinfirm-bridge` |

`run` reaches a sandbox only through the sandbox's front, and the front only through the
bridge. Without `--sandbox-bridge`, there is no front and no reader. The Docker executor then
attaches to the container's stdio, and `run` itself serves the proxies.

## The modules of `src/`

**Connection and protocol**

| Module | What it owns |
|---|---|
| `main.rs` | The CLI: `run`, `login`, `credential`, `images`, `doctor`, `drain`, `mirrors`, `node`, and the hidden subcommands |
| `connection.rs` | The WebSocket to the server: `hello`, `welcome`, reconnects, heartbeats, writing in batches |
| `outbox.rs` | The frames and events that wait for the server, per run, across reconnects |
| `host.rs` | `Shared`, the host's state: run registry, admission, capacity, the server's messages, the take-back of runs at start (`take_back_runs`), shutdown and drain; it calls `run::execute` and `run::take_back` |
| `config.rs`, `paths.rs`, `version.rs` | The server URL and host token, the directories, the versions |
| `capacity.rs`, `health.rs` | Capacity settings and their effective values; `host.health` |
| `public_suffix.rs` | The Public Suffix List from `psl.update` |

**A run's life**

| Module | What it owns |
|---|---|
| `run.rs` | One run, from its assignment to its report (`execute`), and a run taken back after a restart (`take_back`): the stages below |
| `acp.rs` | The ACP client of the agent's turn (`run_session`) |
| `mapper.rs`, `usage.rs`, `limits.rs` | ACP updates to run events and guards; cumulative usage; usage limits and failure classes |
| `steer.rs` | A person's messages to a running turn |
| `attachments.rs`, `output.rs` | The run's files in; the files of `/output` out as artifacts |
| `skills.rs`, `homes.rs` | The run's skills and their cache; session homes and their leases |
| `ee/src/session_bundles.rs` (paid) | Session bundles between hosts |
| `mcp.rs` | The platform tools of a run (`RunTools`) |
| `credentials.rs`, `runtime_env.rs` | The host's credential store; the environment that gives a runtime its model |
| `redact.rs`, `secret_hashes.rs`, `secret_eq.rs`, `secure_fs.rs`, `util.rs` | Redaction, by value and by salted hashes; secret comparison; private files; truncation |

**Sandboxes and executors**

| Module | What it owns |
|---|---|
| `sandbox.rs` | `Executor` (Docker, VM, the local test executor), `Sandbox` and its stops, `SandboxSpec`, `Bridged`, `BridgeKeep`, `TakenBack` |
| `docker.rs`, `images.rs` | The Docker connection and container names; the runner images |
| `vm.rs`, `vm_boot.rs`, `node.rs` | The VM executor and its driver protocol; `vm-boot` in a VM; the `node` driver |
| `pool.rs` | The one path that creates, boots, probes, claims and deletes a VM (`Claimed`, `Busy`) |
| `ee/src/ready_vms.rs`, `ee/src/digitalocean.rs`, `ee/src/kubernetes/` (paid) | Ready VMs; DigitalOcean's driver; the pod-per-run driver |
| `maintenance.rs` | Disk maintenance: mirrors, homes, the skills' cache |

**The bridge, the fronts and the reader**

| Module | What it owns |
|---|---|
| `bridge.rs` | The bridge's options, each sandbox's bridge listener, the tunnels' loopback |
| `sandbox_files.rs` | The run's files over the bridge: puts before the agent, the work collected after it |
| `front/` | The front process (`serve.rs`, `bridge.rs`, `wire.rs`, `conn.rs`, `head.rs`) and `run`'s side of it (`handle.rs`, `sandbox.rs` with `SandboxFront` and `Serving`, `session.rs` with `FrontSession`); `inside.rs` and `tap.rs` serve `tests/path_c.rs` |
| `reader.rs`, `confine.rs` | The reader; the confinement of the reader and of the front |

**Proxies**

| Module | What it owns |
|---|---|
| `git_proxy.rs` | The fetch-only git proxy and the platform tools' endpoint: a route and an MCP guard per run |
| `model_proxy.rs` | The model proxy, which keeps a gateway's key on the host |
| `egress_proxy.rs` | The egress proxy of `--egress-proxy` |

**Git**

| Module | What it owns |
|---|---|
| `git.rs` | Host-side git of one repository: clone, WIP commit, scan, push with a lease |
| `mirror.rs` | The host's mirrors |
| `git_tokens.rs`, `git_refresh.rs` | The one holder of a run's git tokens, its revocations and its record; their renewal |

**Take-back, isolation and doctor**

| Module | What it owns |
|---|---|
| `takeback.rs` | The take-back records (`runs/<run id>.json`): `RunRecord`, `RecordHandle`; a run's kept report (`runs/<run id>.<epoch>.finished.json`, `ReportRecord`) |
| `isolation.rs` | The host's level of isolation, reported in `hello` and by `doctor` |
| `main.rs` (`doctor`, `doctor_*`) | `doctor`'s checks: Docker, VMs, credentials, isolation, the front and the reader |

## A run, from its assignment to its report

`host.rs` admits the run (`Shared::admit`) and calls `run::execute` in the run's own task. All
the stages below are in `run.rs`. They take the run's context, `Run` (the shared state, the
assignment, the run's git tokens, its `EventSink`, its `Failure` and the server's stop). A
stage that ends the run early returns `ControlFlow::Break` with what `execute` returns.

1. `prepare_directories`: the workspace, the leased home and `/output` (`RunDirs`).
2. `redact_the_runs_secrets`, then `git_refresh::spawn` (the tokens' renewal), then
   `clone_repositories` (`clone_all`: the mirror, then the clone): `Vec<Repo>`.
3. `download_attachments`, `restore_the_session` (the session bundle), `provide_skills`,
   `make_the_login_mount_point`, `codex_auth_at_start`.
4. `sandbox_layout` and `file_roots`, then `claim_a_vm` (`pool.rs`, a VM per run only).
5. `serving` (the sandbox's front), then `sandbox_access` (git proxy routes and the platform
   tools, in `SandboxAccess`).
6. `start_the_sandbox`: `start_model_proxy`, `sandbox_spec`, `bridge_puts`, `start_egress`,
   `start_sandbox` (`start_in_front`, or `Executor::start_claimed` for a VM):
   `StartedSandbox`.
7. `keep_a_take_back_record` (`RunGitTokens::make_record`), then STATUS `RUNNING`, then
   `agent_params`.
8. `run_agent`: `connect_the_agent`, `acp::run_session`, the turn's loop over `AgentTurn`, then
   `AgentTurn::drain` and `AgentTurn::end`: `AgentPhase`.
9. `bring_the_work_back`: `a_new_front_takes_the_sandbox_back` when the front ended, the VM's
   end, `stop_agent`, `collect_from_sandbox`, then `keep_the_record_for_the_end`
   (`RecordHandle::collected_at_the_end`: the record says the work came back, with a leak or a
   cancel known then, and stays),
   then `Sandbox::stop`.
10. `close_the_access`: the run's result, and `SandboxAccess` goes.
11. `agent_outcome` (`classify_agent_end`, `usage_limit`): `AgentOutcome`; `keep_the_outcome`
    writes it into the record (`RecordHandle::outcome_decided`).
12. `aborted_in_the_end` before each of these and after the last: `save_the_output` (`collect_output`; the record keeps
    each file as it is saved, `RecordHandle::output_file_saved`, then `output_saved`),
    `finalize_repositories` (`finalize_git`),
    `store_the_session` (`store_session_bundle`).
13. `Run::finish` (`finish`: the report), `keep_the_report` (`RecordHandle::report_made`: the
    report is kept in the record's place), then `cleanup_workspace`. The report goes when the
    outbox forgets the run (`Shared::forget_delivered_reports`, on the host's 5-second tick, and
    `Shared::forget_written_reports` once the connection of an orderly stop has ended).

A run taken back after a restart: `Shared::take_back_runs` (`host.rs`) holds each record's
sandbox (`TakenBack::hold`) and calls `run::take_back`. Its stages are
`redact_the_runs_secrets`, `redact_the_earlier_tokens` (the record's hashes),
`taken_back_dirs`, `restore_repositories`, `bring_the_sandbox_back`
(`collect_the_work_taken_back`), `wait_for_the_server`, the stop, `save_the_work` and
`say_what_was_saved`. A record whose work came back already (`collected`) has no sandbox to
hold. One of a host that died in the run's own end (`RunRecord::end`) is finished the same
way: `save_the_work` does not save again the files of `/output` that the record lists, lets
nothing leave after a leak, and `finalize_git` counts a refused push as pushed when the branch
is at the run's head; `say_what_was_saved` reports the agent's outcome that the record keeps,
or its leak or cancel. Before the records, `Shared::send_kept_reports` puts each kept report
into the outbox.

## Where a credential lives

| Credential | Lives in | Never goes to | Threat model |
|---|---|---|---|
| The host token | `config.rs` (the config directory), `run`'s memory | a sandbox, a front, a reader | 6.6 |
| A host credential (an API key, a login) | `credentials.rs` (one 0600 file each); a Claude or Codex login mounted on Docker | the sandbox's settings with the bridge: the bridge's `start` frame carries it (`sandbox.rs`, `Bridged`) | 6.6, 6.12, 7.4 |
| A run's git tokens | `git_tokens.rs` (`RunGitTokens`), per git command (`git.rs`), the take-back record | the sandbox, any `.git/config`, the process list: the sandbox fetches through `git_proxy.rs` | 6.6, 7.1, 7.5 |
| A gateway's key | `model_proxy.rs`, the take-back record | the sandbox (`start_model_proxy` fails closed) | 7.3, 7.13, R23 |
| A bridge's TLS key | the sandbox's front, the take-back record (`BridgeKeep`) | another sandbox's front | 6.13, 6.20 |
| The take-back record | `takeback.rs`, `runs/<run id>.json`, 0600, from the sandbox's start until the run's report is made (or until a run taken back ends) | the server | 6.20, R36 |
| A run's kept report | `takeback.rs`, `runs/<run id>.<epoch>.finished.json`, 0600: the `run.finished` frame, redacted, no credential; until it is delivered | | 6.20 |

## Tests

| Test binary | What it covers |
|---|---|
| unit tests (`src/**`) | each module on its own; `run.rs`'s splitter, push failures, sandbox env and settings |
| `local_runs` | runs end to end against the fake server and the fake agent, with the local test executor |
| `bridge_runs` | runs over the bridge, the take-back after a restart, a host killed in a run's end, a front that ends during a run |
| `vm_runs` | runs with the VM executor and the fake driver (`tests/fake-vm-driver`) |
| `docker_runs` | key runs in real containers; needs Docker and builds `tinfirm-runner-fake:test` |
| `capacity` | capacity settings, admission codes and drain |
| `cli` | the CLI as a real process |
| `front`, `front_bridge`, `path_c` | the front's confinement and services; the bridge in a front; a front taken over reaches no credential |
| `reader` | the reader and its confinement |
| `git_proxy`, `mirror`, `maintenance` | the git proxy with real git; the mirrors; disk maintenance |
| `node_driver` | the `node` driver against node agents with their fake backend |
| `public_suffix`, `steering` | `psl.update`; `run.steer` |
| `unsafe_code` | no `unsafe` but the exceptions that `runner/Cargo.toml` names |
| `pool_runs`, `digitalocean_runs`, `pod_driver_runs` (paid, `ee/tests`) | ready VMs; DigitalOcean's driver; the pod driver |

`cargo test --workspace` runs them all. `local_runs` runs in two more modes, with the same
tests: `TINFIRM_TEST_SANDBOX_BRIDGE=1 cargo test -p tinfirm-runner-host --test local_runs` runs
every run over the bridge, and `TINFIRM_TEST_VM_EXECUTOR=1` runs every run in a VM of the fake
driver. `TINFIRM_SKIP_DOCKER_TESTS=1` skips the Docker tests.

## Rules a change keeps

- **No `unsafe`.** The workspace denies it. The only exceptions are the functions that
  `runner/Cargo.toml` names, two in `ee/src/kubernetes/tap.rs`; `tests/unsafe_code.rs` fails
  on any other.
- **Test-only hooks are `#[doc(hidden)]` options that no flag sets.** `Executor::Local` (the
  local test executor), `VmOptions::on_this_machine`, `Serving::Listener`,
  `FrontCommand::tap` and `HostOptions::halt_at` (a run's end waits for good at a moment
  that `run::EndStep` names, for the restart tests) exist for the tests. No command-line flag or environment variable of
  `run` reaches them.
- **The first writer of a run's failure wins.** `Failure::set` keeps the first class and
  message. `Failure::force` replaces it, only where a later finding must win: a commit that
  leaks (`finalize_repositories`, `save_the_work`), a new front that did not bring all the work
  back (`bring_the_work_back`), and a damaged take-back record (`say_what_was_saved`).
- **The sandbox is collected before it goes.** With the bridge, the work comes back
  (`collect_from_sandbox`) before `Sandbox::stop`, and the take-back record says so before
  the sandbox stops: `RecordHandle::collected_at_the_end` in a run's own end,
  `RecordHandle::collected` in a run taken back. A run's own end that cannot write the mark
  removes the record once the sandbox has stopped, as before; a run taken back keeps the
  sandbox then.
- **A record or a report is on disk until the run's end has been delivered.** The record goes
  only when the report is made, and the report (`runs/<run id>.<epoch>.finished.json`) takes its
  place in the same step (`RecordHandle::report_made`), the directory synced between the two.
  The report goes once the outbox forgets the run (its end written on a connection that stayed
  up for the retention), on an orderly end of the process when its end was written to a
  connection (`Shared::forget_written_reports`), or when another execution of the run replaces
  it in the outbox. A report that cannot be written does not stop the record from going: the
  run is then lost on a restart before the server has its end, as before the kept report. At
  the start a record of a newer execution of the run wins over its kept report. Only the newest
  handle of a run id writes or removes a record or a report (`Records`); all of them take one
  lock in turn, so a write of one run's record waits for another's. Every step that a host which takes the run back may do
  again is harmless done twice: the push (a branch at the run's head is up to date, with git and
  with the host's own client; a push refused because the branch reached that head meanwhile
  counts as pushed), `/output` (the record lists each file as it is saved: at most the one in
  flight is uploaded again), the session bundle (the server keeps the latest), `run.finished`
  (a server that has it ignores it).
- **After a leak nothing of the run leaves the host, also across a restart.** The leak is in the
  record with the mark, before the sandbox stops (`EndRecord::leak`); a mark that cannot be
  written drops the record before the sandbox stops, and the run is lost rather than pushed.
- **An abort is looked at between the steps of a run's end** (`aborted_in_the_end`, and once in
  `take_back` before its report): no further step begins, and the run makes no report. The step
  under way finishes: the rest of its files of `/output`, the push of its remaining
  repositories, or its session bundle. No cleanup of an aborted or finished execution touches
  the workspace of a newer execution of the run (`Shared::no_newer_execution`).
- **What lives until the run ends is held by `execute`.** The VM the sandbox did not take, the
  `SandboxReader` (the sandbox's front's last owner), the skills' leases, the attachments, the
  clones, the tokens' renewal, the handle of `/output` and the home's lease are its locals or
  their fields, and they drop in that order
  when it returns. `SandboxAccess` drops its fields in order, so that each guard unlistens on
  the front before the front itself goes.
- **A taken-back run pushes only after the server's answer.** `wait_for_the_server` comes before
  any use of the run's git tokens.
