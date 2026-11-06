-- The previews of retention and erasure (CONVENTIONS §15.11). The schema owner runs this file.
--
-- An erasure's dry run counts with the erasure's own queries, and a preview of the periods with the sweep's, on every
-- pause in the Owner's typing. Indexes only: no table, column or grant changes.

-- What a member who left decided and added in others' conversations: an erasure of them, its dry run and
-- ConversationPurge.authoredIn look them up by the person. Before, each read every decision and file of the company;
-- the user's deletion (ON DELETE SET NULL) reads the files by the user too.
CREATE INDEX decision_decided_by_principal_idx ON decision (decided_by_principal_id)
    WHERE decided_by_principal_id IS NOT NULL;
CREATE INDEX attachment_uploaded_by_idx ON attachment (uploaded_by_user_id)
    WHERE uploaded_by_user_id IS NOT NULL;

-- The ended conversations by their end, whatever they keep: the sweep's selection of each kind (ConversationPurge.due),
-- which V023's conversation_content_kept_idx serves only for conversations whose content is kept, reads the company's
-- conversations ended before a cutoff, and a preview reads it for the proposed and the stored periods.
CREATE INDEX conversation_ended_idx ON conversation (company_id, ended_at, id) WHERE state = 'ENDED';
