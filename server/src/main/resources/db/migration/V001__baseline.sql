-- Baseline of the platform redesign (docs/reviews/2026-10-12-platform-redesign.md, Part 9; the package 1 contract,
-- docs/reviews/2026-10-12-package-1-contract.md §2): a fresh schema, as the restart of 2026-10-05 had one. No data
-- is migrated: an installation's database is replaced. The file holds the tables the kept modules need (shared,
-- identity, company, audit, vault, bootstrap, runner, mcp) in their final shape, and the tables of package 1:
-- principals and service identities, systems with their repositories and MCP catalogues, accounts, grants and
-- pending OAuth sign-ins. The old domain's tables (connections, agents, work items, jobs, runs, decisions, model and
-- runner credentials, the group rights on agents) are not created; later packages create their own.
--
-- Conventions of every tenant table (CONVENTIONS §7): company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on
-- app_company_id(), a composite UNIQUE (id, company_id) that composite foreign keys point at, and grants to the
-- runtime role ${app_role}, which owns nothing. The schema owner runs this file.

-- ---- tenant context ----------------------------------------------------------------------------------------------

-- Accessors used by every row-level security policy. The application sets app.company_id, app.user_id and
-- app.instance_admin with set_config(name, value, true) (transaction-local) at the start of every transaction; see
-- com.flowtivo.tinfirm.shared.tenant.TenantAwareDataSource. A missing or empty setting yields NULL (or false), so any
-- policy built on these functions denies access unless the application explicitly established the context.

CREATE FUNCTION app_company_id() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT nullif(current_setting('app.company_id', true), '')::uuid $$;

CREATE FUNCTION app_user_id() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;

CREATE FUNCTION app_is_instance_admin() RETURNS boolean
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT coalesce(current_setting('app.instance_admin', true), '') = 'true' $$;

-- ---- sessions ----------------------------------------------------------------------------------------------------

-- Spring Session JDBC tables (schema-postgresql.sql of spring-session-jdbc 4.1).
-- Spring must not create them itself: spring.session.jdbc.initialize-schema=never.

CREATE TABLE spring_session (
    primary_id            char(36)     NOT NULL,
    session_id            char(36)     NOT NULL,
    creation_time         bigint       NOT NULL,
    last_access_time      bigint       NOT NULL,
    max_inactive_interval int          NOT NULL,
    expiry_time           bigint       NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

GRANT SELECT, INSERT, UPDATE, DELETE ON spring_session, spring_session_attributes TO ${app_role};

-- ---- company -----------------------------------------------------------------------------------------------------

-- The tenant.

CREATE TABLE company (
    id         uuid         PRIMARY KEY,
    name       varchar(100) NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT company_name_ck CHECK (length(name) BETWEEN 1 AND 100)
);

-- listCompanies: keyset on (name, id).
CREATE INDEX company_name_idx ON company (name, id);

-- ---- identity ----------------------------------------------------------------------------------------------------

-- Users are instance-level, not tenant data, so app_user has no RLS.
-- Company-scoped data about users lives in company_membership, which is RLS-protected.
--
--   provisioned_by_company_id  the company whose own identity provider created the user (CONVENTIONS §7, §14.12);
--                              NULL for local users and for users of the instance's providers. Set once, at
--                              creation, and never changed. Only that company can add the user as a member.

CREATE TABLE app_user (
    id                        uuid         PRIMARY KEY,
    email                     varchar(320) NOT NULL,
    display_name              varchar(100) NOT NULL,
    auth_source               varchar(8)   NOT NULL,
    -- Argon2id hash; local users only.
    password_hash             varchar(255),
    -- OIDC identity (issuer, subject); OIDC users only. Users are never matched by email.
    oidc_issuer               varchar(512),
    oidc_subject              varchar(255),
    instance_admin            boolean      NOT NULL DEFAULT false,
    active                    boolean      NOT NULL DEFAULT true,
    created_at                timestamptz  NOT NULL,
    updated_at                timestamptz  NOT NULL,
    last_login_at             timestamptz,
    version                   bigint       NOT NULL DEFAULT 0,
    provisioned_by_company_id uuid         REFERENCES company (id),
    CONSTRAINT app_user_email_uq UNIQUE (email),
    CONSTRAINT app_user_oidc_identity_uq UNIQUE (oidc_issuer, oidc_subject),
    CONSTRAINT app_user_email_lower_ck CHECK (email = lower(email)),
    CONSTRAINT app_user_auth_source_ck CHECK (
        (auth_source = 'LOCAL' AND password_hash IS NOT NULL AND oidc_issuer IS NULL AND oidc_subject IS NULL)
        OR (auth_source = 'OIDC' AND password_hash IS NULL AND oidc_issuer IS NOT NULL AND oidc_subject IS NOT NULL)),
    -- Only users of a company's provider have a company, and those are OIDC users.
    CONSTRAINT app_user_provisioned_by_company_ck CHECK (provisioned_by_company_id IS NULL OR auth_source = 'OIDC')
);

CREATE INDEX app_user_active_admin_idx ON app_user (id) WHERE instance_admin AND active;
-- The foreign key's lookups when a company row changes; few users have the column set.
CREATE INDEX app_user_provisioned_by_company_idx ON app_user (provisioned_by_company_id)
    WHERE provisioned_by_company_id IS NOT NULL;

-- Users are deactivated, never deleted.
GRANT SELECT, INSERT, UPDATE ON app_user TO ${app_role};

-- ---- memberships -------------------------------------------------------------------------------------------------

-- A user's role in a company: OWNER (everything, and the company's settings), ADMIN (systems and their apps, service
-- identities, grants, groups, company runner hosts) or MEMBER (Part 8 of the redesign).

CREATE TABLE company_membership (
    company_id uuid        NOT NULL REFERENCES company (id),
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role       varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT company_membership_pk PRIMARY KEY (company_id, user_id),
    CONSTRAINT company_membership_role_ck CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
);

CREATE INDEX company_membership_user_idx ON company_membership (user_id);
CREATE INDEX company_membership_page_idx ON company_membership (company_id, created_at, user_id);

ALTER TABLE company ENABLE ROW LEVEL SECURITY;
ALTER TABLE company FORCE ROW LEVEL SECURITY;
ALTER TABLE company_membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_membership FORCE ROW LEVEL SECURITY;

-- Visible: the company of the current context, companies the caller is a member of
-- (explicit cross-company read), or any company for an instance admin.
CREATE POLICY company_select ON company FOR SELECT
    USING (app_is_instance_admin()
           OR id = app_company_id()
           OR EXISTS (SELECT 1
                        FROM company_membership m
                       WHERE m.company_id = company.id
                         AND m.user_id = app_user_id()));

CREATE POLICY company_insert ON company FOR INSERT
    WITH CHECK (app_is_instance_admin());

CREATE POLICY company_update ON company FOR UPDATE
    USING (app_is_instance_admin() OR id = app_company_id())
    WITH CHECK (app_is_instance_admin() OR id = app_company_id());

-- Visible: memberships of the current company, the caller's own memberships
-- (explicit cross-company read), or everything for an instance admin.
CREATE POLICY company_membership_select ON company_membership FOR SELECT
    USING (app_is_instance_admin() OR company_id = app_company_id() OR user_id = app_user_id());

CREATE POLICY company_membership_insert ON company_membership FOR INSERT
    WITH CHECK (app_is_instance_admin() OR company_id = app_company_id());

CREATE POLICY company_membership_update ON company_membership FOR UPDATE
    USING (app_is_instance_admin() OR company_id = app_company_id())
    WITH CHECK (app_is_instance_admin() OR company_id = app_company_id());

CREATE POLICY company_membership_delete ON company_membership FOR DELETE
    USING (app_is_instance_admin() OR company_id = app_company_id());

GRANT SELECT, INSERT, UPDATE ON company TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON company_membership TO ${app_role};

-- ---- audit -------------------------------------------------------------------------------------------------------

-- Hash-chained, append-only audit log (CONVENTIONS §8).
-- One chain per company plus one instance chain (company_id IS NULL).
-- hash = hex(sha256(prev_hash || '\n' || canonical_json)); see com.flowtivo.tinfirm.audit.
-- Personal data stays out of the hashed part: the actor is (type, id) and its display name is
-- resolved at read time, so erasing a user never breaks a chain.

-- Head of each chain: locked with SELECT ... FOR UPDATE to serialise appends.
CREATE TABLE audit_chain_head (
    company_id uuid     REFERENCES company (id),
    last_seq   bigint   NOT NULL,
    last_hash  char(64) NOT NULL,
    CONSTRAINT audit_chain_head_company_uq UNIQUE NULLS NOT DISTINCT (company_id),
    CONSTRAINT audit_chain_head_seq_ck CHECK (last_seq >= 0)
);

CREATE TABLE audit_event (
    id          uuid        PRIMARY KEY,
    company_id  uuid        REFERENCES company (id),
    seq         bigint      NOT NULL,
    occurred_at timestamptz NOT NULL,
    actor_type  varchar(16) NOT NULL,
    actor_id    uuid,
    action      varchar(64) NOT NULL,
    target_type varchar(64),
    target_id   varchar(255),
    details     jsonb       NOT NULL,
    prev_hash   char(64)    NOT NULL,
    hash        char(64)    NOT NULL,
    CONSTRAINT audit_event_chain_uq UNIQUE NULLS NOT DISTINCT (company_id, seq),
    CONSTRAINT audit_event_seq_ck CHECK (seq > 0),
    CONSTRAINT audit_event_actor_type_ck CHECK (actor_type IN ('USER', 'SYSTEM', 'AGENT')),
    CONSTRAINT audit_event_details_ck CHECK (jsonb_typeof(details) = 'object')
);

ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_event FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_chain_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_chain_head FORCE ROW LEVEL SECURITY;

-- Company events: visible in their company context or to instance admins.
-- Instance-chain events (company_id IS NULL): visible to instance admins only.
CREATE POLICY audit_event_select ON audit_event FOR SELECT
    USING (company_id = app_company_id() OR app_is_instance_admin());

-- Instance events such as logins are written with no company context.
CREATE POLICY audit_event_insert ON audit_event FOR INSERT
    WITH CHECK (company_id IS NULL OR company_id = app_company_id());

-- The head holds only (seq, hash). Every caller must be able to advance the instance chain
-- (a login happens before any user context exists), so the instance head is not admin-only.
CREATE POLICY audit_chain_head_select ON audit_chain_head FOR SELECT
    USING (company_id IS NULL OR company_id = app_company_id() OR app_is_instance_admin());

CREATE POLICY audit_chain_head_insert ON audit_chain_head FOR INSERT
    WITH CHECK (company_id IS NULL OR company_id = app_company_id());

CREATE POLICY audit_chain_head_update ON audit_chain_head FOR UPDATE
    USING (company_id IS NULL OR company_id = app_company_id())
    WITH CHECK (company_id IS NULL OR company_id = app_company_id());

-- Append-only: the runtime role can never UPDATE or DELETE audit events,
-- and never DELETE a chain head. SELECT ... FOR UPDATE on the head needs UPDATE.
GRANT SELECT, INSERT ON audit_event TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON audit_chain_head TO ${app_role};

-- ---- vault -------------------------------------------------------------------------------------------------------

-- Credential vault (CONVENTIONS §9): envelope-encrypted secrets. Values are never stored in plaintext.
--
--   owner_user_id   the member whose own account on a system the secret holds (contract §3.6, invariant I3); NULL
--                   for the company's own secrets. An owned secret is never listed, revealed or changed through the
--                   Secrets API; it is revealed only for a call that acts as its owner, and it goes with the account,
--                   and with the member when they leave the company.

CREATE TABLE secret (
    id                 uuid         PRIMARY KEY,
    company_id         uuid         NOT NULL REFERENCES company (id),
    name               varchar(64)  NOT NULL,
    description        varchar(500),
    current_version    integer      NOT NULL,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    -- The creator's display name is resolved at read time; personal data stays in app_user.
    created_by         uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    last_accessed_at   timestamptz,
    lock_version       bigint       NOT NULL DEFAULT 0,
    owner_user_id      uuid         REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT secret_company_name_uq UNIQUE (company_id, name),
    CONSTRAINT secret_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT secret_name_ck CHECK (name ~ '^[A-Za-z_][A-Za-z0-9_]{0,63}$'),
    CONSTRAINT secret_current_version_ck CHECK (current_version > 0)
);

CREATE INDEX secret_owner_user_idx ON secret (owner_user_id) WHERE owner_user_id IS NOT NULL;

-- One row per stored value. The DEK is wrapped under the master key identified by key_id;
-- AES-256-GCM with AAD "companyId|secretId|version" for both the value and the wrapped DEK.
CREATE TABLE secret_version (
    secret_id   uuid        NOT NULL,
    version     integer     NOT NULL,
    company_id  uuid        NOT NULL,
    key_id      varchar(64) NOT NULL,
    wrapped_dek bytea       NOT NULL,
    dek_nonce   bytea       NOT NULL,
    value_nonce bytea       NOT NULL,
    ciphertext  bytea       NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT secret_version_pk PRIMARY KEY (secret_id, version),
    CONSTRAINT secret_version_secret_fk FOREIGN KEY (secret_id, company_id)
        REFERENCES secret (id, company_id) ON DELETE CASCADE,
    CONSTRAINT secret_version_nonce_ck CHECK (octet_length(dek_nonce) = 12 AND octet_length(value_nonce) = 12)
);

-- The rewrap finds the versions of a retired key.
CREATE INDEX secret_version_key_idx ON secret_version (key_id);

ALTER TABLE secret ENABLE ROW LEVEL SECURITY;
ALTER TABLE secret FORCE ROW LEVEL SECURITY;
ALTER TABLE secret_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE secret_version FORCE ROW LEVEL SECURITY;

CREATE POLICY secret_tenant ON secret FOR ALL
    USING (company_id = app_company_id() OR app_is_instance_admin())
    WITH CHECK (company_id = app_company_id() OR app_is_instance_admin());

CREATE POLICY secret_version_tenant ON secret_version FOR ALL
    USING (company_id = app_company_id() OR app_is_instance_admin())
    WITH CHECK (company_id = app_company_id() OR app_is_instance_admin());

GRANT SELECT, INSERT, UPDATE, DELETE ON secret, secret_version TO ${app_role};

-- Keys of the instance rather than of a company (CONVENTIONS §9): today the RSA key that signs the authorization
-- server's access tokens. The same envelope encryption as secret_version: the PKCS#8 private key is encrypted with
-- AES-256-GCM under a fresh data key, and the data key under the active master key, both with the AAD
-- "instance|name|version" instead of a company. A key is never listed or revealed over the API; the public half is
-- published in the JWK set (/oauth2/jwks) under `kid`. POST /api/admin/vault/rewrap rewraps these rows as well.
--
--   name        what the key is for, e.g. oauth-signing; unique, so a second replica starting at the same time finds
--               the first one's key instead of making its own
--   kid         the JWK key id, a random identifier
--   algorithm   RSA
--   version     part of the AAD; 1 for now. A rotation adds a key under a new name (CONVENTIONS §9)
--   retired_at  set when a newer key took over signing; a retired key stays in the JWK set until its tokens expired
CREATE TABLE instance_key (
    id          uuid        PRIMARY KEY,
    name        varchar(64) NOT NULL,
    kid         varchar(64) NOT NULL,
    algorithm   varchar(16) NOT NULL,
    version     integer     NOT NULL DEFAULT 1,
    key_id      varchar(64) NOT NULL,
    wrapped_dek bytea       NOT NULL,
    dek_nonce   bytea       NOT NULL,
    value_nonce bytea       NOT NULL,
    ciphertext  bytea       NOT NULL,
    created_at  timestamptz NOT NULL,
    retired_at  timestamptz,
    CONSTRAINT instance_key_name_uq UNIQUE (name),
    CONSTRAINT instance_key_kid_uq UNIQUE (kid),
    CONSTRAINT instance_key_name_ck CHECK (name ~ '^[a-z][a-z0-9-]{0,63}$'),
    CONSTRAINT instance_key_algorithm_ck CHECK (algorithm IN ('RSA')),
    CONSTRAINT instance_key_version_ck CHECK (version > 0),
    CONSTRAINT instance_key_nonce_ck CHECK (octet_length(dek_nonce) = 12 AND octet_length(value_nonce) = 12)
);

-- Keys are retired, never deleted, and their rows are rewrapped in place.
GRANT SELECT, INSERT, UPDATE ON instance_key TO ${app_role};

-- ---- personal access tokens --------------------------------------------------------------------------------------

-- Personal access tokens (ADR-0045): a user creates a token on /me for scripts, curl and CI, and presents it as
-- `Authorization: Bearer tfp_...` on the MCP endpoint of a company (/api/companies/{companyId}/mcp). The REST API
-- itself stays session-only.
--
-- A token belongs to a user, not to a company, so api_token is an instance-level table like app_user and the
-- session tables: no company_id and no row-level security. The server reads and writes it only for the calling user
-- (the /api/me/tokens endpoints) or by the hash of a presented token.
--
--   name          the user's label for the token, e.g. "laptop claude code"
--   scope         READ, WRITE or DECIDE: the OAuth scopes tinfirm:read, tinfirm:write and tinfirm:decide; each
--                 level includes the lower ones. A scope never adds to the member's role: the company's access
--                 guard still decides.
--   token_hash    hex(sha256(token)); the token itself (tfp_ + 43 base64url characters) is shown once and never
--                 stored
--   expires_at    NULL for a token that does not expire
--   last_used_at  the latest request with the token, at most a minute stale (the server writes it at most once a
--                 minute per token)
--   revoked_at    set once by a revoke; a revoked token is refused and is never active again
CREATE TABLE api_token (
    id           uuid         PRIMARY KEY,
    user_id      uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name         varchar(100) NOT NULL,
    scope        varchar(32)  NOT NULL,
    token_hash   char(64)     NOT NULL,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz,
    last_used_at timestamptz,
    revoked_at   timestamptz,
    CONSTRAINT api_token_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT api_token_scope_ck CHECK (scope IN ('READ', 'WRITE', 'DECIDE')),
    CONSTRAINT api_token_name_ck CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT api_token_token_hash_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT api_token_expires_at_ck CHECK (expires_at IS NULL OR expires_at > created_at)
);

-- The user's tokens, newest first; the count of active ones for the cap of 25.
CREATE INDEX api_token_user_idx ON api_token (user_id, created_at DESC);

-- Tokens are revoked, never deleted: the audit log names them by id.
GRANT SELECT, INSERT, UPDATE ON api_token TO ${app_role};

-- ---- Tinfirm's authorization server ------------------------------------------------------------------------------

-- Tinfirm's OAuth 2.1 authorization server for people's MCP clients (ADR-0045): Spring Security 7's three tables.
--
-- A client, an authorization and a consent are a person's, not a company's, so these are instance-level tables like
-- app_user, api_token and the session tables: no company_id and no row-level security. The company an authorization
-- was consented for is its `resource` (the company's MCP endpoint), kept in the authorization request inside
-- `attributes`; the server reads and writes the rows only for the calling user, by a presented token, or by the
-- state of a pending authorization.
--
-- The three tables follow Spring Security 7.1's oauth2-registered-client-schema.sql, oauth2-authorization-schema.sql
-- and oauth2-authorization-consent-schema.sql, with the PostgreSQL types their comments ask for ('blob' becomes text,
-- 'timestamp' becomes timestamptz) and two widenings: a client of a Client ID Metadata Document has its https URL as
-- its id (up to 500 characters, the outbound guard's limit), so the client id columns hold 500 instead of 100; and
-- its document may name 10 redirect URIs of 200 characters each, which Spring stores comma-separated, so
-- redirect_uris is text. The other columns such a client writes stay within Spring's sizes: its name is cut to 100
-- characters, and its scopes and settings are Tinfirm's fixed defaults.

CREATE TABLE oauth2_registered_client (
    id                            varchar(500)  NOT NULL,
    client_id                     varchar(500)  NOT NULL,
    client_id_issued_at           timestamptz   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret                 varchar(200)  DEFAULT NULL,
    client_secret_expires_at      timestamptz   DEFAULT NULL,
    client_name                   varchar(200)  NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types     varchar(1000) NOT NULL,
    redirect_uris                 text          DEFAULT NULL,
    post_logout_redirect_uris     varchar(1000) DEFAULT NULL,
    scopes                        varchar(1000) NOT NULL,
    client_settings               varchar(2000) NOT NULL,
    token_settings                varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);

-- Built-in public clients are written here at startup; a client of a Client ID Metadata Document is written here
-- each time its document is fetched again, so that its stored authorizations can be read without a fetch.
CREATE UNIQUE INDEX oauth2_registered_client_client_id_uq ON oauth2_registered_client (client_id);

CREATE TABLE oauth2_authorization (
    id                            varchar(100)  NOT NULL,
    registered_client_id          varchar(500)  NOT NULL,
    principal_name                varchar(200)  NOT NULL,
    authorization_grant_type      varchar(100)  NOT NULL,
    authorized_scopes             varchar(1000) DEFAULT NULL,
    attributes                    text          DEFAULT NULL,
    state                         varchar(500)  DEFAULT NULL,
    authorization_code_value      text          DEFAULT NULL,
    authorization_code_issued_at  timestamptz   DEFAULT NULL,
    authorization_code_expires_at timestamptz   DEFAULT NULL,
    authorization_code_metadata   text          DEFAULT NULL,
    access_token_value            text          DEFAULT NULL,
    access_token_issued_at        timestamptz   DEFAULT NULL,
    access_token_expires_at       timestamptz   DEFAULT NULL,
    access_token_metadata         text          DEFAULT NULL,
    access_token_type             varchar(100)  DEFAULT NULL,
    access_token_scopes           varchar(1000) DEFAULT NULL,
    oidc_id_token_value           text          DEFAULT NULL,
    oidc_id_token_issued_at       timestamptz   DEFAULT NULL,
    oidc_id_token_expires_at      timestamptz   DEFAULT NULL,
    oidc_id_token_metadata        text          DEFAULT NULL,
    refresh_token_value           text          DEFAULT NULL,
    refresh_token_issued_at       timestamptz   DEFAULT NULL,
    refresh_token_expires_at      timestamptz   DEFAULT NULL,
    refresh_token_metadata        text          DEFAULT NULL,
    user_code_value               text          DEFAULT NULL,
    user_code_issued_at           timestamptz   DEFAULT NULL,
    user_code_expires_at          timestamptz   DEFAULT NULL,
    user_code_metadata            text          DEFAULT NULL,
    device_code_value             text          DEFAULT NULL,
    device_code_issued_at         timestamptz   DEFAULT NULL,
    device_code_expires_at        timestamptz   DEFAULT NULL,
    device_code_metadata          text          DEFAULT NULL,
    PRIMARY KEY (id)
);

-- Spring finds an authorization by one of its tokens (hash indexes: equality only, no length limit) or by the state
-- of a pending consent; "Connected apps" lists a user's authorizations.
CREATE INDEX oauth2_authorization_state_idx ON oauth2_authorization USING hash (state);
CREATE INDEX oauth2_authorization_code_idx ON oauth2_authorization USING hash (authorization_code_value);
CREATE INDEX oauth2_authorization_access_token_idx ON oauth2_authorization USING hash (access_token_value);
CREATE INDEX oauth2_authorization_refresh_token_idx ON oauth2_authorization USING hash (refresh_token_value);
CREATE INDEX oauth2_authorization_principal_idx ON oauth2_authorization (principal_name, registered_client_id);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(500)  NOT NULL,
    principal_name       varchar(200)  NOT NULL,
    authorities          varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

GRANT SELECT, INSERT, UPDATE ON oauth2_registered_client TO ${app_role};
-- Spring removes an authorization when its consent is denied; "Connected apps" removes a revoked grant's.
GRANT SELECT, INSERT, UPDATE, DELETE ON oauth2_authorization, oauth2_authorization_consent TO ${app_role};

-- ---- companies' own identity providers -------------------------------------------------------------------------------

-- Companies' own OpenID Connect providers (ADR-0045, CONVENTIONS §14.12): a company's OWNER configures one provider,
-- and people sign in through it at /oauth2/authorization/company-{companyId}. A user it creates is a member of that
-- company only.
--
--   name            shown on the login button ("Continue with Acme")
--   issuer          the issuer URL exactly as the provider names it in `iss`; https, or http where the operator allows
--                   it (TINFIRM_CONNECTION_ALLOW_HTTP), checked by the outbound guard in the application
--   secret_id       the vault secret with the client secret; write-only over the API, revealed only for the token
--                   request of a login (purpose identity-provider:{id}); NULL once the secret was deleted, and logins
--                   then fail at the provider
--   scopes          space-separated, always with openid; at most 20 values of 100 characters (the API's limits),
--                   so at most 2019 characters
--   email_trust     ADR-0026, as for the instance's providers: REQUIRE_VERIFIED_CLAIM or TRUST_ISSUER
--   email_claims    the claims that may carry the email of a new user, in order; others than ["email"] need
--                   TRUST_ISSUER, because email_verified speaks for the email claim only
--   auto_provision  create a user, with a membership of this company, on the first login of an unknown identity;
--                   it needs at least one claimed domain, without which the provider could create no user anyway
--   default_role    the role of that membership: ADMIN or MEMBER, never OWNER
--   email_domains   the lower-case domains the login page routes to this provider; the same list as the rows of
--                   company_identity_provider_domain, which make each domain unique across the instance
--
-- One provider per company (company_identity_provider_company_uq): the login page shows one button per company.
CREATE TABLE company_identity_provider (
    id             uuid          PRIMARY KEY,
    company_id     uuid          NOT NULL REFERENCES company (id),
    name           varchar(100)  NOT NULL,
    issuer         varchar(500)  NOT NULL,
    client_id      varchar(255)  NOT NULL,
    secret_id      uuid,
    scopes         varchar(2019) NOT NULL DEFAULT 'openid profile email',
    email_trust    varchar(32)   NOT NULL DEFAULT 'REQUIRE_VERIFIED_CLAIM',
    email_claims   jsonb         NOT NULL DEFAULT '["email"]',
    auto_provision boolean       NOT NULL DEFAULT false,
    default_role   varchar(16)   NOT NULL DEFAULT 'MEMBER',
    enabled        boolean       NOT NULL DEFAULT true,
    email_domains  jsonb         NOT NULL DEFAULT '[]',
    created_at     timestamptz   NOT NULL,
    updated_at     timestamptz   NOT NULL,
    CONSTRAINT company_identity_provider_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT company_identity_provider_company_uq UNIQUE (company_id),
    CONSTRAINT company_identity_provider_secret_fk FOREIGN KEY (secret_id, company_id)
        REFERENCES secret (id, company_id) ON DELETE SET NULL (secret_id),
    CONSTRAINT company_identity_provider_name_ck CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    -- https (or http where the operator allows it), without user info, query or fragment.
    CONSTRAINT company_identity_provider_issuer_ck CHECK (issuer ~ '^https?://[^\s/@?#]+(/[^\s?#]*)?$'),
    CONSTRAINT company_identity_provider_client_id_ck CHECK (client_id ~ '^\S{1,255}$'),
    CONSTRAINT company_identity_provider_scopes_ck CHECK (scopes ~ '(^| )openid( |$)'),
    CONSTRAINT company_identity_provider_email_trust_ck
        CHECK (email_trust IN ('REQUIRE_VERIFIED_CLAIM', 'TRUST_ISSUER')),
    CONSTRAINT company_identity_provider_email_claims_ck CHECK (jsonb_typeof(email_claims) = 'array'
        AND jsonb_array_length(email_claims) BETWEEN 1 AND 10
        AND (email_trust = 'TRUST_ISSUER' OR email_claims = '["email"]'::jsonb)),
    CONSTRAINT company_identity_provider_default_role_ck CHECK (default_role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT company_identity_provider_email_domains_ck
        CHECK (jsonb_typeof(email_domains) = 'array' AND jsonb_array_length(email_domains) <= 20),
    CONSTRAINT company_identity_provider_auto_provision_domains_ck
        CHECK (NOT auto_provision OR jsonb_array_length(email_domains) >= 1)
);

-- ON DELETE SET NULL of the secret foreign key.
CREATE INDEX company_identity_provider_secret_idx ON company_identity_provider (secret_id) WHERE secret_id IS NOT NULL;

-- One row per claimed email domain. The primary key on the domain makes a domain unique across the instance: a
-- unique index holds across row-level security, so the insert of a domain another company claimed fails although
-- the inserting company cannot see that row (DOMAIN_CLAIMED). The application writes these rows in the transaction
-- that writes the provider's email_domains, and they go with the provider.
CREATE TABLE company_identity_provider_domain (
    domain      varchar(253) PRIMARY KEY,
    company_id  uuid         NOT NULL,
    provider_id uuid         NOT NULL,
    created_at  timestamptz  NOT NULL,
    CONSTRAINT company_identity_provider_domain_provider_fk FOREIGN KEY (provider_id, company_id)
        REFERENCES company_identity_provider (id, company_id) ON DELETE CASCADE,
    -- A lower-case domain name of at least two labels, without a trailing dot.
    CONSTRAINT company_identity_provider_domain_ck
        CHECK (domain ~ '^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$')
);

CREATE INDEX company_identity_provider_domain_provider_idx ON company_identity_provider_domain (provider_id);

ALTER TABLE company_identity_provider ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_identity_provider FORCE ROW LEVEL SECURITY;
ALTER TABLE company_identity_provider_domain ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_identity_provider_domain FORCE ROW LEVEL SECURITY;

CREATE POLICY company_identity_provider_tenant ON company_identity_provider FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY company_identity_provider_domain_tenant ON company_identity_provider_domain FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON company_identity_provider TO ${app_role};
-- Domains are replaced as a whole; the runtime role never updates one in place.
GRANT SELECT, INSERT, DELETE ON company_identity_provider_domain TO ${app_role};

-- ---- groups of members -------------------------------------------------------------------------------------------

-- A named set of members of the company (ADR-0049 §2); the API calls it a group (`group` is a reserved word). A group
-- receives grants of service identities' accounts and may use service identities (package 1); later packages share
-- agents with groups and make them a trigger's team.
--   name         1 to 100 characters, unique in the company ignoring case (DUPLICATE_GROUP_NAME)
--   description  up to 500 characters
CREATE TABLE user_group (
    id          uuid          PRIMARY KEY,
    company_id  uuid          NOT NULL REFERENCES company (id),
    name        varchar(100)  NOT NULL,
    description varchar(500),
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    CONSTRAINT user_group_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT user_group_name_ck CHECK (length(btrim(name)) BETWEEN 1 AND 100)
);

-- DUPLICATE_GROUP_NAME, ignoring case.
CREATE UNIQUE INDEX user_group_company_name_uq ON user_group (company_id, lower(name));
-- listGroups: keyset on (name, id).
CREATE INDEX user_group_company_name_idx ON user_group (company_id, name, id);

-- A member of a group. The composite foreign key to the membership cascades: leaving the company leaves every group.
CREATE TABLE user_group_member (
    company_id uuid NOT NULL,
    group_id   uuid NOT NULL,
    user_id    uuid NOT NULL,
    CONSTRAINT user_group_member_pk PRIMARY KEY (group_id, user_id),
    CONSTRAINT user_group_member_group_fk FOREIGN KEY (group_id, company_id)
        REFERENCES user_group (id, company_id) ON DELETE CASCADE,
    CONSTRAINT user_group_member_membership_fk FOREIGN KEY (company_id, user_id)
        REFERENCES company_membership (company_id, user_id) ON DELETE CASCADE
);

-- GroupMembership.groupsOf, and the ON DELETE CASCADE of the membership foreign key.
CREATE INDEX user_group_member_user_idx ON user_group_member (company_id, user_id);

ALTER TABLE user_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_group FORCE ROW LEVEL SECURITY;
ALTER TABLE user_group_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_group_member FORCE ROW LEVEL SECURITY;

CREATE POLICY user_group_tenant ON user_group FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY user_group_member_tenant ON user_group_member FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Groups are renamed in place; their members are replaced as a whole.
GRANT SELECT, INSERT, UPDATE, DELETE ON user_group TO ${app_role};
GRANT SELECT, INSERT, DELETE ON user_group_member TO ${app_role};

-- ---- runner hosts ------------------------------------------------------------------------------------------------

-- Runner hosts (PLATFORM.md §11.1, ADR-0006, ADR-0016): the host connection, its registration and its capacity. A host
-- reports its ceiling in hello: its slots (run --max-concurrent-runs), between 1 and 16. Members may store a lower
-- desired value; the host applies min(desired, ceiling) and confirms it with host.config.applied.
-- max_concurrent_runs holds the effective value the host last confirmed (or, after hello, the value the server
-- expects). The credentials a host reports in hello (subscriptions) are not stored: model access, subscriptions on
-- their owner's host included, is package 2's (runner_credential is not created).

CREATE TABLE runner_host (
    id                          uuid         PRIMARY KEY,
    company_id                  uuid         NOT NULL REFERENCES company (id),
    name                        varchar(100) NOT NULL,
    owner_user_id               uuid         NOT NULL REFERENCES app_user (id),
    -- hex(sha256(token)); the token itself (tfr_ + 43 base64url characters) is shown once and never stored.
    token_hash                  char(64)     NOT NULL,
    -- The effective value; 0 exactly while the host drains.
    max_concurrent_runs         integer      NOT NULL DEFAULT 1,
    -- From hello; 1 before the first connection.
    max_concurrent_runs_ceiling integer      NOT NULL DEFAULT 1,
    -- Set through the API; NULL uses the ceiling. It may be above the ceiling and then applies up to it.
    desired_max_concurrent_runs integer,
    -- From host.config.applied; hello resets it. max_concurrent_runs is 0 while it is true.
    draining                    boolean      NOT NULL DEFAULT false,
    -- +1 on every change of a desired value of the host.
    config_version              bigint       NOT NULL DEFAULT 0,
    -- The highest version the host confirmed; configPending is config_version > coalesce(applied_config_version, 0).
    applied_config_version      bigint,
    -- The runtimes of hello.runtimes (those whose images the host found) as of the host's latest hello; NULL before
    -- the first hello, which a dispatcher reads as every runtime.
    runtimes                    varchar(16)[],
    -- Reported in hello.
    runner_version              varchar(100),
    hostname                    varchar(255),
    os                          varchar(64),
    arch                        varchar(64),
    last_seen_at                timestamptz,
    created_at                  timestamptz  NOT NULL,
    CONSTRAINT runner_host_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT runner_host_company_name_uq UNIQUE (company_id, name),
    CONSTRAINT runner_host_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT runner_host_name_ck CHECK (name ~ '^[A-Za-z0-9][A-Za-z0-9._-]*$' AND length(name) <= 100),
    CONSTRAINT runner_host_token_hash_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT runner_host_max_runs_ck
        CHECK (max_concurrent_runs BETWEEN 0 AND 16 AND max_concurrent_runs <= max_concurrent_runs_ceiling),
    CONSTRAINT runner_host_max_runs_ceiling_ck CHECK (max_concurrent_runs_ceiling BETWEEN 1 AND 16),
    CONSTRAINT runner_host_desired_max_runs_ck
        CHECK (desired_max_concurrent_runs IS NULL OR desired_max_concurrent_runs BETWEEN 1 AND 16),
    CONSTRAINT runner_host_draining_ck CHECK (NOT draining OR max_concurrent_runs = 0),
    CONSTRAINT runner_host_config_version_ck CHECK (config_version >= 0),
    CONSTRAINT runner_host_runtimes_ck
        CHECK (runtimes IS NULL OR runtimes <@ ARRAY['CLAUDE_CODE', 'CODEX', 'PI', 'OPENCODE']::varchar(16)[])
);

-- listRunnerHosts: keyset on (name, id); the unique constraint's index serves it.

ALTER TABLE runner_host ENABLE ROW LEVEL SECURITY;
ALTER TABLE runner_host FORCE ROW LEVEL SECURITY;

CREATE POLICY runner_host_tenant ON runner_host FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON runner_host TO ${app_role};

-- ---- principals (package 1 contract §3.1, §3.2) ------------------------------------------------------------------

-- Whom work acts for: a member of the company, or a service identity that admins manage. Accounts hang from a
-- principal (and, from package 2, model access).
--
--   kind         MEMBER or SERVICE; never changes
--   user_id      the member's user; NULL for a service identity, which never logs in and acts only through its
--                accounts
--   name         a service identity's name, 1 to 100 characters, unique in the company ignoring case
--                (DUPLICATE_SERVICE_IDENTITY_NAME); NULL for a member, whose name is the user's
--   description  a service identity's, up to 500 characters; NULL for a member
--   status       ACTIVE; SUSPENDED (an admin stopped it, reversible: its accounts are used for nothing, I7); LEFT (the
--                member left the company, final: only members leave)
--
-- A member's principal is created with the membership and kept, as LEFT, when the membership ends, so that what it did
-- keeps naming who acted. A member who rejoins gets a new principal (contract §10): one principal per member that has
-- not left (principal_member_uq), any number that left. A principal that has not left has its membership
-- (principal_membership_trg, company_membership_principal_trg): the membership is ended after its principal left.

CREATE TABLE principal (
    id          uuid          PRIMARY KEY,
    company_id  uuid          NOT NULL REFERENCES company (id),
    kind        varchar(8)    NOT NULL,
    -- No ON DELETE: users are deactivated, never deleted, and a principal keeps naming who acted.
    user_id     uuid          REFERENCES app_user (id),
    name        varchar(100),
    description varchar(500),
    status      varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    created_by  uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT principal_id_company_uq UNIQUE (id, company_id),
    -- The target of foreign keys that admit only one kind (service_identity_user).
    CONSTRAINT principal_id_company_kind_uq UNIQUE (id, company_id, kind),
    CONSTRAINT principal_kind_ck CHECK (kind IN ('MEMBER', 'SERVICE')),
    CONSTRAINT principal_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'LEFT')),
    CONSTRAINT principal_member_ck
        CHECK (kind <> 'MEMBER' OR (user_id IS NOT NULL AND name IS NULL AND description IS NULL)),
    CONSTRAINT principal_service_ck
        CHECK (kind <> 'SERVICE' OR (user_id IS NULL AND name IS NOT NULL AND length(btrim(name)) BETWEEN 1 AND 100
                                     AND status <> 'LEFT'))
);

-- One principal per member that has not left the company.
CREATE UNIQUE INDEX principal_member_uq ON principal (company_id, user_id) WHERE kind = 'MEMBER' AND status <> 'LEFT';
-- DUPLICATE_SERVICE_IDENTITY_NAME, ignoring case.
CREATE UNIQUE INDEX principal_service_name_uq ON principal (company_id, lower(name)) WHERE kind = 'SERVICE';
-- listServiceIdentities: keyset on (name, id).
CREATE INDEX principal_service_page_idx ON principal (company_id, name, id) WHERE kind = 'SERVICE';
-- The foreign key to app_user.
CREATE INDEX principal_user_idx ON principal (user_id) WHERE user_id IS NOT NULL;

-- A member's principal that has not left has its membership.
CREATE FUNCTION principal_membership_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.kind = 'MEMBER' AND NEW.status <> 'LEFT' AND NOT EXISTS (
            SELECT 1 FROM company_membership m WHERE m.company_id = NEW.company_id AND m.user_id = NEW.user_id) THEN
        RAISE EXCEPTION 'A member''s principal that has not left the company needs its membership'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'principal_membership_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER principal_membership_trg BEFORE INSERT OR UPDATE ON principal
    FOR EACH ROW EXECUTE FUNCTION principal_membership_ck();

-- A membership ends after its principal left (I8: the principal becomes LEFT in the transaction that ends it).
CREATE FUNCTION company_membership_principal_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM principal p
                WHERE p.company_id = OLD.company_id AND p.user_id = OLD.user_id AND p.kind = 'MEMBER'
                  AND p.status <> 'LEFT') THEN
        RAISE EXCEPTION 'A membership ends only after its principal left'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'company_membership_principal_ck';
    END IF;
    RETURN OLD;
END
$$;

CREATE TRIGGER company_membership_principal_trg AFTER DELETE ON company_membership
    FOR EACH ROW EXECUTE FUNCTION company_membership_principal_ck();

-- Who may use a service identity (contract §3.2): pick its accounts and, from package 2, its budget on a trigger. The
-- members of the groups listed here may; OWNER may use every service identity; nobody else, ADMIN included (a specific
-- permission is a group's). Only service identities: principal_kind is always SERVICE, and the foreign key with it
-- admits no member's principal.
CREATE TABLE service_identity_user (
    company_id     uuid       NOT NULL,
    principal_id   uuid       NOT NULL,
    principal_kind varchar(8) NOT NULL DEFAULT 'SERVICE',
    group_id       uuid       NOT NULL,
    CONSTRAINT service_identity_user_pk PRIMARY KEY (principal_id, group_id),
    CONSTRAINT service_identity_user_service_ck CHECK (principal_kind = 'SERVICE'),
    CONSTRAINT service_identity_user_principal_fk FOREIGN KEY (principal_id, company_id, principal_kind)
        REFERENCES principal (id, company_id, kind) ON DELETE CASCADE,
    CONSTRAINT service_identity_user_group_fk FOREIGN KEY (group_id, company_id)
        REFERENCES user_group (id, company_id) ON DELETE CASCADE
);

-- The service identities a member's groups may use, and the ON DELETE CASCADE of the group foreign key.
CREATE INDEX service_identity_user_group_idx ON service_identity_user (company_id, group_id);

ALTER TABLE principal ENABLE ROW LEVEL SECURITY;
ALTER TABLE principal FORCE ROW LEVEL SECURITY;
ALTER TABLE service_identity_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_identity_user FORCE ROW LEVEL SECURITY;

CREATE POLICY principal_tenant ON principal FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY service_identity_user_tenant ON service_identity_user FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- A principal is renamed, suspended, resumed or leaves in place; a service identity is deleted. Who may use one is
-- replaced as a whole.
GRANT SELECT, INSERT, UPDATE, DELETE ON principal TO ${app_role};
GRANT SELECT, INSERT, DELETE ON service_identity_user TO ${app_role};

-- ---- systems (package 1 contract §3.3 to §3.5, §10) ----------------------------------------------------------------

-- An external service registered once ("system" in the UI): what it offers follows from its kind (contract §3.3).
--
--   name                   1 to 100 characters, unique in the company ignoring case (DUPLICATE_SYSTEM_NAME)
--   handle                 the slug that prefixes its tools in a run ({handle}_{tool}): a lower-case letter, then
--                          lower-case letters, digits and underscores, at most 20; unique in the company
--                          (DUPLICATE_SYSTEM_HANDLE)
--   kind                   JIRA_DC, JIRA_CLOUD, GITHUB, GITLAB, GIT, MCP, SLACK, TEAMS, BUZZ, WEBHOOK; never changes
--   base_url               the API root (Jira's root, Jira Cloud's site, GitHub's or GitLab's API, an MCP server's
--                          endpoint); none for GIT and WEBHOOK. Never changes: a member's token is sent only where
--                          it was connected
--   owner_principal_id     NULL for a company system; the member's principal for a personal MCP server (I6), which
--                          binds for its owner only and goes when they leave (I8)
--   oauth_client_id,       the system's app: one OAuth client per system. The client secret is a vault secret the
--   oauth_client_secret_id system keeps (a company secret, or one its owner owns for a personal server); none for a
--                          public client
--   oauth_scopes           the scopes asked for, space-separated; NULL: the profile's
--   oauth_metadata         the authorization server's endpoints, for an MCP server as its discovery found them
--                          (a tracker's are a fixed profile per kind, in code)
--   bot_account_id         chat kinds: the account that is the Tinfirm bot, a service identity's on this system
--                          (I9, package 5)
--   settings               kind-specific, validated per kind (an MCP server's auth header)
--   status, last_error     NEW until a test or a refresh reached it; then OK, or ERROR with the reason
CREATE TABLE external_system (
    id                     uuid          PRIMARY KEY,
    company_id             uuid          NOT NULL REFERENCES company (id),
    name                   varchar(100)  NOT NULL,
    handle                 varchar(20)   NOT NULL,
    kind                   varchar(16)   NOT NULL,
    base_url               varchar(500),
    owner_principal_id     uuid,
    owner_kind             varchar(8)    NOT NULL DEFAULT 'MEMBER',
    oauth_client_id        varchar(500),
    oauth_client_secret_id uuid,
    oauth_scopes           varchar(2000),
    oauth_metadata         jsonb,
    bot_account_id         uuid,
    bot_principal_kind     varchar(8)    NOT NULL DEFAULT 'SERVICE',
    settings               jsonb         NOT NULL DEFAULT '{}'::jsonb,
    status                 varchar(8)    NOT NULL DEFAULT 'NEW',
    last_error             varchar(500),
    created_at             timestamptz   NOT NULL,
    updated_at             timestamptz   NOT NULL,
    created_by             uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT external_system_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT external_system_kind_ck CHECK (kind IN ('JIRA_DC', 'JIRA_CLOUD', 'GITHUB', 'GITLAB', 'GIT', 'MCP',
                                                       'SLACK', 'TEAMS', 'BUZZ', 'WEBHOOK')),
    CONSTRAINT external_system_name_ck CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT external_system_handle_ck CHECK (handle ~ '^[a-z][a-z0-9_]{0,19}$'),
    CONSTRAINT external_system_base_url_ck CHECK ((base_url IS NULL) = (kind IN ('GIT', 'WEBHOOK'))),
    -- I6: a personal system is an MCP server.
    CONSTRAINT external_system_personal_ck CHECK (owner_principal_id IS NULL OR kind = 'MCP'),
    -- Only a member's principal owns a system: owner_kind is always MEMBER, and the foreign key with it admits no
    -- service identity.
    CONSTRAINT external_system_owner_kind_ck CHECK (owner_kind = 'MEMBER'),
    CONSTRAINT external_system_owner_fk FOREIGN KEY (owner_principal_id, company_id, owner_kind)
        REFERENCES principal (id, company_id, kind),
    CONSTRAINT external_system_secret_fk FOREIGN KEY (oauth_client_secret_id, company_id)
        REFERENCES secret (id, company_id) ON DELETE SET NULL (oauth_client_secret_id),
    CONSTRAINT external_system_app_ck CHECK (oauth_client_id IS NOT NULL OR oauth_client_secret_id IS NULL),
    -- I9: only a chat system has a bot, and it is a service identity's account on it (external_system_bot_fk, with
    -- the accounts below): bot_principal_kind is always SERVICE.
    CONSTRAINT external_system_bot_ck CHECK (bot_account_id IS NULL OR kind IN ('SLACK', 'TEAMS', 'BUZZ')),
    CONSTRAINT external_system_bot_kind_ck CHECK (bot_principal_kind = 'SERVICE'),
    CONSTRAINT external_system_status_ck CHECK (status IN ('NEW', 'OK', 'ERROR'))
);

CREATE UNIQUE INDEX external_system_name_uq ON external_system (company_id, lower(name));
CREATE UNIQUE INDEX external_system_handle_uq ON external_system (company_id, handle);
-- listSystems: keyset on (name, id).
CREATE INDEX external_system_page_idx ON external_system (company_id, name, id);
CREATE INDEX external_system_owner_idx ON external_system (company_id, owner_principal_id)
    WHERE owner_principal_id IS NOT NULL;
CREATE INDEX external_system_secret_idx ON external_system (oauth_client_secret_id)
    WHERE oauth_client_secret_id IS NOT NULL;

-- A git repository of a GITHUB, GITLAB or GIT system (contract §3.4), added by URL: an https URL without
-- credentials, a query or a fragment.
CREATE TABLE system_repository (
    id             uuid         PRIMARY KEY,
    company_id     uuid         NOT NULL,
    system_id      uuid         NOT NULL,
    url            varchar(500) NOT NULL,
    name           varchar(200) NOT NULL,
    default_branch varchar(255),
    created_at     timestamptz  NOT NULL,
    created_by     uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT system_repository_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT system_repository_url_uq UNIQUE (system_id, url),
    CONSTRAINT system_repository_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE
);

CREATE INDEX system_repository_page_idx ON system_repository (company_id, system_id, name, id);

-- An MCP server's tool catalogue (contract §3.5), as its tools/list last answered, with what an admin confirmed.
--
--   effect                 READ or WRITE once an admin (the owner, for a personal server) confirmed it; UNKNOWN
--                          until then, which counts as WRITE everywhere. A refresh that finds the tool's description,
--                          schema or hints changed drops the confirmation (the security review, row 13)
--   hint_read_only,        the tool's annotations readOnlyHint and destructiveHint as the server sent them, a
--   hint_destructive       suggestion only: a server may say anything
--   enabled                whether agents may list it (package 3)
CREATE TABLE system_tool (
    company_id       uuid          NOT NULL,
    system_id        uuid          NOT NULL,
    name             varchar(64)   NOT NULL,
    title            varchar(200),
    description      varchar(4000),
    input_schema     jsonb         NOT NULL,
    effect           varchar(8)    NOT NULL DEFAULT 'UNKNOWN',
    hint_read_only   boolean,
    hint_destructive boolean,
    confirmed_by     uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    confirmed_at     timestamptz,
    enabled          boolean       NOT NULL DEFAULT true,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    CONSTRAINT system_tool_pk PRIMARY KEY (system_id, name),
    CONSTRAINT system_tool_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT system_tool_effect_ck CHECK (effect IN ('READ', 'WRITE', 'UNKNOWN')),
    CONSTRAINT system_tool_confirmed_ck CHECK ((effect = 'UNKNOWN') = (confirmed_at IS NULL)),
    CONSTRAINT system_tool_name_ck CHECK (name ~ '^[A-Za-z0-9_-]{1,64}$')
);

CREATE INDEX system_tool_company_idx ON system_tool (company_id, system_id);

-- The company's settings of systems (contract §10): whether members may register personal MCP servers, on by
-- default (no row). Switched off, existing personal servers stay but bind for nobody, and registering one answers
-- PERSONAL_SYSTEMS_OFF.
CREATE TABLE system_setting (
    company_id           uuid        PRIMARY KEY REFERENCES company (id),
    personal_mcp_servers boolean     NOT NULL,
    updated_at           timestamptz NOT NULL,
    updated_by           uuid        REFERENCES app_user (id) ON DELETE SET NULL
);

ALTER TABLE external_system ENABLE ROW LEVEL SECURITY;
ALTER TABLE external_system FORCE ROW LEVEL SECURITY;
ALTER TABLE system_repository ENABLE ROW LEVEL SECURITY;
ALTER TABLE system_repository FORCE ROW LEVEL SECURITY;
ALTER TABLE system_tool ENABLE ROW LEVEL SECURITY;
ALTER TABLE system_tool FORCE ROW LEVEL SECURITY;
ALTER TABLE system_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE system_setting FORCE ROW LEVEL SECURITY;

CREATE POLICY external_system_tenant ON external_system FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY system_repository_tenant ON system_repository FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY system_tool_tenant ON system_tool FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY system_setting_tenant ON system_setting FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON external_system TO ${app_role};
-- A repository is added and removed, never changed.
GRANT SELECT, INSERT, DELETE ON system_repository TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON system_tool TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON system_setting TO ${app_role};
-- ---- accounts and grants (package 1 contract §3.6 to §3.8, I1 to I9) -----------------------------------------------

-- A principal's credential on a system, and who it is there (contract §3.6).
--
--   principal_kind     the principal's kind, kept with the foreign key that checks it: grants lend SERVICE accounts
--                      only (I5), and a bot is a SERVICE account (I9)
--   kind               TOKEN (stored by hand, checked with the system's "who am I"), OAUTH (signed in through the
--                      system's app), LINK (an identity without a credential: a chat user linked through the company's
--                      identity provider, package 5)
--   secret_id          the token or the OAuth grant: a member's own secret (secret.owner_user_id, I3), a service
--                      identity's company secret that the account manages; none for LINK. Deleting a service
--                      identity's managed secret on the Secrets page disconnects the account (ON DELETE CASCADE)
--   external_id,       the identity on the system from its "who am I": a stable id and a readable login; none where
--   external_login     the system has none (MCP servers, GIT). One principal per identity per system (I2)
--   api_base_url       where an OAuth grant calls the system when that is not its base URL (Jira Cloud's gateway)
--   status, last_error OK; RECONNECT (the system refused it or the grant expired: whoever manages it signs in
--                      again); ERROR. Nothing falls back from one that is not OK (I10)
--   connected_by       who connected it: the member, or the admin who connected a service identity's account
CREATE TABLE account (
    id                uuid          PRIMARY KEY,
    company_id        uuid          NOT NULL,
    system_id         uuid          NOT NULL,
    principal_id      uuid          NOT NULL,
    principal_kind    varchar(8)    NOT NULL,
    kind              varchar(8)    NOT NULL,
    secret_id         uuid,
    external_id       varchar(255),
    external_login    varchar(255),
    api_base_url      varchar(500),
    scopes            varchar(2000),
    access_expires_at timestamptz,
    status            varchar(10)   NOT NULL DEFAULT 'OK',
    last_error        varchar(500),
    connected_at      timestamptz   NOT NULL,
    connected_by      uuid          REFERENCES app_user (id) ON DELETE SET NULL,
    last_used_at      timestamptz,
    updated_at        timestamptz   NOT NULL,
    CONSTRAINT account_id_company_uq UNIQUE (id, company_id),
    -- The targets of the foreign keys of grants (I5) and of a chat system's bot (I9).
    CONSTRAINT account_id_company_kind_uq UNIQUE (id, company_id, principal_kind),
    CONSTRAINT account_id_system_uq UNIQUE (id, system_id, company_id, principal_kind),
    -- I1: one account per principal per system.
    CONSTRAINT account_principal_uq UNIQUE (system_id, principal_id),
    CONSTRAINT account_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id),
    CONSTRAINT account_principal_fk FOREIGN KEY (principal_id, company_id, principal_kind)
        REFERENCES principal (id, company_id, kind),
    CONSTRAINT account_secret_fk FOREIGN KEY (secret_id, company_id)
        REFERENCES secret (id, company_id) ON DELETE CASCADE,
    CONSTRAINT account_kind_ck CHECK (kind IN ('TOKEN', 'OAUTH', 'LINK')),
    CONSTRAINT account_secret_ck CHECK ((kind = 'LINK') = (secret_id IS NULL)),
    CONSTRAINT account_status_ck CHECK (status IN ('OK', 'RECONNECT', 'ERROR'))
);

-- I2: one principal per identity per system (IDENTITY_IN_USE).
CREATE UNIQUE INDEX account_identity_uq ON account (system_id, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX account_principal_idx ON account (company_id, principal_id);
CREATE INDEX account_secret_idx ON account (secret_id) WHERE secret_id IS NOT NULL;

-- I6 and I3, which no foreign key can say: on a personal system only its owner holds an account; a member's account
-- keeps its token in a secret the member owns, a service identity's in a company secret.
CREATE FUNCTION account_ck() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_owner        uuid;
    v_user         uuid;
    v_secret_owner uuid;
BEGIN
    SELECT owner_principal_id INTO v_owner
      FROM external_system WHERE id = NEW.system_id AND company_id = NEW.company_id;
    IF v_owner IS NOT NULL AND v_owner <> NEW.principal_id THEN
        RAISE EXCEPTION 'Only its owner holds an account on a personal system'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'account_personal_ck';
    END IF;
    IF NEW.secret_id IS NOT NULL THEN
        SELECT owner_user_id INTO v_secret_owner FROM secret WHERE id = NEW.secret_id AND company_id = NEW.company_id;
        SELECT user_id INTO v_user FROM principal WHERE id = NEW.principal_id AND company_id = NEW.company_id;
        IF v_secret_owner IS DISTINCT FROM v_user THEN
            RAISE EXCEPTION 'A member''s account keeps its token in a secret the member owns, a service identity''s in '
                            'a company secret'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'account_secret_owner_ck';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER account_ck_trg BEFORE INSERT OR UPDATE ON account
    FOR EACH ROW EXECUTE FUNCTION account_ck();

-- I9: a chat system's bot is a service identity's account on that system.
ALTER TABLE external_system ADD CONSTRAINT external_system_bot_fk
    FOREIGN KEY (bot_account_id, id, company_id, bot_principal_kind)
    REFERENCES account (id, system_id, company_id, principal_kind) ON DELETE SET NULL (bot_account_id);

CREATE INDEX external_system_bot_idx ON external_system (bot_account_id) WHERE bot_account_id IS NOT NULL;

-- A service identity's account lent to a group (contract §3.7): its members act with it, READ or WRITE. I5: only a
-- service identity's account, which is on a company system because only its owner holds an account on a personal one
-- (account_ck): account_principal_kind is always SERVICE, and the foreign key with it admits no member's account.
CREATE TABLE account_grant (
    id                     uuid        PRIMARY KEY,
    company_id             uuid        NOT NULL,
    account_id             uuid        NOT NULL,
    account_principal_kind varchar(8)  NOT NULL DEFAULT 'SERVICE',
    group_id               uuid        NOT NULL,
    access                 varchar(8)  NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    created_by             uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT account_grant_id_company_uq UNIQUE (id, company_id),
    CONSTRAINT account_grant_account_group_uq UNIQUE (account_id, group_id),
    CONSTRAINT account_grant_service_ck CHECK (account_principal_kind = 'SERVICE'),
    CONSTRAINT account_grant_service_fk FOREIGN KEY (account_id, company_id, account_principal_kind)
        REFERENCES account (id, company_id, principal_kind) ON DELETE CASCADE,
    CONSTRAINT account_grant_group_fk FOREIGN KEY (group_id, company_id)
        REFERENCES user_group (id, company_id) ON DELETE CASCADE,
    CONSTRAINT account_grant_access_ck CHECK (access IN ('READ', 'WRITE'))
);

CREATE INDEX account_grant_group_idx ON account_grant (company_id, group_id);
CREATE INDEX account_grant_page_idx ON account_grant (company_id, created_at, id);

ALTER TABLE account ENABLE ROW LEVEL SECURITY;
ALTER TABLE account FORCE ROW LEVEL SECURITY;
ALTER TABLE account_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_grant FORCE ROW LEVEL SECURITY;

CREATE POLICY account_tenant ON account FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

CREATE POLICY account_grant_tenant ON account_grant FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- An account is reconnected, refreshed and marked in place; disconnecting deletes it. A grant's access changes in
-- place.
GRANT SELECT, INSERT, UPDATE, DELETE ON account TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON account_grant TO ${app_role};

-- A pending OAuth sign-in (contract §3.8): kept with its PKCE verifier for ten minutes and consumed once by the one
-- callback (GET /api/oauth/callback). It belongs to a person's browser session, and the callback finds it by its state
-- before any company context exists, so this is an instance-level table like api_token, without row-level security:
-- the callback consumes only a request of the caller from the same session, and then checks the company.
--   state_hash     the SHA-256 of the state (32 random bytes, base64url), in hex: the state itself is never stored
--   code_verifier  the PKCE verifier (RFC 7636); the request carried its S256 challenge
--   principal_id   whose account the grant becomes: the member's own principal, or a service identity's
--   user_id        the person in the browser: the member, or the admin who connects a service identity's account
--   session_hash   the SHA-256 of the session's id, in hex: the callback must come in the same session
--   return_to      a validated same-origin path the callback returns to
CREATE TABLE oauth_authorization_request (
    state_hash    varchar(64)   PRIMARY KEY,
    code_verifier varchar(128)  NOT NULL,
    company_id    uuid          NOT NULL REFERENCES company (id),
    system_id     uuid          NOT NULL,
    principal_id  uuid          NOT NULL,
    user_id       uuid          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    session_hash  varchar(64)   NOT NULL,
    return_to     varchar(1000) NOT NULL,
    created_at    timestamptz   NOT NULL,
    expires_at    timestamptz   NOT NULL,
    CONSTRAINT oauth_authorization_request_system_fk FOREIGN KEY (system_id, company_id)
        REFERENCES external_system (id, company_id) ON DELETE CASCADE,
    CONSTRAINT oauth_authorization_request_principal_fk FOREIGN KEY (principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE CASCADE,
    CONSTRAINT oauth_authorization_request_expires_ck CHECK (expires_at > created_at),
    CONSTRAINT oauth_authorization_request_return_to_ck CHECK (return_to LIKE '/%' AND return_to NOT LIKE '//%')
);

-- The sweep of expired requests, and the requests of a system or a principal that goes.
CREATE INDEX oauth_authorization_request_expires_idx ON oauth_authorization_request (expires_at);
CREATE INDEX oauth_authorization_request_system_idx ON oauth_authorization_request (system_id);
CREATE INDEX oauth_authorization_request_principal_idx ON oauth_authorization_request (principal_id);
CREATE INDEX oauth_authorization_request_user_idx ON oauth_authorization_request (user_id);

-- A request is written once, consumed (deleted) by its callback, and swept when it expired.
GRANT SELECT, INSERT, DELETE ON oauth_authorization_request TO ${app_role};
-- ---- cross-company lookups ---------------------------------------------------------------------------------------

-- Some lookups must find a row before the company is known (CONVENTIONS §7). Row-level security is NOT loosened for
-- the runtime role. Instead:
--
--   1. The lookup is a SECURITY DEFINER function owned by the schema owner, with a fixed search_path and
--      schema-qualified names. It returns only ids and company ids, never tenant data.
--   2. Only the runtime role may execute it (EXECUTE is revoked from PUBLIC).
--   3. The server then switches to the tenant context of the returned company (app.company_id) and does the
--      actual work there, under the normal policies: it locks and re-checks the row, so a stale or forged id
--      changes nothing.
--
-- Because the tables use FORCE ROW LEVEL SECURITY, even the owner is subject to policies. The owner therefore
-- gets a SELECT-only policy on each table a function reads. It applies to the owner role alone (the role that
-- runs these migrations and already owns and could alter the tables); the runtime role never matches it.

CREATE POLICY runner_host_owner_lookup ON runner_host FOR SELECT TO CURRENT_USER USING (true);

-- The runner host of a token, by hex(sha256(token)): the runner WebSocket endpoint.
CREATE FUNCTION runner_host_for_token(p_token_hash text)
    RETURNS TABLE (runner_host_id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT h.id, h.company_id
      FROM ${flyway:defaultSchema}.runner_host h
     WHERE h.token_hash = p_token_hash
$$;

REVOKE ALL ON FUNCTION runner_host_for_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION runner_host_for_token(text) TO ${app_role};

-- A login starts before anyone is signed in, and the login page asks for the provider of an email's domain: both
-- need a provider whose company is not known yet.

CREATE POLICY company_identity_provider_owner_lookup ON company_identity_provider FOR SELECT TO CURRENT_USER
    USING (true);
CREATE POLICY company_identity_provider_domain_owner_lookup ON company_identity_provider_domain FOR SELECT
    TO CURRENT_USER USING (true);

-- The provider of a company, for the registration company-{companyId} that a login names.
CREATE FUNCTION company_identity_provider_of_company(p_company_id uuid)
    RETURNS TABLE (provider_id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p.id, p.company_id
      FROM ${flyway:defaultSchema}.company_identity_provider p
     WHERE p.company_id = p_company_id
$$;

-- The provider that claimed an email domain (lower case), enabled or not: the login page's lookup, and the check
-- that a domain is not another company's before it is claimed.
CREATE FUNCTION company_identity_provider_of_domain(p_domain text)
    RETURNS TABLE (provider_id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT d.provider_id, d.company_id
      FROM ${flyway:defaultSchema}.company_identity_provider_domain d
     WHERE d.domain = p_domain
$$;

REVOKE ALL ON FUNCTION company_identity_provider_of_company(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION company_identity_provider_of_domain(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION company_identity_provider_of_company(uuid) TO ${app_role};
GRANT EXECUTE ON FUNCTION company_identity_provider_of_domain(text) TO ${app_role};
