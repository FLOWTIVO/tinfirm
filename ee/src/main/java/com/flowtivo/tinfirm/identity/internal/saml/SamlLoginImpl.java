package com.flowtivo.tinfirm.identity.internal.saml;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.metadata.NameIDFormat;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.metadata.OpenSaml5MetadataResolver;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.OpenSaml5AuthenticationTokenConverter;
import org.springframework.security.saml2.provider.service.web.Saml2WebSsoAuthenticationRequestFilter;
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml5AuthenticationRequestResolver;
import org.springframework.security.saml2.provider.service.web.metadata.RequestMatcherMetadataResponseResolver;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.identity.internal.SamlLogin;
import com.flowtivo.tinfirm.identity.internal.SamlProperties;
import com.flowtivo.tinfirm.identity.internal.SamlSettings;
import com.flowtivo.tinfirm.identity.internal.SamlSignIns;
import com.flowtivo.tinfirm.identity.internal.SamlSpCredentials;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;

/**
 * Enterprise's SAML 2.0 sign-in (ADR-0086), the core's {@link SamlLogin}: Spring Security's SAML service provider on
 * OpenSAML 5, with the registrations from the stored IdP metadata, the requests bound to the browser, Tinfirm's
 * checks of every response, and the person mapped to a user by the core. Sign-in starts at Tinfirm with a signed
 * HTTP-Redirect {@code AuthnRequest} (never the POST binding, whose self-submitting form the CSP forbids); there is no
 * IdP-initiated sign-in and no single logout.
 */
@Component
class SamlLoginImpl implements SamlLogin {

    private final SamlSettings settings;

    private final SamlRegistrations registrations;

    private final SamlAuthnRequests requests;

    private final SamlResponseChecks checks;

    private final Licensing licensing;

    private final Clock clock;

    SamlLoginImpl(SamlSettings settings, SamlRegistrations registrations, SamlAuthnRequests requests,
            SamlResponseChecks checks, Licensing licensing, Clock clock) {
        this.settings = settings;
        this.registrations = registrations;
        this.requests = requests;
        this.checks = checks;
        this.licensing = licensing;
        this.clock = clock;
    }

    @Override
    public void configure(HttpSecurity http, SamlSignIns signIns) throws Exception {
        OpenSaml5AuthenticationProvider provider = new OpenSaml5AuthenticationProvider();
        provider.setResponseValidator(checks::response);
        provider.setAssertionValidator(checks::assertion);
        provider.setResponseElementsDecrypter(SamlDecryption::response);
        provider.setAssertionElementsDecrypter(SamlDecryption::assertion);
        provider.setResponseAuthenticationConverter(token -> {
            Assertion assertion = token.getResponse().getAssertions().get(0);
            String registrationId = token.getToken().getRelyingPartyRegistration().getRegistrationId();
            // Every check passed: the response answers this browser's request, which it now uses up (decision 4).
            if (!requests.consume(registrationId, token.getToken().getAuthenticationRequest().getId())) {
                throw SamlRefusal.of("replayed", "another response answered the request first");
            }
            SamlProperties.Provider settingsOf = Objects.requireNonNull(settings.properties().provider(registrationId));
            SamlPerson person = SamlPerson.of(assertion, settingsOf);
            if (!licensing.has(Feature.SAML)) {
                throw SamlRefusal.editionRequired();
            }
            SamlSignIns.Outcome outcome = signIns.signIn(new SamlSignIns.Assertion(registrationId,
                    token.getToken().getRelyingPartyRegistration().getAssertingPartyMetadata().getEntityId(),
                    person.subject(), person.email(), person.emailAttribute(), person.name(), person.roleValues()));
            return switch (outcome) {
                case SamlSignIns.SignedIn signedIn -> (AbstractAuthenticationToken) signIns.authenticated(signedIn.userId(), sessionNotOnOrAfter(assertion));
                case SamlSignIns.Refused refused -> throw SamlRefusal.audited(refused.reason(), refused.code());
            };
        });

        OpenSaml5AuthenticationRequestResolver start = new OpenSaml5AuthenticationRequestResolver(registrations);
        start.setRequestMatcher(PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, SamlRegistrations.START_PATH + "{registrationId}"));
        start.setClock(clock);

        OpenSaml5AuthenticationTokenConverter acs = new OpenSaml5AuthenticationTokenConverter(registrations);
        acs.setAuthenticationRequestRepository(requests);
        acs.setRequestMatcher(PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, SamlRegistrations.ACS_PATH + "{registrationId}"));

        OpenSaml5MetadataResolver metadata = new OpenSaml5MetadataResolver();
        metadata.setEntityDescriptorCustomizer(parameters -> {
            SPSSODescriptor sp = parameters.getEntityDescriptor().getSPSSODescriptor(SAMLConstants.SAML20P_NS);
            sp.setWantAssertionsSigned(true);
            sp.setAuthnRequestsSigned(true);
            sp.getNameIDFormats().clear();
            for (String format : List.of(NameIDType.PERSISTENT, NameIDType.EMAIL, NameIDType.UNSPECIFIED)) {
                NameIDFormat nameIdFormat = (NameIDFormat) XMLObjectProviderRegistrySupport.getBuilderFactory()
                    .ensureBuilder(NameIDFormat.DEFAULT_ELEMENT_NAME)
                    .buildObject(NameIDFormat.DEFAULT_ELEMENT_NAME);
                nameIdFormat.setURI(format);
                sp.getNameIDFormats().add(nameIdFormat);
            }
        });
        RelyingPartyRegistrationRepository serviceProviders = id -> settings.properties().provider(id) == null ? null
                : registrations.serviceProvider(id);
        RequestMatcherMetadataResponseResolver metadataResponses = new RequestMatcherMetadataResponseResolver(
                serviceProviders, metadata);
        metadataResponses.setRequestMatcher(PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, SamlRegistrations.METADATA_PATH + "{registrationId}"));
        metadataResponses.setMetadataFilename("tinfirm-{registrationId}-sp-metadata.xml");

        http.saml2Login(saml -> saml.loginPage("/login")
            .relyingPartyRegistrationRepository(registrations)
            .authenticationRequestResolver(start)
            .authenticationConverter(acs)
            .authenticationManager(new ProviderManager(classified(provider)))
            .successHandler(signedIn(signIns))
            .failureHandler(failure(signIns)))
            .saml2Metadata(sp -> sp.metadataResponseResolver(metadataResponses))
            .addFilterBefore(new SamlGuardFilter(registrations, signIns, licensing,
                    id -> settings.properties().provider(id) != null), Saml2WebSsoAuthenticationRequestFilter.class);
    }

    /**
     * The provider, with the library's own refusals of a signature given their reason: a response of another issuer
     * fails its signature, as its keys are looked up by its issuer (decision 8).
     */
    private static AuthenticationProvider classified(OpenSaml5AuthenticationProvider provider) {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) {
                try {
                    return provider.authenticate(authentication);
                }
                catch (SamlRefusal refusal) {
                    throw refusal;
                }
                catch (Saml2AuthenticationException ex) {
                    if (Saml2ErrorCodes.INVALID_SIGNATURE.equals(ex.getSaml2Error().getErrorCode())
                            && authentication instanceof Saml2AuthenticationToken token
                            && reason(ex).equals("signature_invalid")) {
                        throw SamlRefusal.of(SamlResponseChecks.signatureReason(token.getSaml2Response(),
                                token.getRelyingPartyRegistration()), ex.getMessage());
                    }
                    throw ex;
                }
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return provider.supports(authentication);
            }
        };
    }

    /** Where a signed-in person goes, with the binding cookie expired: its request is used up. */
    private AuthenticationSuccessHandler signedIn(SamlSignIns signIns) {
        AuthenticationSuccessHandler next = signIns.successHandler();
        return (HttpServletRequest request, HttpServletResponse response, Authentication authentication) -> {
            requests.forget(response);
            next.onAuthenticationSuccess(request, response, authentication);
        };
    }

    /**
     * Where a refused response ends: on the login page, audited unless provisioning audited it already, and counted
     * against the client address either way.
     */
    static AuthenticationFailureHandler failure(SamlSignIns signIns) {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) -> {
            String provider = SamlAuthnRequests.registrationIdOf(request);
            if (exception instanceof SamlRefusal refusal) {
                if (refusal.audited()) {
                    signIns.failed(request);
                }
                else {
                    signIns.refused(request, provider, refusal.reason(), refusal.getMessage());
                }
                response.sendRedirect(signIns.loginError(refusal.code()));
                return;
            }
            String reason = exception instanceof Saml2AuthenticationException saml ? reason(saml) : "invalid_response";
            signIns.refused(request, provider, reason, exception.getMessage());
            response.sendRedirect(signIns.loginError(SamlRefusal.SAML_LOGIN_FAILED));
        };
    }

    /** The audited reason of a refusal the library raised itself. */
    static String reason(Saml2AuthenticationException exception) {
        String code = exception.getSaml2Error().getErrorCode();
        return switch (code) {
            case Saml2ErrorCodes.INVALID_SIGNATURE -> exception.getMessage() != null
                    && exception.getMessage().contains("unsigned") ? "unsigned" : "signature_invalid";
            case Saml2ErrorCodes.INVALID_ISSUER -> "issuer_mismatch";
            case Saml2ErrorCodes.INVALID_DESTINATION -> "destination_mismatch";
            case Saml2ErrorCodes.INVALID_IN_RESPONSE_TO -> "in_response_to_mismatch";
            case Saml2ErrorCodes.DECRYPTION_ERROR -> "decryption_failed";
            default -> "invalid_response";
        };
    }

    private static @Nullable Instant sessionNotOnOrAfter(Assertion assertion) {
        return assertion.getAuthnStatements()
            .stream()
            .map(AuthnStatement::getSessionNotOnOrAfter)
            .filter(Objects::nonNull)
            .min(Instant::compareTo)
            .orElse(null);
    }

    @Override
    public boolean served() {
        return licensing.has(Feature.SAML);
    }

    @Override
    public List<LoginProvider> loginProviders() {
        if (!served()) {
            return List.of();
        }
        List<LoginProvider> providers = new ArrayList<>();
        new TreeMap<>(settings.properties().providers()).forEach((id, provider) -> {
            if (registrations.findByRegistrationId(id) != null) {
                providers.add(new LoginProvider(id, provider.name() != null ? provider.name() : id,
                        SamlRegistrations.START_PATH + id));
            }
        });
        return providers;
    }

    @Override
    public List<ProviderState> providersOf(UUID companyId) {
        List<ProviderState> states = new ArrayList<>();
        String base = settings.publicBaseUrl();
        List<Certificate> sp = settings.credentials()
            .all()
            .stream()
            .map(SamlLoginImpl::certificate)
            .toList();
        new TreeMap<>(settings.properties().providers()).forEach((id, provider) -> {
            if (!settings.fills(id, companyId)) {
                return;
            }
            SamlMetadataStore.Row row = registrations.stored(id).orElse(null);
            states.add(new ProviderState(id, provider.name() != null ? provider.name() : id,
                    base + SamlRegistrations.METADATA_PATH + id, base + SamlRegistrations.ACS_PATH + id,
                    base + SamlRegistrations.METADATA_PATH + id, base + SamlRegistrations.START_PATH + id,
                    row != null ? row.entityId() : null,
                    provider.metadataIsFile() ? "file" : host(provider.metadataLocation()),
                    row != null ? row.readAt() : null, row != null ? row.lastError() : null,
                    row != null ? row.lastErrorAt() : null, row != null ? row.validUntil() : null,
                    row == null ? List.of() : row.certificates()
                        .stream()
                        .map(certificate -> new Certificate(certificate.subject(), certificate.sha256(),
                                certificate.notAfter()))
                        .toList(),
                    sp));
        });
        return states;
    }

    private static Certificate certificate(SamlSpCredentials.Credential credential) {
        return new Certificate(credential.certificate().getSubjectX500Principal().getName(),
                SamlIdpMetadata.fingerprint(credential.certificate()),
                credential.certificate().getNotAfter().toInstant());
    }

    private static String host(String location) {
        try {
            String host = URI.create(location).getHost();
            return host != null ? host : location;
        }
        catch (IllegalArgumentException ex) {
            return "invalid";
        }
    }
}
