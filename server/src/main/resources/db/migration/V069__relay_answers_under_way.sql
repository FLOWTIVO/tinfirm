-- A run's settlement waits for its answers under way (the package 6 contract, §5.1; review 2026-10-27, F10). A run
-- may end while answers the relay admitted still stream (a lease lost, a control channel partitioned): the spend sync
-- settled the run two minutes after its end with the answers counted by then, and an answer that ended later updated
-- the relay's count of a run that was SETTLED, never charged. The relay now notes each request it admits on the run's
-- token, beside the count at the request's end; a run with more admitted than counted has an answer under way, and
-- the sync tries it again later, until the relay's hour settles it anyway (a replica that crashed mid-answer never
-- counts it).
--
--   admitted_count  the requests forwarded with the token, counted when they are admitted; request_count counts them
--                   at their end, so admitted_count - request_count are the answers under way

ALTER TABLE run_model_token ADD COLUMN admitted_count bigint NOT NULL DEFAULT 0;

-- The tokens from before: every request counted at its end was admitted first, so the two counts start equal and
-- a token with requests already counted shows an answer under way only for one admitted after this.
UPDATE run_model_token SET admitted_count = request_count;
