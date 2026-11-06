package com.flowtivo.tinfirm.identity.internal.saml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.schema.XSAny;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.core.xml.schema.XSURI;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.NameIDType;

import com.flowtivo.tinfirm.identity.internal.SamlProperties;

/**
 * Who an accepted assertion says the person is (ADR-0086, decision 5), read only from the verified assertion's
 * subject and {@code AttributeStatement}, decrypted when it came encrypted. An attribute of several values gives its
 * first; OpenSAML reads an element's whole text, comments removed, never its first text node.
 *
 * @param subject        the NameID, or the single value of the attribute {@code _SUBJECT} names
 * @param email          the first of {@code _EMAIL_ATTRIBUTES} that looks like an email address, else an
 *                       {@code emailAddress} NameID; null for none
 * @param emailAttribute the attribute it came from, {@code NameID} for the NameID
 * @param name           the first of {@code _NAME_ATTRIBUTES}, else the given name and surname joined; null for none
 * @param roleValues     every value of the role attribute
 */
record SamlPerson(String subject, @Nullable String email, @Nullable String emailAttribute, @Nullable String name,
        List<String> roleValues) {

    static final int MAX_SUBJECT = 255;

    static final String NAMEID = "NameID";

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final List<String> GIVEN_NAMES = List.of("givenName",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/givenname", "urn:oid:2.5.4.42");

    private static final List<String> SURNAMES = List.of("surname", "sn",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/surname", "urn:oid:2.5.4.4");

    /** The person, or a refusal: no subject, a transient NameID as the subject, or a subject over 255 characters. */
    static SamlPerson of(Assertion assertion, SamlProperties.Provider provider) {
        Map<String, List<String>> attributes = attributes(assertion);
        NameID nameId = assertion.getSubject() == null ? null : assertion.getSubject().getNameID();
        String subject;
        if (provider.subjectIsNameId()) {
            if (nameId == null || text(nameId.getValue()) == null) {
                throw SamlRefusal.of("invalid_response", "the assertion has no NameID");
            }
            if (NameIDType.TRANSIENT.equals(nameId.getFormat())) {
                throw SamlRefusal.of("transient_subject", "the NameID is transient");
            }
            subject = text(nameId.getValue());
        }
        else {
            List<String> values = attributes.getOrDefault(provider.subject(), List.of());
            if (values.size() != 1) {
                throw SamlRefusal.of("invalid_response", "the subject attribute holds " + values.size() + " values");
            }
            subject = values.get(0);
        }
        if (subject.length() > MAX_SUBJECT) {
            throw SamlRefusal.of("invalid_response", "the subject is over " + MAX_SUBJECT + " characters");
        }
        String email = null;
        String emailAttribute = null;
        for (String attribute : provider.emailAttributes()) {
            String value = attributes.getOrDefault(attribute, List.of()).stream().findFirst().orElse(null);
            if (value != null && looksLikeEmail(value)) {
                email = value;
                emailAttribute = attribute;
                break;
            }
        }
        if (email == null && nameId != null && NameIDType.EMAIL.equals(nameId.getFormat())
                && text(nameId.getValue()) != null && looksLikeEmail(text(nameId.getValue()))) {
            email = text(nameId.getValue());
            emailAttribute = NAMEID;
        }
        String name = null;
        for (String attribute : provider.nameAttributes()) {
            name = attributes.getOrDefault(attribute, List.of()).stream().findFirst().orElse(null);
            if (name != null) {
                break;
            }
        }
        if (name == null) {
            String given = first(attributes, GIVEN_NAMES);
            String surname = first(attributes, SURNAMES);
            if (given != null || surname != null) {
                name = String.join(" ", java.util.stream.Stream.of(given, surname)
                    .filter(java.util.Objects::nonNull)
                    .toList());
            }
        }
        List<String> roles = provider.roleAttribute() == null ? List.of()
                : attributes.getOrDefault(provider.roleAttribute(), List.of());
        return new SamlPerson(subject, email, emailAttribute, name, roles);
    }

    private static boolean looksLikeEmail(String value) {
        return value.length() <= 320 && EMAIL.matcher(value).matches();
    }

    private static @Nullable String first(Map<String, List<String>> attributes, List<String> names) {
        for (String name : names) {
            List<String> values = attributes.getOrDefault(name, List.of());
            if (!values.isEmpty()) {
                return values.get(0);
            }
        }
        return null;
    }

    /** The assertion's attributes by name, each with its non-blank text values in order. */
    static Map<String, List<String>> attributes(Assertion assertion) {
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        for (AttributeStatement statement : assertion.getAttributeStatements()) {
            for (Attribute attribute : statement.getAttributes()) {
                if (attribute.getName() == null) {
                    continue;
                }
                List<String> values = attributes.computeIfAbsent(attribute.getName(), name -> new ArrayList<>());
                for (XMLObject value : attribute.getAttributeValues()) {
                    String text = switch (value) {
                        case XSString string -> text(string.getValue());
                        case XSURI uri -> text(uri.getURI());
                        case XSAny any -> text(any.getTextContent());
                        default -> null;
                    };
                    if (text != null) {
                        values.add(text);
                    }
                }
            }
        }
        return attributes;
    }

    private static @Nullable String text(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
