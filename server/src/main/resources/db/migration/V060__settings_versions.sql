-- The company's settings rows carry a version, for optimistic locking (CONVENTIONS §15.15): a change names the version
-- it read, and one made meanwhile is refused with 409 SETTINGS_CHANGED instead of written over. A company without a
-- row is at version 0; a row starts at 1 and every write adds one. The schema owner runs this file.

ALTER TABLE trigger_setting ADD COLUMN version integer NOT NULL DEFAULT 1;
ALTER TABLE chat_setting ADD COLUMN version integer NOT NULL DEFAULT 1;
