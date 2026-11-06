-- The tools of a conversation's runs and what their decisions told the agent (the package 3 contract,
-- docs/reviews/2026-10-12-package-3-contract.md §3.12, §5.4, §5.8, §5.9 and §11): package 3c, part 2.
--
--   conversation_tool_name    the name each tool was listed by in a conversation, kept so that it is stable across
--                             the conversation's turns and mapped back before a call (§11: a name over 64 characters
--                             is cut and hashed; the runner protocol takes lower case only)
--   conversation_tool_denial  the tools a run was refused, once per run and tool, so that tool.denied is audited once
--                             (a prompt-injected agent that keeps calling a denied tool is one row of the hash chain)
--   decision.reported_run_id  the turn whose prompt told the agent a decided or cancelled decision (# Answers), so
--                             that each outcome is told once, in the next turn
--
-- The conventions of V001 apply: company_id on every row, ENABLE plus FORCE ROW LEVEL SECURITY with a policy on
-- app_company_id(), composite foreign keys with the company id, grants to the runtime role ${app_role}.

-- ---- conversation_tool_name ------------------------------------------------------------------------------------

--   name       the name the conversation's runs list the tool by ({handle}_{tool}, ToolNames.of, in lower case; a
--              name the conversation holds already for another tool is made unique with a hash of the tool)
--   system_id  the tool's system. No foreign key: a removed system's names stay, and a call by one is UNKNOWN_TOOL
--   tool       the tool as its system names it (a fixed catalogue's, or an MCP server's own name)
CREATE TABLE conversation_tool_name (
    company_id      uuid        NOT NULL,
    conversation_id uuid        NOT NULL,
    name            varchar(64) NOT NULL,
    system_id       uuid        NOT NULL,
    tool            varchar(64) NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT conversation_tool_name_pk PRIMARY KEY (conversation_id, name),
    CONSTRAINT conversation_tool_name_tool_uq UNIQUE (conversation_id, system_id, tool),
    CONSTRAINT conversation_tool_name_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    -- The runner protocol's ToolSpec.name, within the runtimes' 64 characters.
    CONSTRAINT conversation_tool_name_name_ck CHECK (name ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT conversation_tool_name_tool_ck CHECK (tool ~ '^[A-Za-z0-9_-]{1,64}$')
);

-- ---- conversation_tool_denial ----------------------------------------------------------------------------------

--   step   the step of the tools of a run (§5.4) that refused the call: AGENT (not one of the agent's tools),
--          SETTINGS (switched off in the person's settings), BINDING (a gone binding, or a write through a read-only
--          grant)
CREATE TABLE conversation_tool_denial (
    company_id uuid        NOT NULL,
    run_id     uuid        NOT NULL,
    system_id  uuid        NOT NULL,
    tool       varchar(64) NOT NULL,
    step       varchar(8)  NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT conversation_tool_denial_pk PRIMARY KEY (run_id, system_id, tool),
    CONSTRAINT conversation_tool_denial_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    CONSTRAINT conversation_tool_denial_step_ck CHECK (step IN ('AGENT', 'SETTINGS', 'BINDING'))
);

-- ---- decision.reported_run_id ----------------------------------------------------------------------------------

ALTER TABLE decision ADD COLUMN reported_run_id uuid;
ALTER TABLE decision ADD CONSTRAINT decision_reported_run_fk FOREIGN KEY (reported_run_id, company_id)
    REFERENCES run (id, company_id);
-- Only a decided or cancelled decision is told.
ALTER TABLE decision ADD CONSTRAINT decision_reported_ck CHECK (reported_run_id IS NULL OR status <> 'OPEN');
-- The outcomes a conversation's next turn tells.
CREATE INDEX decision_unreported_idx ON decision (conversation_id) WHERE status <> 'OPEN' AND reported_run_id IS NULL;
CREATE INDEX decision_reported_run_idx ON decision (reported_run_id) WHERE reported_run_id IS NOT NULL;

-- ---- row-level security and grants -----------------------------------------------------------------------------

ALTER TABLE conversation_tool_name ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_tool_name FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation_tool_denial ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_tool_denial FORCE ROW LEVEL SECURITY;

CREATE POLICY conversation_tool_name_tenant ON conversation_tool_name FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY conversation_tool_denial_tenant ON conversation_tool_denial FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- A conversation's names and a run's denials are written once and kept with its history.
GRANT SELECT, INSERT ON conversation_tool_name, conversation_tool_denial TO ${app_role};
