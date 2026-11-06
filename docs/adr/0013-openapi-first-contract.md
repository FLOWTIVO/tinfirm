# ADR-0013: OpenAPI-first HTTP contract

- Status: Accepted
- Date: 2026-09-26
- Sources: CONVENTIONS §2, §6, §10, §11, §13

## Context

- Contributors build the server and the web app in parallel.
- The tenant isolation test must cover every company-scoped path, including paths added later (CONVENTIONS §10).
- The runner host protocol is also schema-first: JSON Schemas in `protocol/` generate Java and Rust types.

## Decision

- `api/openapi.yaml` is the only HTTP contract. Only the maintainer edits it. Others propose changes (CONVENTIONS §2, §13).
- **Server:** controllers implement interfaces that `openapi-generator-maven-plugin` generates (the `spring` generator, `interfaceOnly`). Controllers stay thin; services hold the logic; MapStruct maps DTOs.
- **Web:** `npm run generate:api` generates the client into `web/src/app/api/generated/`. Git ignores that folder. `prestart`, `prebuild` and `pretest` run the script. Hand-written code never duplicates DTO types.
- **Errors** are RFC 9457 problem details (Spring `ProblemDetail`) with a stable `code` property (CONVENTIONS §6).
- **Tests:** the tenant isolation test reads the company-scoped paths from the contract.

## Consequences

- Every API change is explicit and reviewed in one file.
- Drift fails the build on both sides: controllers no longer implement the interface, or the web code no longer compiles.
- The server and the web app can work in parallel from the contract.
- New company-scoped paths are covered by the tenant isolation test automatically.
- Generated code is not in git, so every build needs the generator.
- The maintainer is a bottleneck for API changes.
- The contract uses OpenAPI 3.0.3 (CONVENTIONS §2). The reason for 3.0.3 is not recorded.

## Alternatives considered

- **Code-first: generate the spec from the server code.** Not discussed. The web app would have to wait for server code, and the spec would change as a side effect of code changes.
- **Hand-written client and DTO types in the web app.** Rejected: CONVENTIONS §11 forbids duplicated DTO types.
