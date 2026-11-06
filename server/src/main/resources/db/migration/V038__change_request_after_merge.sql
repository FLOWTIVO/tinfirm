-- A new change request after a merged one (CONVENTIONS §15.7). The schema owner runs this file.
--
-- A conversation had one change request per repository for good, so a turn that pushed new commits after it was
-- merged opened none. Now at most one per conversation and repository is not MERGED: the one later pushes go to. A
-- push with commits the merged one does not have opens the next, and the merged ones stay as the conversation's
-- outputs.
ALTER TABLE run_change_request DROP CONSTRAINT run_change_request_repository_uq;
CREATE UNIQUE INDEX run_change_request_current_uq ON run_change_request (conversation_id, repository_id)
    WHERE state IS DISTINCT FROM 'MERGED';
CREATE INDEX run_change_request_conversation_idx ON run_change_request (conversation_id, repository_id, created_at);

-- head_sha: its head commit as last read from the code host (GitHub's head.sha, GitLab's sha); for a MERGED one the
-- commit it was merged at, which a later push to its branch does not move. NULL before a read that had one: a MERGED
-- change request of an open conversation without it is read once more when the conversation pushes again.
ALTER TABLE run_change_request
    ADD COLUMN head_sha varchar(64),
    ADD CONSTRAINT run_change_request_head_sha_ck CHECK (head_sha IS NULL OR head_sha ~ '^[0-9a-f]{7,64}$');
