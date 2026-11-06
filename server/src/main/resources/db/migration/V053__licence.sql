-- The licence (ADR-0077; CONVENTIONS §7): the keys an instance admin entered, the fourth company role VIEWER, the
-- decisions routed to the Owners and Admins instead of Viewers, the count of seats across the instance, and the new
-- users who joined as Viewers for want of a seat.
--
--   licence_key      the key in force and the next one, as an instance admin pasted them (instance-level, as
--                    api_token)
--   seat_fallback    a new user of an identity provider who joined as VIEWER because every seat was taken
--
-- licence_key is instance-level: no company_id and no row-level security, since one key covers the instance and the
-- licence is read before any company context exists (the seat check of a sign-in, GET /api/licence). It holds no
-- personal data but the user who added a key.

--   slot               CURRENT, the key in force (or that ended, for decision 7), or NEXT, a renewal held until its
--                      start; at most one of each (decision 6: one key in force at a time)
--   licence_id, rev    the licence's id and the key's revision: seats bought during the year come as the same id's
--                      next revision, which replaces the key; a revision not higher than the one held is refused
--   customer           the customer the key names
--   edition            PRO or ENTERPRISE
--   seats              the seats the key holds; NULL only for an Enterprise key without a seat count
--   companies          an Enterprise key's cap of declared companies; NULL for none
--   starts_at, ends_at the key's first and last day (UTC), both included
--   key_text           the key as entered, verified again at every start and every hour against the public key the
--                      server ships with; never returned by the API
--   applied_at, applied_by  when and by which instance admin it was applied
CREATE TABLE licence_key (
    slot       varchar(8)   PRIMARY KEY,
    licence_id varchar(100) NOT NULL,
    rev        integer      NOT NULL,
    customer   varchar(200) NOT NULL,
    edition    varchar(16)  NOT NULL,
    seats      integer,
    companies  integer,
    starts_at  date         NOT NULL,
    ends_at    date         NOT NULL,
    key_text   text         NOT NULL,
    applied_at timestamptz  NOT NULL,
    applied_by uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT licence_key_slot_ck CHECK (slot IN ('CURRENT', 'NEXT')),
    CONSTRAINT licence_key_rev_ck CHECK (rev >= 1),
    CONSTRAINT licence_key_edition_ck CHECK (edition IN ('PRO', 'ENTERPRISE')),
    CONSTRAINT licence_key_seats_ck CHECK (seats IS NULL OR seats >= 1),
    CONSTRAINT licence_key_pro_seats_ck CHECK (edition <> 'PRO' OR (seats IS NOT NULL AND companies IS NULL)),
    CONSTRAINT licence_key_companies_ck CHECK (companies IS NULL OR companies >= 1),
    CONSTRAINT licence_key_period_ck CHECK (starts_at <= ends_at),
    CONSTRAINT licence_key_text_ck CHECK (key_text ~ '^TINFIRM1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$')
);

-- Applied in place of the key held in its slot, and moved from NEXT to CURRENT on its start date; never removed
-- by the API.
GRANT SELECT, INSERT, UPDATE, DELETE ON licence_key TO ${app_role};

-- The fourth role (ADR-0077, decision 5): VIEWER holds no seat.
ALTER TABLE company_membership DROP CONSTRAINT company_membership_role_ck;
ALTER TABLE company_membership
    ADD CONSTRAINT company_membership_role_ck CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER'));

-- A question or an approval whose only candidates are Viewers goes to the company's Owners and Admins instead
-- (decision 5): no person, group or author decides it then, and approver_admins says who does.
ALTER TABLE decision ADD COLUMN approver_admins boolean NOT NULL DEFAULT false;
-- T12 of V011, with the Owners and Admins: an approval names at most one approver, and one while it is open, or the
-- Owners and Admins in place of any; a question no group.
ALTER TABLE decision DROP CONSTRAINT decision_approver_ck;
ALTER TABLE decision ADD CONSTRAINT decision_approver_ck CHECK (
    NOT (approver_principal_id IS NOT NULL AND approver_group_id IS NOT NULL)
    AND (kind = 'APPROVAL' OR approver_group_id IS NULL)
    AND (NOT approver_admins OR (approver_principal_id IS NULL AND approver_group_id IS NULL))
    AND (kind = 'QUESTION' OR status <> 'OPEN' OR approver_admins OR approver_principal_id IS NOT NULL
         OR approver_group_id IS NOT NULL));

-- T12 at the insert, as V011's: a personal conversation's decision names its person; a trigger's question names
-- nobody, and a trigger's approval exactly one approver (its team, or its author without one). Either may name the
-- Owners and Admins instead, and nobody else then.
CREATE OR REPLACE FUNCTION decision_conversation_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_person  uuid;
    v_trigger uuid;
BEGIN
    SELECT c.person_principal_id, c.trigger_id INTO v_person, v_trigger
      FROM conversation c WHERE c.id = NEW.conversation_id AND c.company_id = NEW.company_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    IF NEW.approver_admins THEN
        IF NEW.approver_principal_id IS NOT NULL OR NEW.approver_group_id IS NOT NULL THEN
            RAISE EXCEPTION 'A decision for the Owners and Admins names no other approver'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'decision_conversation_approver_ck';
        END IF;
        RETURN NULL;
    END IF;
    IF (v_trigger IS NULL AND (NEW.approver_principal_id IS DISTINCT FROM v_person OR NEW.approver_group_id IS NOT NULL))
       OR (v_trigger IS NOT NULL AND NEW.kind = 'QUESTION'
           AND (NEW.approver_principal_id IS NOT NULL OR NEW.approver_group_id IS NOT NULL))
       OR (v_trigger IS NOT NULL AND NEW.kind = 'APPROVAL'
           AND (NEW.approver_principal_id IS NULL) = (NEW.approver_group_id IS NULL)) THEN
        RAISE EXCEPTION 'A decision names its conversation''s approver: the person; a trigger''s team or author'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'decision_conversation_approver_ck';
    END IF;
    RETURN NULL;
END
$$;

CREATE INDEX decision_approver_admins_idx ON decision (company_id, created_at, id)
    WHERE status = 'OPEN' AND approver_admins;

-- A seat is a person's, across the instance (decision 5): the distinct active users who hold OWNER, ADMIN or MEMBER
-- in any company. A service account is a principal, not a user, and holds none. Read across the companies, before or
-- outside any company context, so SECURITY DEFINER; they answer counts and a flag, never ids.
CREATE FUNCTION licence_seats_used()
    RETURNS integer
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT count(DISTINCT m.user_id)::integer
      FROM ${flyway:defaultSchema}.company_membership m
      JOIN ${flyway:defaultSchema}.app_user u ON u.id = m.user_id
     WHERE m.role IN ('OWNER', 'ADMIN', 'MEMBER')
       AND u.active
$$;

CREATE FUNCTION licence_holds_seat(p_user_id uuid)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM ${flyway:defaultSchema}.company_membership m
          JOIN ${flyway:defaultSchema}.app_user u ON u.id = m.user_id
         WHERE m.user_id = p_user_id
           AND m.role IN ('OWNER', 'ADMIN', 'MEMBER')
           AND u.active)
$$;

-- Whether the user holds a role that holds a seat, active or not: a deactivated user who becomes active again takes
-- a seat back.
CREATE FUNCTION licence_has_seat_role(p_user_id uuid)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM ${flyway:defaultSchema}.company_membership m
         WHERE m.user_id = p_user_id
           AND m.role IN ('OWNER', 'ADMIN', 'MEMBER'))
$$;

REVOKE ALL ON FUNCTION licence_seats_used() FROM PUBLIC;
REVOKE ALL ON FUNCTION licence_holds_seat(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION licence_has_seat_role(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION licence_seats_used() TO ${app_role};
GRANT EXECUTE ON FUNCTION licence_holds_seat(uuid) TO ${app_role};
GRANT EXECUTE ON FUNCTION licence_has_seat_role(uuid) TO ${app_role};

-- A new user of an identity provider who joined as VIEWER because no seat was free (decision 5), for the inbox of the
-- company's Owners and Admins (SEAT_FALLBACK). A tenant table: the conventions of V001 apply.
--
--   company_id, user_id   the membership; the row goes with it
--   role                  the role the provider gives, which holds a seat (MEMBER or ADMIN)
--   provider              the registration id of the provider
--   joined_at             when they joined; the inbox shows the row for 30 days while they are still a Viewer
CREATE TABLE seat_fallback (
    company_id uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    role       varchar(16) NOT NULL,
    provider   varchar(64) NOT NULL,
    joined_at  timestamptz NOT NULL,
    CONSTRAINT seat_fallback_pk PRIMARY KEY (company_id, user_id),
    CONSTRAINT seat_fallback_role_ck CHECK (role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT seat_fallback_membership_fk FOREIGN KEY (company_id, user_id)
        REFERENCES company_membership (company_id, user_id) ON DELETE CASCADE
);

CREATE INDEX seat_fallback_joined_idx ON seat_fallback (company_id, joined_at);

ALTER TABLE seat_fallback ENABLE ROW LEVEL SECURITY;
ALTER TABLE seat_fallback FORCE ROW LEVEL SECURITY;

CREATE POLICY seat_fallback_tenant ON seat_fallback FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Written once; deleted with its membership.
GRANT SELECT, INSERT, DELETE ON seat_fallback TO ${app_role};
