package com.flowtivo.tinfirm.support;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;

/**
 * TEST FIXTURE ONLY: a key pair and its self-signed certificate, made when the tests run so that no private key is in
 * the repository. RSA 3072, SHA256withRSA, as ADR-0086 asks of the service provider's key.
 */
public record SamlTestKeys(KeyPair keys, X509Certificate certificate) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static SamlTestKeys generate(String commonName) {
        return generate(commonName, Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(365)));
    }

    public static SamlTestKeys generate(String commonName, Instant notBefore, Instant notAfter) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072, RANDOM);
            KeyPair keys = generator.generateKeyPair();
            AlgorithmIdentifier algorithm = new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption,
                    DERNull.INSTANCE);
            X500Name name = new X500Name("CN=" + commonName);
            V3TBSCertificateGenerator tbs = new V3TBSCertificateGenerator();
            tbs.setSerialNumber(new ASN1Integer(new BigInteger(64, RANDOM)));
            tbs.setIssuer(name);
            tbs.setSubject(name);
            tbs.setStartDate(new Time(Date.from(notBefore)));
            tbs.setEndDate(new Time(Date.from(notAfter)));
            tbs.setSignature(algorithm);
            tbs.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keys.getPublic().getEncoded()));
            TBSCertificate certificate = tbs.generateTBSCertificate();
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(keys.getPrivate());
            signer.update(certificate.getEncoded());
            ASN1EncodableVector signed = new ASN1EncodableVector();
            signed.add(certificate);
            signed.add(algorithm);
            signed.add(new DERBitString(signer.sign()));
            X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(new DERSequence(signed).getEncoded()));
            return new SamlTestKeys(keys, x509);
        }
        catch (GeneralSecurityException | java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String privateKeyPem() {
        return pem("PRIVATE KEY", keys.getPrivate().getEncoded());
    }

    public String certificatePem() {
        try {
            return pem("CERTIFICATE", certificate.getEncoded());
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The certificate's DER in base64, as SAML metadata and Keycloak's client settings hold it. */
    public String certificateBase64() {
        try {
            return Base64.getEncoder().encodeToString(certificate.getEncoded());
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
