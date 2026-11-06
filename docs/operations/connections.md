# Connections (removed)

The connections of the design of 2026-10-05 (trackers, repositories and MCP servers, each set up on its own: [ADR-0050](../adr/0050-connection-types.md)), with their company OAuth accounts, shared accounts and the Connections page, are gone. The redesign of 2026-10-12 ([ADR-0058](../adr/0058-authority-from-the-audience.md)) replaced them with **systems**, which hold the company's credentials and lend them to groups and service accounts ([ADR-0072](../adr/0072-system-credentials-lent-to-groups-and-service-accounts.md)), and with triggers that act with a person's own account or a service account's. This page stays so that links from ADRs and review records still resolve.

- [systems.md](systems.md): registering and running systems, their base URLs and tokens, the Sign-in app, credentials and their lending, tests, polls and the outbound guard.
- [accounts.md](accounts.md): members' own accounts, service accounts, and which account a conversation or a trigger uses.
- [triggers.md](triggers.md): triggers on trackers, schedules, chat channels and webhooks.
