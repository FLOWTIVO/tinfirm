-- A run's byte budget for its events (the security review of 2026-10-27, M4): the bytes of the event payloads the run
-- stored, as UTF-8 JSON. Past tinfirm.runner.events-max-bytes (64 MiB by default) the server stores one LOG warning,
-- drops the run's later LOG events and refuses its other events, on every replica and after a reconnect. Runs that
-- exist start at 0: their events stored so far do not count.
--
-- The column joins the table of V004; its grants and row-level security apply.

ALTER TABLE run
    ADD COLUMN event_bytes bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT run_event_bytes_ck CHECK (event_bytes >= 0);
