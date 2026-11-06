-- A pending sign-in keeps no PKCE verifier any more (CONVENTIONS §15.3). The verifier is derived from the sign-in's
-- state, which only the person's browser holds (the table keeps its SHA-256) and which comes back on the callback:
-- base64url(HMAC-SHA256(state)) under the instance key sign-in-pkce. Nothing in the database yields a verifier.
--
-- The sign-ins pending now go with their verifiers, which cannot be derived: their callbacks would fail the code
-- exchange. Without them they answer invalid_state, and the person signs in again.
DELETE FROM oauth_authorization_request;
ALTER TABLE oauth_authorization_request DROP COLUMN code_verifier;

-- The key the verifier is derived with is an instance key of its own (CONVENTIONS §9): 256 random bits for
-- HMAC-SHA256, generated on its first use and kept under the vault's envelope encryption, as the RSA signing key is.
ALTER TABLE instance_key DROP CONSTRAINT instance_key_algorithm_ck;
ALTER TABLE instance_key ADD CONSTRAINT instance_key_algorithm_ck CHECK (algorithm IN ('RSA', 'HMAC'));
