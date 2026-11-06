-- A system's last test, and the count of a webhook system's refusals (CONVENTIONS §15.2, §15.17). The schema owner
-- runs this file.
--
-- The system page showed the last test only from the visit that ran it: the server kept its outcome as the system's
-- status, but neither when it ran nor who ran it. A test now stores itself on the system, replacing the one before:
--
--   last_tested_at              when the test ended
--   last_test_ok                whether it passed
--   last_test_error             why it failed (at most 500 characters, as last_error); NULL when it passed
--   last_test_with_account      whether it reached the system with an account (the tester's own; a chat system's
--                               bot), as the test's answer said
--   last_tested_by_principal_id who ran it: their member principal; NULL for an instance admin who is not a member,
--                               once the principal is gone, and once a person's erasure forgot them. Never a name:
--                               the name is read when shown, so nothing here keeps it
--   last_test_account_id        the tester's own account it used; NULL without one, and once that account is gone
--
-- The conventions of V001 apply: the row is the company's (company_id, row-level security as before), each foreign key
-- with the company id, and the runtime role's grants on external_system stay as they are.

ALTER TABLE external_system
    ADD COLUMN last_tested_at timestamptz,
    ADD COLUMN last_test_ok boolean,
    ADD COLUMN last_test_error varchar(500),
    ADD COLUMN last_test_with_account boolean,
    ADD COLUMN last_tested_by_principal_id uuid,
    ADD COLUMN last_test_account_id uuid,
    ADD CONSTRAINT external_system_last_test_ck CHECK (
        (last_tested_at IS NULL AND last_test_ok IS NULL AND last_test_error IS NULL
         AND last_test_with_account IS NULL AND last_tested_by_principal_id IS NULL AND last_test_account_id IS NULL)
        OR (last_tested_at IS NOT NULL AND last_test_ok IS NOT NULL AND last_test_with_account IS NOT NULL
            AND (last_test_error IS NULL) = last_test_ok
            AND (last_test_account_id IS NULL OR last_test_with_account))),
    ADD CONSTRAINT external_system_last_tested_by_fk FOREIGN KEY (last_tested_by_principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE SET NULL (last_tested_by_principal_id),
    ADD CONSTRAINT external_system_last_test_account_fk FOREIGN KEY (last_test_account_id, company_id)
        REFERENCES account (id, company_id) ON DELETE SET NULL (last_test_account_id);

-- The foreign keys' deletes, and an erasure's forgetting of a tester.
CREATE INDEX external_system_last_tested_by_idx ON external_system (last_tested_by_principal_id)
    WHERE last_tested_by_principal_id IS NOT NULL;
CREATE INDEX external_system_last_test_account_idx ON external_system (last_test_account_id)
    WHERE last_test_account_id IS NOT NULL;

-- A webhook system's refusals over the last 7 days, for its deliveries' page: webhook.refused is recorded at most once
-- a minute per system, reason and replica, with the count since (§8), so the sum over a system's week reads only its
-- own rows of this index, never the company's whole chain.
CREATE INDEX audit_event_webhook_refused_idx ON audit_event (company_id, target_id, occurred_at)
    WHERE action = 'webhook.refused';
