-- A member's dismissals of the notices of their inbox (CONVENTIONS §15.7): a notice the member read and put away, for
-- themselves alone, by its key. The key holds the notice's condition (a budget's threshold and period, a month, a
-- trigger's pause), so the notice comes back once the condition changes and its key with it.
--
--   inbox_dismissal  one notice a member dismissed
--
-- The conventions of V001 apply: company_id, ENABLE plus FORCE ROW LEVEL SECURITY, a policy on app_company_id(),
-- composite foreign keys with the company id, and grants to ${app_role}.

-- Only a member's principal (principal_kind): a service identity reads no inbox, and an instance admin who is not a
-- member dismisses nothing. A member's principal is never deleted (it stays as LEFT), so the rows go when their member
-- leaves (the conversation module, on PrincipalLeft) and with the person's erasure; the cascade is the safety net. The
-- daily retention sweep deletes the rows older than 400 days (inboxDismissals).
CREATE TABLE inbox_dismissal (
    company_id     uuid         NOT NULL,
    principal_id   uuid         NOT NULL,
    principal_kind varchar(8)   NOT NULL DEFAULT 'MEMBER',
    notice_key     varchar(200) NOT NULL,
    dismissed_at   timestamptz  NOT NULL,
    CONSTRAINT inbox_dismissal_pk PRIMARY KEY (principal_id, notice_key),
    CONSTRAINT inbox_dismissal_kind_ck CHECK (principal_kind = 'MEMBER'),
    CONSTRAINT inbox_dismissal_key_ck CHECK (notice_key ~ '^[A-Za-z0-9_.:-]{1,200}$'),
    CONSTRAINT inbox_dismissal_principal_fk FOREIGN KEY (principal_id, company_id, principal_kind)
        REFERENCES principal (id, company_id, kind) ON DELETE CASCADE
);

-- The daily sweep's rows past their time.
CREATE INDEX inbox_dismissal_company_idx ON inbox_dismissal (company_id, dismissed_at);

ALTER TABLE inbox_dismissal ENABLE ROW LEVEL SECURITY;
ALTER TABLE inbox_dismissal FORCE ROW LEVEL SECURITY;

CREATE POLICY inbox_dismissal_tenant ON inbox_dismissal FOR ALL
    USING (company_id = app_company_id())
    WITH CHECK (company_id = app_company_id());

-- Written once and removed, never changed.
GRANT SELECT, INSERT, DELETE ON inbox_dismissal TO ${app_role};
