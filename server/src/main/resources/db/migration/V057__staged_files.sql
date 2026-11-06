-- Files held for a start through a person's client (ADR-0084; CONVENTIONS §7, §15.7). The schema owner runs this
-- file.
--
-- An email's attachments reach Tinfirm before the conversation about the email exists: the Outlook add-in uploads them
-- to the MCP endpoint (POST /api/companies/{companyId}/mcp/files), the Gmail add-on reads them from Gmail. Each is
-- checked and scanned for credentials as a conversation's file is (ADR-0014), held for an hour, and moved into the
-- conversation by the start that names it, as a file its person added, before its first turn.

-- ---- staged_file ----------------------------------------------------------------------------------------------------

-- A file a member holds for a start: an hour, theirs only; within a conversation's limits of files and bytes.
CREATE TABLE staged_file (
    id              uuid         PRIMARY KEY,
    company_id      uuid         NOT NULL REFERENCES company (id),
    principal_id    uuid         NOT NULL,
    principal_kind  varchar(8)   NOT NULL DEFAULT 'MEMBER',
    user_id         uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    file_name       varchar(255) NOT NULL,
    content_type    varchar(255) NOT NULL,
    size_bytes      bigint       NOT NULL,
    sha256          char(64)     NOT NULL,
    content         bytea        NOT NULL,
    created_at      timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    CONSTRAINT staged_file_kind_ck CHECK (principal_kind = 'MEMBER'),
    CONSTRAINT staged_file_principal_fk FOREIGN KEY (principal_id, company_id, principal_kind)
        REFERENCES principal (id, company_id, kind) ON DELETE CASCADE,
    CONSTRAINT staged_file_name_ck CHECK (length(file_name) BETWEEN 1 AND 255
        AND file_name NOT IN ('.', '..') AND position('/' IN file_name) = 0 AND position('\' IN file_name) = 0),
    CONSTRAINT staged_file_size_ck CHECK (size_bytes >= 0 AND size_bytes = octet_length(content)),
    CONSTRAINT staged_file_sha_ck CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT staged_file_expiry_ck CHECK (expires_at > created_at)
);

CREATE INDEX staged_file_principal_idx ON staged_file (principal_id, expires_at);
CREATE INDEX staged_file_expires_idx ON staged_file (expires_at);

ALTER TABLE staged_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE staged_file FORCE ROW LEVEL SECURITY;

CREATE POLICY staged_file_tenant ON staged_file FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- A file is held once, moved once, and swept once expired; UPDATE for the lock a start takes on the files it moves,
-- never to change one.
GRANT SELECT, INSERT, UPDATE, DELETE ON staged_file TO ${app_role};

-- The sweep's lookup runs as the owner, before any company's context: a SELECT-only policy of the owner, which the
-- runtime role never matches, as V026's.
CREATE POLICY staged_file_owner_lookup ON staged_file FOR SELECT TO CURRENT_USER USING (true);

-- The companies with files that expired before p_expired_before, for the sweep: ids only.
CREATE FUNCTION staged_file_expired(p_expired_before timestamptz, p_limit integer)
    RETURNS TABLE (company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT e.company_id
      FROM (SELECT DISTINCT s.company_id
              FROM ${flyway:defaultSchema}.staged_file s
             WHERE s.expires_at < p_expired_before) e
     ORDER BY e.company_id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

REVOKE ALL ON FUNCTION staged_file_expired(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION staged_file_expired(timestamptz, integer) TO ${app_role};
