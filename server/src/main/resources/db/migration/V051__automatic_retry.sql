-- Automatic retries (ADR-0073): a turn that failed for a reason that may pass (the model's provider or gateway
-- unavailable or limiting, the runner host lost) is retried by itself, a few times with a growing wait, when the
-- settings' owner chose so.
--
--   agent_settings.auto_retry  the opt-in of the settings' owner (a person, or a trigger's author), off by default:
--                              an automatic retry spends the payer's budget without a person asking for it
--   conversation.auto_retries  the automatic retries in a row so far
--   run.queue_reason           RETRY_SCHEDULED: an automatic retry waits until run.dispatch_after
--
-- The columns join tables of V003, V004 and V005; their grants and row-level security apply.

ALTER TABLE agent_settings
    ADD COLUMN auto_retry boolean NOT NULL DEFAULT false;

-- conversation.auto_retries: how many automatic retries in a row the conversation's latest turn is (0 for a turn a
-- person or the work started); an automatic retry follows a failed turn only while it is below the limit, three.
ALTER TABLE conversation
    ADD COLUMN auto_retries smallint NOT NULL DEFAULT 0,
    ADD CONSTRAINT conversation_auto_retries_ck CHECK (auto_retries >= 0);

-- ---- a run's queue reason (CONVENTIONS §15.22): RETRY_SCHEDULED, an automatic retry that waits until its time ------

ALTER TABLE run DROP CONSTRAINT run_queue_reason_ck;
ALTER TABLE run ADD CONSTRAINT run_queue_reason_ck CHECK (queue_reason IS NULL OR queue_reason IN (
    'COMPANY_STOPPED', 'SYSTEM_PAUSED',
    'AGENT_PAUSED', 'ACCOUNT_REQUIRED', 'BUDGET_SPENT', 'KEY_ERROR', 'HOST_OFFLINE', 'MODEL_ACCESS_UNAVAILABLE',
    'NO_HOST_FOR_CREDENTIALS', 'NO_RUNNER_HOST', 'RUNNER_HOST_OFFLINE', 'RUNNER_HOST_DRAINING',
    'CREDENTIAL_POOL_FULL', 'RUNNER_HOST_FULL', 'PREVIOUS_RUN_ACTIVE', 'RUNNER_HOST_REJECTED', 'ASSIGNED',
    'TRIGGER_PAUSED', 'NO_HOST_FOR_SKILLS', 'RETRY_SCHEDULED', 'OTHER'));
