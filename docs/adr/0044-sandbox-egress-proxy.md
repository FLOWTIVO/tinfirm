# ADR-0044: An egress proxy for the sandbox

- Status: Accepted (built as opt-in, `run --egress-proxy`)
- Date: 2026-10-07
- Sources: the owner's request of 2026-10-07 (a network proxy for agents, to improve the security of sandboxing); [threat model](../threat-model.md) 7.1, 7.2, 7.13, 7.14, R24; PLATFORM.md §6; [ADR-0025](0025-kata-sandbox-runtime.md), [ADR-0042](0042-provider-api-keys.md)
- Changes: no decision accepted before it. It builds what PLATFORM.md §13 and the threat model call "the egress proxy with an allowlist".

## Context

- A sandbox runs on Docker's default bridge: it can connect to any host. A prompt-injected agent can send out what it read (code, briefs, comments, tool results) and the key of its model credential (R23, R24). Redaction and the scans of pushes and uploads see only Tinfirm's own channels.
- The sandbox already has a controlled way in to the runner host: the git proxy and the platform MCP endpoint ([git_proxy.rs](../../runner/crates/tinfirm-runner-host/src/git_proxy.rs)), bound to the sandbox network's gateway.
- What a run legitimately needs is small and known: its model (a provider's API or the company's gateway), the package mirrors the operator configured (ADR-0025), and a few hosts the operator names.

## Decision

**With `run --egress-proxy` (`TINFIRM_RUNNER_EGRESS_PROXY`), a sandbox has no route to the network except an HTTP proxy of the runner host, which lets a connection out only to an allowlist.**

- **Docker Engine on Linux only.** With Docker Desktop (or any engine in a VM) the sandboxes reach the machine as `host.docker.internal`, which an internal network cannot route to, and Docker's resolver refuses to answer for it there; every run would fail at its first connection with timeouts. `run` refuses `--egress-proxy` when the listeners would be reached that way, naming the requirement (an operator who fixes the bind and host with `TINFIRM_RUNNER_GIT_PROXY_BIND` and `_HOST` to an address of the network is not affected). `--egress-proxy` works on Docker Engine 29 under runc and Kata, and fails as described on Docker Desktop 4.93.
- **No other way out.** The host creates (or reuses) the Docker network `tinfirm-sandbox-egress` with `internal: true` and `com.docker.network.bridge.enable_icc=false` (no traffic between sandboxes, which would be a way around the proxy and a channel between runs) and puts every sandbox on it. `TINFIRM_RUNNER_SANDBOX_NETWORK` may name another network, which must be internal and have `enable_icc=false`, or `run` refuses to start; `none` and `host` are refused. The git proxy and the proxy listen on the network's gateway, so the sandbox reaches the host and nothing beyond it.
- **One listener per run**, on a port of the gateway, started with the run and stopped when it ends. It asks for a credential of the run (`Proxy-Authorization: Basic`, in the proxy URL and the JVM options of the sandbox; masked in the logs), so a sandbox cannot use another run's rules, which may include hosts a member approved for that run only. The listener refuses to start on an unspecified address, never connects to its own address, and allows at most 256 open tunnels per run. It serves `CONNECT host:port` (HTTPS) and plain `http://` requests in absolute form; anything else is `400`. The sandbox gets `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` (the host's own listeners stay direct) and, for the JVM, `JAVA_TOOL_OPTIONS`; Claude Code runs also get `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`. Agent's own dockerd (`--sandbox-docker`) gets them as `proxies` in `daemon.json`. `--sandbox-env` may not set the proxy variables.
- **Allowlist, default deny.** An entry is `HOST[:PORT]`: a name, `*.domain` (the subdomains only; a top-level wildcard is refused), or an IP; the port is 443 unless given, `:*` is any. The list is the host-wide entries (`--egress-allow`, one per line in `TINFIRM_RUNNER_EGRESS_ALLOW`; the origins of the `TINFIRM_SANDBOX_*` mirrors) plus the run's own: its model host (the gateway's URL, or the provider's API, derived from the credential). Everything else is `403`.
- **Resolved and checked by the proxy.** It resolves the name, checks every address and connects to a checked one, so DNS rebinding cannot lead into the runner host's network. Loopback, link-local, multicast, unspecified and the cloud metadata addresses outside link-local (`100.100.100.200`, `168.63.129.16`, `fd00:ec2::254`) are always refused; private ones (RFC 1918, 100.64.0.0/10, fc00::/7) unless `--egress-allow-private` (registries and gateways inside the company's network).
- **No name resolution in the sandbox.** The container's DNS server is set to an address on which nothing answers, so Docker's embedded resolver cannot forward a lookup out (DNS exfiltration); the proxy resolves names itself.
- **Fail closed.** A run whose proxy cannot start does not start. A Bedrock credential without a region gets no model host (never a wildcard over `amazonaws.com`). Addresses refused whatever the rules say include loopback, link-local, multicast, unspecified, reserved ranges (`198.18.0.0/15`, `240.0.0.0/4`) and IPv6 forms that carry an IPv4 address (mapped, 6to4, NAT64).
- **Wildcards of agent requests are checked against the Public Suffix List** (bundled with the server, private hosting section included): `*.com.sg`, `*.asn.au` or `*.github.io` are refused; the exact host, or a wildcard over one tenant (`*.tenant.github.io`), is allowed. The list is a data file to refresh now and then (`server/src/main/resources/psl/README.md`).
- **Plain HTTP goes where it was checked.** The request's `Host` header is replaced by the authority of the URL that the allowlist approved.
- **Visible.** Denied destinations are logged by the host and listed in the run's log at its end, with how to allow them.
- **Opt-in for now.** Without the flag nothing changes. Making it the default is a later decision once operators have run with it.

## Consequences

- Exfiltration to an arbitrary host stops. It does not stop sending data to an allowed host (the model provider, a mirror) or inside a tunnel to one: the proxy reads no traffic (7.2 stays, narrower).
- A tool that ignores proxy settings fails instead of bypassing the proxy: the network has no other route.
- **The runner host's own services are not behind the proxy.** The internal network stops traffic from leaving the host, but a sandbox can still connect to any service that listens on the network's gateway address, which is an address of the runner host: a service bound to all interfaces (SSH, a metrics exporter, a Docker API on TCP) is reachable from a sandbox that ignores its proxy settings. Tinfirm's own listeners bind the gateway only. Operators should bind other services to specific interfaces, or drop input from the sandbox network's bridge in the host firewall except for the runner host's listeners. The ports of the egress listeners are chosen by the system, so a firewall rule needs a fixed range first (`TINFIRM_RUNNER_GIT_PROXY_BIND` fixes the git proxy's port only); a configurable port range is a possible follow-up. A per-run network namespace with its own rules is the heavier alternative.
- Operators must list the hosts their runs need beyond the model and mirrors, or the run fails with a `403` that the log explains.
- Not verified beyond the checks named under Decision (Docker Engine 29, Docker Desktop 4.93): that the gateway address answers on an internal network on every Docker version. The proxy itself and the option handling have automated tests; the Docker network step has none.
- Not done: chaining to a corporate upstream proxy (refused for now); an allowlist per agent.

## Next step: the agent asks for a host

An agent that finds it needs another host should not need an operator to restart the runner host. The planned design reuses the `ACCESS` decision (PLATFORM.md §7): a platform tool `request_network_access(host, reason)` becomes a decision a member answers with **this job only** (the thread's granted hosts, like `job.granted_tools`) or **always** (a standing grant); the next turn's `run.assign` carries the granted hosts, which the host adds to the run's rules. It touches the protocol schema, the server's decisions, storage and API, and the inbox, and it needs a decision on what "always" is scoped to (the agent, or the company); it is not part of this change.

## Alternatives considered

- **A proxy in a sidecar container (Smokescreen, Squid).** Rejected for now: one more image and process per host, and the per-run rules and denied list would need another channel. The proxy here is a small module in the host that already serves the git proxy.
- **iptables rules on the host.** Rejected: they match addresses, not names, need root and differ per platform.
- **Docker's `none` network.** Rejected: the agent could not reach its model.
- **TLS interception to inspect traffic.** Rejected: it needs a CA in every sandbox, and the agent's tools would trust it.
