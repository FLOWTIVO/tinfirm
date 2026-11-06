-- The company's own model key, and the company's memory settings (ADR-0065).
--
--   company_model_key   the company's own LiteLLM key: no principal's, with a budget and models of its own or the
--                       company's default, synced, rotated and reconciled as a principal's key is; it pays for
--                       Tinfirm's own model requests (the tidying of agents' memories, ADR-0063), never for a run
--   memory_settings     the company's settings of agents' memory: the model that tidies them, on the company's key
--
-- They replace the platform credential of V039 (a service identity a company named in its model settings, whose key
-- paid for those requests): its columns go, and the model a company chose carries over to its memory settings. The
-- service identity stays, with its key; it no longer pays for anything of Tinfirm's.
--
-- The conventions of V001 apply: company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on app_company_id(),
-- composite foreign keys with the company id, and grants to ${app_role}.

-- ---- the company's own key ---------------------------------------------------------------------------------------

-- The columns of principal_model_key (V002, rotation V014) but the principal's; one row a company at most, created by
-- the first sync once the company's gateway passed its test.
CREATE TABLE company_model_key (
    company_id            uuid          PRIMARY KEY REFERENCES company (id),
    secret_id             uuid,
    litellm_key_id        varchar(128),
    budget_usd            numeric(12,4),
    models                jsonb,
    status                varchar(16)   NOT NULL DEFAULT 'ERROR',
    last_error            varchar(500),
    spent_usd             numeric(16,6),
    budget_resets_at      timestamptz,
    synced_at             timestamptz,
    sync_lease_until      timestamptz,
    created_at            timestamptz   NOT NULL,
    updated_at            timestamptz   NOT NULL,
    key_generation        integer       NOT NULL DEFAULT 0,
    key_created_at        timestamptz,
    key_rotated_at        timestamptz,
    rotation_requested_at timestamptz,
    rotation_error        varchar(500),
    retiring_key_id       varchar(128),
    retiring_until        timestamptz,
    retiring_blocked_at   timestamptz,
    retiring_spend_usd    numeric(16,6),
    retiring_resets_at    timestamptz,
    CONSTRAINT company_model_key_secret_fk FOREIGN KEY (secret_id, company_id)
        REFERENCES secret (id, company_id) ON DELETE SET NULL (secret_id),
    CONSTRAINT company_model_key_status_ck CHECK (status IN ('OK', 'BUDGET_SPENT', 'BLOCKED', 'ERROR')),
    CONSTRAINT company_model_key_usable_ck CHECK (status <> 'OK' OR (secret_id IS NOT NULL
                                                                      AND litellm_key_id IS NOT NULL)),
    CONSTRAINT company_model_key_budget_ck CHECK (budget_usd IS NULL OR budget_usd >= 0),
    CONSTRAINT company_model_key_models_ck CHECK (models IS NULL OR jsonb_typeof(models) = 'array'),
    CONSTRAINT company_model_key_generation_ck CHECK (key_generation >= 0),
    CONSTRAINT company_model_key_created_ck CHECK (litellm_key_id IS NULL OR key_created_at IS NOT NULL),
    CONSTRAINT company_model_key_retiring_ck CHECK ((retiring_key_id IS NULL) = (retiring_until IS NULL)),
    CONSTRAINT company_model_key_retiring_blocked_ck CHECK (retiring_blocked_at IS NULL OR retiring_key_id IS NOT NULL),
    CONSTRAINT company_model_key_retiring_other_ck
        CHECK (retiring_key_id IS NULL OR retiring_key_id IS DISTINCT FROM litellm_key_id)
);

-- M1: the company's key is never a principal's, nor the key of another company's.
CREATE UNIQUE INDEX company_model_key_secret_uq ON company_model_key (secret_id) WHERE secret_id IS NOT NULL;

ALTER TABLE company_model_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_model_key FORCE ROW LEVEL SECURITY;

CREATE POLICY company_model_key_tenant ON company_model_key FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Created by its first sync, then synced, rotated and forgotten in place; never deleted but with its company.
GRANT SELECT, INSERT, UPDATE ON company_model_key TO ${app_role};

-- ---- the company's memory settings -------------------------------------------------------------------------------

-- No row, or no model: a memory's compaction only removes duplicates.
CREATE TABLE memory_settings (
    company_id       uuid         PRIMARY KEY REFERENCES company (id),
    compaction_model varchar(200),
    updated_at       timestamptz  NOT NULL,
    updated_by       uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT memory_settings_model_ck
        CHECK (compaction_model IS NULL OR compaction_model ~ '^[A-Za-z0-9][A-Za-z0-9._:/@+\[\]-]{0,199}$')
);

-- The model a company chose for its platform credential carries over. The schema owner bypasses row-level security
-- for this step only, as in V014.
ALTER TABLE model_settings NO FORCE ROW LEVEL SECURITY;
INSERT INTO memory_settings (company_id, compaction_model, updated_at, updated_by)
SELECT company_id, platform_model, updated_at, updated_by
  FROM model_settings
 WHERE platform_principal_id IS NOT NULL AND platform_model IS NOT NULL;
ALTER TABLE model_settings FORCE ROW LEVEL SECURITY;

ALTER TABLE memory_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE memory_settings FORCE ROW LEVEL SECURITY;

CREATE POLICY memory_settings_tenant ON memory_settings FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Changed in place, never removed.
GRANT SELECT, INSERT, UPDATE ON memory_settings TO ${app_role};

-- ---- the platform credential goes --------------------------------------------------------------------------------

DROP INDEX model_settings_platform_idx;
ALTER TABLE model_settings
    DROP CONSTRAINT model_settings_platform_model_ck,
    DROP CONSTRAINT model_settings_platform_ck,
    DROP CONSTRAINT model_settings_platform_fk,
    DROP CONSTRAINT model_settings_platform_kind_ck,
    DROP COLUMN platform_model,
    DROP COLUMN platform_principal_kind,
    DROP COLUMN platform_principal_id;
