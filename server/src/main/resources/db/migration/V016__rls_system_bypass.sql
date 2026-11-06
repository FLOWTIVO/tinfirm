-- The bypass of row-level security across companies narrowed to server-side work (CONVENTIONS §7).
--
-- app.instance_admin is the flag of the caller: an instance admin's browser session sets it for the whole request,
-- before any company context exists. V001 let it see and write every company's secrets and their versions, read every
-- company's audit events and chain heads, and see and write every company's memberships. None of that is needed by an
-- instance admin's request: a company's rows are read in its context, which the access guard sets after its check (and
-- audits as the break-glass for an instance admin who is not a member). Only trusted server-side work spans companies:
-- the bootstrap and the vault's rewrap, in the SYSTEM tenant context (TenantContext.SYSTEM, SystemContext.call), which
-- now also sets app.system.
--
-- Kept for the instance admin's flag: the company table (GET /api/companies lists every company, the guard finds the
-- company it lets an instance admin into, POST /api/companies creates one), and the instance chain of the audit log
-- (company_id NULL: GET /api/admin/audit-events and its verification).

CREATE FUNCTION app_is_system() RETURNS boolean
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT coalesce(current_setting('app.system', true), '') = 'true' $$;

-- ---- vault -------------------------------------------------------------------------------------------------------

ALTER POLICY secret_tenant ON secret
    USING (company_id = app_company_id() OR app_is_system())
    WITH CHECK (company_id = app_company_id() OR app_is_system());

ALTER POLICY secret_version_tenant ON secret_version
    USING (company_id = app_company_id() OR app_is_system())
    WITH CHECK (company_id = app_company_id() OR app_is_system());

-- ---- audit -------------------------------------------------------------------------------------------------------

-- Company events in their company's context; the instance chain to instance admins; everything to the system.
ALTER POLICY audit_event_select ON audit_event
    USING (company_id = app_company_id()
           OR (company_id IS NULL AND app_is_instance_admin())
           OR app_is_system());

-- Every caller advances the instance chain (a login comes before any context); a company's head in its context.
ALTER POLICY audit_chain_head_select ON audit_chain_head
    USING (company_id IS NULL OR company_id = app_company_id() OR app_is_system());

-- ---- companies and memberships -----------------------------------------------------------------------------------

-- The instance admin's flag still lists and creates companies; the system's sees them all without it.
ALTER POLICY company_select ON company
    USING (app_is_instance_admin()
           OR app_is_system()
           OR id = app_company_id()
           OR EXISTS (SELECT 1
                        FROM company_membership m
                       WHERE m.company_id = company.id
                         AND m.user_id = app_user_id()));

ALTER POLICY company_insert ON company
    WITH CHECK (app_is_instance_admin() OR app_is_system());

-- A company is renamed in its own context (the guard's), never across companies.
ALTER POLICY company_update ON company
    USING (id = app_company_id() OR app_is_system())
    WITH CHECK (id = app_company_id() OR app_is_system());

-- Memberships of the company of the context, and the caller's own across companies (GET /api/me, the guard's role
-- check, the companies the caller lists); written only in the company's context.
ALTER POLICY company_membership_select ON company_membership
    USING (company_id = app_company_id() OR user_id = app_user_id() OR app_is_system());

ALTER POLICY company_membership_insert ON company_membership
    WITH CHECK (company_id = app_company_id() OR app_is_system());

ALTER POLICY company_membership_update ON company_membership
    USING (company_id = app_company_id() OR app_is_system())
    WITH CHECK (company_id = app_company_id() OR app_is_system());

ALTER POLICY company_membership_delete ON company_membership
    USING (company_id = app_company_id() OR app_is_system());
