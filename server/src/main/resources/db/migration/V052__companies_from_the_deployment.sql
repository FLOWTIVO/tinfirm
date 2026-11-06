-- Companies and their sign-in come from the deployment (ADR-0075; CONVENTIONS §5, §7). The schema owner runs this
-- file.
--
-- A company is declared by a key in the deployment's configuration (TINFIRM_COMPANIES_<KEY>_*), which the server
-- creates or adopts at its start; people sign in through the deployment's OIDC providers, each of which may name the
-- declared company its new users join. Companies' own identity providers, which an OWNER configured in the app
-- (ADR-0045), go: their settings, their claimed email domains, the client secrets in the companies' vaults, and the
-- company a user created through one belonged to.
--
--   company.key   the key the deployment declares the company by: 1 to 40 lower-case letters and digits, starting
--                 with a letter, unique; NULL for a company from before keys until a declaration adopts it

-- ---- the companies' own identity providers go -------------------------------------------------------------------

-- Their client secrets, with their versions (secret_version, ON DELETE CASCADE). The schema owner bypasses row-level
-- security for this step only, as in V033.
ALTER TABLE secret NO FORCE ROW LEVEL SECURITY;
ALTER TABLE company_identity_provider NO FORCE ROW LEVEL SECURITY;

DELETE FROM secret s
 USING company_identity_provider p
 WHERE s.id = p.secret_id
   AND s.company_id = p.company_id;

ALTER TABLE secret FORCE ROW LEVEL SECURITY;

DROP FUNCTION company_identity_provider_of_domain(text);
DROP FUNCTION company_identity_provider_of_company(uuid);
DROP TABLE company_identity_provider_domain;
DROP TABLE company_identity_provider;

-- A user that a company's provider created no longer belongs to that company: any company may add them as a member.
DROP INDEX app_user_provisioned_by_company_idx;
ALTER TABLE app_user DROP CONSTRAINT app_user_provisioned_by_company_ck;
ALTER TABLE app_user DROP COLUMN provisioned_by_company_id;

-- ---- the declared company's key ---------------------------------------------------------------------------------

ALTER TABLE company ADD COLUMN key varchar(40);
ALTER TABLE company ADD CONSTRAINT company_key_uq UNIQUE (key);
ALTER TABLE company ADD CONSTRAINT company_key_ck CHECK (key ~ '^[a-z][a-z0-9]{0,39}$');
