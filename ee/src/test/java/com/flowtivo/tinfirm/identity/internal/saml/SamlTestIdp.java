package com.flowtivo.tinfirm.identity.internal.saml;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.Inflater;

import javax.xml.namespace.QName;

import org.jspecify.annotations.Nullable;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.common.SignableSAMLObject;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.AttributeValue;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnContext;
import org.opensaml.saml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.EncryptedAssertion;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.saml.saml2.encryption.Encrypter;
import org.opensaml.saml.common.SAMLObjectContentReference;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.credential.CredentialSupport;
import org.opensaml.xmlsec.encryption.support.DataEncryptionParameters;
import org.opensaml.xmlsec.encryption.support.EncryptionConstants;
import org.opensaml.xmlsec.encryption.support.KeyEncryptionParameters;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.Signer;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.w3c.dom.Element;

import net.shibboleth.shared.xml.SerializeSupport;

import com.flowtivo.tinfirm.support.SamlTestKeys;

/**
 * TEST FIXTURE ONLY: an identity provider that signs SAML responses with a test key, built with OpenSAML as a real
 * IdP would build them, so that each check of ADR-0086 decision 8 can be met by a response changed in one way. Its
 * metadata names an HTTP-Redirect {@code SingleSignOnService} that is never called: the tests take the request's ID
 * from the start's redirect and answer it themselves.
 */
final class SamlTestIdp {

    static {
        OpenSamlInitializationService.initialize();
    }

    final String entityId;

    final SamlTestKeys keys;

    SamlTestIdp(String entityId) {
        this.entityId = entityId;
        this.keys = SamlTestKeys.generate("tinfirm-test-idp");
    }

    /** The IdP's metadata, publishing its signing certificate, and optionally a second one beside it. */
    String metadata(SamlTestKeys... extraSigning) {
        StringBuilder keyDescriptors = new StringBuilder(keyDescriptor(keys));
        for (SamlTestKeys extra : extraSigning) {
            keyDescriptors.append(keyDescriptor(extra));
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
                  <md:IDPSSODescriptor WantAuthnRequestsSigned="true"
                      protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    %s
                    <md:NameIDFormat>urn:oasis:names:tc:SAML:2.0:nameid-format:persistent</md:NameIDFormat>
                    <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect"
                        Location="https://idp.test/sso"/>
                  </md:IDPSSODescriptor>
                </md:EntityDescriptor>
                """.formatted(entityId, keyDescriptors);
    }

    private static String keyDescriptor(SamlTestKeys keys) {
        return """
                <md:KeyDescriptor use="signing"><ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">\
                <ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>
                """.formatted(keys.certificateBase64());
    }

    /** The ID of the AuthnRequest a start's redirect carries (HTTP-Redirect binding: deflated, base64). */
    static String requestId(String location) {
        String query = location.substring(location.indexOf('?') + 1);
        String encoded = null;
        for (String parameter : query.split("&")) {
            if (parameter.startsWith("SAMLRequest=")) {
                encoded = java.net.URLDecoder.decode(parameter.substring("SAMLRequest=".length()),
                        StandardCharsets.UTF_8);
            }
        }
        if (encoded == null) {
            throw new IllegalStateException("No SAMLRequest in " + location);
        }
        Inflater inflater = new Inflater(true);
        inflater.setInput(Base64.getDecoder().decode(encoded));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && inflater.needsInput()) {
                    break;
                }
                out.write(buffer, 0, n);
            }
        }
        catch (java.util.zip.DataFormatException ex) {
            throw new IllegalStateException(ex);
        }
        String xml = out.toString(StandardCharsets.UTF_8);
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("\\sID=\"([^\"]+)\"").matcher(xml);
        if (!id.find()) {
            throw new IllegalStateException("No ID in " + xml);
        }
        return id.group(1);
    }

    /** A response to a request, as an IdP builds it: one assertion, signed or not as the test asks. */
    final class ResponseBuilder {

        String spEntityId;

        String acs;

        @Nullable String inResponseTo;

        String nameId = "persistent-" + UUID.randomUUID();

        String nameIdFormat = NameIDType.PERSISTENT;

        final Map<String, List<String>> attributes = new LinkedHashMap<>();

        Instant now = Instant.now();

        boolean signResponse = true;

        boolean signAssertion = true;

        String signatureAlgorithm = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256;

        String digestAlgorithm = SignatureConstants.ALGO_ID_DIGEST_SHA256;

        SamlTestKeys signingKeys = keys;

        @Nullable SamlTestKeys encryptFor;

        String keyTransport = EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSAOAEP;

        Consumer<Response> response = r -> {
        };

        Consumer<Assertion> assertion = a -> {
        };

        ResponseBuilder(String spEntityId, String acs, @Nullable String inResponseTo) {
            this.spEntityId = spEntityId;
            this.acs = acs;
            this.inResponseTo = inResponseTo;
        }

        ResponseBuilder attribute(String name, String... values) {
            attributes.put(name, List.of(values));
            return this;
        }

        /** The response, serialised and base64-encoded as the HTTP-POST binding carries it. */
        String encoded() {
            return Base64.getEncoder().encodeToString(xml().getBytes(StandardCharsets.UTF_8));
        }

        /** The response's XML. */
        String xml() {
            return SerializeSupport.nodeToString(element());
        }

        /** The response's DOM, signed. */
        Element element() {
            try {
                Response built = build();
                return XMLObjectSupport.marshall(built);
            }
            catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private Response build() throws Exception {
            Response r = object(Response.DEFAULT_ELEMENT_NAME);
            r.setID("_r" + UUID.randomUUID());
            r.setVersion(SAMLVersion.VERSION_20);
            r.setIssueInstant(now);
            r.setDestination(acs);
            r.setInResponseTo(inResponseTo);
            r.setIssuer(issuer());
            Status status = object(Status.DEFAULT_ELEMENT_NAME);
            StatusCode code = object(StatusCode.DEFAULT_ELEMENT_NAME);
            code.setValue(StatusCode.SUCCESS);
            status.setStatusCode(code);
            r.setStatus(status);

            Assertion a = object(Assertion.DEFAULT_ELEMENT_NAME);
            a.setID("_a" + UUID.randomUUID());
            a.setVersion(SAMLVersion.VERSION_20);
            a.setIssueInstant(now);
            a.setIssuer(issuer());
            Subject subject = object(Subject.DEFAULT_ELEMENT_NAME);
            NameID id = object(NameID.DEFAULT_ELEMENT_NAME);
            id.setFormat(nameIdFormat);
            id.setValue(nameId);
            subject.setNameID(id);
            SubjectConfirmation confirmation = object(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
            confirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
            SubjectConfirmationData data = object(SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
            data.setNotOnOrAfter(now.plus(Duration.ofMinutes(5)));
            data.setRecipient(acs);
            data.setInResponseTo(inResponseTo);
            confirmation.setSubjectConfirmationData(data);
            subject.getSubjectConfirmations().add(confirmation);
            a.setSubject(subject);
            Conditions conditions = object(Conditions.DEFAULT_ELEMENT_NAME);
            conditions.setNotBefore(now.minus(Duration.ofSeconds(30)));
            conditions.setNotOnOrAfter(now.plus(Duration.ofMinutes(5)));
            AudienceRestriction restriction = object(AudienceRestriction.DEFAULT_ELEMENT_NAME);
            Audience audience = object(Audience.DEFAULT_ELEMENT_NAME);
            audience.setURI(spEntityId);
            restriction.getAudiences().add(audience);
            conditions.getAudienceRestrictions().add(restriction);
            a.setConditions(conditions);
            AuthnStatement authn = object(AuthnStatement.DEFAULT_ELEMENT_NAME);
            authn.setAuthnInstant(now);
            authn.setSessionIndex("_s" + UUID.randomUUID());
            AuthnContext context = object(AuthnContext.DEFAULT_ELEMENT_NAME);
            AuthnContextClassRef classRef = object(AuthnContextClassRef.DEFAULT_ELEMENT_NAME);
            classRef.setURI(AuthnContext.PPT_AUTHN_CTX);
            context.setAuthnContextClassRef(classRef);
            authn.setAuthnContext(context);
            a.getAuthnStatements().add(authn);
            if (!attributes.isEmpty()) {
                AttributeStatement statement = object(AttributeStatement.DEFAULT_ELEMENT_NAME);
                attributes.forEach((name, values) -> {
                    Attribute attribute = object(Attribute.DEFAULT_ELEMENT_NAME);
                    attribute.setName(name);
                    attribute.setNameFormat(Attribute.BASIC);
                    for (String value : values) {
                        XSString text = (XSString) XMLObjectProviderRegistrySupport.getBuilderFactory()
                            .ensureBuilder(XSString.TYPE_NAME)
                            .buildObject(AttributeValue.DEFAULT_ELEMENT_NAME, XSString.TYPE_NAME);
                        text.setValue(value);
                        attribute.getAttributeValues().add(text);
                    }
                    statement.getAttributes().add(attribute);
                });
                a.getAttributeStatements().add(statement);
            }
            assertion.accept(a);

            Signature assertionSignature = signAssertion ? signature(a) : null;
            if (encryptFor != null) {
                if (assertionSignature != null) {
                    XMLObjectSupport.marshall(a);
                    Signer.signObject(assertionSignature);
                }
                r.getEncryptedAssertions().add(encrypt(a, encryptFor));
            }
            else {
                r.getAssertions().add(a);
            }
            response.accept(r);
            Signature responseSignature = signResponse ? signature(r) : null;
            XMLObjectSupport.marshall(r);
            if (assertionSignature != null && encryptFor == null) {
                Signer.signObject(assertionSignature);
            }
            if (responseSignature != null) {
                Signer.signObject(responseSignature);
            }
            return r;
        }

        private EncryptedAssertion encrypt(Assertion a, SamlTestKeys sp) throws Exception {
            DataEncryptionParameters data = new DataEncryptionParameters();
            data.setAlgorithm(EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES256_GCM);
            KeyEncryptionParameters key = new KeyEncryptionParameters();
            key.setEncryptionCredential(CredentialSupport.getSimpleCredential(sp.certificate(), null));
            key.setAlgorithm(keyTransport);
            Encrypter encrypter = new Encrypter(data, key);
            encrypter.setKeyPlacement(Encrypter.KeyPlacement.INLINE);
            return encrypter.encrypt(a);
        }

        private Signature signature(SignableSAMLObject signable) {
            Credential credential = CredentialSupport.getSimpleCredential(signingKeys.certificate(),
                    signingKeys.keys().getPrivate());
            Signature signature = object(Signature.DEFAULT_ELEMENT_NAME);
            signature.setSigningCredential(credential);
            signature.setSignatureAlgorithm(signatureAlgorithm);
            signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
            signable.setSignature(signature);
            ((SAMLObjectContentReference) signature.getContentReferences().get(0)).setDigestAlgorithm(digestAlgorithm);
            return signature;
        }

        private Issuer issuer() {
            Issuer issuer = object(Issuer.DEFAULT_ELEMENT_NAME);
            issuer.setValue(entityId);
            return issuer;
        }
    }

    ResponseBuilder respond(String spEntityId, String acs, @Nullable String inResponseTo) {
        return new ResponseBuilder(spEntityId, acs, inResponseTo);
    }

    @SuppressWarnings("unchecked")
    static <T extends XMLObject> T object(QName name) {
        return (T) XMLObjectProviderRegistrySupport.getBuilderFactory().ensureBuilder(name).buildObject(name);
    }

    static String protocol() {
        return SAMLConstants.SAML20P_NS;
    }
}
