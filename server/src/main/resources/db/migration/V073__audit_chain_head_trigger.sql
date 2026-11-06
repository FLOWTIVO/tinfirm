-- The head of an audit chain moves only with its chain (security review of 2026-10-27, L16; CONVENTIONS §8).
--
-- audit_chain_head is UPDATE-able by the runtime role, which advances it with every append (JdbcAuditLog). Nothing tied
-- the head to the events: SQL run as the runtime role (an injection, a compromised process) could set a head backwards,
-- after which every audited action of that chain fails on the unique (company_id, seq), a denial of service R5 did
-- not describe. A BEFORE UPDATE trigger now lets a head move only one step forward, to an event of its chain that
-- exists with that seq and hash and links to the head it replaces:
--
--   NEW.company_id = OLD.company_id   a head stays with its chain
--   NEW.last_seq = OLD.last_seq + 1   one event at a time, never backwards
--   an audit_event (company_id, seq, hash, prev_hash) = (NEW.company_id, NEW.last_seq, NEW.last_hash, OLD.last_hash)
--
-- The append inserts the event and then advances the head in one transaction, so it passes. The function is SECURITY
-- DEFINER, so that the lookup sees the event whatever the caller's tenant context (an instance event appended in a
-- user's context is not visible to it); it reads one row by the chain's unique key. The schema owner, who owns the
-- trigger, can still disable it, as it can switch off row-level security: R5 stays.
--
-- The runtime role's grants on audit_chain_head and audit_event are unchanged (V001).

CREATE POLICY audit_event_owner_lookup ON audit_event FOR SELECT TO CURRENT_USER USING (true);

CREATE FUNCTION audit_chain_head_advance_ck() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.company_id IS DISTINCT FROM OLD.company_id THEN
        RAISE EXCEPTION 'An audit chain''s head stays with its chain'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'audit_chain_head_advance_ck';
    END IF;
    IF NEW.last_seq IS DISTINCT FROM OLD.last_seq + 1 THEN
        RAISE EXCEPTION 'An audit chain''s head advances by one event'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'audit_chain_head_advance_ck';
    END IF;
    -- The chain's unique (company_id, seq) finds the event: = for a company's chain, IS NULL for the instance chain.
    IF NOT EXISTS (SELECT 1
                     FROM ${flyway:defaultSchema}.audit_event e
                    WHERE NEW.company_id IS NOT NULL
                      AND e.company_id = NEW.company_id
                      AND e.seq = NEW.last_seq
                      AND e.hash = NEW.last_hash
                      AND e.prev_hash = OLD.last_hash)
       AND NOT EXISTS (SELECT 1
                         FROM ${flyway:defaultSchema}.audit_event e
                        WHERE NEW.company_id IS NULL
                          AND e.company_id IS NULL
                          AND e.seq = NEW.last_seq
                          AND e.hash = NEW.last_hash
                          AND e.prev_hash = OLD.last_hash) THEN
        RAISE EXCEPTION 'An audit chain''s head names an event of its chain that follows the head it replaces'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'audit_chain_head_advance_ck';
    END IF;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION audit_chain_head_advance_ck() FROM PUBLIC;

CREATE TRIGGER audit_chain_head_advance_ck_trg BEFORE UPDATE ON audit_chain_head
    FOR EACH ROW EXECUTE FUNCTION audit_chain_head_advance_ck();
