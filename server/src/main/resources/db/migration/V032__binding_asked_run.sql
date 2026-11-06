-- The run whose tool call or workspace first asked for a conversation's binding, so
-- that the conversation page places the binding's Connect card in that turn. Set once, when the binding is made by a
-- run (a tool call; a repository of the turn's workspace, once the turn's run exists), and never changed. NULL for a
-- binding no run asked for: a trigger's FIXED bindings, made at its start, and the bindings made before this column.
-- No foreign key, as for the binding's other references: a binding is its conversation's history.
ALTER TABLE conversation_binding ADD COLUMN run_id uuid;
