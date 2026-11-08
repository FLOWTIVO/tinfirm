-- ADR-0111: the audit chains' heads anchored outside the database. A worker writes signed checkpoints of the chains'
-- heads to a sink the database's writer cannot change (object storage with object lock, a directory, syslog); these
-- tables only remember what was written, for the app and the daily check. Verification never trusts them: the
-- checkpoints in the sink, checked with public keys from the deployment's configuration, are the anchor.
--
--   audit_anchor_state       one row: the instance's id in the sink, and the writer's last success and failure
--   audit_checkpoint         a checkpoint written: its number, time, key, digest, place in the sink, and its document
--   audit_checkpoint_chain   the heads a checkpoint names, one row per chain, so that a company's context finds the
--                            latest checkpoint of its own chain without reading the other companies'
--   audit_check              a fourth failure, ANCHOR_MISMATCH: the chain disagrees with a checkpoint

-- ---- audit_anchor_state -------------------------------------------------------------------------------------------

--   instance_id            a random id made at the first write, the checkpoints' folder in the sink
--   last_number            the latest checkpoint written (NULL before the first)
--   consecutive_failures   writes that failed since the last success; 0 after one
CREATE TABLE audit_anchor_state (
    id                   smallint     PRIMARY KEY CHECK (id = 1),
    instance_id          uuid         NOT NULL,
    last_number          bigint,
    last_attempt_at      timestamptz,
    last_success_at      timestamptz,
    last_failure_at      timestamptz,
    last_failure         varchar(1000),
    consecutive_failures integer      NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0)
);

-- ---- audit_checkpoint ---------------------------------------------------------------------------------------------

--   number      1, 2, 3 …: the checkpoints are a chain of their own (each names the digest of the one before)
--   digest      hex(sha256) of the checkpoint's canonical form, which the next one names as prev
--   location    where the sink put it: an object key, a file name, or the syslog collector
--   document    the signed checkpoint as written: {"checkpoint": {…}, "signature": "…"}
--   chains      how many chains' heads it names
CREATE TABLE audit_checkpoint (
    number     bigint        PRIMARY KEY CHECK (number >= 1),
    issued_at  timestamptz   NOT NULL,
    key_id     varchar(64)   NOT NULL,
    chains     integer       NOT NULL CHECK (chains >= 1),
    digest     char(64)      NOT NULL,
    location   varchar(1000) NOT NULL,
    document   text          NOT NULL,
    written_at timestamptz   NOT NULL
);

-- ---- audit_checkpoint_chain ---------------------------------------------------------------------------------------

--   company_id  NULL for the instance chain
--   seq, hash   the chain's head as the checkpoint names it
CREATE TABLE audit_checkpoint_chain (
    number     bigint      NOT NULL REFERENCES audit_checkpoint (number),
    company_id uuid        REFERENCES company (id),
    seq        bigint      NOT NULL CHECK (seq >= 0),
    hash       char(64)    NOT NULL,
    issued_at  timestamptz NOT NULL,
    key_id     varchar(64) NOT NULL
);

-- A chain's checkpoints, newest first.
CREATE INDEX audit_checkpoint_chain_idx ON audit_checkpoint_chain (company_id, number DESC);
CREATE UNIQUE INDEX audit_checkpoint_chain_company_uq ON audit_checkpoint_chain (number, company_id)
    WHERE company_id IS NOT NULL;
CREATE UNIQUE INDEX audit_checkpoint_chain_instance_uq ON audit_checkpoint_chain (number) WHERE company_id IS NULL;

-- ---- row-level security -------------------------------------------------------------------------------------------

-- The checkpoints are the instance's: for instance admins (the status page) and server-side work across companies
-- (app.system: the writer). A checkpoint names every company's head, so no company's context reads it. The state
-- holds no company's data, and every chain's daily check reads it (the instance's id and the latest number, to find
-- the latest checkpoint in the sink): any context reads it, only server-side work writes it.
ALTER TABLE audit_anchor_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_anchor_state FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_anchor_state_select ON audit_anchor_state FOR SELECT USING (true);
CREATE POLICY audit_anchor_state_insert ON audit_anchor_state FOR INSERT WITH CHECK (app_is_system());
CREATE POLICY audit_anchor_state_update ON audit_anchor_state FOR UPDATE
    USING (app_is_system()) WITH CHECK (app_is_system());

ALTER TABLE audit_checkpoint ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_checkpoint FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_checkpoint_instance ON audit_checkpoint
    USING (app_is_instance_admin() OR app_is_system())
    WITH CHECK (app_is_system());

-- As audit_check: a company's rows in its context, the instance chain's for instance admins, every chain's for
-- server-side work; only the writer inserts.
ALTER TABLE audit_checkpoint_chain ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_checkpoint_chain FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_checkpoint_chain_select ON audit_checkpoint_chain FOR SELECT
    USING (company_id = app_company_id() OR (company_id IS NULL AND app_is_instance_admin()) OR app_is_system());
CREATE POLICY audit_checkpoint_chain_insert ON audit_checkpoint_chain FOR INSERT
    WITH CHECK (app_is_system());

-- Written once, never changed or deleted by the application: a checkpoint is a record as the events are.
GRANT SELECT, INSERT, UPDATE ON audit_anchor_state TO ${app_role};
GRANT SELECT, INSERT ON audit_checkpoint TO ${app_role};
GRANT SELECT, INSERT ON audit_checkpoint_chain TO ${app_role};

-- ---- audit_check --------------------------------------------------------------------------------------------------

--   failure            ANCHOR_MISMATCH: the event at a checkpoint's seq is gone or has another hash
--                      (first_invalid_seq that seq, anchor_checkpoint the checkpoint's number)
ALTER TABLE audit_check ADD COLUMN anchor_checkpoint bigint;
ALTER TABLE audit_check DROP CONSTRAINT audit_check_failure_ck;
ALTER TABLE audit_check ADD CONSTRAINT audit_check_failure_ck CHECK (
    (ok AND failure IS NULL AND first_invalid_seq IS NULL AND anchor_checkpoint IS NULL)
    OR (NOT ok AND failure IN ('BROKEN', 'SHORTER', 'HEAD_CHANGED', 'ANCHOR_MISMATCH')
        AND (failure IN ('BROKEN', 'ANCHOR_MISMATCH')) = (first_invalid_seq IS NOT NULL)
        AND (failure = 'ANCHOR_MISMATCH') = (anchor_checkpoint IS NOT NULL)));
