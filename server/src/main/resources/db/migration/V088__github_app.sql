-- ADR-0116, decisions 6 to 9: a GitHub App on a GITHUB system, credentials backed by one of its installations, and
-- the queue reason of a run that waits for a host that renews such tokens.
--
--   system_github_app      a GITHUB system's one App (the owner's answer 2): its numeric app_id, its slug and name as
--                          GitHub answered when it was set (for the system's page), and its private key as a managed
--                          company secret (private_key_secret_id, named SYSTEM_GITHUB_APP_KEY), never shown again. The
--                          App is the company's own (ADR-0007). Only a company system of kind GITHUB has one: the
--                          system module sets it, and checks the kind. Removed with its system.
--   account.kind           gains APP: a credential of the system (principal_kind SYSTEM, never a member's own account)
--                          that is one installation of the system's App. Its secret holds the installation's id and the
--                          token the server minted last for its own calls; external_id is installation:{id}, so a
--                          system has at most one credential per installation (account_app_installation_uq), and
--                          external_login is the login of the account the App is installed on.
--   run.queue_reason       gains NO_HOST_FOR_APP_TOKENS: a run with a binding on an APP credential waits for a host that
--                          lists run.git.refresh and run.git.revoke.
--
-- The conventions of V001 apply: company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on app_company_id(),
-- composite foreign keys, and grants to the runtime role ${app_role}, which owns nothing. The schema owner runs this
-- file.

CREATE TABLE system_github_app (
    system_id            uuid         PRIMARY KEY,
    company_id           uuid         NOT NULL,
    app_id               bigint       NOT NULL,
    app_slug             varchar(100),
    app_name             varchar(200),
    private_key_secret_id uuid        NOT NULL,
    set_at               timestamptz  NOT NULL,
    set_by               uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT system_github_app_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT system_github_app_secret_fk FOREIGN KEY (private_key_secret_id, company_id)
        REFERENCES secret (id, company_id),
    CONSTRAINT system_github_app_app_id_ck CHECK (app_id > 0)
);

CREATE INDEX system_github_app_company_idx ON system_github_app (company_id);
CREATE INDEX system_github_app_secret_idx ON system_github_app (private_key_secret_id);
CREATE INDEX system_github_app_set_by_idx ON system_github_app (set_by) WHERE set_by IS NOT NULL;

ALTER TABLE system_github_app ENABLE ROW LEVEL SECURITY;
ALTER TABLE system_github_app FORCE ROW LEVEL SECURITY;

CREATE POLICY system_github_app_tenant ON system_github_app FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON system_github_app TO ${app_role};

ALTER TABLE account DROP CONSTRAINT account_kind_ck;
ALTER TABLE account
    ADD CONSTRAINT account_kind_ck CHECK (kind IN ('TOKEN', 'OAUTH', 'CLIENT', 'APP', 'LINK'));

ALTER TABLE account DROP CONSTRAINT account_holder_ck;
ALTER TABLE account
    ADD CONSTRAINT account_holder_ck CHECK (
        principal_kind IN ('MEMBER', 'SYSTEM')
        AND (principal_kind = 'SYSTEM') = (principal_id IS NULL)
        AND (principal_kind = 'SYSTEM') = (name IS NOT NULL)
        AND (name IS NULL OR length(btrim(name)) BETWEEN 1 AND 100)
        AND (enabled OR principal_kind = 'SYSTEM')
        AND (principal_kind <> 'SYSTEM' OR kind IN ('TOKEN', 'OAUTH', 'CLIENT', 'APP'))
        AND (kind NOT IN ('CLIENT', 'APP') OR principal_kind = 'SYSTEM')
        AND (kind <> 'APP' OR external_id ~ '^installation:[1-9][0-9]{0,18}$'));

CREATE UNIQUE INDEX account_app_installation_uq ON account (system_id, external_id) WHERE kind = 'APP';

ALTER TABLE run DROP CONSTRAINT run_queue_reason_ck;
ALTER TABLE run ADD CONSTRAINT run_queue_reason_ck CHECK (queue_reason IS NULL OR queue_reason IN (
    'COMPANY_STOPPED', 'SYSTEM_PAUSED',
    'AGENT_PAUSED', 'ACCOUNT_REQUIRED', 'BUDGET_SPENT', 'KEY_ERROR', 'NO_MODEL_KEY', 'HOST_OFFLINE',
    'MODEL_ACCESS_UNAVAILABLE', 'NO_HOST_FOR_CREDENTIALS', 'NO_RUNNER_HOST', 'NO_HOST_OF_LEVEL', 'RUNNER_HOST_OFFLINE',
    'RUNNER_HOST_DRAINING', 'CREDENTIAL_POOL_FULL', 'RUNNER_HOST_FULL', 'PREVIOUS_RUN_ACTIVE', 'RUNNER_HOST_REJECTED',
    'ASSIGNED', 'TRIGGER_PAUSED', 'NO_HOST_FOR_SKILLS', 'NO_HOST_FOR_APP_TOKENS', 'RETRY_SCHEDULED', 'OTHER'));
