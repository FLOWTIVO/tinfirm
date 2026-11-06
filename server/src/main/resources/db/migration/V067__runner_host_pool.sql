-- Pool settings of runner hosts that keep ready sandboxes (ADR-0098, ADR-0099; the Kubernetes runner analysis §6.4,
-- D5, D11 and D12). OWNER and ADMIN set how many sandboxes wait ready, by a weekly schedule in a time zone; the server
-- evaluates the schedule and sends the host numbers in host.config.pool, which the operator's ceiling bounds.
--
--   pool_max_ready_ceiling  the host's ceiling of ready sandboxes (hello.pool.maxReady): NULL for a host that does not
--                           list the feature sandbox.pool, which takes no pool settings
--   pool_ready_default      how many wait when no window applies; NULL: no pool settings (D5: none ready)
--   pool_zone               the IANA time zone of the windows' times; required once a window lacks a zone of its own
--   pool_windows            the windows, a JSON array (at most 14, checked by the API): days, from, to, ready, zone
--   pool_ready_target,      the count, and its next change, last sent in host.config.pool; config_version increases
--   pool_next_target,       with every change of them
--   pool_next_at
--   pool_status, _at        the host's latest host.pool.status, for its page
--
-- A host that creates a sandbox per run has no fixed maximum of slots (D12): its ceiling may be up to 1000, a host with
-- Docker still reports at most 16.

ALTER TABLE runner_host
    ADD COLUMN pool_max_ready_ceiling integer,
    ADD COLUMN pool_ready_default     integer,
    ADD COLUMN pool_zone              varchar(64),
    ADD COLUMN pool_windows           jsonb,
    ADD COLUMN pool_ready_target      integer NOT NULL DEFAULT 0,
    ADD COLUMN pool_next_target       integer,
    ADD COLUMN pool_next_at           timestamptz,
    ADD COLUMN pool_status            jsonb,
    ADD COLUMN pool_status_at         timestamptz,
    ADD CONSTRAINT runner_host_pool_ceiling_ck
        CHECK (pool_max_ready_ceiling IS NULL OR pool_max_ready_ceiling BETWEEN 0 AND 1000),
    ADD CONSTRAINT runner_host_pool_default_ck
        CHECK (pool_ready_default IS NULL OR pool_ready_default BETWEEN 0 AND 1000),
    ADD CONSTRAINT runner_host_pool_settings_ck
        CHECK ((pool_ready_default IS NULL) = (pool_windows IS NULL)
            AND (pool_windows IS NULL OR jsonb_typeof(pool_windows) = 'array')
            AND (pool_zone IS NULL OR pool_ready_default IS NOT NULL)),
    ADD CONSTRAINT runner_host_pool_target_ck CHECK (pool_ready_target BETWEEN 0 AND 1000),
    ADD CONSTRAINT runner_host_pool_next_ck
        CHECK ((pool_next_target IS NULL) = (pool_next_at IS NULL)
            AND (pool_next_target IS NULL OR pool_next_target BETWEEN 0 AND 1000)),
    ADD CONSTRAINT runner_host_pool_status_ck
        CHECK ((pool_status IS NULL) = (pool_status_at IS NULL)
            AND (pool_status IS NULL OR jsonb_typeof(pool_status) = 'object'));

-- D12: up to 1000 slots for a host with a sandbox per run.
ALTER TABLE runner_host
    DROP CONSTRAINT runner_host_max_runs_ck,
    DROP CONSTRAINT runner_host_max_runs_ceiling_ck,
    DROP CONSTRAINT runner_host_desired_max_runs_ck,
    ADD CONSTRAINT runner_host_max_runs_ck
        CHECK (max_concurrent_runs BETWEEN 0 AND 1000 AND max_concurrent_runs <= max_concurrent_runs_ceiling),
    ADD CONSTRAINT runner_host_max_runs_ceiling_ck CHECK (max_concurrent_runs_ceiling BETWEEN 1 AND 1000),
    ADD CONSTRAINT runner_host_desired_max_runs_ck
        CHECK (desired_max_concurrent_runs IS NULL OR desired_max_concurrent_runs BETWEEN 1 AND 1000);

-- The same for a member's machine (ADR-0092), whose slots its presences share.
ALTER TABLE personal_runner_host
    DROP CONSTRAINT personal_runner_host_max_runs_ck,
    DROP CONSTRAINT personal_runner_host_max_runs_ceiling_ck,
    DROP CONSTRAINT personal_runner_host_desired_max_runs_ck,
    ADD CONSTRAINT personal_runner_host_max_runs_ck
        CHECK (max_concurrent_runs BETWEEN 0 AND 1000 AND max_concurrent_runs <= max_concurrent_runs_ceiling),
    ADD CONSTRAINT personal_runner_host_max_runs_ceiling_ck CHECK (max_concurrent_runs_ceiling BETWEEN 1 AND 1000),
    ADD CONSTRAINT personal_runner_host_desired_max_runs_ck
        CHECK (desired_max_concurrent_runs IS NULL OR desired_max_concurrent_runs BETWEEN 1 AND 1000);
