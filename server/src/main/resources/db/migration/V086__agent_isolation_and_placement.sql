-- ADR-0113, decision 4: where an agent's runs go by the level of isolation its company's hosts report (V085).
--
--   agent.isolation_level   the level chosen for the agent (MACHINE, VM_JAIL, CONTAINER); NULL is automatic, the
--                           strongest level among the hosts that count for a run. Existing agents are automatic.
--   run.isolation_level     the level the run's host was placed at when the run was assigned (a host without a level
--                           is placed at CONTAINER); NULL before its first assignment, and for runs from before V086
--   run.placement_note      why the turn ran where it did when its conversation's session was on a host of another
--                           level: STAYED_ON_WEAKER_HOST (an automatic agent's conversation stayed with its session),
--                           FRESH_SESSION_CHOSEN_LEVEL (the chosen level holds: a fresh session on it),
--                           FRESH_SESSION_STRONGER_LEVEL (the weaker host of the session could not take the turn);
--                           NULL otherwise
--   run.queue_reason        NO_HOST_OF_LEVEL: no host that may run the conversation reports the level chosen for its
--                           agent, and the run waits for one
--
-- Both tables keep their forced row-level security and their grants; no table is added.

ALTER TABLE agent
    ADD COLUMN isolation_level varchar(16),
    ADD CONSTRAINT agent_isolation_level_ck
        CHECK (isolation_level IS NULL OR isolation_level IN ('MACHINE', 'VM_JAIL', 'CONTAINER'));

ALTER TABLE run
    ADD COLUMN isolation_level varchar(16),
    ADD COLUMN placement_note  varchar(40),
    ADD CONSTRAINT run_isolation_level_ck
        CHECK (isolation_level IS NULL OR isolation_level IN ('MACHINE', 'VM_JAIL', 'CONTAINER')),
    ADD CONSTRAINT run_placement_note_ck
        CHECK (placement_note IS NULL OR (isolation_level IS NOT NULL AND placement_note IN
            ('STAYED_ON_WEAKER_HOST', 'FRESH_SESSION_CHOSEN_LEVEL', 'FRESH_SESSION_STRONGER_LEVEL')));

ALTER TABLE run DROP CONSTRAINT run_queue_reason_ck;
ALTER TABLE run ADD CONSTRAINT run_queue_reason_ck CHECK (queue_reason IS NULL OR queue_reason IN (
    'COMPANY_STOPPED', 'SYSTEM_PAUSED',
    'AGENT_PAUSED', 'ACCOUNT_REQUIRED', 'BUDGET_SPENT', 'KEY_ERROR', 'NO_MODEL_KEY', 'HOST_OFFLINE',
    'MODEL_ACCESS_UNAVAILABLE', 'NO_HOST_FOR_CREDENTIALS', 'NO_RUNNER_HOST', 'NO_HOST_OF_LEVEL', 'RUNNER_HOST_OFFLINE',
    'RUNNER_HOST_DRAINING', 'CREDENTIAL_POOL_FULL', 'RUNNER_HOST_FULL', 'PREVIOUS_RUN_ACTIVE', 'RUNNER_HOST_REJECTED',
    'ASSIGNED', 'TRIGGER_PAUSED', 'NO_HOST_FOR_SKILLS', 'RETRY_SCHEDULED', 'OTHER'));
