package com.flowtivo.tinfirm.identity.internal;

import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import com.flowtivo.tinfirm.vault.InstanceKeys;

/**
 * The signing key of the authorization server's access tokens (ADR-0045): the instance key {@value #KEY_NAME} of the
 * vault, an RSA key generated on the first use and kept envelope-encrypted, never on disk or in configuration. The
 * JWK set endpoint publishes its public half under its {@code kid}; the MCP endpoint validates tokens against the same
 * source. Loaded once, on the first token or JWK set request, so the server starts without touching the vault.
 * <p>
 * Rotation is later work: a second key under a new name, both in the set, the old one retired (CONVENTIONS §9,
 * "Rotating the signing key").
 */
final class InstanceJwkSource implements JWKSource<SecurityContext> {

    static final String KEY_NAME = "oauth-signing";

    private final InstanceKeys keys;

    private volatile JWKSet jwkSet;

    InstanceJwkSource(InstanceKeys keys) {
        this.keys = keys;
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) {
        return selector.select(jwkSet());
    }

    private JWKSet jwkSet() {
        JWKSet set = jwkSet;
        if (set == null) {
            synchronized (this) {
                set = jwkSet;
                if (set == null) {
                    InstanceKeys.RsaKey key = keys.rsaKey(KEY_NAME);
                    set = new JWKSet(new RSAKey.Builder(key.publicKey()).privateKey(key.privateKey())
                        .keyID(key.kid())
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.RS256)
                        .build());
                    jwkSet = set;
                }
            }
        }
        return set;
    }
}
