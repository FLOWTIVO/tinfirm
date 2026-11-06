# ADR-0070: Error reports: each installation groups its own errors, scrubbed, and sends them to the vendor only when its operator or instance admin turns delivery on

- Status: Accepted
- Date: 2026-10-16
- Decided by: the owner, 2026-10-16
- Sources: CONVENTIONS §4 ("The server sends no product telemetry"), §7, §10; the threat model, 2.14 (personal data out of what cannot be erased)
- Changes: a new module `diagnostics`; `V046__error_reports.sql`; `POST /api/client-errors`, `/api/admin/error-reports/**` and `/api/admin/error-reporting`; `SystemInfo.telemetryEnabled`; the web app's `ErrorHandler` and the admin page *Errors*; four variables `TINFIRM_ERROR_REPORTS*`

## Context

- Tinfirm is self-hosted: each company runs its own installation, many of them without any route to the internet. The vendor (FLOWTIVO) sees none of the errors those installations hit, and hears of a bug only when someone writes.
- An error tracker's SDK in the browser (Sentry, GlitchTip, Bugsink) needs the browser to reach the tracker: the strict CSP (`connect-src 'self'`) refuses it, an installation without internet loses every event, and the SDK sends what it captures (URLs, breadcrumbs, request data) without anyone seeing it first.
- An SDK in the server, pointed at the vendor's project, has the same problems on the server's side: it sends without a preview, there is nothing to send by hand when the installation is offline, and it ties the receiving side to one vendor.
- An error's message often holds the company's data: an issue's title in a Jira error, a value in a SQL error, an email, a URL of the company's systems. URLs of the web app hold the ids of companies and conversations. Whatever leaves the installation, or is kept outside the erasure of governance (§15.11), must not.

## Decision

**The server collects every error of its own process and of its web app, scrubbed when captured, grouped into one row per kind of error; an instance admin sees them on the page *Errors* and downloads them; they are sent to the vendor only when delivery is automatic, which the operator sets in the environment or the instance admin chooses on the page.**

1. **Capture.**
   - The server: every log event at `ERROR` of any logger of the process, outside the `diagnostics` module itself, through a Logback appender attached at startup. An unhandled exception of a request is one of them (`ApiExceptionHandler` logs it); so are the workers' failures. The request's route pattern (`GET /api/companies/{companyId}/conversations`, never the URL) is taken when the event is logged in a request.
   - The web app: every error Angular's `ErrorHandler` sees (uncaught errors and unhandled rejections, through `provideBrowserGlobalErrorListeners`), except an `HttpErrorResponse` (the server captured its own failure) and a stale chunk after a deploy, which reloads the page. The browser posts it to `POST /api/client-errors` on Tinfirm itself (a member's session, CSRF), at most 10 a page load and one per kind of error a minute; the server takes at most 20 a minute from one user and drops the rest, always `204`. The CSP stays as it is.
2. **Scrubbed when captured, from an allowlist.** What is stored is what may be sent; nothing else is kept.
   - Kept: the exception's type and its causes' (at most 5), their stack frames (class or module, function, file, line and column; at most 40 an exception; of the web app's, the bundle's file, line and column only, as a minified name says nothing), whether a frame is Tinfirm's own code (`com.flowtivo.tinfirm.`, the web app's bundles), the logger's name and the log line's template (`"Unhandled exception"`, the `{}` placeholders unfilled), the route's pattern, the version and commit, the Java version or the browser's name and major version.
   - Masked in an exception's message (then cut to 300 characters): URLs, emails, UUIDs, IP addresses, quoted strings, file paths, hexadecimal values, tokens of letters and digits, and numbers of 4 digits or more.
   - Never: a user, a company, an id of Tinfirm's objects, an IP address, a request's URL, query, headers, cookies or body, the log line's arguments, the console. So the rows hold no personal data, and governance's erasure has nothing to find in them.
3. **Grouped.** One row per fingerprint, the SHA-256 of the source, the logger and template, and each exception's type with its first five frames of Tinfirm's code (of any code when it has none); for the web app, of the type, the masked message and the route, since minified frames change with every build. A row counts its occurrences, keeps its first and last time and version, and the newest sample. Each replica holds what it captured in memory, at most 200 kinds of error, and adds them to the rows every 30 seconds; at most 1000 rows are kept (the least recently seen goes first) and a row not seen for 90 days is deleted. An instance-level table without row-level security, as `api_token`: it belongs to no company.
4. **Three modes**, `TINFIRM_ERROR_REPORTS`:
   - `off`: nothing is captured (the endpoint still answers `204`);
   - `local`: captured and shown, never sent;
   - `automatic`: captured, shown and sent.

   Unset, the instance admin chooses between `local` and `automatic` on the page, `local` until they do (`PUT /api/admin/error-reporting`, audited `error_reporting.changed` in the instance chain). Set, the page shows the operator's choice and refuses a change (`409 ERROR_REPORTING_SET_BY_OPERATOR`). The download works in every mode, so an installation without internet sends its errors by mail or a support portal.
5. **Delivery in Sentry's protocol.** The destination is a DSN (`TINFIRM_ERROR_REPORTS_DSN`), which any tracker that speaks Sentry's protocol gives (Sentry, GlitchTip, Bugsink): `https`, or `http` to a loopback address; a wrong one stops the server. On `worker` replicas, every 15 minutes, one pass at a time across replicas (a ShedLock lock that expires by itself when its server crashes), the rows with occurrences not yet sent go, at most 100 a pass, as one Sentry event each to `/api/{projectId}/envelope/`, with the row's fingerprint as the event's, so the tracker groups as the installation does. A failure ends the pass and is shown on the page; the next pass tries again; a `429` waits its `Retry-After`. The call is outside any transaction, on a thread of its own, without the outbound guard of company systems (§14.4): the operator names the destination, no member does. `TINFIRM_ERROR_REPORTS_PROXY` sends through an HTTP proxy. The event names the installation by a random id made at its first start and by `TINFIRM_ERROR_REPORTS_LABEL`, a name the operator may give it; nothing else says whose it is.
6. **The download is the preview.** `GET /api/admin/error-reports/export` gives the very events that delivery sends, as one JSON file; the page lists the same rows.
7. **`SystemInfo.telemetryEnabled`** is true exactly while delivery is automatic with a destination. CONVENTIONS §4's sentence becomes: the server sends nothing but error reports, and those only when delivery is automatic.

## Consequences

- The vendor gets the errors of the installations that allow it, grouped, with versions and stack traces, in a tracker of its choice; the others can send a file.
- An installation's operator sees its own errors on a page, without the logs.
- A masked message can still hold a word of the company's data (an unquoted name in an exception's message). The page and the download show exactly what would leave, before the admin turns delivery on.
- The web app's frames are minified until the source maps of each release are uploaded to the receiving tracker (later work); the type, message and route group them meanwhile.
- No call reaches the vendor while the mode is not `automatic`, and the default is `local`: nothing leaves an installation whose operator and admins did nothing.
- The runner hosts' errors are not captured yet; they would reach the server over the runner protocol later.

## Alternatives considered

- **A tracker's SDK in the browser.** Rejected: the CSP, the installations without internet, and no preview of what leaves.
- **A tracker's SDK in the server, pointed at the vendor.** Rejected: no preview, nothing to send by hand, and it captures request data unless trimmed carefully.
- **The server's logs only**, to be collected by each company's own tooling. Not enough: the vendor never sees them. The server's logs still hold every error in full, as before.
- **OpenTelemetry (OTLP).** Not chosen: no grouping of errors at the receiving end, and the errors are what the vendor needs.
- **Unscrubbed rows locally, scrubbed when sent.** Rejected: the rows would hold the company's data outside governance's retention and erasure, and the preview would not be what is stored.
