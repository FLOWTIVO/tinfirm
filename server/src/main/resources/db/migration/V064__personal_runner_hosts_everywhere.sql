-- ADR-0092: a member's personal runner host serves them in every company they belong to.
--
-- The machine becomes a row of its own, personal_runner_host, an instance-level table of its owner's (owner_user_id)
-- with the token and everything the host reports and is set: the ceiling, the slots, the desired value, the
-- runtimes, the version, the facts of its hello. In each company where the owner is a member the host has a
-- presence: the runner_host row of that company (scope PERSONAL, owner_principal_id the owner's principal there),
-- which runs and subscriptions name, and which row-level security keeps to its company. A presence carries no token
-- and no facts of its own: every read joins the machine's row. A company host is unchanged.
--
-- Credentials a personal host reports are the machine's too: personal_runner_credential replaces runner_credential.
--
-- Existing personal hosts become machines with a presence in the company they were registered in, and in every
-- other company where their owner has a member principal that has not left.

CREATE TABLE personal_runner_host (
    id                          uuid         PRIMARY KEY,
    owner_user_id               uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name                        varchar(100) NOT NULL,
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
    CONSTRAINT personal_runner_host_owner_name_uq UNIQUE (owner_user_id, name),
    CONSTRAINT personal_runner_host_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT personal_runner_host_name_ck CHECK (name ~ '^[A-Za-z0-9][A-Za-z0-9._-]*$' AND length(name) <= 100),
    CONSTRAINT personal_runner_host_token_hash_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT personal_runner_host_max_runs_ck
        CHECK (max_concurrent_runs BETWEEN 0 AND 16 AND max_concurrent_runs <= max_concurrent_runs_ceiling),
    CONSTRAINT personal_runner_host_max_runs_ceiling_ck CHECK (max_concurrent_runs_ceiling BETWEEN 1 AND 16),
    CONSTRAINT personal_runner_host_desired_max_runs_ck
        CHECK (desired_max_concurrent_runs IS NULL OR desired_max_concurrent_runs BETWEEN 1 AND 16),
    CONSTRAINT personal_runner_host_draining_ck CHECK (NOT draining OR max_concurrent_runs = 0),
    CONSTRAINT personal_runner_host_config_version_ck CHECK (config_version >= 0),
    CONSTRAINT personal_runner_host_runtimes_ck
        CHECK (runtimes IS NULL OR runtimes <@ ARRAY['CLAUDE_CODE', 'CODEX', 'PI', 'OPENCODE']::varchar(16)[])
);

-- An instance-level table of its owner's, as api_token is: no company, no row-level security. The code keeps it to
-- its owner and to the presences of the companies the caller is in.
GRANT SELECT, INSERT, UPDATE, DELETE ON personal_runner_host TO ${app_role};

-- The credentials a machine reported in hello (the package 2 contract, §2; ADR-0085), by label.
CREATE TABLE personal_runner_credential (
    id               uuid          PRIMARY KEY,
    personal_host_id uuid          NOT NULL REFERENCES personal_runner_host (id) ON DELETE CASCADE,
    label            varchar(63)   NOT NULL,
    kind             varchar(32)   NOT NULL,
    runtimes         varchar(16)[] NOT NULL,
    pool_size        integer       NOT NULL,
    status           varchar(16)   NOT NULL DEFAULT 'AVAILABLE',
    cooldown_until   timestamptz,
    detail           varchar(500),
    reported         boolean       NOT NULL DEFAULT true,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    CONSTRAINT personal_runner_credential_host_label_uq UNIQUE (personal_host_id, label),
    CONSTRAINT personal_runner_credential_label_ck CHECK (label ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT personal_runner_credential_kind_ck CHECK (kind IN ('CLAUDE_LOGIN',
                                                                  'CODEX_CHATGPT_AUTH', 'ANTHROPIC_API_KEY',
                                                                  'OPENAI_API_KEY')),
    CONSTRAINT personal_runner_credential_runtimes_ck
        CHECK (cardinality(runtimes) >= 1
               AND runtimes <@ ARRAY['CLAUDE_CODE', 'CODEX', 'PI', 'OPENCODE']::varchar(16)[]),
    CONSTRAINT personal_runner_credential_pool_ck CHECK (pool_size BETWEEN 1 AND 16),
    CONSTRAINT personal_runner_credential_status_ck CHECK (status IN ('AVAILABLE', 'COOLDOWN', 'INVALID'))
);

GRANT SELECT, INSERT, UPDATE, DELETE ON personal_runner_credential TO ${app_role};

-- ---- the presence: a personal host's row in a company -------------------------------------------------------------

ALTER TABLE runner_host ADD COLUMN personal_host_id uuid REFERENCES personal_runner_host (id) ON DELETE CASCADE;

-- A presence carries no token: the machine does.
ALTER TABLE runner_host ALTER COLUMN token_hash DROP NOT NULL;

-- Every existing personal host becomes a machine of its owner, keeping its id as the presence's.
INSERT INTO personal_runner_host (id, owner_user_id, name, token_hash, max_concurrent_runs,
                                  max_concurrent_runs_ceiling, desired_max_concurrent_runs, draining,
                                  config_version, applied_config_version, runtimes, runner_version, hostname, os,
                                  arch, last_seen_at, created_at)
SELECT gen_random_uuid(), p.user_id, h.name, h.token_hash, h.max_concurrent_runs, h.max_concurrent_runs_ceiling,
       h.desired_max_concurrent_runs, h.draining, h.config_version, h.applied_config_version, h.runtimes,
       h.runner_version, h.hostname, h.os, h.arch, h.last_seen_at, h.created_at
  FROM runner_host h
  JOIN principal p ON p.id = h.owner_principal_id AND p.company_id = h.company_id
 WHERE h.scope = 'PERSONAL' AND p.user_id IS NOT NULL
 ORDER BY h.created_at, h.id
    ON CONFLICT (owner_user_id, name) DO NOTHING;

-- A second host of the same owner and name, registered in another company: the first keeps the name, this one is
-- renamed after its company's key or id, so that both stay the owner's.
WITH second AS (
    SELECT h.*, p.user_id,
           '-' || coalesce(c.key, left(replace(c.id::text, '-', ''), 12)) AS by_key,
           '-' || left(replace(c.id::text, '-', ''), 12) AS by_id,
           '-' || left(replace(h.id::text, '-', ''), 12) AS by_host
      FROM runner_host h
      JOIN principal p ON p.id = h.owner_principal_id AND p.company_id = h.company_id
      JOIN company c ON c.id = h.company_id
     WHERE h.scope = 'PERSONAL' AND p.user_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM personal_runner_host m WHERE m.token_hash = h.token_hash)
), wanted AS (
    -- Within the column's 100 characters; the company's id when the owner already has the keyed name.
    SELECT s.*,
           CASE WHEN EXISTS (SELECT 1 FROM personal_runner_host m
                              WHERE m.owner_user_id = s.user_id AND m.name = left(s.name, 100 - length(s.by_key)) || s.by_key)
                THEN left(s.name, 100 - length(s.by_id)) || s.by_id
                ELSE left(s.name, 100 - length(s.by_key)) || s.by_key END AS new_name
      FROM second s
), distinct_named AS (
    -- Two long names cut to one: all but the first take the host's own id instead.
    SELECT w.*, row_number() OVER (PARTITION BY w.user_id, w.new_name ORDER BY w.created_at, w.id) AS nth
      FROM wanted w
)
INSERT INTO personal_runner_host (id, owner_user_id, name, token_hash, max_concurrent_runs,
                                  max_concurrent_runs_ceiling, desired_max_concurrent_runs, draining,
                                  config_version, applied_config_version, runtimes, runner_version, hostname, os,
                                  arch, last_seen_at, created_at)
SELECT gen_random_uuid(), d.user_id,
       CASE WHEN d.nth = 1 THEN d.new_name ELSE left(d.name, 100 - length(d.by_host)) || d.by_host END,
       d.token_hash, d.max_concurrent_runs, d.max_concurrent_runs_ceiling, d.desired_max_concurrent_runs,
       d.draining, d.config_version, d.applied_config_version, d.runtimes, d.runner_version, d.hostname, d.os,
       d.arch, d.last_seen_at, d.created_at
  FROM distinct_named d;

UPDATE runner_host h
   SET personal_host_id = m.id
  FROM personal_runner_host m
 WHERE h.scope = 'PERSONAL' AND m.token_hash = h.token_hash;

-- A personal host whose owner has no user (none should exist: runner_host_owner_fk admits members only) goes.
DELETE FROM runner_host WHERE scope = 'PERSONAL' AND personal_host_id IS NULL;

-- Its credentials move to the machine.
INSERT INTO personal_runner_credential (id, personal_host_id, label, kind, runtimes, pool_size, status,
                                        cooldown_until, detail, reported, created_at, updated_at)
SELECT c.id, h.personal_host_id, c.label, c.kind, c.runtimes, c.pool_size, c.status, c.cooldown_until, c.detail,
       c.reported, c.created_at, c.updated_at
  FROM runner_credential c
  JOIN runner_host h ON h.id = c.runner_host_id AND h.company_id = c.company_id;

DROP TABLE runner_credential;

-- The presence carries no token and no facts.
UPDATE runner_host SET token_hash = NULL WHERE scope = 'PERSONAL';

ALTER TABLE runner_host
    ADD CONSTRAINT runner_host_personal_ck CHECK ((scope = 'PERSONAL') = (personal_host_id IS NOT NULL)),
    ADD CONSTRAINT runner_host_token_ck CHECK ((scope = 'COMPANY') = (token_hash IS NOT NULL)),
    ADD CONSTRAINT runner_host_presence_uq UNIQUE (personal_host_id, company_id);

-- A personal host's name is its owner's: two members may each have a host of one name in a company, and a company
-- host and a personal host may share one. The keyset listing on (name, id) keeps an index.
ALTER TABLE runner_host DROP CONSTRAINT runner_host_company_name_uq;
CREATE UNIQUE INDEX runner_host_company_name_uq ON runner_host (company_id, name) WHERE scope = 'COMPANY';
CREATE UNIQUE INDEX runner_host_personal_name_uq ON runner_host (company_id, owner_principal_id, name)
    WHERE scope = 'PERSONAL';
CREATE INDEX runner_host_company_name_idx ON runner_host (company_id, name, id);
CREATE INDEX runner_host_personal_idx ON runner_host (personal_host_id) WHERE personal_host_id IS NOT NULL;

-- A presence in every other company where the owner is a member who has not left: the runner_host row there, named
-- as the machine, owned by the owner's principal in that company.
INSERT INTO runner_host (id, company_id, name, scope, owner_principal_id, personal_host_id, created_at)
SELECT gen_random_uuid(), p.company_id, m.name, 'PERSONAL', p.id, m.id, m.created_at
  FROM personal_runner_host m
  JOIN principal p ON p.user_id = m.owner_user_id AND p.kind = 'MEMBER' AND p.status <> 'LEFT'
 WHERE NOT EXISTS (SELECT 1 FROM runner_host h WHERE h.personal_host_id = m.id AND h.company_id = p.company_id)
    ON CONFLICT DO NOTHING;

-- ---- lookups across companies -----------------------------------------------------------------------------------

-- The host of a token: a company host (its row and company, no machine), or a machine with each of its presences
-- (one row per company; a machine without any presence answers one row without a company). The runner endpoint,
-- before any company context exists.
DROP FUNCTION runner_host_for_token(text);

CREATE FUNCTION runner_host_for_token(p_token_hash text)
    RETURNS TABLE (runner_host_id uuid, company_id uuid, personal_host_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT h.id, h.company_id, NULL::uuid
      FROM ${flyway:defaultSchema}.runner_host h
     WHERE h.token_hash = p_token_hash AND h.scope = 'COMPANY'
    UNION ALL
    SELECT h.id, h.company_id, m.id
      FROM ${flyway:defaultSchema}.personal_runner_host m
      LEFT JOIN ${flyway:defaultSchema}.runner_host h ON h.personal_host_id = m.id
     WHERE m.token_hash = p_token_hash
     ORDER BY 2
$$;

REVOKE ALL ON FUNCTION runner_host_for_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION runner_host_for_token(text) TO ${app_role};

-- The presences of a machine, for the connection's frames, the registration and the removal of the machine: each
-- company it is in, with the presence's id.
CREATE FUNCTION personal_runner_host_presences(p_personal_host_id uuid)
    RETURNS TABLE (runner_host_id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT h.id, h.company_id FROM ${flyway:defaultSchema}.runner_host h
     WHERE h.personal_host_id = p_personal_host_id
     ORDER BY h.company_id
$$;

REVOKE ALL ON FUNCTION personal_runner_host_presences(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION personal_runner_host_presences(uuid) TO ${app_role};

-- The companies where a user is a member who has not left, with their principal there: where a machine of theirs
-- gets a presence.
CREATE FUNCTION member_principals(p_user_id uuid)
    RETURNS TABLE (company_id uuid, principal_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p.company_id, p.id FROM ${flyway:defaultSchema}.principal p
     WHERE p.user_id = p_user_id AND p.kind = 'MEMBER' AND p.status <> 'LEFT'
     ORDER BY p.company_id
$$;

REVOKE ALL ON FUNCTION member_principals(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION member_principals(uuid) TO ${app_role};

-- The company of a run, by id: the frames of a machine's connection, which may concern any of its presences.
CREATE FUNCTION run_company(p_run_id uuid)
    RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT r.company_id FROM ${flyway:defaultSchema}.run r WHERE r.id = p_run_id
$$;

REVOKE ALL ON FUNCTION run_company(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION run_company(uuid) TO ${app_role};

-- The slots a machine's runs hold, across its presences: the dispatcher of any company counts them under the
-- machine's lock, so that its slots are never given twice.
CREATE FUNCTION personal_runner_host_occupying(p_personal_host_ids uuid[])
    RETURNS TABLE (personal_host_id uuid, occupying integer)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT h.personal_host_id, count(*)::integer
      FROM ${flyway:defaultSchema}.run r
      JOIN ${flyway:defaultSchema}.runner_host h ON h.id = r.runner_host_id AND h.company_id = r.company_id
     WHERE h.personal_host_id = ANY (p_personal_host_ids)
       AND (r.status = 'RUNNING' OR (r.status = 'QUEUED' AND r.dispatched_at IS NOT NULL))
     GROUP BY h.personal_host_id
$$;

REVOKE ALL ON FUNCTION personal_runner_host_occupying(uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION personal_runner_host_occupying(uuid[]) TO ${app_role};

-- The slots of a subscription's pool its runs hold, across the machine's presences.
CREATE FUNCTION personal_runner_host_occupying_subscription(p_personal_host_id uuid, p_label text)
    RETURNS integer
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT count(*)::integer
      FROM ${flyway:defaultSchema}.run r
      JOIN ${flyway:defaultSchema}.runner_host h ON h.id = r.subscription_host_id AND h.company_id = r.company_id
     WHERE h.personal_host_id = p_personal_host_id AND r.subscription_label = p_label
       AND (r.status = 'RUNNING' OR (r.status = 'QUEUED' AND r.dispatched_at IS NOT NULL))
$$;

REVOKE ALL ON FUNCTION personal_runner_host_occupying_subscription(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION personal_runner_host_occupying_subscription(uuid, text) TO ${app_role};

-- Whether a machine has a run queued for or running on any of its presences, or one waiting for a subscription of
-- its: it is not removed while it does.
CREATE FUNCTION personal_runner_host_active(p_personal_host_id uuid)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1 FROM ${flyway:defaultSchema}.run r
          JOIN ${flyway:defaultSchema}.runner_host h
            ON h.company_id = r.company_id AND (h.id = r.runner_host_id OR h.id = r.subscription_host_id)
         WHERE h.personal_host_id = p_personal_host_id AND r.status IN ('QUEUED', 'RUNNING'))
$$;

REVOKE ALL ON FUNCTION personal_runner_host_active(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION personal_runner_host_active(uuid) TO ${app_role};
