# ADR-0066: Internal hosts: company systems on the deployment's own network, without allowing every private address

- Status: Accepted
- Date: 2026-10-15
- Decided by: the owner, 2026-10-15
- Sources: CONVENTIONS §4 and §14.4 (the outbound guard); the security review of the redesign, rows 12 and 21; [docs/operations/mcp-servers.md](../operations/mcp-servers.md), section 1
- Changes: the outbound guard (`AddressGuard`, `GuardedHosts`, `GuardedResolverProvider`, `OutboundUrls`) and the system module's outbound client (`OutboundHttp`, `OutboundProperties`); a new variable `TINFIRM_CONNECTION_INTERNAL_HOSTS`

## Context

- Every call to a company's system goes through one outbound client that refuses loopback, link-local and private addresses, checks them again at connect time against DNS rebinding, and refuses `http`. The operator can lift each refusal only for every company system at once: `TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES` and `TINFIRM_CONNECTION_ALLOW_HTTP`.
- A service on the deployment's own network lives at a private address (a Compose service name, a Kubernetes ClusterIP) and speaks `http` on that network, as LiteLLM does to the server. Connecting it as an MCP server needs both switches today.
- Allowing every private address also lets any company system an admin registers reach the server's own network: the management port on loopback, the cloud's metadata service on a link-local address, the database. That is the request forgery the guard exists to stop.
- `tinfirm.connection.allowed-private-hosts` already names hosts that may resolve to private addresses, but it is for the tests only: the server refuses to start with it outside the `test` profile, and Spring's relaxed binding maps `TINFIRM_CONNECTION_ALLOWED_PRIVATE_HOSTS` onto it.

## Decision

**The operator names internal hosts; a company system on one of them reaches the private network and may use `http`, and nothing else changes.**

1. **`TINFIRM_CONNECTION_INTERNAL_HOSTS`** (`tinfirm.connection.internal-hosts`): host names or IPv4 literals, comma-separated, without a scheme, port, path or wildcard; compared ignoring case and a trailing dot. A wrong entry stops the server with the reason. Empty by default.
2. **A third reach of the guard.** `AddressGuard.Reach` is `PUBLIC` (the default), `PRIVATE_NETWORK` or `ANY` (the operator's existing switch). `PRIVATE_NETWORK` keeps private addresses only (RFC 1918, the shared address space, IPv6 unique local). It refuses public addresses, as an internal host may be called over `http` and its traffic, with the account's token, must not leave the deployment's network when a name resolves wrongly (a search domain, a wildcard record, a rebinding). It refuses loopback, link-local, unspecified and multicast ones: no service of the deployment's own network needs those, and they are where the server's management port and a cloud's metadata service answer.
3. **The check and the connect-time guard both hold.** An internal host is resolved and every address checked at that reach before the request, and its lookups are registered at that reach while it is in flight (`GuardedHosts.open(host, reach)`), so a DNS answer that rebinds to a public, loopback or link-local address is refused. A host's lookups keep only the addresses every request in flight to it may reach: `PUBLIC` and `PRIVATE_NETWORK` share none, so while requests of both are in flight to one host it resolves to nothing.
4. **`http` for internal hosts.** Their traffic stays on the deployment's private network, as the server's to LiteLLM does (the public guide's [An external LiteLLM](../../docs-site/content/operator-guide/model-gateway.md#an-external-litellm), which section 6 of [litellm.md](../operations/litellm.md) points to: TLS between the two unless they share a private network). The refusal of an `http` URL names the variable.
5. **Company systems only.** A personal MCP server never honours the list (row 12 of the security review: any member registers one), nor do companies' own OIDC providers, client metadata documents or the model gateway, which keep their own rules.
6. **The test-only property stays test-only**, under its own name; the refusal to start with it now points the operator to `TINFIRM_CONNECTION_INTERNAL_HOSTS`.

## Consequences

- Such a service is connected with its Compose or Kubernetes service name in the list and `http://…` as its base URL, with no switch that widens every other company system.
- An admin who registers a system on an internal host reaches that host's private address: the list is the operator's statement that this is intended. The operator names services, not networks.
- Nothing changes for an operator who sets nothing. `TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES` keeps its meaning and still wins: it allows any address for every company system.
- IPv6 literals cannot be named; a host name that resolves to an IPv6 address works.

## Alternatives considered

- **`TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES` for such a service.** Rejected: it opens loopback, link-local and every private address to every company system.
- **Making `tinfirm.connection.allowed-private-hosts` a production setting.** Rejected: it allows any address (loopback included) for its hosts, and its name is bound to the tests' guard against a stray variable.
- **A public https name for the service.** Rejected: such a service may hold the company's data and has no reason to be reachable from outside the deployment.
