-- An admin's change of a trigger on its author's behalf (the package 4 contract, §11, open point 7): admins may edit a
-- trigger's definition, settings, account table and payer, and accept a widening, each checked for the author and
-- audited with the admin as the editor; the author gets an inbox item ("{admin} changed your trigger {name}: …").
--
--   admin_changed_at            the latest such change; the author's inbox shows it for seven days, until the author
--                               saves the trigger themselves
--   admin_changed_by            the admin's principal (NULL for an instance admin who is not a member)
--   admin_changed_what          what changed, in words: the fields, "accounts", "settings", "accepted version {n}"
ALTER TABLE trigger ADD COLUMN admin_changed_at timestamptz;
ALTER TABLE trigger ADD COLUMN admin_changed_by uuid;
ALTER TABLE trigger ADD COLUMN admin_changed_what varchar(500);
ALTER TABLE trigger ADD CONSTRAINT trigger_admin_changed_by_fk FOREIGN KEY (admin_changed_by, company_id)
    REFERENCES principal (id, company_id) ON DELETE SET NULL (admin_changed_by);
ALTER TABLE trigger ADD CONSTRAINT trigger_admin_changed_ck CHECK (
    (admin_changed_at IS NULL) = (admin_changed_what IS NULL)
    AND (admin_changed_by IS NULL OR admin_changed_at IS NOT NULL));
CREATE INDEX trigger_admin_changed_idx ON trigger (company_id, author_principal_id)
    WHERE admin_changed_at IS NOT NULL AND deleted_at IS NULL;
