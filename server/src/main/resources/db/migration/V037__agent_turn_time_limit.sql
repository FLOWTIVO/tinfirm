-- An agent's time limit for one turn, and the failure class of a turn stopped by it. The schema owner runs this file.
--
-- turn_time_limit_minutes: how long one run of the agent may take, in minutes (5 to 1440), sent to the runner host as
-- run.assign.limits.maxWallClockSeconds; NULL takes the deployment's TINFIRM_RUN_MAX_WALL_CLOCK (60 minutes by
-- default). The runner host's other guards (the tool calls, a repeated call, inactivity) are unchanged.
ALTER TABLE agent
    ADD COLUMN turn_time_limit_minutes integer,
    ADD CONSTRAINT agent_turn_time_limit_ck CHECK (
        turn_time_limit_minutes IS NULL OR turn_time_limit_minutes BETWEEN 5 AND 1440);

-- TIME_LIMIT: the runner host stopped the run at its time limit. It was a GUARD_STOPPED before; the turn can go on
-- where it stopped, since the session and the work so far are kept, so it is told apart from the other guards. The
-- list keeps V035's MODEL_REQUIRED and MODEL_NOT_SERVED.
ALTER TABLE run
    DROP CONSTRAINT run_failure_class_ck,
    ADD CONSTRAINT run_failure_class_ck CHECK (failure_class IS NULL OR failure_class IN (
        'USAGE_LIMIT', 'SERVER_LIMITED', 'CONTEXT_OVERFLOW', 'LOGIN_EXPIRED', 'VENDOR_BLOCKED', 'CREDENTIAL_LEAK',
        'PROTOCOL_ERROR', 'GUARD_STOPPED', 'TIME_LIMIT', 'GIT_ERROR', 'INFRA', 'RUNNER_UNAVAILABLE', 'MODEL_REFUSED',
        'LOST', 'RUNTIME_REMOVED', 'MODEL_NOT_ALLOWED', 'MODEL_REQUIRED', 'MODEL_NOT_SERVED'));
