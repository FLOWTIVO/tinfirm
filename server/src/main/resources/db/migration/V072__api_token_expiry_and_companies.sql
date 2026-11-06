-- Personal access tokens expire and name their companies (security review of 2026-10-27, D9 and L34; the owner's
-- decision Q6; CONVENTIONS §5).
--
--   company_ids  the companies the token reaches; NULL for every company of the user, which every token made before
--                this migration keeps. Leaving a named company revokes the token (ApiTokenService).
--   expires_at   every new token expires, 90 days after its creation unless renewed (ApiTokenService.create and
--                renew set it; the column stays nullable for the rows below). A token that did not expire, or that
--                expired later than 90 days from now, expires 90 days from now, and people renew theirs on /me.
--
-- api_token stays an instance-level table (V001): no company_id column, no row-level security; the runtime role's
-- grants are unchanged.

ALTER TABLE api_token ADD COLUMN company_ids uuid[];

ALTER TABLE api_token ADD CONSTRAINT api_token_company_ids_ck CHECK (
    company_ids IS NULL
    OR (cardinality(company_ids) BETWEEN 1 AND 100 AND array_position(company_ids, NULL) IS NULL));

UPDATE api_token
   SET expires_at = now() + interval '90 days'
 WHERE revoked_at IS NULL
   AND (expires_at IS NULL OR expires_at > now() + interval '90 days');
