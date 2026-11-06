-- A system's credential may be an OAuth client's own credentials (kind CLIENT): an Atlassian service account's OAuth
-- 2.0 credential on Jira Cloud, which mints its access tokens with the client_credentials grant, without a sign-in or
-- a refresh token. Its secret holds the client's id and secret, and the access token it minted last. Only a system's
-- credential is one (principal_kind SYSTEM), never a member's own account. The schema owner runs this file.

ALTER TABLE account DROP CONSTRAINT account_kind_ck;
ALTER TABLE account
    ADD CONSTRAINT account_kind_ck CHECK (kind IN ('TOKEN', 'OAUTH', 'CLIENT', 'LINK'));

ALTER TABLE account DROP CONSTRAINT account_holder_ck;
ALTER TABLE account
    ADD CONSTRAINT account_holder_ck CHECK (
        principal_kind IN ('MEMBER', 'SYSTEM')
        AND (principal_kind = 'SYSTEM') = (principal_id IS NULL)
        AND (principal_kind = 'SYSTEM') = (name IS NOT NULL)
        AND (name IS NULL OR length(btrim(name)) BETWEEN 1 AND 100)
        AND (enabled OR principal_kind = 'SYSTEM')
        AND (principal_kind <> 'SYSTEM' OR kind IN ('TOKEN', 'OAUTH', 'CLIENT'))
        AND (kind <> 'CLIENT' OR principal_kind = 'SYSTEM'));
