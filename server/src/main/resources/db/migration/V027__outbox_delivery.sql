-- The write-back's delivery (the package 4 contract, docs/reviews/2026-10-12-package-4-contract.md §5.8, §5.9 and
-- T14): the writes of a result under review, kept exactly as the team was shown them until it decides.
--
--   outbox_review  per review of a turn's result (a decision of subject RESULT), the rows its approval queues: the
--                  RESULT comment and the ON_SUCCESS actions, exactly as the decision's writes show them. Approved, the
--                  rows are queued in the outbox and this row goes; returned, or once the conversation ended (which
--                  cancels the decision), it goes and nothing is queued. Nothing of a result under review is in the
--                  outbox (T14: RESULT and ON_SUCCESS rows are queued only on APPROVED).
--
-- The conventions of V001 apply: company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on app_company_id(),
-- composite foreign keys with the company id, and grants to the runtime role ${app_role}.

CREATE TABLE outbox_review (
    decision_id     uuid          PRIMARY KEY,
    company_id      uuid          NOT NULL,
    conversation_id uuid          NOT NULL,
    run_id          uuid          NOT NULL,
    writes          jsonb         NOT NULL,
    created_at      timestamptz   NOT NULL,
    CONSTRAINT outbox_review_decision_fk FOREIGN KEY (decision_id, company_id)
        REFERENCES decision (id, company_id),
    CONSTRAINT outbox_review_conversation_fk FOREIGN KEY (conversation_id, company_id)
        REFERENCES conversation (id, company_id),
    CONSTRAINT outbox_review_run_fk FOREIGN KEY (run_id, company_id) REFERENCES run (id, company_id),
    -- At most 1 comment, 10 actions, the conversation's link and the turn's documents (at most 50 a conversation).
    CONSTRAINT outbox_review_writes_ck CHECK (jsonb_typeof(writes) = 'array' AND jsonb_array_length(writes) BETWEEN 1 AND 100)
);

CREATE INDEX outbox_review_conversation_idx ON outbox_review (conversation_id);
CREATE INDEX outbox_review_run_idx ON outbox_review (run_id);

ALTER TABLE outbox_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_review FORCE ROW LEVEL SECURITY;

CREATE POLICY outbox_review_tenant ON outbox_review FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Written once, taken once (its approval, return or the conversation's end deletes it).
GRANT SELECT, INSERT, DELETE ON outbox_review TO ${app_role};
