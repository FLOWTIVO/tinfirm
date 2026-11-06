-- Package 5b's follow-up: Slack's history tool (the package 5 contract, §2.2, open point 13). An agent may list a SLACK
-- system for its fixed tool `history`; TEAMS and WEBHOOK stay refused (I2 of package 3, `agent_system_kind_ck`).
CREATE OR REPLACE FUNCTION agent_system_ck() RETURNS trigger
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
    IF v_kind IS NOT NULL AND v_kind NOT IN ('JIRA_DC', 'JIRA_CLOUD', 'GITHUB', 'GITLAB', 'GIT', 'MCP', 'SLACK') THEN
        RAISE EXCEPTION 'An agent lists only systems with tools or repositories'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'agent_system_kind_ck';
    END IF;
    RETURN NEW;
END
$$;
