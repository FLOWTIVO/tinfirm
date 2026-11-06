# ADR-0008: BFF session authentication with Spring Session JDBC

- Status: Accepted; [ADR-0026](0026-per-provider-oidc-email-trust.md) makes the `email_verified` rule a per-provider setting, with this rule as the default
- Date: 2026-09-26
- Sources: CONVENTIONS §4, §5, §12
- Amended by: [ADR-0045](0045-mcp-server-for-peoples-clients.md), under which an authorization request survives the login round trip, and a company's own OIDC provider stands beside the instance's

## Context

- The product is multi-user from M0, with local accounts and OIDC (Keycloak, Entra, Google, Authentik).
- A no-auth "local trusted" mode is a non-goal.
- The web app is an Angular SPA.
- Server replicas must work without sticky sessions. HA with several replicas is planned for M3.

## Decision

- **Backend for frontend (BFF).** The server is the OIDC client (Spring Security `oauth2Login`) and keeps the session. The browser holds only an HttpOnly cookie.
  - Cookie `TINFIRM_SESSION`: HttpOnly, `SameSite=Lax`, and `Secure` when `TINFIRM_PUBLIC_URL` is https.
  - After an OIDC login, the server redirects to `/`.
  - PKCE is on for every registration, confidential clients included.
- **OIDC identity.** An OIDC user is identified by `(issuer, subject)`, never by email.
  - The first login with an unknown `(issuer, subject)` creates a new user, if auto-provisioning is on and the IdP asserts `email_verified=true`.
  - The server never links an OIDC login to an existing user. If the email already belongs to another user, the login fails with `EMAIL_IN_USE`.
  - OIDC users cannot set a local password (`409 NOT_LOCAL_USER`).
- **Session lifecycle.**
  - Every login, local or OIDC, rotates the session ID.
  - Idle timeout `TINFIRM_SESSION_IDLE_TIMEOUT` (default 8 hours) and absolute lifetime `TINFIRM_SESSION_MAX_AGE` (default 7 days), checked against the creation time on every request.
  - Logout deletes the session in the database and clears the session and XSRF cookies.
- **Security headers** on every response: a strict Content-Security-Policy without inline scripts and with `frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, and HSTS when `TINFIRM_PUBLIC_URL` is https.
- **Sessions in PostgreSQL** with Spring Session JDBC. Flyway creates the session tables. Spring does not initialize them.
- **CSRF:** cookie `XSRF-TOKEN`, readable by JavaScript, plus the header `X-XSRF-TOKEN`. These are the Angular `HttpClient` defaults.
  - `GET /api/auth/providers` sets the cookie, because the SPA calls it before `POST /api/auth/login`.
  - Every mutating request needs the token, login and logout included.
- **Local accounts:** Argon2id hashes. Login is throttled per email and per client IP (`429 RATE_LIMITED`). The client IP comes from `X-Forwarded-For` of trusted proxies only (`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`). Logins are audited in the instance chain.
- A deactivated user cannot log in, and their existing sessions stop working.

## Consequences

- No access token or refresh token is ever visible to JavaScript in the browser.
- Every mutating request needs CSRF protection, because the browser sends the cookie automatically.
- Each request reads its session from PostgreSQL.
- The database and its backups hold session IDs. Access to them must be restricted (threat model, R6).
- Any replica can serve any request. The login throttle is in memory, so each replica counts attempts separately. CONVENTIONS §5 accepts this for M0.
- The SPA must work under the CSP: no inline scripts, and Angular's critical-CSS inlining is off.
- An account cannot be taken over by an IdP that asserts someone else's email. An IdP that asserts false emails can still create new users (threat model, R7).
- Runner hosts and agents do not use browser sessions. They use host key pairs and run-scoped JWTs.

## Alternatives considered

- **The SPA as a public OIDC client that holds tokens.** Not discussed. It would expose tokens to any script in the page.
- **In-memory sessions with sticky routing.** Not discussed. Sessions would be lost on restart, and the load balancer would need affinity.
- **A separate session store such as Redis.** Not discussed. It would add a stateful service ([ADR-0003](0003-postgresql-only-state-and-queue.md)).
- **A no-auth local mode.** Rejected: a non-goal.
