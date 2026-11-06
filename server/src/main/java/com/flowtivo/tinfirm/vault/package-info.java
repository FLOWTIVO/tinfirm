/**
 * Credential vault (CONVENTIONS §9): envelope encryption with AES-256-GCM, master keys behind
 * {@link com.flowtivo.tinfirm.vault.MasterKeyProvider}, write-only secrets over HTTP, and the internal
 * {@link com.flowtivo.tinfirm.vault.VaultService} for audited reveal and key rewrap,
 * {@link com.flowtivo.tinfirm.vault.SecretWriter} for secrets another module's form writes (a system's OAuth client
 * secret) and for the secrets a person owns (the token of a member's own account on a
 * connection, ADR-0049 §5: never shown by the Secrets API, revealed only for their owner with
 * {@link com.flowtivo.tinfirm.vault.VaultService#revealOwned}), and the keys of the instance
 * ({@link com.flowtivo.tinfirm.vault.InstanceKeys}: the signing key of the OAuth authorization server, ADR-0045, and
 * the key a sign-in's PKCE verifier is derived with), and {@link com.flowtivo.tinfirm.vault.DataKeys}: data keys,
 * wrapped under the master key, for content another module encrypts itself (the session bundles, ADR-0097).
 * Port: {@link com.flowtivo.tinfirm.vault.SecretKeeper} (what keeps a managed secret, which the Secrets API then does
 * not delete), implemented by the account and system modules.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Vault")
package com.flowtivo.tinfirm.vault;
