-- Error reports (ADR-0070; CONVENTIONS §7, §15.21): the errors of the server and of its web app, scrubbed when captured
-- and grouped by their fingerprint, and the installation's choice of whether they are delivered to the vendor.
--
--   error_report            one kind of error: its count, first and last time and version, and its newest sample
--   error_reporting         one row: the installation's random id, the instance admin's choice, the delivery's state
--
-- Instance-level tables, as api_token: no company_id and no row-level security. They hold no personal data by
-- construction (ADR-0070, §2): types, frames, masked messages, route patterns and versions, never a user, a company,
-- an id of Tinfirm's objects, a URL or a log line's arguments. So governance's erasure has nothing to find here.

--   fingerprint             the SHA-256 (hex) of what groups the error (ADR-0070, §3), unique
--   source                  SERVER (a log event at ERROR of the server's process) or WEB (the web app's ErrorHandler)
--   title                   the outermost exception's type; for a log line without one, its logger
--   message                 the outermost exception's message, masked and cut to 300 characters; for a log line
--                           without one, its template
--   logger, log_message     the server's logger and the log line's template ({} placeholders unfilled)
--   route                   the route's pattern: the server's request (GET /api/companies/{companyId}/...), the web
--                           app's page (/c/:companyId/inbox); never a URL
--   runtime                 the Java version, or the browser's name and major version
--   sample                  the newest occurrence's exceptions and frames (jsonb, at most 64 KiB as the application
--                           writes it)
--   occurrences             every occurrence the replicas added since the row was made
--   reported_occurrences    the occurrences the last delivery sent; occurrences - reported_occurrences wait
--   reported_at             the last delivery of the row
CREATE TABLE error_report (
    id                   uuid          PRIMARY KEY,
    fingerprint          char(64)      NOT NULL,
    source               varchar(8)    NOT NULL,
    title                varchar(200)  NOT NULL,
    message              varchar(300),
    logger               varchar(200),
    log_message          varchar(300),
    route                varchar(300),
    runtime              varchar(100),
    sample               jsonb         NOT NULL,
    occurrences          bigint        NOT NULL,
    reported_occurrences bigint        NOT NULL DEFAULT 0,
    first_seen_at        timestamptz   NOT NULL,
    last_seen_at         timestamptz   NOT NULL,
    first_version        varchar(100)  NOT NULL,
    last_version         varchar(100)  NOT NULL,
    last_commit          varchar(40),
    reported_at          timestamptz,
    CONSTRAINT error_report_fingerprint_uq UNIQUE (fingerprint),
    CONSTRAINT error_report_fingerprint_ck CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT error_report_source_ck CHECK (source IN ('SERVER', 'WEB')),
    CONSTRAINT error_report_occurrences_ck CHECK (occurrences >= 1 AND reported_occurrences BETWEEN 0 AND occurrences),
    CONSTRAINT error_report_seen_ck CHECK (first_seen_at <= last_seen_at)
);

-- The page's order and its cursor, the sweep of rows not seen for 90 days, and the eviction beyond 1000 rows.
CREATE INDEX error_report_last_seen_idx ON error_report (last_seen_at DESC, id DESC);

--   id = 1                  one row, made by the server at its first start
--   instance_id             the installation's random id in its reports (UUIDv7, made with the row, never changed)
--   automatic               the instance admin's choice while TINFIRM_ERROR_REPORTS is unset: AUTOMATIC when true,
--                           LOCAL when false (the default)
--   changed_at, changed_by  when and by which user the choice was last made; NULL until then
--   retry_after             a destination's 429: no pass before then (one pass at a time is ShedLock's lock, V047)
--   last_attempt_at         the last pass that tried to send
--   last_delivery_at        the last pass that sent every row it took
--   last_delivery_error     why the last pass failed (at most 300 characters); NULL once one succeeds
CREATE TABLE error_reporting (
    id                  smallint     PRIMARY KEY,
    instance_id         uuid         NOT NULL,
    automatic           boolean      NOT NULL DEFAULT false,
    changed_at          timestamptz,
    changed_by          uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    retry_after         timestamptz,
    last_attempt_at     timestamptz,
    last_delivery_at    timestamptz,
    last_delivery_error varchar(300),
    CONSTRAINT error_reporting_one_ck CHECK (id = 1)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON error_report TO ${app_role};
-- The one row is made once and never deleted.
GRANT SELECT, INSERT, UPDATE ON error_reporting TO ${app_role};
