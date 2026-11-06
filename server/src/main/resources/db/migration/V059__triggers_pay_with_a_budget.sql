-- A trigger pays with its payer's budget, never a subscription, unless the company allows it (ADR-0085, decision 7).
-- The schema owner runs this file.
--
-- A subscription serves its member's own conversations: a trigger fires with nobody present, and its work goes to its
-- audience, which the vendors' terms for a personal plan do not cover. The company's switch, trigger_setting.
-- subscriptions, is off by default: the company that turns it on takes that reading of the terms on itself. Every
-- company starts off, so the settings of every trigger go to the budget, and so do the open conversations of triggers
-- and their queued runs, whose model access is otherwise fixed for their life (conversation_fixed_ck): the trigger is
-- off for the one statement. Ended conversations and finished runs keep what paid them. A trigger whose budget's
-- settings name no model, or one its payer's key does not allow, pauses at its next firing (MODEL_REQUIRED,
-- MODEL_NOT_ALLOWED), as any budget's trigger does, and its author sets the model.

ALTER TABLE trigger_setting ADD COLUMN subscriptions boolean NOT NULL DEFAULT false;

-- The schema owner bypasses row-level security for this step only, as in V033 and V050: the three tables are forced,
-- so without this the updates would touch no row of any company. No trigger of theirs queues a deferred event on an
-- update (conversation's and run's constraint triggers fire on an insert), so the security is forced again at once.
ALTER TABLE agent_settings NO FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation NO FORCE ROW LEVEL SECURITY;
ALTER TABLE run NO FORCE ROW LEVEL SECURITY;

UPDATE agent_settings
   SET model_access = 'BUDGET', subscription_host_id = NULL, subscription_label = NULL, updated_at = now()
 WHERE trigger_id IS NOT NULL AND model_access = 'SUBSCRIPTION';

ALTER TABLE conversation DISABLE TRIGGER conversation_fixed_ck_trg;
UPDATE conversation
   SET model_access = 'BUDGET', subscription_host_id = NULL, subscription_label = NULL, updated_at = now()
 WHERE trigger_id IS NOT NULL AND model_access = 'SUBSCRIPTION' AND state <> 'ENDED';
ALTER TABLE conversation ENABLE TRIGGER conversation_fixed_ck_trg;

UPDATE run r
   SET model_access = 'BUDGET', subscription_host_id = NULL, subscription_label = NULL
  FROM conversation c
 WHERE r.conversation_id = c.id AND c.trigger_id IS NOT NULL
   AND r.model_access = 'SUBSCRIPTION' AND r.status = 'QUEUED';

-- Nothing of a trigger pays with a subscription any more: checked while the owner still sees every company's rows.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM agent_settings WHERE trigger_id IS NOT NULL AND model_access = 'SUBSCRIPTION')
       OR EXISTS (SELECT 1 FROM conversation
                   WHERE trigger_id IS NOT NULL AND model_access = 'SUBSCRIPTION' AND state <> 'ENDED')
       OR EXISTS (SELECT 1 FROM run r JOIN conversation c ON c.id = r.conversation_id
                   WHERE c.trigger_id IS NOT NULL AND r.model_access = 'SUBSCRIPTION' AND r.status = 'QUEUED') THEN
        RAISE EXCEPTION 'V059: a trigger still pays with a subscription';
    END IF;
END
$$;

ALTER TABLE run FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_settings FORCE ROW LEVEL SECURITY;
