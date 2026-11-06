-- SCIM 2.0 provisioning of a company's members (ADR-0087; CONVENTIONS §5, §7, §8, §15.13). The schema owner runs
-- this file.
--
-- A company that the deployment allows serves SCIM's Users to a bearer token its OWNER issues. SCIM creates and
-- updates the company's members, suspends them on deactivation and removes them as members who leave; a user it
-- creates is bound to their first sign-in by the identity provider's externalId.
--
--   scim_token                          a company's SCIM bearer tokens: hex(sha256(token)), never the token; at most
--                                       two active (the service's check, under the company's lock)
--   company_membership.scim_external_id the IdP's stable id of the person, unique in the company; binds the first
--                                       sign-in (decision 6)
--   company_membership.scim_suspended_at SCIM's suspension, kept apart from an admin's (the principal's status), so
--                                       that SCIM never lifts an admin's block (decision 7)
--   company_membership.scim_last_owner_refused_at
--                                       when SCIM last tried to deactivate or delete this OWNER, the last active
--                                       one, and was refused: the OWNER's inbox says so (decision 7)
--   app_user.scim_deactivated_at        SCIM's deactivation of the user, kept apart from an instance admin's
--   app_user.admin_deactivated          an instance admin's deactivation; active is the user's standing as a whole,
--                                       false while either holds (app_user_active_ck), so that every reader of
--                                       active stays as it was
--   app_user.scim_company_id            the company whose SCIM created or bound the user: only that company's IdP
--                                       renames them, and only while they are its member alone
--   auth_source SCIM                    a user SCIM created, with no password and no identity yet
--   principal.suspended_cause SCIM      a member's principal suspended by SCIM
--   audit_event.actor_type SCIM         a SCIM token, by its id (decision 3)

-- ---- users -------------------------------------------------------------------------------------------------------

ALTER TABLE app_user
    ADD COLUMN admin_deactivated   boolean NOT NULL DEFAULT false,
    ADD COLUMN scim_deactivated_at timestamptz,
    ADD COLUMN scim_company_id     uuid REFERENCES company (id) ON DELETE SET NULL;

UPDATE app_user SET admin_deactivated = NOT active;

ALTER TABLE app_user
    ADD CONSTRAINT app_user_active_ck CHECK (active = (NOT admin_deactivated AND scim_deactivated_at IS NULL));

ALTER TABLE app_user DROP CONSTRAINT app_user_auth_source_ck;
ALTER TABLE app_user
    ADD CONSTRAINT app_user_auth_source_ck CHECK (
        (auth_source = 'LOCAL' AND password_hash IS NOT NULL AND oidc_issuer IS NULL AND oidc_subject IS NULL)
        OR (auth_source = 'OIDC' AND password_hash IS NULL AND oidc_issuer IS NOT NULL AND oidc_subject IS NOT NULL)
        OR (auth_source = 'SCIM' AND password_hash IS NULL AND oidc_issuer IS NULL AND oidc_subject IS NULL));

CREATE INDEX app_user_scim_company_idx ON app_user (scim_company_id) WHERE scim_company_id IS NOT NULL;

-- ---- memberships -------------------------------------------------------------------------------------------------

ALTER TABLE company_membership
    ADD COLUMN scim_external_id            varchar(255),
    ADD COLUMN scim_suspended_at           timestamptz,
    ADD COLUMN scim_last_owner_refused_at  timestamptz;

ALTER TABLE company_membership
    ADD CONSTRAINT company_membership_scim_external_id_ck CHECK (scim_external_id IS NULL
        OR length(btrim(scim_external_id)) BETWEEN 1 AND 255);

-- A filter by externalId, which Entra ID matches by, and the binding of a sign-in, are index lookups.
CREATE UNIQUE INDEX company_membership_scim_external_id_uq ON company_membership (company_id, scim_external_id)
    WHERE scim_external_id IS NOT NULL;

-- SCIM's pages are read by the user's id inside one company (decision 1, the bounded offset of CONVENTIONS §7).
CREATE INDEX company_membership_scim_page_idx ON company_membership (company_id, user_id);

-- ---- principals and the audit ------------------------------------------------------------------------------------

ALTER TABLE principal DROP CONSTRAINT principal_suspended_cause_ck;
ALTER TABLE principal
    ADD CONSTRAINT principal_suspended_cause_ck CHECK (
        (suspended_at IS NULL AND suspended_cause IS NULL AND suspended_reason IS NULL AND suspended_by IS NULL)
        OR (suspended_at IS NOT NULL AND suspended_cause IN ('ADMIN', 'USER_DEACTIVATED', 'SCIM')));

ALTER TABLE audit_event DROP CONSTRAINT audit_event_actor_type_ck;
ALTER TABLE audit_event
    ADD CONSTRAINT audit_event_actor_type_ck CHECK (actor_type IN ('USER', 'SYSTEM', 'AGENT', 'SCIM'));

-- ---- the company's tokens ----------------------------------------------------------------------------------------

CREATE TABLE scim_token (
    id           uuid         PRIMARY KEY,
    company_id   uuid         NOT NULL REFERENCES company (id),
    name         varchar(100) NOT NULL,
    token_hash   char(64)     NOT NULL,
    created_by   uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz,
    last_used_at timestamptz,
    revoked_at   timestamptz,
    revoked_by   uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT scim_token_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT scim_token_name_ck CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT scim_token_token_hash_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT scim_token_expires_at_ck CHECK (expires_at IS NULL OR expires_at > created_at)
);

-- The company's tokens, newest first; the count of active ones for the cap of two.
CREATE INDEX scim_token_company_idx ON scim_token (company_id, created_at DESC);

ALTER TABLE scim_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE scim_token FORCE ROW LEVEL SECURITY;

CREATE POLICY scim_token_tenant ON scim_token FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Tokens are revoked, never deleted: the audit log names them by id.
GRANT SELECT, INSERT, UPDATE ON scim_token TO ${app_role};

-- A token is resolved by its hash before any tenant is known, as a personal access token is (decision 3): the
-- token's id, company and standing, never another column.
CREATE FUNCTION scim_token_by_hash(p_token_hash text)
    RETURNS TABLE (id uuid, company_id uuid, expires_at timestamptz, revoked_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.id, t.company_id, t.expires_at, t.revoked_at
      FROM ${flyway:defaultSchema}.scim_token t
     WHERE t.token_hash = p_token_hash
$$;

-- The function runs as the schema's owner, whom the table's forced row-level security binds too: it reads every row.
CREATE POLICY scim_token_owner_lookup ON scim_token FOR SELECT TO CURRENT_USER USING (true);

REVOKE ALL ON FUNCTION scim_token_by_hash(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION scim_token_by_hash(text) TO ${app_role};

-- ---- across companies --------------------------------------------------------------------------------------------

-- Whether the user is an OWNER of any company: no sign-in binds to an owner's account (decision 6), and SCIM never
-- sets an owner's externalId. A flag, never a company.
CREATE FUNCTION user_owns_a_company(p_user_id uuid)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM ${flyway:defaultSchema}.company_membership m
                    WHERE m.user_id = p_user_id AND m.role = 'OWNER')
$$;

REVOKE ALL ON FUNCTION user_owns_a_company(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION user_owns_a_company(uuid) TO ${app_role};

-- Whether the user is a member of a company other than this one in which their principal is not suspended: SCIM
-- deactivates the user only when no other company holds them (decision 7), and renames them only while this company
-- is their only one. Counts, never ids.
CREATE POLICY principal_owner_lookup ON principal FOR SELECT TO CURRENT_USER USING (true);

CREATE FUNCTION user_memberships_elsewhere(p_user_id uuid, p_company_id uuid)
    RETURNS TABLE (memberships integer, active_memberships integer)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT count(*)::integer,
           count(*) FILTER (WHERE NOT EXISTS (
               SELECT 1 FROM ${flyway:defaultSchema}.principal p
                WHERE p.company_id = m.company_id AND p.user_id = m.user_id AND p.kind = 'MEMBER'
                  AND p.status = 'SUSPENDED'))::integer
      FROM ${flyway:defaultSchema}.company_membership m
     WHERE m.user_id = p_user_id AND m.company_id <> p_company_id
$$;

REVOKE ALL ON FUNCTION user_memberships_elsewhere(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION user_memberships_elsewhere(uuid, uuid) TO ${app_role};
