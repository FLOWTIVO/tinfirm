-- An agent shared with members on their own, beside the groups of agent_share (V003): a person in no group, or in
-- none of the agent's groups, may be given its use right directly (CONVENTIONS §15.5).
--
--   agent_member_share  a member whose principal may use the agent
--
-- The conventions of V001 apply: company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on app_company_id(),
-- composite foreign keys with the company id, and grants to ${app_role}. The principal a row names is who may use the
-- agent, never one it acts as (the package 3 contract's I1): a turn acts for its own person.

-- A member who may use the agent; at most 100 per agent (the service). Only a member's principal (principal_kind): a
-- service identity uses no agent. A member's principal is never deleted (it stays as LEFT), so the row goes when its
-- member leaves (AgentDependents.memberLeaving), and one who rejoins, with a new principal, is not shared with again;
-- the cascade is the safety net.
CREATE TABLE agent_member_share (
    company_id     uuid        NOT NULL,
    agent_id       uuid        NOT NULL,
    principal_id   uuid        NOT NULL,
    principal_kind varchar(8)  NOT NULL DEFAULT 'MEMBER',
    shared_by      uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    shared_at      timestamptz NOT NULL,
    CONSTRAINT agent_member_share_pk PRIMARY KEY (agent_id, principal_id),
    CONSTRAINT agent_member_share_agent_fk FOREIGN KEY (agent_id, company_id)
        REFERENCES agent (id, company_id) ON DELETE CASCADE,
    CONSTRAINT agent_member_share_kind_ck CHECK (principal_kind = 'MEMBER'),
    CONSTRAINT agent_member_share_principal_fk FOREIGN KEY (principal_id, company_id, principal_kind)
        REFERENCES principal (id, company_id, kind) ON DELETE CASCADE
);

-- The agents a member may use on their own (the listing of the agents they use, their leaving), and the foreign key.
CREATE INDEX agent_member_share_principal_idx ON agent_member_share (company_id, principal_id);

ALTER TABLE agent_member_share ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_member_share FORCE ROW LEVEL SECURITY;

CREATE POLICY agent_member_share_tenant ON agent_member_share FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Replaced as a whole, as agent_share is.
GRANT SELECT, INSERT, DELETE ON agent_member_share TO ${app_role};
