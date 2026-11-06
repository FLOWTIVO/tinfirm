-- A Claude subscription on a personal runner host is a login, not a token (ADR-0085; CONVENTIONS §7, §15.4). The
-- schema owner runs this file.
--
-- The runner host no longer holds a `claude setup-token` token: `credential add claude` runs Claude Code's own sign-in
-- into a directory of the host, which the host mounts into the sandbox and never reads. The host reports such a
-- credential as CLAUDE_LOGIN. A credential of the kind before, CLAUDE_OAUTH_TOKEN, comes from a host that still holds
-- a token: it is removed here, and the host reports its credentials again in its next hello once it is upgraded.

-- The schema owner bypasses row-level security for the one statement, as in V033: the table is forced, so without
-- this the delete would touch no row of any company, and the constraint below would then refuse.
ALTER TABLE runner_credential NO FORCE ROW LEVEL SECURITY;
DELETE FROM runner_credential WHERE kind = 'CLAUDE_OAUTH_TOKEN';
ALTER TABLE runner_credential FORCE ROW LEVEL SECURITY;

ALTER TABLE runner_credential DROP CONSTRAINT runner_credential_kind_ck;
ALTER TABLE runner_credential
    ADD CONSTRAINT runner_credential_kind_ck CHECK (kind IN ('CLAUDE_LOGIN', 'CODEX_CHATGPT_AUTH',
                                                             'ANTHROPIC_API_KEY', 'OPENAI_API_KEY'));
