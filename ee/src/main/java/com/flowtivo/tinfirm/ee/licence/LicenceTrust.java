package com.flowtivo.tinfirm.ee.licence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The public keys a licence key may be signed by, by key id (ADR-0077, decision 6): FLOWTIVO's, which the server
 * ships with, one file a key ({@code ee/licence-public-keys/<key id>.pub}, an Ed25519 key in X.509 PEM). A key names
 * its signer's id ({@code kid}), and one whose id is not here is refused: FLOWTIVO adds a key to rotate, and removes
 * one to retire it. Two test fixtures add one, under ids no bundled key may take, and the server refuses to start with
 * either outside the profiles that name them: a {@link TestLicenceTrust} bean (key id {@code test}), which only the
 * server's tests define ({@code test}); and {@code TINFIRM_LICENCE_TEST_PUBLIC_KEY} (key id {@code e2e}, {@code tinfirm.licence.test-public-key},
 * the PEM), for the web app's end-to-end harness, which runs the real jar ({@code e2e} or {@code test}).
 */
@Component
class LicenceTrust {

    static final String RESOURCES = "classpath*:ee/licence-public-keys/*.pub";

    static final String TEST_KEY_PROPERTY = "tinfirm.licence.test-public-key";

    /** The key id of the server tests' public key. */
    static final String TEST_KEY_ID = "test";

    /** The key id of the end-to-end harness's public key. */
    static final String E2E_KEY_ID = "e2e";

    private final Map<String, PublicKey> keys;

    LicenceTrust(Environment environment, ObjectProvider<TestLicenceTrust> test,
            @Value("${" + TEST_KEY_PROPERTY + ":}") @Nullable String testPublicKey) {
        Map<String, PublicKey> trusted = new LinkedHashMap<>(bundled());
        if (testPublicKey != null && !testPublicKey.isBlank()) {
            if (!environment.acceptsProfiles(Profiles.of("e2e | test"))) {
                throw new IllegalStateException("TINFIRM_LICENCE_TEST_PUBLIC_KEY is set outside the e2e and test "
                        + "profiles: it is for the end-to-end tests only, and a licence key is verified against "
                        + "FLOWTIVO's public keys alone");
            }
            trusted.put(E2E_KEY_ID, publicKey(testPublicKey));
        }
        TestLicenceTrust extra = test.getIfAvailable();
        if (extra != null) {
            if (!environment.acceptsProfiles(Profiles.of("test"))) {
                throw new IllegalStateException("A TestLicenceTrust bean is defined outside the test profile: it is "
                        + "for the tests only, and a licence key is verified against FLOWTIVO's public keys alone");
            }
            trusted.put(TEST_KEY_ID, extra.publicKey());
        }
        this.keys = Map.copyOf(trusted);
    }

    /** The trusted public keys, by key id. */
    Map<String, PublicKey> keys() {
        return keys;
    }

    /** FLOWTIVO's public keys, from the files the build bundles, each by its file's name. */
    static Map<String, PublicKey> bundled() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver(LicenceTrust.class.getClassLoader())
                .getResources(RESOURCES);
        }
        catch (IOException ex) {
            throw new IllegalStateException("The licence's public keys cannot be listed", ex);
        }
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (Resource resource : resources) {
            String name = resource.getFilename();
            String id = name == null ? "" : name.substring(0, name.length() - ".pub".length());
            if (!LicenceKeyFormat.KEY_ID.matcher(id).matches() || Set.of(TEST_KEY_ID, E2E_KEY_ID).contains(id)) {
                throw new IllegalStateException("The licence's public key " + name + " is not named <key id>.pub, "
                        + "the key id up to 64 lower-case letters, digits or '-', neither test nor e2e");
            }
            try (InputStream in = resource.getInputStream()) {
                if (keys.put(id, publicKey(new String(in.readAllBytes(), StandardCharsets.US_ASCII))) != null) {
                    throw new IllegalStateException("The licence's public key " + id + " is bundled twice");
                }
            }
            catch (IOException ex) {
                throw new IllegalStateException("The licence's public key " + id + " cannot be read", ex);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("No licence public key is in the build (ee/licence-public-keys/*.pub)");
        }
        return keys;
    }

    /** An Ed25519 public key from its X.509 PEM. */
    static PublicKey publicKey(String pem) {
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(base64)));
        }
        catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("The licence's public key is not an Ed25519 key in X.509 PEM", ex);
        }
    }
}
