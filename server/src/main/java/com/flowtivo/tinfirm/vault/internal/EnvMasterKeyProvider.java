package com.flowtivo.tinfirm.vault.internal;

import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flowtivo.tinfirm.shared.config.PublishedDevSecrets;
import com.flowtivo.tinfirm.vault.MasterKeyProvider;

/**
 * Master keys from {@code TINFIRM_VAULT_KEYS} ({@code keyId:base64(32 bytes)}, comma-separated) and
 * {@code TINFIRM_VAULT_ACTIVE_KEY}. The constructor validates everything, so a missing or malformed configuration
 * stops the server at startup. The development key that {@code deploy/compose/.env.example} publishes is refused as
 * the active key unless {@code TINFIRM_PUBLIC_URL} is a localhost URL ({@link PublishedDevSecrets}; L17). It is still
 * read as a retired key, with a warning at every start, so that an installation that ran on it can rewrap its data to
 * a key of its own and then remove it.
 */
final class EnvMasterKeyProvider implements MasterKeyProvider {

    static final int KEY_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(EnvMasterKeyProvider.class);

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final Map<String, SecretKey> keys;

    private final String activeKeyId;

    /** For tests: the keys of a deployment on localhost. */
    EnvMasterKeyProvider(@Nullable String keysSpec, @Nullable String activeKeyId) {
        this(keysSpec, activeKeyId, URI.create("http://localhost:8080"));
    }

    EnvMasterKeyProvider(@Nullable String keysSpec, @Nullable String activeKeyId, URI publicUrl) {
        Set<String> published = new HashSet<>();
        this.keys = Map.copyOf(parse(keysSpec, published));
        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new VaultConfigurationException("TINFIRM_VAULT_ACTIVE_KEY is not set");
        }
        String active = activeKeyId.trim();
        if (!keys.containsKey(active)) {
            throw new VaultConfigurationException(
                    "TINFIRM_VAULT_ACTIVE_KEY '" + active + "' is not one of the keys in TINFIRM_VAULT_KEYS");
        }
        if (published.contains(active) && !PublishedDevSecrets.localUrl(publicUrl)) {
            throw new VaultConfigurationException("Key '" + active + "' in TINFIRM_VAULT_KEYS is the development key "
                    + "published in deploy/compose/.env.example, and TINFIRM_PUBLIC_URL is not a localhost URL: make a "
                    + "key of your own the active one (`openssl rand -base64 32`), keep this one until the rewrap, "
                    + "then remove it");
        }
        published.stream().filter(keyId -> !keyId.equals(active)).forEach(keyId -> log.warn("Key '{}' in "
                + "TINFIRM_VAULT_KEYS is the development key published in deploy/compose/.env.example: rewrap the "
                + "vault and remove it", keyId));
        this.activeKeyId = active;
    }

    /** The keys by id; the ids of those that are the published development key go into {@code published}. */
    private static Map<String, SecretKey> parse(@Nullable String keysSpec, Set<String> published) {
        if (keysSpec == null || keysSpec.isBlank()) {
            throw new VaultConfigurationException(
                    "TINFIRM_VAULT_KEYS is not set; expected comma-separated keyId:base64(32 bytes)");
        }
        Map<String, SecretKey> parsed = new LinkedHashMap<>();
        String[] entries = keysSpec.split(",");
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i].trim();
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                throw new VaultConfigurationException("Entry " + (i + 1)
                        + " of TINFIRM_VAULT_KEYS is malformed; expected keyId:base64(32 bytes)");
            }
            String keyId = entry.substring(0, colon).trim();
            if (!KEY_ID.matcher(keyId).matches()) {
                throw new VaultConfigurationException("Entry " + (i + 1)
                        + " of TINFIRM_VAULT_KEYS has an invalid key id; use 1-64 characters [A-Za-z0-9._-]");
            }
            if (parsed.containsKey(keyId)) {
                throw new VaultConfigurationException("Key id '" + keyId + "' appears twice in TINFIRM_VAULT_KEYS");
            }
            byte[] material;
            try {
                material = Base64.getDecoder().decode(entry.substring(colon + 1).trim());
            }
            catch (IllegalArgumentException ex) {
                throw new VaultConfigurationException("Key '" + keyId + "' in TINFIRM_VAULT_KEYS is not valid base64");
            }
            try {
                if (material.length != KEY_BYTES) {
                    throw new VaultConfigurationException("Key '" + keyId + "' in TINFIRM_VAULT_KEYS must be "
                            + KEY_BYTES + " bytes (256 bits), but is " + material.length + " bytes");
                }
                if (PublishedDevSecrets.isPublishedVaultKey(material)) {
                    published.add(keyId);
                }
                parsed.put(keyId, new SecretKeySpec(material, "AES"));
            }
            finally {
                Arrays.fill(material, (byte) 0);
            }
        }
        return parsed;
    }

    @Override
    public String activeKeyId() {
        return activeKeyId;
    }

    @Override
    public Set<String> keyIds() {
        return keys.keySet();
    }

    @Override
    public SecretKey key(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new IllegalArgumentException("Master key '" + keyId + "' is not configured");
        }
        return key;
    }

    @Override
    public String toString() {
        return "EnvMasterKeyProvider[keyIds=" + keys.keySet() + ", active=" + activeKeyId + "]";
    }
}
