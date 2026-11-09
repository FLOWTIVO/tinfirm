package com.flowtivo.tinfirm.audit.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.jspecify.annotations.Nullable;

/**
 * The checkpoints' Ed25519 keys (ADR-0111, decision 7): the active key that signs, and every key that verifies, the
 * retired ones included. They come from the deployment's configuration, never from the database: whoever could write
 * the database must not be able to choose what the server trusts. Used by the server and by the offline tool alike.
 */
final class AnchorKeys {

    /** The DER prefix of an Ed25519 public key (X.509 SubjectPublicKeyInfo), before its 32 bytes. */
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END \\1-----");

    private final @Nullable String activeId;

    private final @Nullable PrivateKey signing;

    private final Map<String, PublicKey> verifying;

    private AnchorKeys(@Nullable String activeId, @Nullable PrivateKey signing, Map<String, PublicKey> verifying) {
        this.activeId = activeId;
        this.signing = signing;
        this.verifying = Map.copyOf(verifying);
    }

    /** The keys of the configuration: every key's public half, and the active key's private half. */
    static AnchorKeys of(AuditAnchorProperties properties) {
        Map<String, PublicKey> verifying = new LinkedHashMap<>();
        PrivateKey signing = null;
        for (Map.Entry<String, AuditAnchorProperties.Key> entry : properties.keys().entrySet()) {
            String id = entry.getKey();
            AuditAnchorProperties.Key key = entry.getValue();
            if (blank(key.privateKey()) && blank(key.publicKey())) {
                // A variable set to nothing, such as a slot of a deployment's template left empty.
                continue;
            }
            PublicKey publicKey = null;
            if (key.privateKey() != null && !key.privateKey().isBlank()) {
                PrivateKey privateKey = privateKey(key.privateKey(), id);
                publicKey = publicKeyOf(privateKey);
                if (id.equals(properties.activeKey())) {
                    signing = privateKey;
                }
            }
            if (key.publicKey() != null && !key.publicKey().isBlank()) {
                PublicKey given = publicKey(key.publicKey(), id);
                if (publicKey != null && !Arrays.equals(publicKey.getEncoded(), given.getEncoded())) {
                    throw new IllegalArgumentException("tinfirm.audit.anchor.keys." + id
                            + ": the public key is not the private key's");
                }
                publicKey = given;
            }
            if (publicKey == null) {
                throw new IllegalArgumentException("tinfirm.audit.anchor.keys." + id + " has neither a private nor a"
                        + " public key");
            }
            verifying.put(id, publicKey);
        }
        if (properties.activeKey() != null && !verifying.containsKey(properties.activeKey())) {
            throw new IllegalArgumentException("tinfirm.audit.anchor.active-key " + properties.activeKey()
                    + " is not among tinfirm.audit.anchor.keys");
        }
        return new AnchorKeys(signing != null ? properties.activeKey() : null, signing, verifying);
    }

    /** Keys that only verify: the offline tool's, given on its command line. */
    static AnchorKeys verifyingOnly(Map<String, PublicKey> keys) {
        return new AnchorKeys(null, null, keys);
    }

    /** The key id that signs; empty without one. */
    Optional<String> activeId() {
        return Optional.ofNullable(activeId);
    }

    /** Every key that verifies, by id. */
    Map<String, PublicKey> verifying() {
        return verifying;
    }

    /** Signs with the active key. */
    byte[] sign(byte[] content) {
        if (signing == null) {
            throw new IllegalStateException("No active key signs the checkpoints");
        }
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(signing);
            signature.update(content);
            return signature.sign();
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot sign a checkpoint with Ed25519", ex);
        }
    }

    /** Whether the key of that id signed the content; false for a key it does not trust. */
    boolean verify(String keyId, byte[] content, byte[] signatureBytes) {
        PublicKey key = verifying.get(keyId);
        if (key == null) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(key);
            signature.update(content);
            return signature.verify(signatureBytes);
        }
        catch (GeneralSecurityException | IllegalArgumentException ex) {
            return false;
        }
    }

    /** An Ed25519 private key from a PKCS#8 PEM ({@code PRIVATE KEY}). */
    static PrivateKey privateKey(String pem, String id) {
        try {
            return KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(der(pem, "PRIVATE KEY", id)));
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("tinfirm.audit.anchor.keys." + id
                    + ": the private key is not an unencrypted Ed25519 PKCS#8 key", ex);
        }
    }

    /** An Ed25519 public key from an X.509 PEM ({@code PUBLIC KEY}). */
    static PublicKey publicKey(String pem, String id) {
        try {
            return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(der(pem, "PUBLIC KEY", id)));
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("The public key " + id + " is not an Ed25519 X.509 key", ex);
        }
    }

    /** The public half of an Ed25519 private key, which the JDK does not derive. */
    static PublicKey publicKeyOf(PrivateKey privateKey) {
        byte[] encoded = privateKey.getEncoded();
        // An Ed25519 PKCS#8 key ends in its 32-byte seed (RFC 8410, without the optional public key).
        byte[] seed = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        byte[] raw = new Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().getEncoded();
        byte[] der = Arrays.copyOf(X509_PREFIX, X509_PREFIX.length + raw.length);
        System.arraycopy(raw, 0, der, X509_PREFIX.length, raw.length);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot derive the Ed25519 public key", ex);
        }
    }

    /** A new key pair, for {@code AuditAnchorTool keygen}. */
    static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Ed25519 is not available", ex);
        }
    }

    static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    static String publicPem(PublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static byte[] der(String pem, String type, String id) {
        // A PEM given through an environment variable may come with its line breaks as literal "\n".
        Matcher matcher = PEM.matcher(pem.replace("\\n", "\n"));
        if (!matcher.find() || !matcher.group(1).equals(type)) {
            throw new IllegalArgumentException("The key " + id + " is not a PEM \"" + type + "\"");
        }
        return Base64.getMimeDecoder().decode(matcher.group(2));
    }
}
