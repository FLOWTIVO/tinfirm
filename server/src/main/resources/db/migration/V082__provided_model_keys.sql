-- Keys a company's gateway team provides (ADR-0094, phase 1): a company's gateway may be one whose keys Tinfirm does
-- not make (PROVIDED). Tinfirm then holds no admin key: the gateway team creates a key per principal, and the company's
-- own, with their budgets and models, and hands each to Tinfirm, which checks it with the gateway's model list, keeps
-- it in the vault and pays with it. The schema owner runs this file.
--
--   model_settings.gateway        BUNDLED, EXTERNAL, or PROVIDED: the company's own gateway, by its model URL alone
--   model_settings.model_url      a PROVIDED gateway's root for model requests (OpenAI's and Anthropic's routes)
--   model_settings.member_keys    whether members may enter their own key (PROVIDED only); admins and the API
--                                 always may
--   model_settings.vendor         GENERIC too: a PROVIDED gateway of no vendor Tinfirm knows
--   *_model_key.provided_at       when the current key was entered (NULL: Tinfirm made it)
--   *_model_key.provided_by       who entered it: a user, or NULL for a service account's API token
--   *_model_key.provided_by_self  whether the principal entered its own key (a member, where the company allows it)
--   *_model_key.key_hint          the key's last four characters, as the app shows it; the key itself is never shown
--   run.queue_reason              NO_MODEL_KEY too: the payer has no key yet at a PROVIDED gateway, and the run waits
--                                 until someone enters one

ALTER TABLE model_settings DROP CONSTRAINT model_settings_gateway_ck;
ALTER TABLE model_settings
    ADD CONSTRAINT model_settings_gateway_ck CHECK (gateway IN ('BUNDLED', 'EXTERNAL', 'PROVIDED'));

ALTER TABLE model_settings
    ADD COLUMN model_url varchar(500),
    ADD COLUMN member_keys boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT model_settings_provided_ck CHECK ((gateway = 'PROVIDED') = (model_url IS NOT NULL)),
    ADD CONSTRAINT model_settings_member_keys_ck CHECK (NOT member_keys OR gateway = 'PROVIDED');

ALTER TABLE model_settings DROP CONSTRAINT model_settings_vendor_ck;
ALTER TABLE model_settings
    ADD CONSTRAINT model_settings_vendor_ck CHECK (vendor IN ('LITELLM', 'GENERIC')
        AND (vendor <> 'GENERIC' OR gateway = 'PROVIDED'));

ALTER TABLE principal_model_key
    ADD COLUMN provided_at      timestamptz,
    ADD COLUMN provided_by      uuid REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN provided_by_self boolean NOT NULL DEFAULT false,
    ADD COLUMN key_hint         varchar(8),
    ADD CONSTRAINT principal_model_key_provided_ck CHECK (provided_at IS NOT NULL OR (provided_by IS NULL
        AND NOT provided_by_self AND key_hint IS NULL));

ALTER TABLE company_model_key
    ADD COLUMN provided_at      timestamptz,
    ADD COLUMN provided_by      uuid REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN provided_by_self boolean NOT NULL DEFAULT false,
    ADD COLUMN key_hint         varchar(8),
    ADD CONSTRAINT company_model_key_provided_ck CHECK (provided_at IS NOT NULL OR (provided_by IS NULL
        AND NOT provided_by_self AND key_hint IS NULL)),
    ADD CONSTRAINT company_model_key_self_ck CHECK (NOT provided_by_self);

-- ---- a run's queue reason: NO_MODEL_KEY, a payer without a key at a gateway whose keys its team provides ---------

ALTER TABLE run DROP CONSTRAINT run_queue_reason_ck;
ALTER TABLE run ADD CONSTRAINT run_queue_reason_ck CHECK (queue_reason IS NULL OR queue_reason IN (
    'COMPANY_STOPPED', 'SYSTEM_PAUSED',
    'AGENT_PAUSED', 'ACCOUNT_REQUIRED', 'BUDGET_SPENT', 'KEY_ERROR', 'NO_MODEL_KEY', 'HOST_OFFLINE',
    'MODEL_ACCESS_UNAVAILABLE', 'NO_HOST_FOR_CREDENTIALS', 'NO_RUNNER_HOST', 'RUNNER_HOST_OFFLINE',
    'RUNNER_HOST_DRAINING', 'CREDENTIAL_POOL_FULL', 'RUNNER_HOST_FULL', 'PREVIOUS_RUN_ACTIVE', 'RUNNER_HOST_REJECTED',
    'ASSIGNED', 'TRIGGER_PAUSED', 'NO_HOST_FOR_SKILLS', 'RETRY_SCHEDULED', 'OTHER'));
