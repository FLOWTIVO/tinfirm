-- ShedLock's locks (CONVENTIONS §7, §15.21): a scheduled job that one worker replica at a time runs takes its lock
-- here (first the delivery of the error reports, ADR-0070). The table is ShedLock's own for PostgreSQL. Its times are
-- the database's clock in UTC, as ShedLock writes them (usingDbTime: timezone('utc', CURRENT_TIMESTAMP)), hence
-- timestamp and not timestamptz. An instance-level table, as error_reporting: no company_id, no row-level security.
--
--   name          the job's lock, such as error-reports.delivery
--   lock_until    the lock is held until then (lockAtMostFor from its take, or its last extension)
--   locked_at     when it was last taken
--   locked_by     the host name of the replica that took it; only that host extends or releases it
CREATE TABLE shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamp    NOT NULL,
    locked_at  timestamp    NOT NULL,
    locked_by  varchar(255) NOT NULL
);

-- A lock's row is made once and then taken, extended and released in place.
GRANT SELECT, INSERT, UPDATE ON shedlock TO ${app_role};
