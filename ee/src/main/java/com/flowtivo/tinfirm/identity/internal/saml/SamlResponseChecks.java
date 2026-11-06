package com.flowtivo.tinfirm.identity.internal.saml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.saml2.core.Saml2ResponseValidatorResult;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider.AssertionToken;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider.ResponseToken;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.flowtivo.tinfirm.identity.internal.SamlProperties;
import com.flowtivo.tinfirm.identity.internal.SamlSettings;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The checks Tinfirm adds to Spring Security's {@code OpenSaml5AuthenticationProvider} (ADR-0086, decision 8), and the
 * reason each failure is audited with. The library parses with OpenSAML's hardened pool, verifies the signatures
 * against the IdP metadata's keys with OpenSAML's signature profile, and checks the status, the issuer, the audience,
 * the subject confirmation and the conditions' times; Tinfirm adds: a response or every assertion signed, with
 * accepted algorithms only; exactly one assertion; a {@code Destination} on a signed response; an
 * {@code InResponseTo} that names this browser's saved request; a clock skew of 60 seconds and an assertion issued at
 * most 5 minutes ago; an {@code AuthnStatement}; an encrypted assertion when the provider requires one; and one use of
 * each assertion. A check that fails throws at once, so the first failure is the one audited.
 */
@Component
class SamlResponseChecks {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final SamlSettings settings;

    private final SamlAuthnRequests requests;

    private final SamlAssertionsSeen seen;

    private final TimeSource time;

    private final Converter<ResponseToken, Saml2ResponseValidatorResult> springResponse = OpenSaml5AuthenticationProvider
        .createDefaultResponseValidator();

    private final Converter<AssertionToken, Saml2ResponseValidatorResult> springAssertion = OpenSaml5AuthenticationProvider.AssertionValidator
        .builder()
        .clockSkew(CLOCK_SKEW)
        .build();

    SamlResponseChecks(SamlSettings settings, SamlAuthnRequests requests, SamlAssertionsSeen seen, TimeSource time) {
        this.settings = settings;
        this.requests = requests;
        this.seen = seen;
        this.time = time;
    }

    /** The response's checks: after its signature, before its assertions'. */
    Saml2ResponseValidatorResult response(ResponseToken token) {
        Response response = token.getResponse();
        RelyingPartyRegistration registration = token.getToken().getRelyingPartyRegistration();
        String registrationId = registration.getRegistrationId();
        // Signed: the response, or every assertion; an encrypted assertion is decrypted only in a signed response.
        if (!response.isSigned()) {
            if (!response.getEncryptedAssertions().isEmpty()) {
                throw SamlRefusal.of("unsigned", "an encrypted assertion in an unsigned response");
            }
            if (response.getAssertions().stream().anyMatch(assertion -> !assertion.isSigned())) {
                throw SamlRefusal.of("unsigned", "neither the response nor its assertion is signed");
            }
        }
        SamlAlgorithms.requireStrong(response.getSignature());
        if (response.getStatus() != null && response.getStatus().getStatusCode() != null
                && !StatusCode.SUCCESS.equals(response.getStatus().getStatusCode().getValue())) {
            throw SamlRefusal.of("status_not_success",
                    "the status is " + response.getStatus().getStatusCode().getValue());
        }
        String entityId = registration.getAssertingPartyMetadata().getEntityId();
        if (response.getIssuer() != null && !entityId.equals(response.getIssuer().getValue())) {
            throw SamlRefusal.of("issuer_mismatch", "the response's issuer is " + response.getIssuer().getValue());
        }
        // Exactly one assertion as it arrived: an encrypted one stays beside the one decrypted from it.
        int encrypted = response.getEncryptedAssertions().size();
        int assertions = response.getAssertions().size();
        boolean one = encrypted == 0 ? assertions == 1 : encrypted == 1 && assertions == 1;
        if (!one) {
            throw SamlRefusal.of("multiple_assertions", assertions + " assertion(s) and " + encrypted
                    + " encrypted assertion(s): exactly one is accepted");
        }
        String acs = registration.getAssertionConsumerServiceLocation();
        String destination = response.getDestination();
        if (StringUtils.hasText(destination) ? !destination.equals(acs) : response.isSigned()) {
            throw SamlRefusal.of("destination_mismatch", "the response's Destination is " + destination);
        }
        String inResponseTo = response.getInResponseTo();
        if (!StringUtils.hasText(inResponseTo)) {
            throw SamlRefusal.of("unsolicited", "the response has no InResponseTo");
        }
        AbstractSaml2AuthenticationRequest saved = token.getToken().getAuthenticationRequest();
        if (saved == null) {
            throw requests.exists(registrationId, inResponseTo)
                    ? SamlRefusal.of("browser_mismatch", "the request was saved for another browser")
                    : SamlRefusal.of("in_response_to_mismatch", "no saved request of this browser answers it");
        }
        if (!inResponseTo.equals(saved.getId())) {
            throw SamlRefusal.of("in_response_to_mismatch", "the response answers another request");
        }
        SamlProperties.Provider provider = settings.properties().provider(registrationId);
        if (provider != null && provider.requireEncryptedAssertions() && encrypted == 0) {
            throw SamlRefusal.of("not_encrypted", "the provider requires an encrypted assertion");
        }
        Saml2ResponseValidatorResult result = springResponse.convert(token);
        if (result != null && result.hasErrors()) {
            throw SamlRefusal.of("invalid_response", result.getErrors().toString());
        }
        return Saml2ResponseValidatorResult.success();
    }

    /** The assertion's checks: after its signature and its decryption. The assertion's one use is the last. */
    Saml2ResponseValidatorResult assertion(AssertionToken token) {
        Assertion assertion = token.getAssertion();
        RelyingPartyRegistration registration = token.getToken().getRelyingPartyRegistration();
        SamlAlgorithms.requireStrong(assertion.getSignature());
        Saml2ResponseValidatorResult result = springAssertion.convert(token);
        if (result != null && result.hasErrors()) {
            throw SamlRefusal.of(classify(assertion, registration, token.getToken().getAuthenticationRequest()),
                    result.getErrors().toString());
        }
        // The assertion's own bearer confirmation names this browser's request, so that an assertion signed alone
        // for an IdP-initiated sign-in cannot be wrapped in an unsigned response that answers a request of
        // another browser (decision 8): the response's InResponseTo is not signed then.
        AbstractSaml2AuthenticationRequest saved = token.getToken().getAuthenticationRequest();
        String requestId = saved != null ? saved.getId() : null;
        boolean answers = assertion.getSubject() != null && assertion.getSubject()
            .getSubjectConfirmations()
            .stream()
            .filter(confirmation -> SubjectConfirmation.METHOD_BEARER.equals(confirmation.getMethod()))
            .map(SubjectConfirmation::getSubjectConfirmationData)
            .filter(Objects::nonNull)
            .anyMatch(data -> requestId != null && requestId.equals(data.getInResponseTo()));
        if (!answers) {
            throw SamlRefusal.of("in_response_to_mismatch",
                    "the assertion's subject confirmation does not answer this browser's request");
        }
        Instant now = time.now();
        Instant issued = assertion.getIssueInstant();
        if (issued == null) {
            throw SamlRefusal.of("invalid_response", "the assertion has no IssueInstant");
        }
        if (issued.isAfter(now.plus(CLOCK_SKEW))) {
            throw SamlRefusal.of("not_yet_valid", "the assertion is issued in the future");
        }
        if (issued.plus(MAX_AGE).plus(CLOCK_SKEW).isBefore(now)) {
            throw SamlRefusal.of("expired", "the assertion was issued more than " + MAX_AGE + " ago");
        }
        if (assertion.getAuthnStatements().isEmpty()) {
            throw SamlRefusal.of("invalid_response", "the assertion has no AuthnStatement");
        }
        if (assertion.getID() == null || assertion.getID().isBlank() || assertion.getID().length() > 256) {
            throw SamlRefusal.of("invalid_response", "the assertion's ID is missing or too long");
        }
        if (!seen.firstUse(registration.getRegistrationId(), assertion.getID(), expiry(assertion, issued))) {
            throw SamlRefusal.of("replayed", "the assertion was used before");
        }
        return Saml2ResponseValidatorResult.success();
    }

    /** Until when a replay would be refused for its validity anyway: the latest end the assertion names, plus skew. */
    private static Instant expiry(Assertion assertion, Instant issued) {
        Instant end = issued.plus(MAX_AGE);
        Conditions conditions = assertion.getConditions();
        if (conditions != null && conditions.getNotOnOrAfter() != null && conditions.getNotOnOrAfter().isAfter(end)) {
            end = conditions.getNotOnOrAfter();
        }
        if (assertion.getSubject() != null) {
            for (SubjectConfirmation confirmation : assertion.getSubject().getSubjectConfirmations()) {
                SubjectConfirmationData data = confirmation.getSubjectConfirmationData();
                if (data != null && data.getNotOnOrAfter() != null && data.getNotOnOrAfter().isAfter(end)) {
                    end = data.getNotOnOrAfter();
                }
            }
        }
        return end.plus(CLOCK_SKEW);
    }

    /**
     * The reason of a signature the library refused: the IdP's keys are looked up by the issuer, so a response or an
     * assertion of another issuer fails its signature first, and is refused as {@code issuer_mismatch}. The document
     * is parsed again by the library's parser only to say why it was refused, never to accept it.
     */
    static String signatureReason(String serialized, RelyingPartyRegistration registration) {
        try {
            Object parsed = XMLObjectSupport.unmarshallFromInputStream(XMLObjectProviderRegistrySupport.getParserPool(),
                    new ByteArrayInputStream(serialized.getBytes(StandardCharsets.UTF_8)));
            if (!(parsed instanceof Response response)) {
                return "invalid_response";
            }
            String entityId = registration.getAssertingPartyMetadata().getEntityId();
            if (response.getIssuer() != null && !entityId.equals(response.getIssuer().getValue())) {
                return "issuer_mismatch";
            }
            boolean otherIssuer = response.getAssertions()
                .stream()
                .anyMatch(assertion -> assertion.getIssuer() == null
                        || !entityId.equals(assertion.getIssuer().getValue()));
            return otherIssuer ? "issuer_mismatch" : "signature_invalid";
        }
        catch (Exception ex) {
            return "signature_invalid";
        }
    }

    /** The reason the library's assertion validation failed, found by looking at what it checks. */
    private String classify(Assertion assertion, RelyingPartyRegistration registration,
            @Nullable AbstractSaml2AuthenticationRequest saved) {
        Instant now = time.now();
        if (assertion.getIssuer() == null
                || !registration.getAssertingPartyMetadata().getEntityId().equals(assertion.getIssuer().getValue())) {
            return "issuer_mismatch";
        }
        Instant issued = assertion.getIssueInstant();
        if (issued != null && issued.isAfter(now.plus(CLOCK_SKEW))) {
            return "not_yet_valid";
        }
        if (issued != null && issued.plus(MAX_AGE).plus(CLOCK_SKEW).isBefore(now)) {
            return "expired";
        }
        Conditions conditions = assertion.getConditions();
        if (conditions != null) {
            if (conditions.getNotBefore() != null && conditions.getNotBefore().isAfter(now.plus(CLOCK_SKEW))) {
                return "not_yet_valid";
            }
            if (conditions.getNotOnOrAfter() != null
                    && !conditions.getNotOnOrAfter().isAfter(now.minus(CLOCK_SKEW))) {
                return "expired";
            }
            List<AudienceRestriction> restrictions = conditions.getAudienceRestrictions();
            boolean audience = restrictions.isEmpty() || restrictions.stream()
                .allMatch(restriction -> restriction.getAudiences()
                    .stream()
                    .map(Audience::getURI)
                    .anyMatch(registration.getEntityId()::equals));
            if (!audience) {
                return "audience_mismatch";
            }
        }
        if (assertion.getSubject() == null) {
            return "invalid_response";
        }
        List<SubjectConfirmationData> bearer = assertion.getSubject()
            .getSubjectConfirmations()
            .stream()
            .filter(confirmation -> SubjectConfirmation.METHOD_BEARER.equals(confirmation.getMethod()))
            .map(SubjectConfirmation::getSubjectConfirmationData)
            .filter(Objects::nonNull)
            .toList();
        if (bearer.isEmpty()) {
            return "invalid_response";
        }
        String acs = registration.getAssertionConsumerServiceLocation();
        if (bearer.stream().noneMatch(data -> acs.equals(data.getRecipient()))) {
            return "recipient_mismatch";
        }
        if (bearer.stream()
            .allMatch(data -> data.getNotOnOrAfter() != null && !data.getNotOnOrAfter().isAfter(now.minus(CLOCK_SKEW)))) {
            return "expired";
        }
        String requestId = saved != null ? saved.getId() : null;
        if (bearer.stream()
            .anyMatch(data -> data.getInResponseTo() != null && !data.getInResponseTo().equals(requestId))) {
            return "in_response_to_mismatch";
        }
        return "invalid_response";
    }
}
