-- A trigger's settings add no instructions: the trigger's own instruction is in every turn of its conversations, so a
-- second place for its author's words only split them (the settings form offers added instructions to a person alone,
-- and a save of a trigger's settings with them is 400 VALIDATION_FAILED). What a trigger's settings added before joins
-- the end of its instruction, which keeps at most its 20 000 characters, and the settings keep none.
--
-- The schema owner bypasses row-level security for this step only, as in V002: on the two tables it changes, and on
-- principal and trigger_account, which the checks of a changed trigger row read (trigger_ck, trigger_table_ck). The
-- deferred check runs before the security is forced again: it then still sees them, and ALTER TABLE refuses a table
-- with pending trigger events.
ALTER TABLE trigger NO FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_settings NO FORCE ROW LEVEL SECURITY;
ALTER TABLE trigger_account NO FORCE ROW LEVEL SECURITY;
ALTER TABLE principal NO FORCE ROW LEVEL SECURITY;

UPDATE trigger t
   SET instruction = left(btrim(t.instruction) || E'\n\n' || btrim(s.instructions), 20000)
  FROM agent_settings s
 WHERE s.trigger_id = t.id
   AND btrim(coalesce(s.instructions, '')) <> '';

UPDATE agent_settings SET instructions = NULL WHERE trigger_id IS NOT NULL AND instructions IS NOT NULL;

SET CONSTRAINTS trigger_table_ck_trg IMMEDIATE;
SET CONSTRAINTS trigger_table_ck_trg DEFERRED;

ALTER TABLE principal FORCE ROW LEVEL SECURITY;
ALTER TABLE trigger_account FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE trigger FORCE ROW LEVEL SECURITY;

ALTER TABLE agent_settings ADD CONSTRAINT agent_settings_trigger_instructions_ck
    CHECK (trigger_id IS NULL OR instructions IS NULL);
