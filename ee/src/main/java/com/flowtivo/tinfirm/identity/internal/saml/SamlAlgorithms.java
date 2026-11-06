package com.flowtivo.tinfirm.identity.internal.saml;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.xml.security.signature.XMLSignature;
import org.jspecify.annotations.Nullable;
import org.opensaml.saml.saml2.encryption.Decrypter;
import org.opensaml.xmlsec.DecryptionParameters;
import org.opensaml.xmlsec.encryption.EncryptedData;
import org.opensaml.xmlsec.encryption.EncryptedKey;
import org.opensaml.xmlsec.encryption.EncryptedType;
import org.opensaml.xmlsec.encryption.support.ChainingEncryptedKeyResolver;
import org.opensaml.xmlsec.encryption.support.EncryptedKeyResolver;
import org.opensaml.xmlsec.encryption.support.EncryptionConstants;
import org.opensaml.xmlsec.encryption.support.InlineEncryptedKeyResolver;
import org.opensaml.xmlsec.encryption.support.SimpleRetrievalMethodEncryptedKeyResolver;
import org.opensaml.saml.saml2.encryption.EncryptedElementTypeEncryptedKeyResolver;
import org.opensaml.saml.saml2.core.EncryptedElementType;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.impl.SignatureImpl;
import org.opensaml.xmlsec.signature.support.SignatureConstants;

/**
 * The algorithms Tinfirm accepts in a SAML response (ADR-0086, decisions 8 and 9), set explicitly rather than left to
 * OpenSAML's defaults: signatures RSA-SHA256 or stronger, or ECDSA, with SHA-256 or stronger digests, so SHA-1 and MD5
 * are refused; key transport RSA-OAEP only, so RSA PKCS#1 v1.5 is refused; content AES-GCM or AES-CBC. A response that
 * uses another is refused as {@code weak_algorithm}.
 */
final class SamlAlgorithms {

    static final Set<String> SIGNATURE = Set.of(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256,
            SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA384, SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA512,
            SignatureConstants.ALGO_ID_SIGNATURE_ECDSA_SHA256, SignatureConstants.ALGO_ID_SIGNATURE_ECDSA_SHA384,
            SignatureConstants.ALGO_ID_SIGNATURE_ECDSA_SHA512);

    static final Set<String> DIGEST = Set.of(SignatureConstants.ALGO_ID_DIGEST_SHA256,
            SignatureConstants.ALGO_ID_DIGEST_SHA384, SignatureConstants.ALGO_ID_DIGEST_SHA512);

    static final Set<String> KEY_TRANSPORT = Set.of(EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSAOAEP,
            EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSAOAEP11);

    static final Set<String> CONTENT = Set.of(EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES128,
            EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES192, EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES256,
            EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES128_GCM, EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES192_GCM,
            EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES256_GCM);

    /** Spring Security's chain of resolvers, with which IdPs place the encrypted key. */
    static final EncryptedKeyResolver ENCRYPTED_KEYS = new ChainingEncryptedKeyResolver(
            List.of(new InlineEncryptedKeyResolver(), new EncryptedElementTypeEncryptedKeyResolver(),
                    new SimpleRetrievalMethodEncryptedKeyResolver()));

    private SamlAlgorithms() {
    }

    /**
     * Refuses a signature whose method or any digest is not accepted. An unsigned element passes: whether it must be
     * signed is another check.
     */
    static void requireStrong(@Nullable Signature signature) {
        if (signature == null) {
            return;
        }
        String method = signature.getSignatureAlgorithm();
        if (method == null || !SIGNATURE.contains(method)) {
            throw SamlRefusal.of("weak_algorithm", "the signature method " + method + " is not accepted");
        }
        XMLSignature xml = signature instanceof SignatureImpl impl ? impl.getXMLSignature() : null;
        if (xml == null) {
            throw SamlRefusal.of("signature_invalid", "the signature cannot be read");
        }
        try {
            for (int i = 0; i < xml.getSignedInfo().getLength(); i++) {
                String digest = xml.getSignedInfo().item(i).getMessageDigestAlgorithm().getAlgorithmURI();
                if (!DIGEST.contains(digest)) {
                    throw SamlRefusal.of("weak_algorithm", "the digest method " + digest + " is not accepted");
                }
            }
        }
        catch (org.apache.xml.security.exceptions.XMLSecurityException ex) {
            throw SamlRefusal.of("signature_invalid", "the signature's references cannot be read");
        }
    }

    /** Refuses an encrypted element whose content or key transport algorithm is not accepted. */
    static void requireStrong(EncryptedElementType encrypted) {
        EncryptedData data = encrypted.getEncryptedData();
        if (data == null) {
            throw SamlRefusal.of("decryption_failed", "the encrypted element holds no EncryptedData");
        }
        requireAlgorithm(data, CONTENT, "content encryption");
        List<EncryptedKey> keys = new ArrayList<>(encrypted.getEncryptedKeys());
        if (data.getKeyInfo() != null) {
            keys.addAll(data.getKeyInfo().getEncryptedKeys());
        }
        if (keys.isEmpty()) {
            throw SamlRefusal.of("decryption_failed", "the encrypted element holds no EncryptedKey");
        }
        keys.forEach(key -> requireAlgorithm(key, KEY_TRANSPORT, "key transport"));
    }

    private static void requireAlgorithm(EncryptedType type, Set<String> accepted, String what) {
        String algorithm = type.getEncryptionMethod() == null ? null : type.getEncryptionMethod().getAlgorithm();
        if (algorithm == null || !accepted.contains(algorithm)) {
            throw SamlRefusal.of("weak_algorithm", "the " + what + " algorithm " + algorithm + " is not accepted");
        }
    }

    /** OpenSAML's decrypter for the SP's keys, held to the accepted algorithms as well. */
    static Decrypter decrypter(org.opensaml.xmlsec.keyinfo.KeyInfoCredentialResolver keys) {
        DecryptionParameters parameters = new DecryptionParameters();
        parameters.setKEKKeyInfoCredentialResolver(keys);
        parameters.setEncryptedKeyResolver(ENCRYPTED_KEYS);
        // The allow-lists above are checked before decrypting; OpenSAML refuses RSA 1.5 on its own as well.
        parameters.setExcludedAlgorithms(List.of(EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSA15));
        Decrypter decrypter = new Decrypter(parameters);
        decrypter.setRootInNewDocument(true);
        return decrypter;
    }
}
