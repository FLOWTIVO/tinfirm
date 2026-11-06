-- SAML 2.0 sign-in (ADR-0086; CONVENTIONS §5, §7). The schema owner runs this file.
--
-- A SAML identity provider is declared in the deployment and signs people in under an Enterprise key. Tinfirm is the
-- service provider; sign-in starts at Tinfirm, and every response is checked against the request it answers, the
-- browser that asked and the assertions already used.
--
--   auth_source SAML      a user a SAML provider signed in: identified by (the IdP's entity ID, the subject), held in
--                         oidc_issuer and oidc_subject as an OIDC user's (issuer, subject)
--   saml_authn_request    the AuthnRequests sent, until their response arrives, for 5 minutes (decision 4)
--   saml_assertion_seen   the assertions already used, until they expire (decision 8, one-time use)
--   saml_idp_metadata     each provider's IdP metadata as a worker last read it (decision 3): every replica builds its
--                         registration from this row, never from a fetch of its own
--
-- The three tables are instance-level, as shedlock and licence_key: a SAML response is checked before any company
-- context exists, and none holds personal data. The SAML sweep removes their expired rows.

-- ---- users -------------------------------------------------------------------------------------------------------

ALTER TABLE app_user DROP CONSTRAINT app_user_auth_source_ck;
ALTER TABLE app_user
    ADD CONSTRAINT app_user_auth_source_ck CHECK (
        (auth_source = 'LOCAL' AND password_hash IS NOT NULL AND oidc_issuer IS NULL AND oidc_subject IS NULL)
        OR (auth_source IN ('OIDC', 'SAML') AND password_hash IS NULL AND oidc_issuer IS NOT NULL
            AND oidc_subject IS NOT NULL)
        OR (auth_source = 'SCIM' AND password_hash IS NULL AND oidc_issuer IS NULL AND oidc_subject IS NULL));

-- ---- the requests sent -------------------------------------------------------------------------------------------

--   id              the AuthnRequest's ID, which the response's InResponseTo names
--   registration_id the provider the request went to
--   binding_hash    hex(sha256) of the random value in the browser's TINFIRM_SAML cookie, never the value
--   request         the request as Spring Security keeps it (its ID, relay state, the IdP's location, the binding),
--                   and the OAuth authorization request the sign-in interrupted (ADR-0045), JSON; no personal data
--   created_at      when it was sent; it is used at most 5 minutes later, and once
CREATE TABLE saml_authn_request (
    id              varchar(100) PRIMARY KEY,
    registration_id varchar(64)  NOT NULL,
    binding_hash    char(64)     NOT NULL,
    request         jsonb        NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT saml_authn_request_binding_hash_ck CHECK (binding_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX saml_authn_request_created_idx ON saml_authn_request (created_at);

-- A request is saved, then deleted in the transaction that checks its response, or swept.
GRANT SELECT, INSERT, DELETE ON saml_authn_request TO ${app_role};

-- ---- the assertions used -----------------------------------------------------------------------------------------

--   registration_id, assertion_id   the provider and the assertion's ID; a second insert is a replay
--   expires_at                      the assertion's NotOnOrAfter plus the clock skew: a replay is refused by its
--                                   validity before then, so the row may go
CREATE TABLE saml_assertion_seen (
    registration_id varchar(64)  NOT NULL,
    assertion_id    varchar(256) NOT NULL,
    expires_at      timestamptz  NOT NULL,
    PRIMARY KEY (registration_id, assertion_id)
);

CREATE INDEX saml_assertion_seen_expires_idx ON saml_assertion_seen (expires_at);

GRANT SELECT, INSERT, DELETE ON saml_assertion_seen TO ${app_role};

-- ---- the IdPs' metadata ------------------------------------------------------------------------------------------

--   registration_id the provider
--   document        the whole metadata document as read and validated, or NULL before a first good read
--   document_hash   hex(sha256) of the document: a replica builds its registration again when it changes
--   entity_id       the IdP's entity ID taken from the document
--   certificates    the IdP's signing certificates: [{"sha256", "subject", "notAfter"}], JSON
--   valid_until     the document's validUntil, or NULL; after it the document is not used
--   read_at         when the document was last read and found valid
--   last_error      the reason of the last failed read (unreachable, invalid, no_idp, expired, too_large), NULL
--                   after a good one
--   last_error_at   when that read failed
--   next_read_at    when a worker reads it again: the provider's refresh, sooner when the document's cacheDuration
--                   or validUntil asks
CREATE TABLE saml_idp_metadata (
    registration_id varchar(64)  PRIMARY KEY,
    document        text,
    document_hash   char(64),
    entity_id       varchar(1024),
    certificates    jsonb        NOT NULL DEFAULT '[]',
    valid_until     timestamptz,
    read_at         timestamptz,
    last_error      varchar(32),
    last_error_at   timestamptz,
    next_read_at    timestamptz  NOT NULL,
    CONSTRAINT saml_idp_metadata_document_ck CHECK ((document IS NULL) = (document_hash IS NULL)
        AND (document IS NULL) = (entity_id IS NULL) AND (document IS NULL) = (read_at IS NULL)),
    CONSTRAINT saml_idp_metadata_document_hash_ck CHECK (document_hash IS NULL OR document_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT saml_idp_metadata_size_ck CHECK (document IS NULL OR octet_length(document) <= 1048576),
    CONSTRAINT saml_idp_metadata_error_ck CHECK ((last_error IS NULL) = (last_error_at IS NULL)),
    CONSTRAINT saml_idp_metadata_last_error_ck CHECK (last_error IS NULL
        OR last_error IN ('unreachable', 'invalid', 'no_idp', 'expired', 'too_large'))
);

-- A provider's row is made at its first read and then updated in place; a provider the deployment no longer declares
-- keeps its row, unused, until the sweep removes it.
GRANT SELECT, INSERT, UPDATE, DELETE ON saml_idp_metadata TO ${app_role};
