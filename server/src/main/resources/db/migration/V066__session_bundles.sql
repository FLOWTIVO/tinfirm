-- Session bundles (ADR-0097; the Kubernetes runner analysis §6.5, D3): the session folders of a conversation's agent,
-- which a company runner host uploads when a run ends and the next run's host restores, so that a turn resumes its
-- session on any host. The bundle itself is an object in the bundle store (S3-compatible storage, or a directory),
-- encrypted with a data key of its own that the vault's master key wraps; this table holds what finds and opens it.
--
--   session_bundle  the newest bundle of a conversation and its agent, one at most
--
-- The conventions of V001 apply: company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on app_company_id(),
-- composite foreign keys with the company id, and grants to ${app_role}.

-- The newest bundle of a conversation's agent.
--
--   runtime      the runtime whose session folders it holds; a run of another runtime never gets it
--   session_id   the agent's session it holds, as the uploading run reported it: a run gets the bundle only when
--                its resumeSessionId is this one
--   object_name  the object in the store: a random UUID, which names no company, agent or conversation
--   size_bytes   the bundle's size before encryption, what the host checks (at most 32 MiB)
--   sha256       the SHA-256 of the bundle before encryption, in lower-case hex
--   key_id,      the bundle's data key, wrapped under the vault's master key key_id
--   wrapped_key,
--   key_nonce
--   run_id       the run that uploaded it
CREATE TABLE session_bundle (
    company_id      uuid         NOT NULL,
    conversation_id uuid         NOT NULL,
    agent_id        uuid         NOT NULL,
    runtime         varchar(16)  NOT NULL,
    session_id      varchar(255) NOT NULL,
    object_name     uuid         NOT NULL,
    size_bytes      integer      NOT NULL,
    sha256          char(64)     NOT NULL,
    key_id          varchar(64)  NOT NULL,
    wrapped_key     bytea        NOT NULL,
    key_nonce       bytea        NOT NULL,
    run_id          uuid         NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT session_bundle_pk PRIMARY KEY (conversation_id, agent_id),
    CONSTRAINT session_bundle_object_uq UNIQUE (object_name),
    CONSTRAINT session_bundle_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id) ON DELETE CASCADE,
    CONSTRAINT session_bundle_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id) ON DELETE CASCADE,
    CONSTRAINT session_bundle_runtime_ck CHECK (runtime IN ('CLAUDE_CODE', 'CODEX', 'PI', 'OPENCODE')),
    CONSTRAINT session_bundle_session_ck CHECK (length(session_id) BETWEEN 1 AND 255),
    CONSTRAINT session_bundle_size_ck CHECK (size_bytes BETWEEN 1 AND 33554432),
    CONSTRAINT session_bundle_sha256_ck CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX session_bundle_company_idx ON session_bundle (company_id, conversation_id);
CREATE INDEX session_bundle_run_idx ON session_bundle (run_id);

ALTER TABLE session_bundle ENABLE ROW LEVEL SECURITY;
ALTER TABLE session_bundle FORCE ROW LEVEL SECURITY;

CREATE POLICY session_bundle_tenant ON session_bundle FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- A bundle is replaced as a whole and deleted with its conversation's session, never changed in place.
GRANT SELECT, INSERT, DELETE ON session_bundle TO ${app_role};

-- ---- cross-company lookups (CONVENTIONS §7, as in V004) ----------------------------------------------------------

-- Narrow SECURITY DEFINER lookups for the bundle sweeper: the bundles that are due (their conversation ended, or took
-- no turn since the cutoff), and which of the store's objects a row still names. Each returns ids only; the server
-- deletes a due bundle again under the company's tenant context. The owner gets SELECT-only policies on the tables
-- the functions read; the runtime role never matches them.

CREATE POLICY session_bundle_owner_lookup ON session_bundle FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY conversation_owner_lookup ON conversation FOR SELECT TO CURRENT_USER USING (true);

-- Bundles whose conversation ended, or whose conversation's last turn (else the bundle) is older than the cutoff.
CREATE FUNCTION session_bundle_due(p_before timestamptz, p_limit integer)
    RETURNS TABLE (company_id uuid, conversation_id uuid, agent_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT b.company_id, b.conversation_id, b.agent_id
      FROM ${flyway:defaultSchema}.session_bundle b
      JOIN ${flyway:defaultSchema}.conversation c ON c.id = b.conversation_id AND c.company_id = b.company_id
     WHERE c.state = 'ENDED'
        OR coalesce(c.last_turn_at, b.created_at) < p_before
     ORDER BY b.created_at, b.conversation_id, b.agent_id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

-- Which of these objects a bundle row names.
CREATE FUNCTION session_bundle_objects_known(p_names uuid[])
    RETURNS TABLE (object_name uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT b.object_name
      FROM ${flyway:defaultSchema}.session_bundle b
     WHERE b.object_name = ANY (p_names)
$$;

REVOKE ALL ON FUNCTION session_bundle_due(timestamptz, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION session_bundle_objects_known(uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION session_bundle_due(timestamptz, integer) TO ${app_role};
GRANT EXECUTE ON FUNCTION session_bundle_objects_known(uuid[]) TO ${app_role};
