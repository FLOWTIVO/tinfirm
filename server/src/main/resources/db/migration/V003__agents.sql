-- Package 3a of the platform redesign (docs/reviews/2026-10-12-package-3-contract.md, §3.1 to §3.7, §4 and §11):
-- agents as definitions, their versions and the widening a person accepts, and each person's settings of an agent.
--
--   agent                   a specialist's definition, private to its owner until shared (contract §3.1)
--   agent_version           every change of the definition, with whether it widens (contract §11)
--   agent_system            the ceiling of systems (§3.2)
--   agent_tool              the tools of those systems and each one's mode (§3.3)
--   agent_repository        the repositories of those systems, READ or WRITE (§3.4)
--   agent_host              the network hosts its runs may reach (§3.5)
--   agent_share             the groups that may use it (§3.6)
--   agent_settings,         one person's narrowing of one agent, and the version they accepted (§3.7, §11)
--   agent_settings_system,
--   agent_settings_tool
--
-- The conventions of V001 apply: every tenant table has company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on
-- app_company_id(), composite foreign keys with the company id, and grants to ${app_role}. No column of an agent's
-- tables names a secret, an account, a model key, a runner credential or a principal the agent acts as (I1).

-- ---- agents (contract §3.1) --------------------------------------------------------------------------------------

-- Whether an array of runtimes is a non-empty list of known runtimes without duplicates (the first is the default).
CREATE FUNCTION agent_runtimes_valid(p_runtimes varchar(16)[]) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT cardinality(p_runtimes) BETWEEN 1 AND 4
       AND p_runtimes <@ ARRAY['CLAUDE_CODE', 'CODEX', 'PI', 'OPENCODE']::varchar(16)[]
       AND cardinality(p_runtimes) = (SELECT count(DISTINCT r) FROM unnest(p_runtimes) AS r)
$$;

-- A specialist's definition (the contract's §3.1, ADR-0058 decision 4).
--
--   name                1 to 100 characters, unique among the company's live agents ignoring case
--                       (DUPLICATE_AGENT_NAME)
--   handle              @handle: 1 to 50 lower-case ASCII letters, digits and hyphens, not starting or ending with a
--                       hyphen, unique among the live agents (DUPLICATE_AGENT_HANDLE; ADR-0034's grammar)
--   description         what it is for, at most 1000 characters
--   instructions        the standing instructions, the first part of every prompt, at most 100 000 characters
--   runtimes            the runtimes its instructions are tested on; the first is the default
--   owner_principal_id  the member who edits it besides admins: its creator; NULL once it passed to the admins (its
--                       creator left the company). owner_kind is always MEMBER: the foreign key with it admits no
--                       service identity
--   status, paused_at,  ACTIVE or PAUSED (the kill switch); paused_at and paused_by exactly while PAUSED
--   paused_by
--   version             the current version, the newest row of agent_version
--   deleted_at,         soft delete: the row stays for its conversations' history, its name and handle are free again
--   deleted_by
--   created_by          the user who created it, kept after they leave
CREATE TABLE agent (
    id                 uuid          PRIMARY KEY,
    company_id         uuid          NOT NULL REFERENCES company (id),
    name               varchar(100)  NOT NULL,
    handle             varchar(50)   NOT NULL,
    description        varchar(1000),
    instructions       text          NOT NULL DEFAULT '',
    runtimes           varchar(16)[] NOT NULL,
    owner_principal_id uuid,
    owner_kind         varchar(8)    NOT NULL DEFAULT 'MEMBER',
    status             varchar(8)    NOT NULL DEFAULT 'ACTIVE',
    paused_at          timestamptz,
    paused_by          uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    version            integer       NOT NULL DEFAULT 1,
    deleted_at         timestamptz,
    deleted_by         uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    created_at         timestamptz   NOT NULL,
    updated_at         timestamptz   NOT NULL,
    created_by         uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT agent_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT agent_name_ck CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT agent_handle_ck CHECK (handle ~ '^[a-z0-9]([a-z0-9-]{0,48}[a-z0-9])?$'),
    CONSTRAINT agent_instructions_ck CHECK (length(instructions) <= 100000),
    CONSTRAINT agent_runtimes_ck CHECK (agent_runtimes_valid(runtimes)),
    CONSTRAINT agent_owner_kind_ck CHECK (owner_kind = 'MEMBER'),
    CONSTRAINT agent_owner_fk FOREIGN KEY (owner_principal_id, company_id, owner_kind)
        REFERENCES principal (id, company_id, kind),
    CONSTRAINT agent_status_ck CHECK (status IN ('ACTIVE', 'PAUSED')),
    CONSTRAINT agent_paused_ck CHECK ((status = 'PAUSED') = (paused_at IS NOT NULL)),
    CONSTRAINT agent_version_ck CHECK (version >= 1),
    CONSTRAINT agent_deleted_ck CHECK (deleted_by IS NULL OR deleted_at IS NOT NULL)
);

-- DUPLICATE_AGENT_NAME and DUPLICATE_AGENT_HANDLE among the live agents; a deleted agent frees both.
CREATE UNIQUE INDEX agent_name_uq ON agent (company_id, lower(name)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX agent_handle_uq ON agent (company_id, handle) WHERE deleted_at IS NULL;
-- listAgents: keyset on (name, id) over the live agents.
CREATE INDEX agent_page_idx ON agent (company_id, name, id) WHERE deleted_at IS NULL;
-- A member's agents: the list, and passing them to the admins when the member leaves.
CREATE INDEX agent_owner_idx ON agent (company_id, owner_principal_id) WHERE owner_principal_id IS NOT NULL;

-- ---- versions (contract §11) -------------------------------------------------------------------------------------

-- Every change of an agent's definition makes a version, never changed afterwards.
--
--   definition  the definition as of the version: name, handle, runtimes and the ceiling (systems with their tools
--               and modes, repositories with their access, hosts); the instructions and the description follow the
--               current version and are not kept here
--   widens      whether it adds a system, a tool, a repository or a network host, raises a repository to WRITE, or
--               a tool towards ALLOW, over the version before; version 1 widens nothing
--   decision_id the approval of a request for good (scope AGENT) that made it; no foreign key: decisions are the
--               conversations' (package 3c)
CREATE TABLE agent_version (
    company_id  uuid        NOT NULL,
    agent_id    uuid        NOT NULL,
    version     integer     NOT NULL,
    definition  jsonb       NOT NULL,
    widens      boolean     NOT NULL,
    decision_id uuid,
    created_at  timestamptz NOT NULL,
    created_by  uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT agent_version_pk PRIMARY KEY (agent_id, version),
    CONSTRAINT agent_version_agent_fk FOREIGN KEY (agent_id, company_id)
        REFERENCES agent (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_version_number_ck CHECK (version >= 1),
    CONSTRAINT agent_version_first_ck CHECK (version > 1 OR NOT widens),
    CONSTRAINT agent_version_definition_ck CHECK (jsonb_typeof(definition) = 'object')
);

-- ---- the ceiling (contract §3.2 to §3.5) -------------------------------------------------------------------------

-- The systems an agent may use: only company systems (I2: a personal MCP server is the person's, added in their
-- settings) of a kind with tools or repositories. The chat kinds come with package 5.
CREATE TABLE agent_system (
    company_id uuid NOT NULL,
    agent_id   uuid NOT NULL,
    system_id  uuid NOT NULL,
    CONSTRAINT agent_system_pk PRIMARY KEY (agent_id, system_id),
    -- The target of the composite foreign keys of agent_tool and agent_repository.
    CONSTRAINT agent_system_company_uq UNIQUE (agent_id, system_id, company_id),
    CONSTRAINT agent_system_agent_fk FOREIGN KEY (agent_id, company_id)
        REFERENCES agent (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_system_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE
);

-- A removed system leaves every agent (ON DELETE CASCADE), and the settings that switched it off.
CREATE INDEX agent_system_system_idx ON agent_system (system_id);

-- I2: a company system of a kind with tools or repositories.
CREATE FUNCTION agent_system_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_kind  varchar(16);
    v_owner uuid;
BEGIN
    SELECT s.kind, s.owner_principal_id INTO v_kind, v_owner
      FROM external_system s
     WHERE s.id = NEW.system_id AND s.company_id = NEW.company_id;
    IF v_owner IS NOT NULL THEN
        RAISE EXCEPTION 'An agent lists only company systems; a personal MCP server is added in one''s settings'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'agent_system_personal_ck';
    END IF;
    IF v_kind IS NOT NULL AND v_kind NOT IN ('JIRA_DC', 'JIRA_CLOUD', 'GITHUB', 'GITLAB', 'GIT', 'MCP') THEN
        RAISE EXCEPTION 'An agent lists only systems with tools or repositories'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'agent_system_kind_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER agent_system_ck_trg BEFORE INSERT OR UPDATE ON agent_system
    FOR EACH ROW EXECUTE FUNCTION agent_system_ck();

-- A tool of a system the agent lists, as its system names it (a fixed catalogue's or an MCP server's own name), and
-- its mode: ALLOW, or ASK (each call an approval). A tool without a row is not available; there is no DENY (I3).
--
--   chosen_for_effect  the tool's effect when the mode was chosen, READ or WRITE (an unconfirmed tool's is WRITE): an
--                      ALLOW chosen for a read counts as ASK once the tool's effect is WRITE (I19)
CREATE TABLE agent_tool (
    company_id        uuid        NOT NULL,
    agent_id          uuid        NOT NULL,
    system_id         uuid        NOT NULL,
    tool              varchar(64) NOT NULL,
    mode              varchar(8)  NOT NULL,
    chosen_for_effect varchar(8)  NOT NULL,
    CONSTRAINT agent_tool_pk PRIMARY KEY (agent_id, system_id, tool),
    CONSTRAINT agent_tool_system_fk FOREIGN KEY (agent_id, system_id, company_id)
        REFERENCES agent_system (agent_id, system_id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_tool_name_ck CHECK (tool ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT agent_tool_mode_ck CHECK (mode IN ('ALLOW', 'ASK')),
    CONSTRAINT agent_tool_effect_ck CHECK (chosen_for_effect IN ('READ', 'WRITE'))
);

-- A repository is listed only with its system (I2): its system on the agent, and the repository of that system.
ALTER TABLE system_repository ADD CONSTRAINT system_repository_id_system_uq UNIQUE (id, system_id, company_id);

-- A repository the agent may use, READ or WRITE (ADR-0017); at most 100 per agent (the service).
CREATE TABLE agent_repository (
    company_id    uuid       NOT NULL,
    agent_id      uuid       NOT NULL,
    system_id     uuid       NOT NULL,
    repository_id uuid       NOT NULL,
    access        varchar(8) NOT NULL,
    CONSTRAINT agent_repository_pk PRIMARY KEY (agent_id, repository_id),
    CONSTRAINT agent_repository_system_fk FOREIGN KEY (agent_id, system_id, company_id)
        REFERENCES agent_system (agent_id, system_id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_repository_repository_fk FOREIGN KEY (repository_id, system_id, company_id)
        REFERENCES system_repository (id, system_id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_repository_access_ck CHECK (access IN ('READ', 'WRITE'))
);

-- A removed repository leaves every agent (ON DELETE CASCADE).
CREATE INDEX agent_repository_repository_idx ON agent_repository (repository_id);

-- A network host the agent's runs may reach (ADR-0044): HOST[:PORT] or *.example.com[:PORT], normalized (lower case,
-- no trailing dot, no :443); at most 100 per agent (NETWORK_HOST_LIMIT, the service).
--
--   decision_id  the approval of a request for good (scope AGENT) that added it; no foreign key (package 3c)
CREATE TABLE agent_host (
    id          uuid         PRIMARY KEY,
    company_id  uuid         NOT NULL,
    agent_id    uuid         NOT NULL,
    host        varchar(260) NOT NULL,
    reason      varchar(500),
    added_by    uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    decision_id uuid,
    added_at    timestamptz  NOT NULL,
    CONSTRAINT agent_host_uq UNIQUE (agent_id, host),
    CONSTRAINT agent_host_agent_fk FOREIGN KEY (agent_id, company_id)
        REFERENCES agent (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_host_host_ck CHECK (host ~ '^(\*\.)?[a-z0-9.-]{1,253}(:[0-9]{1,5})?$')
);

-- ---- the use right (contract §3.6) -------------------------------------------------------------------------------

-- A group whose members may use the agent; at most 50 per agent (the service).
CREATE TABLE agent_share (
    company_id uuid        NOT NULL,
    agent_id   uuid        NOT NULL,
    group_id   uuid        NOT NULL,
    shared_by  uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    shared_at  timestamptz NOT NULL,
    CONSTRAINT agent_share_pk PRIMARY KEY (agent_id, group_id),
    CONSTRAINT agent_share_agent_fk FOREIGN KEY (agent_id, company_id)
        REFERENCES agent (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_share_group_fk FOREIGN KEY (group_id, company_id)
        REFERENCES user_group (id, company_id) ON DELETE CASCADE
);

-- The agents a member's groups may use, and the ON DELETE CASCADE of the group foreign key.
CREATE INDEX agent_share_group_idx ON agent_share (company_id, group_id);

-- ---- settings (contract §3.7, §11) -------------------------------------------------------------------------------

-- One person's narrowing of one agent, made on first use (their first conversation with it, their first save, or
-- their first acceptance of a widening). Package 4 adds trigger_id as the other owner, with a check that exactly one
-- is set.
--
--   principal_id          the member's principal (principal_kind is always MEMBER: the foreign key admits no service
--                         identity)
--   runtime               one of the agent's runtimes; NULL is the agent's first
--   model                 one the person's model access allows; NULL is the runtime's default
--   effort                DEFAULT, LOW, MEDIUM, HIGH, XHIGH or MAX
--   model_access,         BUDGET (the default), or SUBSCRIPTION with one of the person's subscriptions, named by its
--   subscription_host_id, personal runner host and the label the host reports (the package 2 contract, §2). The
--   subscription_label    host's removal leaves SUBSCRIPTION without a subscription, which no conversation starts with
--                         (SUBSCRIPTION_GONE); Tinfirm never switches it to the budget by itself
--   instructions          added after the agent's, at most 20 000 characters
--   default_repository_ids the repositories a new conversation's workspace starts with, some of the agent's; empty
--                         means all of them. Ids of repositories the agent no longer lists are ignored
--   default_projects      [{systemId, key}] of systems the agent lists, at most 20
--   accepted_version      the agent's version the person accepted (contract §11): the agent of their turns is the
--                         current version narrowed by this one
CREATE TABLE agent_settings (
    id                     uuid         PRIMARY KEY,
    company_id             uuid         NOT NULL,
    agent_id               uuid         NOT NULL,
    principal_id           uuid         NOT NULL,
    principal_kind         varchar(8)   NOT NULL DEFAULT 'MEMBER',
    runtime                varchar(16),
    model                  varchar(200),
    effort                 varchar(8)   NOT NULL DEFAULT 'DEFAULT',
    model_access           varchar(16)  NOT NULL DEFAULT 'BUDGET',
    subscription_host_id   uuid,
    subscription_label     varchar(63),
    instructions           text,
    default_repository_ids uuid[]       NOT NULL DEFAULT '{}',
    default_projects       jsonb        NOT NULL DEFAULT '[]'::jsonb,
    accepted_version       integer      NOT NULL,
    created_at             timestamptz  NOT NULL,
    updated_at             timestamptz  NOT NULL,
    CONSTRAINT agent_settings_id_company_uq UNIQUE (id, company_id),
    -- I5: one settings row per person per agent.
    CONSTRAINT agent_settings_person_uq UNIQUE (agent_id, principal_id),
    CONSTRAINT agent_settings_agent_fk FOREIGN KEY (agent_id, company_id)
        REFERENCES agent (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_settings_principal_kind_ck CHECK (principal_kind = 'MEMBER'),
    CONSTRAINT agent_settings_principal_fk FOREIGN KEY (principal_id, company_id, principal_kind)
        REFERENCES principal (id, company_id, kind),
    CONSTRAINT agent_settings_host_fk FOREIGN KEY (subscription_host_id, company_id)
        REFERENCES runner_host (id, company_id) ON DELETE SET NULL (subscription_host_id),
    CONSTRAINT agent_settings_runtime_ck
        CHECK (runtime IS NULL OR runtime IN ('CLAUDE_CODE', 'CODEX', 'PI', 'OPENCODE')),
    CONSTRAINT agent_settings_model_ck
        CHECK (model IS NULL OR model ~ '^[A-Za-z0-9][A-Za-z0-9._:/@+\[\]-]{0,199}$'),
    CONSTRAINT agent_settings_effort_ck CHECK (effort IN ('DEFAULT', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX')),
    CONSTRAINT agent_settings_model_access_ck CHECK (model_access IN ('BUDGET', 'SUBSCRIPTION')),
    -- A subscription only with SUBSCRIPTION; its label when its host is named.
    CONSTRAINT agent_settings_subscription_ck
        CHECK ((model_access = 'SUBSCRIPTION' OR (subscription_host_id IS NULL AND subscription_label IS NULL))
               AND (subscription_host_id IS NULL OR subscription_label IS NOT NULL)),
    CONSTRAINT agent_settings_instructions_ck CHECK (instructions IS NULL OR length(instructions) <= 20000),
    CONSTRAINT agent_settings_repositories_ck CHECK (cardinality(default_repository_ids) <= 100),
    CONSTRAINT agent_settings_projects_ck
        CHECK (jsonb_typeof(default_projects) = 'array' AND jsonb_array_length(default_projects) <= 20),
    CONSTRAINT agent_settings_version_ck CHECK (accepted_version >= 1)
);

-- A person's settings: a leaving member's go; the foreign keys to the principal and the runner host.
CREATE INDEX agent_settings_principal_idx ON agent_settings (company_id, principal_id);
CREATE INDEX agent_settings_host_idx ON agent_settings (subscription_host_id) WHERE subscription_host_id IS NOT NULL;

-- A system of the agent switched off for this person (OFF: its tools and repositories are gone for them), or the
-- person's own MCP server added (ADDED).
CREATE TABLE agent_settings_system (
    company_id  uuid       NOT NULL,
    settings_id uuid       NOT NULL,
    system_id   uuid       NOT NULL,
    state       varchar(8) NOT NULL,
    CONSTRAINT agent_settings_system_pk PRIMARY KEY (settings_id, system_id),
    CONSTRAINT agent_settings_system_settings_fk FOREIGN KEY (settings_id, company_id)
        REFERENCES agent_settings (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_settings_system_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_settings_system_state_ck CHECK (state IN ('OFF', 'ADDED'))
);

CREATE INDEX agent_settings_system_system_idx ON agent_settings_system (system_id);

-- A tool's mode in one person's settings. On a system of the agent: ASK (an ALLOW turned into ASK) or OFF; never ALLOW.
-- On the person's own MCP server (ADDED): the list of its tools, ALLOW or ASK, as an agent's rows are for its systems
-- (open point 6), with the effect the mode was chosen for (I19).
CREATE TABLE agent_settings_tool (
    company_id        uuid        NOT NULL,
    settings_id       uuid        NOT NULL,
    system_id         uuid        NOT NULL,
    tool              varchar(64) NOT NULL,
    mode              varchar(8)  NOT NULL,
    chosen_for_effect varchar(8)  NOT NULL,
    CONSTRAINT agent_settings_tool_pk PRIMARY KEY (settings_id, system_id, tool),
    CONSTRAINT agent_settings_tool_settings_fk FOREIGN KEY (settings_id, company_id)
        REFERENCES agent_settings (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_settings_tool_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_settings_tool_name_ck CHECK (tool ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT agent_settings_tool_mode_ck CHECK (mode IN ('ALLOW', 'ASK', 'OFF')),
    CONSTRAINT agent_settings_tool_effect_ck CHECK (chosen_for_effect IN ('READ', 'WRITE'))
);

CREATE INDEX agent_settings_tool_system_idx ON agent_settings_tool (system_id);

-- I4: settings narrow, never widen. OFF only for a system the agent lists; ADDED only for an MCP server the person
-- owns.
CREATE FUNCTION agent_settings_system_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_agent     uuid;
    v_principal uuid;
BEGIN
    SELECT st.agent_id, st.principal_id INTO v_agent, v_principal
      FROM agent_settings st
     WHERE st.id = NEW.settings_id AND st.company_id = NEW.company_id;
    IF NEW.state = 'OFF' AND NOT EXISTS (
            SELECT 1 FROM agent_system a WHERE a.agent_id = v_agent AND a.system_id = NEW.system_id) THEN
        RAISE EXCEPTION 'Settings switch off only a system the agent lists'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'agent_settings_widen_ck';
    END IF;
    IF NEW.state = 'ADDED' AND NOT EXISTS (
            SELECT 1 FROM external_system s
             WHERE s.id = NEW.system_id AND s.company_id = NEW.company_id AND s.kind = 'MCP'
               AND s.owner_principal_id = v_principal) THEN
        RAISE EXCEPTION 'Settings add only the person''s own MCP server'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'agent_settings_widen_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER agent_settings_system_ck_trg BEFORE INSERT OR UPDATE ON agent_settings_system
    FOR EACH ROW EXECUTE FUNCTION agent_settings_system_ck();

-- I4: on the person's own server (ADDED) ALLOW or ASK; on a system the agent lists ASK or OFF, never ALLOW; nothing on
-- any other system.
CREATE FUNCTION agent_settings_tool_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_agent uuid;
BEGIN
    IF EXISTS (SELECT 1 FROM agent_settings_system ss
                WHERE ss.settings_id = NEW.settings_id AND ss.system_id = NEW.system_id AND ss.state = 'ADDED') THEN
        IF NEW.mode NOT IN ('ALLOW', 'ASK') THEN
            RAISE EXCEPTION 'A tool of one''s own MCP server is ALLOW or ASK'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'agent_settings_widen_ck';
        END IF;
        RETURN NEW;
    END IF;
    SELECT st.agent_id INTO v_agent
      FROM agent_settings st
     WHERE st.id = NEW.settings_id AND st.company_id = NEW.company_id;
    IF NEW.mode = 'ALLOW' OR NOT EXISTS (
            SELECT 1 FROM agent_system a WHERE a.agent_id = v_agent AND a.system_id = NEW.system_id) THEN
        RAISE EXCEPTION 'Settings turn a tool of the agent to ASK or OFF only, and only on a system the agent lists'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'agent_settings_widen_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER agent_settings_tool_ck_trg BEFORE INSERT OR UPDATE ON agent_settings_tool
    FOR EACH ROW EXECUTE FUNCTION agent_settings_tool_ck();

-- ---- row-level security and grants -------------------------------------------------------------------------------

ALTER TABLE agent ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_version FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_system ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_system FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_tool ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_tool FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_repository ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_repository FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_host ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_host FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_share ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_share FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_settings_system ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_settings_system FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_settings_tool ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_settings_tool FORCE ROW LEVEL SECURITY;

CREATE POLICY agent_tenant ON agent FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY agent_version_tenant ON agent_version FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY agent_system_tenant ON agent_system FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY agent_tool_tenant ON agent_tool FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY agent_repository_tenant ON agent_repository FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY agent_host_tenant ON agent_host FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY agent_share_tenant ON agent_share FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY agent_settings_tenant ON agent_settings FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY agent_settings_system_tenant ON agent_settings_system FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY agent_settings_tool_tenant ON agent_settings_tool FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- An agent is changed in place and soft-deleted, never removed; a version is written once. The ceiling, the shares
-- and a person's settings are replaced as a whole; the settings' row is changed in place and goes when its person
-- leaves or its agent is deleted.
GRANT SELECT, INSERT, UPDATE ON agent TO ${app_role};
GRANT SELECT, INSERT ON agent_version TO ${app_role};
GRANT SELECT, INSERT, DELETE ON agent_system TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON agent_tool TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON agent_repository TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON agent_host TO ${app_role};
GRANT SELECT, INSERT, DELETE ON agent_share TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON agent_settings TO ${app_role};
GRANT SELECT, INSERT, DELETE ON agent_settings_system TO ${app_role};
GRANT SELECT, INSERT, DELETE ON agent_settings_tool TO ${app_role};
