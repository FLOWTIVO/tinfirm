-- The session bundle sweeper's lookup names the object (ADR-0097, amended): session_bundle_due answered ids only, and
-- the sweeper deleted each pair's row again in its company's context without looking at it. Between the lookup and
-- the delete a conversation may have resumed: its turn sets conversation.last_turn_at and its run replaces the bundle
-- with a new object (SessionBundleRows.replace), and the sweeper deleted the current bundle of a live conversation.
-- The lookup now answers the object it found due too, and the sweeper deletes a row only while it still names that
-- object and its conversation is still due (SessionBundleStore.deleteDue), under the conversation's row lock.
--
-- The function is dropped and created again, since its row type changes; the REVOKE and GRANT of V066 follow it.

DROP FUNCTION session_bundle_due(timestamptz, integer);

-- Bundles whose conversation ended, or whose conversation's last turn (else the bundle) is older than the cutoff:
-- the pair, its company and the object the row names at this moment.
CREATE FUNCTION session_bundle_due(p_before timestamptz, p_limit integer)
    RETURNS TABLE (company_id uuid, conversation_id uuid, agent_id uuid, object_name uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT b.company_id, b.conversation_id, b.agent_id, b.object_name
      FROM ${flyway:defaultSchema}.session_bundle b
      JOIN ${flyway:defaultSchema}.conversation c ON c.id = b.conversation_id AND c.company_id = b.company_id
     WHERE c.state = 'ENDED'
        OR coalesce(c.last_turn_at, b.created_at) < p_before
     ORDER BY b.created_at, b.conversation_id, b.agent_id
     LIMIT least(greatest(p_limit, 1), 500)
$$;

REVOKE ALL ON FUNCTION session_bundle_due(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION session_bundle_due(timestamptz, integer) TO ${app_role};
