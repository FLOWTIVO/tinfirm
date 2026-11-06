-- A SAML provider's entity ID is pinned (H4 of the security review of 2026-10-27). The identity of a SAML user is (the
-- IdP's entity ID, the subject), and the keys that sign for it come from the provider's own metadata document, so a
-- document must never name another IdP than the one the provider was first read as, nor another provider's IdP.
--
-- saml_idp_metadata.entity_id (V063), the entity ID of the provider's last good document, is the pin. Without
-- _ENTITY_ID, a later document that names another IdP is refused with the reason entity_id_changed (audited
-- saml.idp_entity_id_changed); a document that names the IdP of another provider reading other metadata, or an OIDC
-- provider's issuer, is refused with entity_id_in_use. Both are a failed read's reasons, kept beside the last good
-- document, which stays in use. _ENTITY_ID, where set, is the pin instead.

ALTER TABLE saml_idp_metadata DROP CONSTRAINT saml_idp_metadata_last_error_ck;

ALTER TABLE saml_idp_metadata ADD CONSTRAINT saml_idp_metadata_last_error_ck CHECK (last_error IS NULL
    OR last_error IN ('unreachable', 'invalid', 'no_idp', 'expired', 'too_large', 'entity_id_changed',
                      'entity_id_in_use'));
