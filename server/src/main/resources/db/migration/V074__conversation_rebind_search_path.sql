-- conversation_rebind fixes its search path as every other SECURITY DEFINER function does (security review of
-- 2026-10-27, L20; CONVENTIONS §7).
--
-- V049 gave it search_path = public, pg_temp and an unqualified app_company_id(), because the fixed-column triggers its
-- updates fire (conversation_fixed_ck, conversation_binding_ck) run with the search path in effect and name their
-- tables and conversation_rebinding() unqualified. A definer function that searches a schema the caller might create
-- objects in is the shape the other 39 avoid. The function is created again as V049 wrote it, with search_path
-- pg_catalog, pg_temp and its one unqualified call qualified; the two trigger functions, which are not SECURITY
-- DEFINER and run as whoever fires them, get the schema in their own search path, so that they find their tables
-- whoever calls them. Nothing else changes: the body, the REVOKE and the runtime role's EXECUTE are V049's.

ALTER FUNCTION conversation_fixed_ck() SET search_path = ${flyway:defaultSchema}, pg_temp;
ALTER FUNCTION conversation_binding_ck() SET search_path = ${flyway:defaultSchema}, pg_temp;

-- As V049 says it; a binding is bound again also when only its holder changed: a reassigned trigger's row may name
-- another service account that borrows the same credential. It bypasses row-level security, so it takes a conversation
-- of the company the caller's tenant context names only.
CREATE OR REPLACE FUNCTION conversation_rebind(p_conversation_id uuid) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_conversation record;
    v_trigger      record;
    v_settings     record;
    v_binding      record;
    v_row          record;
    v_systems      jsonb := '[]'::jsonb;
    v_now          timestamptz := now();
BEGIN
    SELECT c.id, c.company_id, c.trigger_id, c.state, c.active_run_id, c.author_principal_id, c.payer_principal_id,
           c.model_access, c.subscription_label
      INTO v_conversation
      FROM ${flyway:defaultSchema}.conversation c
     WHERE c.id = p_conversation_id AND c.company_id = ${flyway:defaultSchema}.app_company_id()
       FOR NO KEY UPDATE;
    IF NOT FOUND OR v_conversation.trigger_id IS NULL OR v_conversation.state <> 'OPEN'
       OR v_conversation.active_run_id IS NOT NULL THEN
        RAISE EXCEPTION 'conversation_rebind takes an open conversation of a trigger without an active run'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_rebind_ck';
    END IF;
    SELECT t.id, t.author_principal_id, t.payer_principal_id, t.status, t.deleted_at
      INTO v_trigger
      FROM ${flyway:defaultSchema}.trigger t
     WHERE t.id = v_conversation.trigger_id AND t.company_id = v_conversation.company_id;
    IF NOT FOUND OR v_trigger.deleted_at IS NOT NULL OR v_trigger.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'conversation_rebind takes the conversation of a live trigger that was resumed'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_rebind_ck';
    END IF;
    SELECT s.model_access, s.subscription_host_id, s.subscription_label
      INTO v_settings
      FROM ${flyway:defaultSchema}.agent_settings s
     WHERE s.trigger_id = v_trigger.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'conversation_rebind needs the trigger''s settings'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'conversation_rebind_ck';
    END IF;

    PERFORM set_config('tinfirm.conversation_rebind', p_conversation_id::text, true);

    UPDATE ${flyway:defaultSchema}.conversation
       SET author_principal_id = v_trigger.author_principal_id,
           payer_principal_id = v_trigger.payer_principal_id,
           model_access = v_settings.model_access,
           subscription_host_id = CASE WHEN v_settings.model_access = 'SUBSCRIPTION'
                                       THEN v_settings.subscription_host_id END,
           subscription_label = CASE WHEN v_settings.model_access = 'SUBSCRIPTION'
                                     THEN v_settings.subscription_label END,
           updated_at = v_now
     WHERE id = p_conversation_id;

    FOR v_binding IN
        SELECT b.id, b.system_id, b.state, b.how, b.account_id, b.holder_principal_id, b.grant_id
          FROM ${flyway:defaultSchema}.conversation_binding b
         WHERE b.conversation_id = p_conversation_id
         ORDER BY b.system_id
           FOR NO KEY UPDATE
    LOOP
        SELECT a.account_id, a.holder_principal_id, acc.external_id
          INTO v_row
          FROM ${flyway:defaultSchema}.trigger_account a
          JOIN ${flyway:defaultSchema}.account acc ON acc.id = a.account_id
         WHERE a.trigger_id = v_trigger.id AND a.system_id = v_binding.system_id;
        IF FOUND THEN
            IF v_binding.account_id IS DISTINCT FROM v_row.account_id
               OR v_binding.holder_principal_id IS DISTINCT FROM v_row.holder_principal_id
               OR v_binding.state <> 'BOUND' THEN
                UPDATE ${flyway:defaultSchema}.conversation_binding
                   SET state = 'BOUND', how = 'FIXED', account_id = v_row.account_id,
                       holder_principal_id = v_row.holder_principal_id, external_id = v_row.external_id,
                       grant_id = NULL, reason = NULL, choices = '{}', waiting_since = NULL, bound_at = v_now,
                       bound_by = NULL, updated_at = v_now
                 WHERE id = v_binding.id;
                v_systems := v_systems || jsonb_build_object(
                    'systemId', v_binding.system_id, 'fromHolder', v_binding.holder_principal_id,
                    'fromAccount', v_binding.account_id, 'fromGrant', v_binding.grant_id,
                    'toHolder', v_row.holder_principal_id, 'toAccount', v_row.account_id, 'state', 'BOUND');
            END IF;
        ELSIF v_binding.state <> 'GONE' AND v_binding.how IS DISTINCT FROM 'OPEN' THEN
            UPDATE ${flyway:defaultSchema}.conversation_binding
               SET state = 'GONE', reason = 'ACCOUNT_REMOVED', choices = '{}', waiting_since = NULL,
                   updated_at = v_now
             WHERE id = v_binding.id;
            v_systems := v_systems || jsonb_build_object(
                'systemId', v_binding.system_id, 'fromHolder', v_binding.holder_principal_id,
                'fromAccount', v_binding.account_id, 'fromGrant', v_binding.grant_id,
                'toHolder', NULL, 'toAccount', NULL, 'state', 'GONE');
        END IF;
    END LOOP;

    PERFORM set_config('tinfirm.conversation_rebind', '', true);

    RETURN jsonb_build_object(
        'author', jsonb_build_object('from', v_conversation.author_principal_id, 'to', v_trigger.author_principal_id),
        'payer', jsonb_build_object('from', v_conversation.payer_principal_id, 'to', v_trigger.payer_principal_id),
        'modelAccess', jsonb_build_object('from', v_conversation.model_access, 'to', v_settings.model_access),
        'systems', v_systems);
END
$$;
REVOKE ALL ON FUNCTION conversation_rebind(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION conversation_rebind(uuid) TO ${app_role};
