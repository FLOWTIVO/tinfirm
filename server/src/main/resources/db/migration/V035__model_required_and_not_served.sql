-- A run paid by a budget names its model, and a model the gateway does not serve is said so.
--
-- The run's failure classes gain the server's MODEL_REQUIRED (a budget's settings name no model: the company's
-- gateway serves only the models it lists, and a runtime's own default need not be one of them) and MODEL_NOT_SERVED
-- (the gateway answered that it does not serve the model the run asked for; the runner, which sees only the
-- runtime's error, would class it PROTOCOL_ERROR).
ALTER TABLE run DROP CONSTRAINT run_failure_class_ck;
ALTER TABLE run ADD CONSTRAINT run_failure_class_ck CHECK (failure_class IS NULL OR failure_class IN (
    'USAGE_LIMIT', 'SERVER_LIMITED', 'CONTEXT_OVERFLOW', 'LOGIN_EXPIRED', 'VENDOR_BLOCKED', 'CREDENTIAL_LEAK',
    'PROTOCOL_ERROR', 'GUARD_STOPPED', 'GIT_ERROR', 'INFRA', 'RUNNER_UNAVAILABLE', 'MODEL_REFUSED', 'LOST',
    'RUNTIME_REMOVED', 'MODEL_NOT_ALLOWED', 'MODEL_REQUIRED', 'MODEL_NOT_SERVED'));

-- The model the gateway last answered it does not serve, for a request with this token; the run's end reads it.
ALTER TABLE run_model_token ADD COLUMN model_not_served varchar(200);
