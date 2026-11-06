package com.flowtivo.tinfirm.vault;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Keys of the instance rather than of a company (ADR-0045): the RSA key that signs the access tokens of Tinfirm's
 * OAuth authorization server, and the HMAC key a sign-in's PKCE verifier is derived with (CONVENTIONS §15.3). They are
 * kept with the same envelope encryption as company secrets (CONVENTIONS §9), rewrapped by
 * {@code POST /api/admin/vault/rewrap}, and never listed or revealed over the API. This is a trusted internal API:
 * whoever holds a signing key mints tokens for every company.
 */
public interface InstanceKeys {

    /**
     * The RSA key pair with this name ({@code [a-z][a-z0-9-]*}, at most 64 characters). The first call on an instance
     * generates a 2048-bit key, stores it and audits {@code secret.created} with target {@code instance_key} in the
     * instance chain; every later call, on any replica, returns that key.
     *
     * @throws IllegalStateException when the key of this name is not an RSA key
     */
    RsaKey rsaKey(String name);

    /**
     * The HMAC-SHA256 of the data under the key with this name ({@code [a-z][a-z0-9-]*}, at most 64 characters),
     * which never leaves the vault. The first call on an instance generates 256 random bits, stores them and audits
     * {@code secret.created} as {@link #rsaKey} does; every later call, on any replica, uses that key.
     *
     * @throws IllegalStateException when the key of this name is not an HMAC key
     */
    byte[] hmacSha256(String name, byte[] data);

    /** An RSA key pair of the instance and its JWK key id. {@link #toString()} shows the key id only. */
    record RsaKey(String kid, RSAPublicKey publicKey, RSAPrivateKey privateKey) {

        @Override
        public String toString() {
            return "RsaKey[kid=" + kid + "]";
        }
    }
}
