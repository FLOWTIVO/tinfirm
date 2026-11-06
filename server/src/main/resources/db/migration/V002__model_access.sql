-- Package 2 of the platform redesign (docs/reviews/2026-10-12-package-2-contract.md; ADR-0059): who pays for a run,
-- how Tinfirm manages LiteLLM, where a run may execute, and the one-run tokens of the server's model relay.
--
--   runner_host          becomes COMPANY or PERSONAL (contract §2, M4): owner_user_id gives way to the owner's
--                        principal, set for a personal host only
--   runner_credential    a credential a personal host reports in hello, by label (contract §2, M5): its owner's
--                        subscription or key; a company host holds none
--   model_settings       the company's LiteLLM and its defaults (contract §3.1)
--   principal_model_key  a principal's LiteLLM key (contract §3.2, M1)
--   run_model_token      a run's one-run token for the model relay (contract §6.1, M7)
--   secret.internal      the vault's secrets that Tinfirm keeps for itself: a principal's LiteLLM key
--
-- The conventions of V001 apply: every tenant table has company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on
-- app_company_id(), composite foreign keys with the company id, and grants to ${app_role}.

-- ---- the vault: internal secrets ---------------------------------------------------------------------------------

-- A secret Tinfirm keeps for itself (contract §3.2: a principal's LiteLLM key, which never leaves the server): never
-- listed, shown, changed or deleted through the Secrets API (its id is 404 there, as for a secret a person owns), and
-- never named by anything a member configures; only the module that keeps it reveals, replaces and deletes it. It is a
-- company secret (no owner), encrypted as every secret is, and covered by the rewrap and the upload scan.
ALTER TABLE secret ADD COLUMN internal boolean NOT NULL DEFAULT false;
ALTER TABLE secret ADD CONSTRAINT secret_internal_ck CHECK (NOT internal OR owner_user_id IS NULL);

-- ---- runner hosts: company or personal (contract §2, M4, M5) -----------------------------------------------------

-- A COMPANY host is registered by an admin and runs any conversation; a PERSONAL host is registered by a member, whose
-- principal owns it, and runs only its owner's work (Hosts.eligible, contract §5.2). The scope is set when the host is
-- registered and never changes (runner_host_scope_trg); a personal host goes when its owner leaves the company.
--
--   owner_principal_id  the member's principal for a personal host; NULL for a company host
--   owner_kind          always MEMBER: the foreign key with it admits no service identity as an owner
ALTER TABLE runner_host
    ADD COLUMN scope              varchar(8),
    ADD COLUMN owner_principal_id uuid,
    ADD COLUMN owner_kind         varchar(8) NOT NULL DEFAULT 'MEMBER';

-- A host registered before this migration was its registering member's: it becomes that member's personal host, and one
-- whose member has no principal in the company any longer goes. The schema owner bypasses row-level security for this
-- step, and for the check of the new foreign key on the rows it kept, only.
ALTER TABLE runner_host NO FORCE ROW LEVEL SECURITY;
ALTER TABLE principal NO FORCE ROW LEVEL SECURITY;
UPDATE runner_host h
   SET scope = 'PERSONAL', owner_principal_id = p.id
  FROM principal p
 WHERE p.company_id = h.company_id AND p.user_id = h.owner_user_id AND p.kind = 'MEMBER' AND p.status <> 'LEFT';
DELETE FROM runner_host WHERE scope IS NULL;

ALTER TABLE runner_host DROP COLUMN owner_user_id;
ALTER TABLE runner_host ALTER COLUMN scope SET NOT NULL;
ALTER TABLE runner_host
    ADD CONSTRAINT runner_host_scope_ck CHECK (scope IN ('COMPANY', 'PERSONAL')),
    ADD CONSTRAINT runner_host_owner_ck CHECK ((scope = 'PERSONAL') = (owner_principal_id IS NOT NULL)),
    ADD CONSTRAINT runner_host_owner_kind_ck CHECK (owner_kind = 'MEMBER'),
    ADD CONSTRAINT runner_host_owner_fk FOREIGN KEY (owner_principal_id, company_id, owner_kind)
        REFERENCES principal (id, company_id, kind),
    -- The target of runner_credential_host_fk, which admits personal hosts only (M5).
    ADD CONSTRAINT runner_host_id_company_scope_uq UNIQUE (id, company_id, scope);
ALTER TABLE runner_host FORCE ROW LEVEL SECURITY;
ALTER TABLE principal FORCE ROW LEVEL SECURITY;

-- A member's personal hosts: listing, eligibility, and the foreign key to the principal.
CREATE INDEX runner_host_owner_idx ON runner_host (company_id, owner_principal_id) WHERE owner_principal_id IS NOT NULL;

-- A host's scope and owner never change: a company host never becomes a member's, nor the reverse.
CREATE FUNCTION runner_host_scope_fixed_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.scope <> OLD.scope OR NEW.owner_principal_id IS DISTINCT FROM OLD.owner_principal_id THEN
        RAISE EXCEPTION 'A runner host''s scope and owner never change'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'runner_host_scope_fixed_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER runner_host_scope_fixed_trg BEFORE UPDATE OF scope, owner_principal_id ON runner_host
    FOR EACH ROW EXECUTE FUNCTION runner_host_scope_fixed_ck();

-- A credential a personal host holds (contract §2, M5): a subscription (CLAUDE_OAUTH_TOKEN, CODEX_CHATGPT_AUTH) or a
-- provider key (ANTHROPIC_API_KEY, OPENAI_API_KEY) on the host, reported by label in hello, never its value. It is its
-- owner's model access and serves only its owner's runs. Only a personal host holds one: host_scope is always
-- PERSONAL, and the foreign key with it admits no company host (a company host's credentials are not stored).
--
--   runtimes   the runtimes the host says it serves with it
--   pool_size  the most runs the host runs with it at once, as it reported (1 to 16)
--   status     AVAILABLE, COOLDOWN or INVALID as last reported (credential.status); OFFLINE is derived from the
--              connection when read
--   reported   false when the host's latest hello no longer listed it: the subscription is gone (SUBSCRIPTION_GONE)
CREATE TABLE runner_credential (
    id             uuid          PRIMARY KEY,
    company_id     uuid          NOT NULL,
    runner_host_id uuid          NOT NULL,
    host_scope     varchar(8)    NOT NULL DEFAULT 'PERSONAL',
    label          varchar(63)   NOT NULL,
    kind           varchar(32)   NOT NULL,
    runtimes       varchar(16)[] NOT NULL,
    pool_size      integer       NOT NULL,
    status         varchar(16)   NOT NULL DEFAULT 'AVAILABLE',
    cooldown_until timestamptz,
    detail         varchar(500),
    reported       boolean       NOT NULL DEFAULT true,
    created_at     timestamptz   NOT NULL,
    updated_at     timestamptz   NOT NULL,
    CONSTRAINT runner_credential_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT runner_credential_host_label_uq UNIQUE (runner_host_id, label),
    CONSTRAINT runner_credential_personal_ck CHECK (host_scope = 'PERSONAL'),
    CONSTRAINT runner_credential_host_fk FOREIGN KEY (runner_host_id, company_id, host_scope)
        REFERENCES runner_host (id, company_id, scope) ON DELETE CASCADE,
    CONSTRAINT runner_credential_label_ck CHECK (label ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT runner_credential_kind_ck CHECK (kind IN ('CLAUDE_OAUTH_TOKEN', 'CODEX_CHATGPT_AUTH',
                                                         'ANTHROPIC_API_KEY', 'OPENAI_API_KEY')),
    CONSTRAINT runner_credential_runtimes_ck
        CHECK (cardinality(runtimes) >= 1
               AND runtimes <@ ARRAY['CLAUDE_CODE', 'CODEX', 'PI', 'OPENCODE']::varchar(16)[]),
    CONSTRAINT runner_credential_pool_ck CHECK (pool_size BETWEEN 1 AND 16),
    CONSTRAINT runner_credential_status_ck CHECK (status IN ('AVAILABLE', 'COOLDOWN', 'INVALID'))
);

-- The credentials of a page of hosts.
CREATE INDEX runner_credential_company_idx ON runner_credential (company_id, runner_host_id, label);

ALTER TABLE runner_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE runner_credential FORCE ROW LEVEL SECURITY;

CREATE POLICY runner_credential_tenant ON runner_credential FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Credentials go away with their host (ON DELETE CASCADE); one a hello no longer lists stays, unreported.
GRANT SELECT, INSERT, UPDATE ON runner_credential TO ${app_role};

-- ---- model settings (contract §3.1) ------------------------------------------------------------------------------

-- The company's LiteLLM: the one bundled with the deployment (BUNDLED: TINFIRM_LITELLM_ADMIN_URL and
-- TINFIRM_LITELLM_MASTER_KEY), or the company's own (EXTERNAL: an https URL and a master or admin key that an admin
-- entered, kept as a company secret). No row: the company has not configured one, and no conversation starts
-- (GATEWAY_NOT_CONFIGURED). Each company is a LiteLLM team, tinfirm-{companyId} (security review, row 17).
--
--   relay_enabled       whether gateway runs may start: false until LiteLLM answered the admin test, and again after
--                       the gateway, its URL or its key changed
--   default_budget_usd  the budget of every member without an override, in USD per budget_period
--   budget_period       LiteLLM's budget_duration: a number and h, d, w or mo (30d by default)
--   default_models      the model names a key may use by default, a JSON array; empty allows every model the gateway
--                       serves (LiteLLM's rule for an empty list)
--   tested_at,          the last admin test: when, and its failure (NULL when it passed)
--   last_error
CREATE TABLE model_settings (
    company_id          uuid          PRIMARY KEY REFERENCES company (id),
    gateway             varchar(8)    NOT NULL,
    admin_url           varchar(500),
    admin_key_secret_id uuid,
    relay_enabled       boolean       NOT NULL DEFAULT false,
    default_budget_usd  numeric(12,4) NOT NULL,
    budget_period       varchar(8)    NOT NULL DEFAULT '30d',
    default_models      jsonb         NOT NULL DEFAULT '[]'::jsonb,
    tested_at           timestamptz,
    last_error          varchar(500),
    updated_at          timestamptz   NOT NULL,
    updated_by          uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT model_settings_gateway_ck CHECK (gateway IN ('BUNDLED', 'EXTERNAL')),
    CONSTRAINT model_settings_external_ck CHECK ((gateway = 'EXTERNAL') = (admin_url IS NOT NULL)),
    CONSTRAINT model_settings_bundled_key_ck CHECK (gateway = 'EXTERNAL' OR admin_key_secret_id IS NULL),
    CONSTRAINT model_settings_secret_fk FOREIGN KEY (admin_key_secret_id, company_id)
        REFERENCES secret (id, company_id) ON DELETE SET NULL (admin_key_secret_id),
    CONSTRAINT model_settings_budget_ck CHECK (default_budget_usd >= 0),
    CONSTRAINT model_settings_period_ck CHECK (budget_period ~ '^[1-9][0-9]{0,3}(h|d|w|mo)$'),
    CONSTRAINT model_settings_models_ck CHECK (jsonb_typeof(default_models) = 'array')
);

CREATE INDEX model_settings_secret_idx ON model_settings (admin_key_secret_id) WHERE admin_key_secret_id IS NOT NULL;

-- ---- principals' keys (contract §3.2, M1, M6) --------------------------------------------------------------------

-- A principal's LiteLLM key (members and service identities alike): one per principal (M1, the primary key), never
-- shared, granted or reused. The row exists once Tinfirm tried to create the key; a principal who leaves, or a service
-- identity that is deleted, takes its row and secret along, and the key is deleted in LiteLLM after the commit (the
-- reconciliation deletes what that missed).
--
--   secret_id         the virtual key, an internal secret this row manages (secret.internal): never shown, listed or
--                     changed through the Secrets API; NULL while the key does not exist (status ERROR)
--   litellm_key_id    LiteLLM's id of the key (the SHA-256 of the key, its "token"), for update, block and delete
--                     without the key itself; its alias is tinfirm-{companyId}-{principalId}
--   budget_usd,       overrides; NULL takes the company's default
--   models
--   status            OK; BUDGET_SPENT until budget_resets_at; BLOCKED (the principal is suspended); ERROR (LiteLLM
--                     refused or is unreachable, or the key does not exist yet: last_error)
--   spent_usd,        the last values read from LiteLLM
--   budget_resets_at,
--   synced_at
--   sync_lease_until  a replica that talks to LiteLLM about the key holds it until then, so that two never create or
--                     change one key at once
CREATE TABLE principal_model_key (
    principal_id     uuid          PRIMARY KEY,
    company_id       uuid          NOT NULL,
    secret_id        uuid,
    litellm_key_id   varchar(128),
    budget_usd       numeric(12,4),
    models           jsonb,
    status           varchar(16)   NOT NULL DEFAULT 'ERROR',
    last_error       varchar(500),
    spent_usd        numeric(16,6),
    budget_resets_at timestamptz,
    synced_at        timestamptz,
    sync_lease_until timestamptz,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    CONSTRAINT principal_model_key_principal_fk FOREIGN KEY (principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE CASCADE,
    CONSTRAINT principal_model_key_secret_fk FOREIGN KEY (secret_id, company_id)
        REFERENCES secret (id, company_id) ON DELETE SET NULL (secret_id),
    CONSTRAINT principal_model_key_status_ck CHECK (status IN ('OK', 'BUDGET_SPENT', 'BLOCKED', 'ERROR')),
    CONSTRAINT principal_model_key_usable_ck CHECK (status <> 'OK' OR (secret_id IS NOT NULL
                                                                        AND litellm_key_id IS NOT NULL)),
    CONSTRAINT principal_model_key_budget_ck CHECK (budget_usd IS NULL OR budget_usd >= 0),
    CONSTRAINT principal_model_key_models_ck CHECK (models IS NULL OR jsonb_typeof(models) = 'array')
);

-- M1: a key is never another principal's.
CREATE UNIQUE INDEX principal_model_key_secret_uq ON principal_model_key (secret_id) WHERE secret_id IS NOT NULL;
CREATE UNIQUE INDEX principal_model_key_litellm_uq ON principal_model_key (company_id, litellm_key_id)
    WHERE litellm_key_id IS NOT NULL;
CREATE INDEX principal_model_key_company_idx ON principal_model_key (company_id, principal_id);

-- ---- the model relay's one-run tokens (contract §6.1, M7) --------------------------------------------------------

-- A run's token for the server's model relay, created when the run is assigned to a host and revoked when it ends or
-- is reassigned. The relay accepts it only while the run is RUNNING on that host with that epoch (the port
-- RunModelTokens, which package 3's run module implements), and then forwards with the paying principal's key; the
-- token itself (tfm_ and 32 random bytes in base64url) is never stored, only hex(sha256(token)). The run's id,
-- conversation and agent become LiteLLM tags of every relayed request. The run table and its foreign key come with
-- package 3.
--
--   format          ANTHROPIC or OPENAI: the relay's root the token is for
--   request_count,  what the relay passed for the run (contract §6.1: audited per run, not per request)
--   request_bytes,
--   response_bytes
CREATE TABLE run_model_token (
    token_hash          char(64)     PRIMARY KEY,
    company_id          uuid         NOT NULL REFERENCES company (id),
    run_id              uuid         NOT NULL,
    epoch               bigint       NOT NULL,
    runner_host_id      uuid         NOT NULL,
    paying_principal_id uuid         NOT NULL,
    format              varchar(16)  NOT NULL,
    conversation_id     uuid,
    agent_id            uuid,
    request_count       bigint       NOT NULL DEFAULT 0,
    request_bytes       bigint       NOT NULL DEFAULT 0,
    response_bytes      bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL,
    revoked_at          timestamptz,
    CONSTRAINT run_model_token_hash_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT run_model_token_format_ck CHECK (format IN ('ANTHROPIC', 'OPENAI')),
    CONSTRAINT run_model_token_epoch_ck CHECK (epoch >= 0),
    CONSTRAINT run_model_token_host_fk FOREIGN KEY (runner_host_id, company_id)
        REFERENCES runner_host (id, company_id) ON DELETE CASCADE,
    CONSTRAINT run_model_token_principal_fk FOREIGN KEY (paying_principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE CASCADE
);

-- A run's tokens (revoke, usage), the sweep of revoked ones, and the foreign keys' lookups.
CREATE INDEX run_model_token_run_idx ON run_model_token (company_id, run_id);
CREATE INDEX run_model_token_revoked_idx ON run_model_token (company_id, revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX run_model_token_host_idx ON run_model_token (runner_host_id);
CREATE INDEX run_model_token_principal_idx ON run_model_token (paying_principal_id);

ALTER TABLE model_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE model_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE principal_model_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE principal_model_key FORCE ROW LEVEL SECURITY;
ALTER TABLE run_model_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_model_token FORCE ROW LEVEL SECURITY;

CREATE POLICY model_settings_tenant ON model_settings FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY principal_model_key_tenant ON principal_model_key FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY run_model_token_tenant ON run_model_token FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Settings are changed in place, never removed (a company's row goes with nothing). A key's row is created, synced,
-- and deleted when its principal leaves or is deleted. A token is revoked in place and swept a day later.
GRANT SELECT, INSERT, UPDATE ON model_settings TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON principal_model_key TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON run_model_token TO ${app_role};

-- ---- cross-company lookups (CONVENTIONS §7, as in V001) ----------------------------------------------------------

CREATE POLICY model_settings_owner_lookup ON model_settings FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY run_model_token_owner_lookup ON run_model_token FOR SELECT TO CURRENT_USER USING (true);

-- The companies whose gateway passed its admin test: the reconciliation of their keys (a worker job).
CREATE FUNCTION model_settings_companies()
    RETURNS TABLE (company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT s.company_id
      FROM ${flyway:defaultSchema}.model_settings s
     WHERE s.relay_enabled
     ORDER BY s.company_id
$$;

-- The company of a one-run token, by hex(sha256(token)): the model relay, before any company context exists.
CREATE FUNCTION run_model_token_company(p_token_hash text)
    RETURNS TABLE (company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.company_id
      FROM ${flyway:defaultSchema}.run_model_token t
     WHERE t.token_hash = p_token_hash
$$;

REVOKE ALL ON FUNCTION model_settings_companies() FROM PUBLIC;
REVOKE ALL ON FUNCTION run_model_token_company(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION model_settings_companies() TO ${app_role};
GRANT EXECUTE ON FUNCTION run_model_token_company(text) TO ${app_role};
