-- Agent memory's writes in the call journal (CONVENTIONS §15.14, §15.18; ADR-0063). The schema owner runs this file.
--
-- A run's remember was idempotent through the note row's own request_id: a later remember that replaced the note
-- overwrote it, and a forget, the owner's delete, a compaction or retention took the row away, so a request sent again
-- after that (a reconnect, another replica) found nothing and saved its note a second time. The outcome of a memory
-- tool's request is now kept apart from the notes, in package R's call journal (V026), keyed by its run and request as
-- a system tool's call: remember and forget claim their row in the transaction that writes the memory, before its
-- lock, and store the answer as it went out, so that a request sent again, at once or later, gets that answer and
-- writes nothing. It is kept for as long as a request may be sent again, by the journal's rules: the answer until the
-- run ends (a host sends a request again only while its run runs), the row TINFIRM_TOOL_JOURNAL_RETENTION after (seven
-- days), and none past its conversation's purge or erasure. A row holds the request's ids, the arguments' SHA-256 and
-- the answer ("Saved as m3."), never a note's words.
--
--   tool_call.system_id       NULL for a memory tool's call, which no system serves; every other call names its system
--   agent_memory_note         request_id and its unique index go: a note no longer records a request
--
-- The conventions of V001 apply as before: tool_call keeps its company_id, its row-level security (enabled and
-- forced, its policy on app_company_id()), its grants to the runtime role and the sweep's lookup (tool_call_due), which
-- never finds a memory tool's row RUNNING: its claim and its answer commit together.

ALTER TABLE tool_call
    ALTER COLUMN system_id DROP NOT NULL,
    ADD CONSTRAINT tool_call_system_ck
        CHECK (system_id IS NOT NULL OR (tool IN ('remember', 'forget') AND name = tool));

DROP INDEX agent_memory_note_request_uq;
ALTER TABLE agent_memory_note DROP COLUMN request_id;
