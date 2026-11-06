package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import com.flowtivo.tinfirm.identity.ProvisionedMemberships;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.support.SamlTestKeys;

/** What stops the start with SAML providers declared (ADR-0086, decisions 1, 2 and 4). */
class SamlSettingsTest {

    private static final SamlTestKeys SP = SamlTestKeys.generate("sp");

    private final ProvisionedMemberships memberships = mock(ProvisionedMemberships.class);

    private final TimeSource time = new TimeSource(Clock.systemUTC());

    @Test
    void validProvidersStartWithTheirKeys() {
        when(memberships.declares("acme")).thenReturn(true);

        SamlSettings settings = settings(Map.of("corp", provider("acme")), "https://tinfirm.example.com");

        assertThat(settings.declared()).isTrue();
        assertThat(settings.credentials().active().kid()).isEqualTo("k1");
        assertThat(settings.publicBaseUrl()).isEqualTo("https://tinfirm.example.com");
    }

    @Test
    void withoutProvidersNothingIsChecked() {
        SamlSettings settings = new SamlSettings(new SamlProperties(Map.of(), new SamlProperties.Sp(Map.of(), null)),
                new TinfirmClientRegistrations(List.of()), memberships, tinfirm("http://tinfirm.example.com"), time);

        assertThat(settings.declared()).isFalse();
    }

    @Test
    void anIdAnOidcProviderUsesTooOrThatIsNotLowerCaseStopsTheStart() {
        when(memberships.declares("acme")).thenReturn(true);

        assertThatIllegalStateException()
            .isThrownBy(() -> settings(Map.of("keycloak", provider("acme")), "https://tinfirm.example.com"))
            .withMessageContaining("an OIDC provider has the id keycloak too");
        assertThatIllegalStateException()
            .isThrownBy(() -> settings(Map.of("Corp_1", provider("acme")), "https://tinfirm.example.com"))
            .withMessageContaining("1 to 64 lower-case letters and digits");
    }

    @Test
    void anUndeclaredCompanyOrRolesThatCannotBeAppliedStopTheStart() {
        assertThatIllegalStateException()
            .isThrownBy(() -> settings(Map.of("corp", provider("nowhere")), "https://tinfirm.example.com"))
            .withMessageContaining("TINFIRM_AUTH_SAML_PROVIDERS_CORP_COMPANY names nowhere, which is not declared");
        when(memberships.declares("acme")).thenReturn(true);
        SamlProperties.Provider noMap = new SamlProperties.Provider(null, "https://idp.example.com/m.xml", null,
                "acme", CompanyRole.MEMBER, List.of(), SamlProperties.Provider.NAMEID, List.of("email"),
                List.of("displayName"), null, "groups", List.of(), List.of(), List.of(), true, false,
                Duration.ofHours(12), AuthProperties.ScimMatch.EXTERNAL_ID);
        assertThatIllegalStateException()
            .isThrownBy(() -> settings(Map.of("corp", noMap), "https://tinfirm.example.com"))
            .withMessageContaining("TINFIRM_AUTH_SAML_PROVIDERS_CORP_ROLE_CLAIM needs at least one of");
    }

    @Test
    void aPublicUrlOverHttpStopsTheStartExceptOnLocalhost() {
        when(memberships.declares("acme")).thenReturn(true);

        assertThatIllegalStateException()
            .isThrownBy(() -> settings(Map.of("corp", provider("acme")), "http://tinfirm.example.com"))
            .withMessageContaining("SAML sign-in needs an https TINFIRM_PUBLIC_URL");
        assertThat(settings(Map.of("corp", provider("acme")), "http://localhost:8080").declared()).isTrue();
    }

    /**
     * H4 of the security review of 2026-10-27: two providers that read different metadata never name one IdP, and no
     * provider names an OIDC provider's issuer; two that read the same document are one IdP with two settings.
     */
    @Test
    void anEntityIdAnotherProviderOrAnOidcIssuerNamesStopsTheStart() {
        when(memberships.declares("acme")).thenReturn(true);
        String idp = "https://idp.example.com/entity";

        assertThatIllegalStateException()
            .isThrownBy(() -> settings(Map.of("corp", provider("acme", "https://idp.example.com/m.xml", idp), "other",
                    provider("acme", "https://other.example.com/m.xml", idp)), "https://tinfirm.example.com"))
            .withMessageContaining("the SAML providers corp and other name the IdP " + idp + " from different metadata");
        assertThatIllegalStateException()
            .isThrownBy(() -> settings(Map.of("corp", provider("acme", "https://idp.example.com/m.xml",
                    "https://kc.example.com/realms/tinfirm")), "https://tinfirm.example.com"))
            .withMessageContaining("which is an OIDC provider's issuer");
        assertThat(settings(Map.of("corp", provider("acme", "https://idp.example.com/m.xml", idp), "corpenc",
                provider("acme", "https://idp.example.com/m.xml", idp)), "https://tinfirm.example.com").declared())
            .isTrue();
        assertThat(settings(Map.of("corp", provider("acme")), "https://tinfirm.example.com").oidcIssuers())
            .containsExactly("https://kc.example.com/realms/tinfirm");
    }

    private SamlSettings settings(Map<String, SamlProperties.Provider> providers, String publicUrl) {
        SamlProperties.Sp sp = new SamlProperties.Sp(Map.of("k1",
                new SamlProperties.Credential(SP.privateKeyPem(), SP.certificatePem())), "k1");
        return new SamlSettings(new SamlProperties(providers, sp), new TinfirmClientRegistrations(List.of(keycloak())),
                memberships, tinfirm(publicUrl), time);
    }

    private static TinfirmProperties tinfirm(String publicUrl) {
        return new TinfirmProperties(URI.create(publicUrl), Set.of("api", "worker"));
    }

    private static SamlProperties.Provider provider(String company) {
        return provider(company, "https://idp.example.com/m.xml", null);
    }

    private static SamlProperties.Provider provider(String company, String metadataLocation, String entityId) {
        return new SamlProperties.Provider(null, metadataLocation, entityId, company, CompanyRole.MEMBER,
                List.of(), SamlProperties.Provider.NAMEID, List.of("email"), List.of("displayName"), null, null,
                List.of(), List.of(), List.of(), true, false, Duration.ofHours(12),
                AuthProperties.ScimMatch.EXTERNAL_ID);
    }

    private static ClientRegistration keycloak() {
        return ClientRegistration.withRegistrationId("keycloak")
            .clientId("tinfirm")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://tinfirm.example.com/login/oauth2/code/keycloak")
            .authorizationUri("https://kc.example.com/auth")
            .tokenUri("https://kc.example.com/token")
            .issuerUri("https://kc.example.com/realms/tinfirm")
            .build();
    }
}
