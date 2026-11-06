package com.flowtivo.tinfirm.identity.internal.saml;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.EncryptedAssertion;
import org.opensaml.saml.saml2.core.EncryptedAttribute;
import org.opensaml.saml.saml2.core.EncryptedID;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.encryption.Decrypter;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.credential.CredentialSupport;
import org.opensaml.xmlsec.encryption.support.DecryptionException;
import org.opensaml.xmlsec.keyinfo.impl.CollectionKeyInfoCredentialResolver;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;

/**
 * Decrypts an assertion encrypted with the SP's published key (ADR-0086, decision 9), as Spring Security does, but
 * with the algorithms held to {@link SamlAlgorithms}: each encrypted element's algorithms are checked before it is
 * decrypted. An {@code EncryptedAssertion} stays in the response beside the assertion decrypted from it, so that the
 * response's checks know it came encrypted and count the assertions as they arrived.
 */
final class SamlDecryption {

    private SamlDecryption() {
    }

    /** The response's encrypted assertions; Spring calls this only for a signed response. */
    static void response(OpenSaml5AuthenticationProvider.ResponseToken token) {
        Response response = token.getResponse();
        if (response.getEncryptedAssertions().isEmpty()) {
            return;
        }
        Decrypter decrypter = decrypter(token.getToken().getRelyingPartyRegistration().getDecryptionX509Credentials());
        List<Assertion> decrypted = new ArrayList<>();
        for (EncryptedAssertion encrypted : response.getEncryptedAssertions()) {
            SamlAlgorithms.requireStrong(encrypted);
            try {
                Assertion assertion = decrypter.decrypt(encrypted);
                if (assertion == null) {
                    throw SamlRefusal.of("decryption_failed", "the assertion decrypted to nothing");
                }
                decrypted.add(assertion);
            }
            catch (DecryptionException ex) {
                throw SamlRefusal.of("decryption_failed", "the assertion cannot be decrypted: " + ex.getMessage());
            }
        }
        response.getAssertions().addAll(decrypted);
        try {
            // Marshalled again, so that the decrypted assertions' IDs are IDs at the DOM level for their signatures.
            XMLObjectSupport.marshall(response);
        }
        catch (MarshallingException ex) {
            throw SamlRefusal.of("decryption_failed", "the decrypted response cannot be marshalled");
        }
    }

    /** An assertion's encrypted NameIDs and attributes; for a signed response or a signed assertion. */
    static void assertion(OpenSaml5AuthenticationProvider.AssertionToken token) {
        Assertion assertion = token.getAssertion();
        Decrypter decrypter = decrypter(token.getToken().getRelyingPartyRegistration().getDecryptionX509Credentials());
        for (AttributeStatement statement : assertion.getAttributeStatements()) {
            List<Attribute> decrypted = new ArrayList<>();
            for (EncryptedAttribute encrypted : statement.getEncryptedAttributes()) {
                SamlAlgorithms.requireStrong(encrypted);
                try {
                    Attribute attribute = decrypter.decrypt(encrypted);
                    if (attribute != null) {
                        decrypted.add(attribute);
                    }
                }
                catch (DecryptionException ex) {
                    throw SamlRefusal.of("decryption_failed", "an attribute cannot be decrypted: " + ex.getMessage());
                }
            }
            statement.getAttributes().addAll(decrypted);
        }
        Subject subject = assertion.getSubject();
        if (subject == null) {
            return;
        }
        NameID nameId = nameId(decrypter, subject.getEncryptedID());
        if (nameId != null) {
            subject.setNameID(nameId);
        }
        for (SubjectConfirmation confirmation : subject.getSubjectConfirmations()) {
            NameID confirmed = nameId(decrypter, confirmation.getEncryptedID());
            if (confirmed != null) {
                confirmation.setNameID(confirmed);
            }
        }
    }

    private static @Nullable NameID nameId(Decrypter decrypter, @Nullable EncryptedID encrypted) {
        if (encrypted == null) {
            return null;
        }
        SamlAlgorithms.requireStrong(encrypted);
        try {
            return (NameID) decrypter.decrypt(encrypted);
        }
        catch (DecryptionException | ClassCastException ex) {
            throw SamlRefusal.of("decryption_failed", "the NameID cannot be decrypted: " + ex.getMessage());
        }
    }

    private static Decrypter decrypter(java.util.Collection<Saml2X509Credential> keys) {
        List<Credential> credentials = new ArrayList<>();
        for (Saml2X509Credential key : keys) {
            credentials.add(CredentialSupport.getSimpleCredential(key.getCertificate(), key.getPrivateKey()));
        }
        return SamlAlgorithms.decrypter(new CollectionKeyInfoCredentialResolver(credentials));
    }
}
