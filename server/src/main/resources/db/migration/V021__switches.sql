-- Package 6, the kill switches (the package 6 contract, §3.3, §5.4 and §5.5; open points 5, 6, 7, 13, 21 and 24): a
-- company's stop of its runs, a company system's pause, a member's suspension (and every principal of a user an
-- instance admin deactivated), and the admins' checklist of a member who left.
--
--   company.runs_stopped_*        the company's runs are stopped while runs_stopped_at is set: who, why, and the
--                                 break-glass access of an instance admin who stopped them (NULL otherwise; the
--                                 column has no foreign key). The
--                                 reason a person typed stays on this row, out of the audit chain.
--   external_system.paused_*      a company system's pause: every use of its accounts stops (Accounts.check answers
--                                 SYSTEM_PAUSED), its tools are not listed, a turn whose workspace holds one of its
--                                 repositories waits. A personal MCP server has the company's switch of personal
--                                 servers instead (external_system_paused_ck).
--   principal.suspended_*         a principal's suspension, a member's or a service identity's: when, by whom, why,
--                                 and its cause (ADMIN, or USER_DEACTIVATED when an instance admin deactivated the
--                                 user, which reactivating lifts, and only that). status stays the switch;
--                                 suspended_at is set exactly while it is SUSPENDED (principal_suspended_ck).
--   principal.departure_closed_*  a member who left: the admins closed the checklist of what was theirs.
--   principal_departure_group     the groups a member was in when they left (the membership's cascade takes them out
--                                 of every group, and nothing else remembers it): the checklist lists those left
--                                 without a member. Goes with the group.
--   agent.former_owner_principal_id
--                                 the member an agent passed to the admins from, for the leaver's checklist; cleared
--                                 when admins give the agent a new owner.
--   run.queue_reason              gains COMPANY_STOPPED (the dispatcher's step 0 while the company's runs are stopped)
--                                 and SYSTEM_PAUSED (a paused system's repository in the turn's workspace).
--   trigger.paused_reason         gains SYSTEM_PAUSED (a paused system's triggers, package 4), and
--   trigger.waiting_reason        COMPANY_STOPPED (every active trigger while the company's runs are stopped).
--
-- The conventions of V001 apply: every tenant table has company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on
-- app_company_id(), composite foreign keys with the company, and grants to the runtime role ${app_role}.

-- ---- The company's stop ------------------------------------------------------------------------------------------

ALTER TABLE company
    ADD COLUMN runs_stopped_at        timestamptz,
    ADD COLUMN runs_stopped_by        uuid REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN runs_stopped_reason    varchar(500),
    ADD COLUMN runs_stopped_access_id uuid,
    ADD CONSTRAINT company_runs_stopped_ck CHECK (
        (runs_stopped_at IS NULL AND runs_stopped_reason IS NULL AND runs_stopped_by IS NULL
             AND runs_stopped_access_id IS NULL)
        OR (runs_stopped_at IS NOT NULL AND length(btrim(runs_stopped_reason)) BETWEEN 1 AND 500));

-- ---- A system's pause ---------------------------------------------------------------------------------------------

ALTER TABLE external_system
    ADD COLUMN paused_at     timestamptz,
    ADD COLUMN paused_by     uuid REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN paused_reason varchar(500),
    ADD CONSTRAINT external_system_paused_ck CHECK (paused_at IS NULL OR owner_principal_id IS NULL),
    ADD CONSTRAINT external_system_paused_reason_ck CHECK (
        (paused_at IS NULL AND paused_reason IS NULL AND paused_by IS NULL)
        OR (paused_at IS NOT NULL AND length(btrim(paused_reason)) BETWEEN 1 AND 500));

-- ---- A principal's suspension and a member's departure -----------------------------------------------------------

ALTER TABLE principal
    ADD COLUMN suspended_at         timestamptz,
    ADD COLUMN suspended_by         uuid REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN suspended_reason     varchar(500),
    ADD COLUMN suspended_cause      varchar(16),
    ADD COLUMN departure_closed_at  timestamptz,
    ADD COLUMN departure_closed_by  uuid REFERENCES app_user (id) ON DELETE SET NULL;

-- A service identity suspended before V021 gets its suspension's time from its last change.
UPDATE principal SET suspended_at = updated_at, suspended_cause = 'ADMIN' WHERE status = 'SUSPENDED';

ALTER TABLE principal
    ADD CONSTRAINT principal_suspended_ck CHECK ((status = 'SUSPENDED') = (suspended_at IS NOT NULL)),
    ADD CONSTRAINT principal_suspended_cause_ck CHECK (
        (suspended_at IS NULL AND suspended_cause IS NULL AND suspended_reason IS NULL AND suspended_by IS NULL)
        OR (suspended_at IS NOT NULL AND suspended_cause IN ('ADMIN', 'USER_DEACTIVATED'))),
    ADD CONSTRAINT principal_suspended_reason_ck CHECK (suspended_reason IS NULL
        OR length(btrim(suspended_reason)) BETWEEN 1 AND 500),
    ADD CONSTRAINT principal_departure_ck CHECK (departure_closed_at IS NULL
        OR (status = 'LEFT' AND kind = 'MEMBER'));

-- The suspended principals of a company, for the switches' page; a member's departures still open, for the inbox.
CREATE INDEX principal_suspended_idx ON principal (company_id) WHERE status = 'SUSPENDED';
CREATE INDEX principal_departure_open_idx ON principal (company_id)
    WHERE status = 'LEFT' AND departure_closed_at IS NULL;

CREATE TABLE principal_departure_group (
    company_id   uuid NOT NULL,
    principal_id uuid NOT NULL,
    group_id     uuid NOT NULL,
    CONSTRAINT principal_departure_group_pk PRIMARY KEY (principal_id, group_id),
    CONSTRAINT principal_departure_group_principal_fk FOREIGN KEY (principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE CASCADE,
    CONSTRAINT principal_departure_group_group_fk FOREIGN KEY (group_id, company_id)
        REFERENCES user_group (id, company_id) ON DELETE CASCADE
);

CREATE INDEX principal_departure_group_group_idx ON principal_departure_group (company_id, group_id);

ALTER TABLE principal_departure_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE principal_departure_group FORCE ROW LEVEL SECURITY;

CREATE POLICY principal_departure_group_tenant ON principal_departure_group FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Written once when the member leaves; goes with the group or the principal.
GRANT SELECT, INSERT ON principal_departure_group TO ${app_role};

-- ---- An agent whose owner left -------------------------------------------------------------------------------------

ALTER TABLE agent
    ADD COLUMN former_owner_principal_id uuid,
    ADD CONSTRAINT agent_former_owner_fk FOREIGN KEY (former_owner_principal_id, company_id)
        REFERENCES principal (id, company_id),
    ADD CONSTRAINT agent_former_owner_ck CHECK (former_owner_principal_id IS NULL OR owner_principal_id IS NULL);

CREATE INDEX agent_former_owner_idx ON agent (company_id, former_owner_principal_id)
    WHERE former_owner_principal_id IS NOT NULL;

-- ---- Queue reasons ------------------------------------------------------------------------------------------------

ALTER TABLE run DROP CONSTRAINT run_queue_reason_ck;
ALTER TABLE run ADD CONSTRAINT run_queue_reason_ck CHECK (queue_reason IS NULL OR queue_reason IN (
    'COMPANY_STOPPED', 'SYSTEM_PAUSED',
    'AGENT_PAUSED', 'ACCOUNT_REQUIRED', 'BUDGET_SPENT', 'KEY_ERROR', 'HOST_OFFLINE', 'MODEL_ACCESS_UNAVAILABLE',
    'NO_HOST_FOR_CREDENTIALS', 'NO_RUNNER_HOST', 'RUNNER_HOST_OFFLINE', 'RUNNER_HOST_DRAINING',
    'CREDENTIAL_POOL_FULL', 'RUNNER_HOST_FULL', 'PREVIOUS_RUN_ACTIVE', 'RUNNER_HOST_REJECTED', 'ASSIGNED',
    'TRIGGER_PAUSED', 'OTHER'));

-- ---- Triggers (package 4): a paused system pauses its triggers; a stopped company's wait ---------------------------

ALTER TABLE trigger DROP CONSTRAINT trigger_paused_reason_ck;
ALTER TABLE trigger ADD CONSTRAINT trigger_paused_reason_ck CHECK (paused_reason IS NULL OR paused_reason IN (
    'USER', 'AUTHOR_LEFT', 'NOT_ENTITLED', 'ACCOUNT_REMOVED', 'SYSTEM_REMOVED', 'ACCOUNT_BROKEN', 'SYSTEM_PAUSED'));

ALTER TABLE trigger DROP CONSTRAINT trigger_waiting_reason_ck;
ALTER TABLE trigger ADD CONSTRAINT trigger_waiting_reason_ck CHECK (waiting_reason IS NULL OR waiting_reason IN (
    'RECONNECT_YOURS', 'RECONNECT_COMPANY', 'SUSPENDED', 'SYSTEM_UNREACHABLE', 'SCOPE_UNREADABLE',
    'GATEWAY_NOT_CONFIGURED', 'COMPANY_STOPPED'));

-- ---- A user's deactivation -----------------------------------------------------------------------------------------

-- The companies the user is a member of, ids only, for an instance admin's deactivation or reactivation of the user,
-- which suspends or resumes their principal in each (the package 6 contract, §5.4). The instance admin's flag reads no
-- other user's memberships (V016), so this narrow lookup does, as runner_host_for_token does; each company's rows are
-- then written in its own tenant context. It reads company_membership through the owner's SELECT-only policy, which
-- the runtime role never matches.
CREATE POLICY company_membership_owner_lookup ON company_membership FOR SELECT TO CURRENT_USER USING (true);

CREATE FUNCTION member_companies(p_user_id uuid)
    RETURNS TABLE (company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT m.company_id FROM ${flyway:defaultSchema}.company_membership m WHERE m.user_id = p_user_id
     ORDER BY m.company_id
$$;

REVOKE ALL ON FUNCTION member_companies(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION member_companies(uuid) TO ${app_role};
