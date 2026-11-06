package com.flowtivo.tinfirm.identity.internal;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The service provider's keys (ADR-0086, decision 2), read from {@link SamlProperties.Sp} once at the start: every
 * credential is published in the SP metadata and tried to decrypt, and only the active one signs. The server does not
 * start on a key or certificate it cannot read, a key that is not RSA of at least 3072 bits or EC P-256, a certificate
 * that is not the key's, no active credential, or an active certificate that has ended.
 */
public final class SamlSpCredentials {

    /** From this long before the active certificate ends, the start warns. */
    public static final Duration WARN_BEFORE_END = Duration.ofDays(30);

    private static final int MIN_RSA_BITS = 3072;

    private static final Pattern PEM = Pattern.compile("-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private static final Pattern KID = Pattern.compile("^[a-z0-9]{1,32}$");

    /** A credential: its key id, its certificate and its private key. */
    public record Credential(String kid, X509Certificate certificate, PrivateKey privateKey) {

        @Override
        public String toString() {
            return "Credential[kid=" + kid + "]";
        }
    }

    private final Credential active;

    private final List<Credential> all;

    private SamlSpCredentials(Credential active, List<Credential> all) {
        this.active = active;
        this.all = List.copyOf(all);
    }

    /** The credential that signs. */
    public Credential active() {
        return active;
    }

    /** Every credential, the active one first, then by key id. */
    public List<Credential> all() {
        return all;
    }

    /**
     * Reads the credentials, failing with the setting to fix.
     *
     * @param now the time the active certificate must not have ended by
     */
    static SamlSpCredentials read(SamlProperties.Sp sp, Instant now) {
        if (sp.credentials().isEmpty()) {
            throw new IllegalStateException("SAML providers are declared but the service provider has no key: set "
                    + "TINFIRM_AUTH_SAML_SP_CREDENTIALS_<KID>_KEY and _CERT, and TINFIRM_AUTH_SAML_SP_ACTIVE_CREDENTIAL "
                    + "(ADR-0086)");
        }
        Map<String, Credential> read = new TreeMap<>();
        for (Map.Entry<String, SamlProperties.Credential> entry : new TreeMap<>(sp.credentials()).entrySet()) {
            String kid = entry.getKey();
            String setting = "TINFIRM_AUTH_SAML_SP_CREDENTIALS_" + kid.toUpperCase(java.util.Locale.ROOT);
            if (!KID.matcher(kid).matches()) {
                throw new IllegalStateException(setting + ": a key id is 1 to 32 lower-case letters and digits");
            }
            read.put(kid, credential(kid, setting, entry.getValue()));
        }
        String activeKid = sp.activeCredential() == null ? null : sp.activeCredential().trim();
        if (activeKid == null || activeKid.isEmpty()) {
            throw new IllegalStateException("TINFIRM_AUTH_SAML_SP_ACTIVE_CREDENTIAL is not set: it names the credential "
                    + "that signs, one of " + read.keySet());
        }
        Credential active = read.get(activeKid);
        if (active == null) {
            throw new IllegalStateException("TINFIRM_AUTH_SAML_SP_ACTIVE_CREDENTIAL names " + activeKid
                    + ", which is not one of the credentials " + read.keySet());
        }
        if (!active.certificate().getNotAfter().toInstant().isAfter(now)) {
            throw new IllegalStateException("The active SAML service provider certificate " + activeKid + " ended on "
                    + active.certificate().getNotAfter().toInstant() + ": add a new credential and make it active");
        }
        List<Credential> all = new ArrayList<>();
        all.add(active);
        read.values().stream().filter(credential -> credential != active).forEach(all::add);
        return new SamlSpCredentials(active, all);
    }

    /** Whether the active certificate ends within {@link #WARN_BEFORE_END} of {@code now}. */
    boolean activeEndsSoon(Instant now) {
        return active.certificate().getNotAfter().toInstant().isBefore(now.plus(WARN_BEFORE_END));
    }

    private static Credential credential(String kid, String setting, SamlProperties.Credential configured) {
        X509Certificate certificate;
        try {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem(configured.cert(), "CERTIFICATE", setting + "_CERT")));
        }
        catch (GeneralSecurityException | ClassCastException ex) {
            throw new IllegalStateException(setting + "_CERT is not an X.509 certificate in PEM", ex);
        }
        byte[] der = pem(configured.key(), "PRIVATE KEY", setting + "_KEY");
        PrivateKey key = privateKey(der, setting);
        if (key instanceof RSAKey rsa && rsa.getModulus().bitLength() < MIN_RSA_BITS) {
            throw new IllegalStateException(setting + "_KEY is RSA of " + rsa.getModulus().bitLength()
                    + " bits: at least " + MIN_RSA_BITS);
        }
        if (key instanceof ECKey ec && ec.getParams().getCurve().getField().getFieldSize() != 256) {
            throw new IllegalStateException(setting + "_KEY is an EC key that is not P-256");
        }
        if (!matches(key, certificate.getPublicKey())) {
            throw new IllegalStateException(setting + "_CERT is not the certificate of " + setting + "_KEY");
        }
        return new Credential(kid, certificate, key);
    }

    private static PrivateKey privateKey(byte[] der, String setting) {
        for (String algorithm : List.of("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            }
            catch (GeneralSecurityException ex) {
                // The next algorithm.
            }
        }
        throw new IllegalStateException(setting + "_KEY is not a PKCS#8 RSA or EC private key");
    }

    /** Whether the certificate's public key is the private key's: a signature by one verifies with the other. */
    private static boolean matches(PrivateKey key, PublicKey publicKey) {
        if (!key.getAlgorithm().equals(publicKey.getAlgorithm())) {
            return false;
        }
        String algorithm = "RSA".equals(key.getAlgorithm()) ? "SHA256withRSA" : "SHA256withECDSA";
        byte[] probe = "tinfirm-saml-sp-credential".getBytes(StandardCharsets.US_ASCII);
        try {
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(probe);
            byte[] signed = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(signed);
        }
        catch (GeneralSecurityException ex) {
            return false;
        }
    }

    private static byte[] pem(String text, String type, String setting) {
        Matcher matcher = PEM.matcher(text.replace("\\n", "\n"));
        if (!matcher.find() || !type.equals(matcher.group(1))) {
            throw new IllegalStateException(setting + " is not a PEM " + type + " (-----BEGIN " + type + "-----)");
        }
        try {
            return Base64.getMimeDecoder().decode(matcher.group(2));
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalStateException(setting + " is not a PEM " + type, ex);
        }
    }
}
