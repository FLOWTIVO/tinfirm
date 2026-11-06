-- Trigger authority (the security review of 2026-10-27, H2 and D2; the owner's decisions Q2 and Q3). The schema owner
-- runs this file.
--
--   trigger.paused_reason         gains TO_ACCEPT: a trigger an admin reassigned after its author's loss (AUTHOR_LEFT,
--                                 NOT_ENTITLED) waits for its new author to accept its accounts, its payer and the
--                                 acknowledgement of its open events, which resumes it (H2, Q2)
--   trigger_credential_exposure   the first time a trigger on a source anyone may write to names a credential lent to
--                                 a service account: whoever lent it is told in their inbox, once per trigger and
--                                 credential (D2, Q3)
--
-- The conventions of V001 apply: the rows are the company's, under row-level security, with the runtime role's grants.

-- ---- trigger.paused_reason: TO_ACCEPT -------------------------------------------------------------------------------

ALTER TABLE trigger DROP CONSTRAINT trigger_paused_reason_ck;
ALTER TABLE trigger ADD CONSTRAINT trigger_paused_reason_ck CHECK (paused_reason IS NULL OR paused_reason IN (
    'USER', 'AUTHOR_LEFT', 'NOT_ENTITLED', 'ACCOUNT_REMOVED', 'SYSTEM_REMOVED', 'ACCOUNT_BROKEN', 'SYSTEM_PAUSED',
    'TO_ACCEPT'));

-- ---- trigger_credential_exposure ------------------------------------------------------------------------------------

-- A credential a trigger open to anyone acts with, through the service account that borrows it, recorded the first
-- time a save of the trigger names it (a create, a change of its table, a reassignment, a followed lending):
--
--   service_principal_id  the service account the trigger's row acts through
--   lent_by               who lent the credential to that service account (service_account_credential.created_by at
--                         the time), told in their inbox; NULL when it was not recorded, and then the company's OWNERs
--                         and ADMINs are told
--
-- It goes with its trigger, its credential and its service account. Nothing changes a row once written.
CREATE TABLE trigger_credential_exposure (
    company_id           uuid        NOT NULL,
    trigger_id           uuid        NOT NULL,
    account_id           uuid        NOT NULL,
    service_principal_id uuid        NOT NULL,
    lent_by              uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL,
    CONSTRAINT trigger_credential_exposure_pk PRIMARY KEY (trigger_id, account_id),
    CONSTRAINT trigger_credential_exposure_trigger_fk FOREIGN KEY (trigger_id, company_id)
        REFERENCES trigger (id, company_id) ON DELETE CASCADE,
    CONSTRAINT trigger_credential_exposure_account_fk FOREIGN KEY (account_id, company_id)
        REFERENCES account (id, company_id) ON DELETE CASCADE,
    CONSTRAINT trigger_credential_exposure_service_fk FOREIGN KEY (service_principal_id, company_id)
        REFERENCES principal (id, company_id) ON DELETE CASCADE
);

CREATE INDEX trigger_credential_exposure_company_idx ON trigger_credential_exposure (company_id, created_at);
CREATE INDEX trigger_credential_exposure_account_idx ON trigger_credential_exposure (account_id);
CREATE INDEX trigger_credential_exposure_service_idx ON trigger_credential_exposure (service_principal_id);

ALTER TABLE trigger_credential_exposure ENABLE ROW LEVEL SECURITY;
ALTER TABLE trigger_credential_exposure FORCE ROW LEVEL SECURITY;

CREATE POLICY trigger_credential_exposure_tenant ON trigger_credential_exposure FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Written once (ON CONFLICT DO NOTHING) and read for the inbox; it goes only with what it names.
GRANT SELECT, INSERT ON trigger_credential_exposure TO ${app_role};
