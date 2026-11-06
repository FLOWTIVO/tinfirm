-- ADR-0088: who decides a membership's role. APP: the company's Owners and Admins, in the app. IDP:<registration id>:
-- the identity provider of that registration, at every sign-in through it. SCIM: the company's SCIM client
-- (ADR-0087). While it is not APP, the app answers a change of the role 409 ROLE_MANAGED. Every existing membership
-- is the app's: a provider takes it at the person's next sign-in, when the provider decides.

ALTER TABLE company_membership
    ADD COLUMN role_source varchar(80) NOT NULL DEFAULT 'APP',
    ADD CONSTRAINT company_membership_role_source_ck
        CHECK (role_source IN ('APP', 'SCIM') OR role_source ~ '^IDP:[A-Za-z0-9._-]{1,64}$'),
    -- OWNER is never a provider's or SCIM's (ADR-0088 decision 4).
    ADD CONSTRAINT company_membership_owner_source_ck CHECK (role <> 'OWNER' OR role_source = 'APP');

-- The release of a provider's memberships at the start (decision 7) looks for the managed ones only.
CREATE INDEX company_membership_role_source_idx ON company_membership (role_source) WHERE role_source <> 'APP';

-- A provider's or SCIM's role that falls back to VIEWER at a later sign-in or write updates the admins' item, one per
-- person (ADR-0088 decision 2).
GRANT UPDATE ON seat_fallback TO ${app_role};
