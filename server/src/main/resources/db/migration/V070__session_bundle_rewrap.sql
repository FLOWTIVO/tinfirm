-- The vault's rewrap covers the session bundles' data keys (ADR-0101). Each bundle's data key is wrapped under the
-- vault's master key (V066: key_id, wrapped_key, key_nonce); the rewrap (POST /api/admin/vault/rewrap) wraps it again
-- under the active master key, as it does the secrets' keys, so that a retired master key can go once the rewrap ran.
--
-- The rewrap runs across companies in the SYSTEM tenant context (V016), which the tenant policy now admits, as the
-- secrets' do. The runtime role may change the three columns of a bundle's wrapping only: a bundle is still replaced as
-- a whole and never changed in place otherwise.

ALTER POLICY session_bundle_tenant ON session_bundle
    USING (company_id = app_company_id() OR app_is_system())
    WITH CHECK (company_id = app_company_id() OR app_is_system());

GRANT UPDATE (key_id, wrapped_key, key_nonce) ON session_bundle TO ${app_role};

-- The rewrap's lookup: the bundles not wrapped under the active key.
CREATE INDEX session_bundle_key_idx ON session_bundle (key_id);
