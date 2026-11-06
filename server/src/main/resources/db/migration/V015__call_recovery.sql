-- The recovery of the calls a restart leaves behind (CONVENTIONS §15.7, Work after the commit; the package 3 contract,
-- docs/reviews/2026-10-12-package-3-contract.md §5.3, §5.8): package 3c, part 2. An approved tool call and a parked
-- call run after the commit that set them going, on a thread of the server; a restart between that commit and the
-- call's record lost them for good. A pass of the server now finds them again, in every company, and runs each once
-- or settles it.
--
--   decision.call_started_at   when an approved tool call was taken to run (its claim, under the decision's lock):
--                              null until then, and again after a passing failure that called nothing (a token that
--                              could not be got). A call taken long ago without a result was running when the server
--                              stopped: it may have written, so it is never run again but settled as of unknown
--                              outcome. A parked call's claim is its claimedAt in conversation_binding.waiting_calls.
--
-- The conventions of V001 apply: company_id on every row, ENABLE plus FORCE ROW LEVEL SECURITY with a policy on
-- app_company_id(), grants to the runtime role ${app_role}.

ALTER TABLE decision ADD COLUMN call_started_at timestamptz;
-- Only an approved call is taken to run.
ALTER TABLE decision ADD CONSTRAINT decision_call_started_ck CHECK (call_started_at IS NULL OR outcome = 'APPROVED');

-- The approved calls without a result: the recovery's candidates. A call leaves the index with its result.
CREATE INDEX decision_call_pending_idx ON decision (decided_at)
    WHERE outcome = 'APPROVED' AND result IS NULL AND subject ->> 'type' = 'TOOL_CALL';
-- The bindings that hold calls: parked, run, or told and not dropped yet.
CREATE INDEX conversation_binding_calls_idx ON conversation_binding (id) WHERE jsonb_array_length(waiting_calls) > 0;

-- ---- cross-company lookup (CONVENTIONS §7, as in V004 and V008) ---------------------------------------------------

-- The recovery (a worker replica) looks for the calls of every company: ids and company ids only; the server claims
-- each again under its locks in its company's tenant context. The owner gets a SELECT-only policy on the tables the
-- function reads, which the runtime role never matches.
CREATE POLICY decision_owner_lookup ON decision FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY conversation_binding_owner_lookup ON conversation_binding FOR SELECT TO CURRENT_USER USING (true);

-- What a restart may have left, at most p_limit of each kind:
--   DECISION  an approved tool call without a result, never taken and approved before p_started_before, or taken
--             before p_running_before;
--   BINDING   a binding with a call ALLOWED before p_started_before (claimedAt), or RUNNING since before
--             p_running_before, or still PARKED although the binding is BOUND.
CREATE FUNCTION conversation_calls_due(p_started_before timestamptz, p_running_before timestamptz, p_limit integer)
    RETURNS TABLE (kind text, id uuid, company_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    (SELECT 'DECISION'::text, d.id, d.company_id
       FROM ${flyway:defaultSchema}.decision d
      WHERE d.outcome = 'APPROVED' AND d.result IS NULL AND d.subject ->> 'type' = 'TOOL_CALL'
        AND ((d.call_started_at IS NULL AND d.decided_at < p_started_before)
             OR d.call_started_at < p_running_before)
      ORDER BY d.decided_at, d.id
      LIMIT least(greatest(p_limit, 1), 500))
    UNION ALL
    (SELECT 'BINDING'::text, b.id, b.company_id
       FROM ${flyway:defaultSchema}.conversation_binding b
      WHERE jsonb_array_length(b.waiting_calls) > 0
        AND EXISTS (SELECT 1 FROM jsonb_array_elements(b.waiting_calls) AS c
                     WHERE (c ->> 'status' = 'ALLOWED'
                            AND coalesce(c ->> 'claimedAt', c ->> 'parkedAt', '-infinity')::timestamptz < p_started_before)
                        OR (c ->> 'status' = 'RUNNING'
                            AND coalesce(c ->> 'claimedAt', c ->> 'parkedAt', '-infinity')::timestamptz < p_running_before)
                        OR (c ->> 'status' = 'PARKED' AND b.state = 'BOUND'))
      ORDER BY b.id
      LIMIT least(greatest(p_limit, 1), 500))
$$;

REVOKE ALL ON FUNCTION conversation_calls_due(timestamptz, timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION conversation_calls_due(timestamptz, timestamptz, integer) TO ${app_role};
