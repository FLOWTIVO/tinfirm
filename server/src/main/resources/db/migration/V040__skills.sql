-- The skills library (ADR-0064; docs/reviews/2026-10-15-skills-contract.md, §3): a company's skills in the Agent Skills
-- format, in immutable versions that agents pin, switched off in a person's or a trigger's settings, and the skills a
-- run was assigned.
--
--   skill                 a skill of the library, private to its owner until shared (contract §3.1, §4.1)
--   skill_version         a version of its folder, as a canonical zip, written once (§3.2, §4.2)
--   skill_share,          the groups and the members that see and use it (§3.3, §4.3)
--   skill_member_share
--   agent_skill           a skill of an agent at the version it pins (§3.4, §5.1)
--   agent_settings_skill  a skill switched off in a person's or a trigger's settings (§3.5, §5.3)
--   run_skill             the skills a run was assigned, which its runner host downloads (§3.6, §7)
--
-- The conventions of V001 apply: every tenant table has company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on
-- app_company_id(), composite foreign keys with the company id, and grants to ${app_role}.

-- ---- skills (contract §3.1) --------------------------------------------------------------------------------------

-- A skill of the library.
--
--   name                the Agent Skills name (lower-case letters and digits in hyphen-separated runs, at most 64),
--                       its folder's name in a sandbox; unique among the company's live skills (DUPLICATE_SKILL_NAME)
--   owner_principal_id  the member who edits it besides admins: its creator; NULL once it passed to the admins.
--                       owner_kind is always MEMBER: the foreign key with it admits no service identity
--   latest_version      the newest row of skill_version
--   updated_at          the last new version or change of its shares
--   deleted_at,         soft delete: its versions stay for the agents that pin them, its name is free again
--   deleted_by
CREATE TABLE skill (
    id                 uuid         PRIMARY KEY,
    company_id         uuid         NOT NULL REFERENCES company (id),
    name               varchar(64)  NOT NULL,
    owner_principal_id uuid,
    owner_kind         varchar(8)   NOT NULL DEFAULT 'MEMBER',
    latest_version     integer      NOT NULL DEFAULT 1,
    deleted_at         timestamptz,
    deleted_by         uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    created_by         uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT skill_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT skill_name_ck CHECK (name ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(name) <= 64),
    CONSTRAINT skill_owner_kind_ck CHECK (owner_kind = 'MEMBER'),
    CONSTRAINT skill_owner_fk FOREIGN KEY (owner_principal_id, company_id, owner_kind)
        REFERENCES principal (id, company_id, kind),
    CONSTRAINT skill_latest_version_ck CHECK (latest_version BETWEEN 1 AND 100),
    CONSTRAINT skill_deleted_ck CHECK (deleted_by IS NULL OR deleted_at IS NOT NULL)
);

-- DUPLICATE_SKILL_NAME among the live skills; a deleted skill frees its name.
CREATE UNIQUE INDEX skill_name_uq ON skill (company_id, name) WHERE deleted_at IS NULL;
-- listSkills: keyset on (name, id).
CREATE INDEX skill_page_idx ON skill (company_id, name, id);
-- A member's skills, when they leave.
CREATE INDEX skill_owner_idx ON skill (company_id, owner_principal_id) WHERE owner_principal_id IS NOT NULL;

-- ---- versions (contract §3.2) ------------------------------------------------------------------------------------

-- A version of a skill's folder, never changed: the canonical zip (§4.2 step 4) and what the library shows of it.
--
--   description  its SKILL.md's description, 1 to 1024 characters
--   sha256       the SHA-256 of content, in lower-case hex: what a runner host checks
--   files        [{path, sizeBytes}] sorted by path, the uncompressed sizes
--   note         what changed, as its uploader wrote it
CREATE TABLE skill_version (
    company_id  uuid          NOT NULL,
    skill_id    uuid          NOT NULL,
    version     integer       NOT NULL,
    description varchar(1024) NOT NULL,
    sha256      char(64)      NOT NULL,
    size_bytes  integer       NOT NULL,
    file_count  integer       NOT NULL,
    files       jsonb         NOT NULL,
    note        varchar(500),
    content     bytea         NOT NULL,
    created_at  timestamptz   NOT NULL,
    created_by  uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT skill_version_pk PRIMARY KEY (skill_id, version),
    -- The target of agent_skill's and run_skill's composite foreign keys.
    CONSTRAINT skill_version_company_uq UNIQUE (skill_id, version, company_id),
    CONSTRAINT skill_version_skill_fk FOREIGN KEY (skill_id, company_id) REFERENCES skill (id, company_id),
    CONSTRAINT skill_version_number_ck CHECK (version BETWEEN 1 AND 100),
    CONSTRAINT skill_version_description_ck CHECK (length(btrim(description)) BETWEEN 1 AND 1024),
    CONSTRAINT skill_version_sha256_ck CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT skill_version_size_ck CHECK (size_bytes BETWEEN 1 AND 5242880 AND size_bytes = octet_length(content)),
    CONSTRAINT skill_version_files_ck CHECK (file_count BETWEEN 1 AND 200 AND jsonb_typeof(files) = 'array'
        AND jsonb_array_length(files) = file_count)
);

-- The zip is read whole, never searched: keep it out of line, uncompressed (it is deflated already).
ALTER TABLE skill_version ALTER COLUMN content SET STORAGE EXTERNAL;

-- ---- shares (contract §3.3) --------------------------------------------------------------------------------------

-- A group whose members see and use the skill; a deleted group's rows go with it (GroupDependents first, the cascade
-- as the safety net).
CREATE TABLE skill_share (
    company_id uuid        NOT NULL,
    skill_id   uuid        NOT NULL,
    group_id   uuid        NOT NULL,
    shared_by  uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    shared_at  timestamptz NOT NULL,
    CONSTRAINT skill_share_pk PRIMARY KEY (skill_id, group_id),
    CONSTRAINT skill_share_skill_fk FOREIGN KEY (skill_id, company_id)
        REFERENCES skill (id, company_id) ON DELETE CASCADE,
    CONSTRAINT skill_share_group_fk FOREIGN KEY (group_id, company_id)
        REFERENCES user_group (id, company_id) ON DELETE CASCADE
);

CREATE INDEX skill_share_group_idx ON skill_share (company_id, group_id);

-- A member who sees and uses the skill on their own, by their member's principal, as agent_member_share (V034).
CREATE TABLE skill_member_share (
    company_id     uuid        NOT NULL,
    skill_id       uuid        NOT NULL,
    principal_id   uuid        NOT NULL,
    principal_kind varchar(8)  NOT NULL DEFAULT 'MEMBER',
    shared_by      uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    shared_at      timestamptz NOT NULL,
    CONSTRAINT skill_member_share_pk PRIMARY KEY (skill_id, principal_id),
    CONSTRAINT skill_member_share_skill_fk FOREIGN KEY (skill_id, company_id)
        REFERENCES skill (id, company_id) ON DELETE CASCADE,
    CONSTRAINT skill_member_share_kind_ck CHECK (principal_kind = 'MEMBER'),
    CONSTRAINT skill_member_share_principal_fk FOREIGN KEY (principal_id, company_id, principal_kind)
        REFERENCES principal (id, company_id, kind) ON DELETE CASCADE
);

CREATE INDEX skill_member_share_principal_idx ON skill_member_share (company_id, principal_id);

-- ---- an agent's skills (contract §3.4) ---------------------------------------------------------------------------

-- A skill of an agent at the version it pins. The version's row is never deleted, so a deleted skill stays in the
-- agents that pin it.
CREATE TABLE agent_skill (
    company_id uuid        NOT NULL,
    agent_id   uuid        NOT NULL,
    skill_id   uuid        NOT NULL,
    version    integer     NOT NULL,
    added_at   timestamptz NOT NULL,
    added_by   uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    changed_at timestamptz NOT NULL,
    changed_by uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT agent_skill_pk PRIMARY KEY (agent_id, skill_id),
    -- The target of agent_settings_skill's foreign key.
    CONSTRAINT agent_skill_company_uq UNIQUE (agent_id, skill_id, company_id),
    CONSTRAINT agent_skill_agent_fk FOREIGN KEY (agent_id, company_id)
        REFERENCES agent (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_skill_version_fk FOREIGN KEY (skill_id, version, company_id)
        REFERENCES skill_version (skill_id, version, company_id)
);

-- The agents that pin a skill (agentCount, a deleted skill's count) and the foreign key.
CREATE INDEX agent_skill_skill_idx ON agent_skill (company_id, skill_id);

-- ---- settings (contract §3.5) ------------------------------------------------------------------------------------

-- A skill of the agent switched off in a person's or a trigger's settings. The foreign key to agent_skill keeps it a
-- narrowing (I4): a switch of a skill the agent does not have is refused (SETTINGS_WIDEN), and one whose skill leaves
-- the agent goes with it.
CREATE TABLE agent_settings_skill (
    company_id  uuid NOT NULL,
    settings_id uuid NOT NULL,
    agent_id    uuid NOT NULL,
    skill_id    uuid NOT NULL,
    CONSTRAINT agent_settings_skill_pk PRIMARY KEY (settings_id, skill_id),
    CONSTRAINT agent_settings_skill_settings_fk FOREIGN KEY (settings_id, company_id)
        REFERENCES agent_settings (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_settings_skill_skill_fk FOREIGN KEY (agent_id, skill_id, company_id)
        REFERENCES agent_skill (agent_id, skill_id, company_id) ON DELETE CASCADE
);

CREATE INDEX agent_settings_skill_skill_idx ON agent_settings_skill (agent_id, skill_id);

-- ---- runs (contract §3.6) ----------------------------------------------------------------------------------------

-- A skill a run was assigned, at its version: what the run's runner host may download.
CREATE TABLE run_skill (
    company_id uuid    NOT NULL,
    run_id     uuid    NOT NULL,
    skill_id   uuid    NOT NULL,
    version    integer NOT NULL,
    CONSTRAINT run_skill_pk PRIMARY KEY (run_id, skill_id),
    CONSTRAINT run_skill_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id) ON DELETE CASCADE,
    CONSTRAINT run_skill_version_fk FOREIGN KEY (skill_id, version, company_id)
        REFERENCES skill_version (skill_id, version, company_id)
);

CREATE INDEX run_skill_version_idx ON run_skill (skill_id, version);

-- ---- a run's queue reason (contract §7.1) ------------------------------------------------------------------------

ALTER TABLE run DROP CONSTRAINT run_queue_reason_ck;
ALTER TABLE run ADD CONSTRAINT run_queue_reason_ck CHECK (queue_reason IS NULL OR queue_reason IN (
    'COMPANY_STOPPED', 'SYSTEM_PAUSED',
    'AGENT_PAUSED', 'ACCOUNT_REQUIRED', 'BUDGET_SPENT', 'KEY_ERROR', 'HOST_OFFLINE', 'MODEL_ACCESS_UNAVAILABLE',
    'NO_HOST_FOR_CREDENTIALS', 'NO_RUNNER_HOST', 'RUNNER_HOST_OFFLINE', 'RUNNER_HOST_DRAINING',
    'CREDENTIAL_POOL_FULL', 'RUNNER_HOST_FULL', 'PREVIOUS_RUN_ACTIVE', 'RUNNER_HOST_REJECTED', 'ASSIGNED',
    'TRIGGER_PAUSED', 'NO_HOST_FOR_SKILLS', 'OTHER'));

-- ---- row-level security and grants -------------------------------------------------------------------------------

ALTER TABLE skill ENABLE ROW LEVEL SECURITY;
ALTER TABLE skill FORCE ROW LEVEL SECURITY;
ALTER TABLE skill_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE skill_version FORCE ROW LEVEL SECURITY;
ALTER TABLE skill_share ENABLE ROW LEVEL SECURITY;
ALTER TABLE skill_share FORCE ROW LEVEL SECURITY;
ALTER TABLE skill_member_share ENABLE ROW LEVEL SECURITY;
ALTER TABLE skill_member_share FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_skill ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_skill FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_settings_skill ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_settings_skill FORCE ROW LEVEL SECURITY;
ALTER TABLE run_skill ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_skill FORCE ROW LEVEL SECURITY;

CREATE POLICY skill_tenant ON skill FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY skill_version_tenant ON skill_version FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY skill_share_tenant ON skill_share FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY skill_member_share_tenant ON skill_member_share FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY agent_skill_tenant ON agent_skill FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY agent_settings_skill_tenant ON agent_settings_skill FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY run_skill_tenant ON run_skill FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- A skill is changed in place and soft-deleted, never removed; a version is written once and never removed. Shares,
-- an agent's skills and a settings' switches are replaced as a whole; an agent's pin is moved in place. A run's skills
-- are written once and go with the run.
GRANT SELECT, INSERT, UPDATE ON skill TO ${app_role};
GRANT SELECT, INSERT ON skill_version TO ${app_role};
GRANT SELECT, INSERT, DELETE ON skill_share TO ${app_role};
GRANT SELECT, INSERT, DELETE ON skill_member_share TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON agent_skill TO ${app_role};
GRANT SELECT, INSERT, DELETE ON agent_settings_skill TO ${app_role};
GRANT SELECT, INSERT, DELETE ON run_skill TO ${app_role};
