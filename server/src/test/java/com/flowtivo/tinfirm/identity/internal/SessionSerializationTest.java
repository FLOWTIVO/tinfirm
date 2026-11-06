package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Serializable;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.management.ObjectName;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.util.LinkedMultiValueMap;

/**
 * L21 (threat model R18): what Tinfirm keeps in a session survives Spring Session's round trip through the filtered
 * deserialiser, and a class outside the allow-list is never instantiated.
 */
class SessionSerializationTest {

    private final ConversionService conversions = new SessionSerializationConfiguration()
        .springSessionConversionService();

    @Test
    void aLocalLoginsContextRoundTrips() {
        UUID userId = UUID.randomUUID();
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken
            .authenticated(new SessionPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES);
        authentication.setDetails(new WebAuthenticationDetails(new MockHttpServletRequest()));

        SecurityContextImpl read = roundTrip(new SecurityContextImpl(authentication));

        assertThat(read.getAuthentication().getPrincipal()).isEqualTo(new SessionPrincipal(userId));
        assertThat(read.getAuthentication().isAuthenticated()).isTrue();
    }

    @Test
    void anOidcLoginsContextRoundTrips() {
        UUID userId = UUID.randomUUID();
        Instant issued = Instant.parse("2026-10-27T10:00:00Z");
        OidcIdToken idToken = new OidcIdToken("id-token-value", issued, issued.plusSeconds(300),
                Map.of("iss", URI.create("https://idp.example.com/realms/acme").toString(), "sub", "subject-1", "aud",
                        List.of("tinfirm"), "email", "alice@example.com", "email_verified", true, "groups",
                        List.of("admins", "staff")));
        OidcUserInfo userInfo = new OidcUserInfo(Map.of("sub", "subject-1", "name", "Alice"));
        TinfirmOidcUser user = new TinfirmOidcUser(userId,
                List.of(new OidcUserAuthority(idToken, userInfo), new SimpleGrantedAuthority("SCOPE_openid")), idToken,
                userInfo);
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(),
                "keycloak");

        SecurityContextImpl read = roundTrip(new SecurityContextImpl(authentication));

        assertThat(((TinfirmOidcUser) read.getAuthentication().getPrincipal()).userId()).isEqualTo(userId);
        assertThat(((OAuth2AuthenticationToken) read.getAuthentication()).getAuthorizedClientRegistrationId())
            .isEqualTo("keycloak");
    }

    @Test
    void aSamlSignInsContextRoundTrips() {
        UUID userId = UUID.randomUUID();
        SamlSignIns.SamlAuthentication authentication = new SamlSignIns.SamlAuthentication(new SessionPrincipal(userId),
                Instant.parse("2026-10-27T18:00:00Z"));

        SecurityContextImpl read = roundTrip(new SecurityContextImpl(authentication));

        assertThat(read.getAuthentication().getPrincipal()).isEqualTo(new SessionPrincipal(userId));
    }

    @Test
    void theSavedAuthorizationRequestAndTheLoginsRequestRoundTrip() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setQueryString("response_type=code&client_id=https%3A%2F%2Fclaude.ai%2Fclient.json&state=xyz");
        request.setParameter("response_type", "code");
        request.addHeader("Accept-Language", "de-DE");
        request.setCookies(new Cookie("TINFIRM_SESSION", "abc"));
        DefaultSavedRequest saved = roundTrip(new DefaultSavedRequest(request));
        assertThat(saved.getRequestURI()).isEqualTo("/oauth2/authorize");

        OAuth2AuthorizationRequest authorization = OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://idp.example.com/auth")
            .clientId("tinfirm")
            .redirectUri("https://tinfirm.example.com/login/oauth2/code/keycloak")
            .scopes(Set.of("openid", "email"))
            .state("state-1")
            .additionalParameters(Map.of("nonce", "n-1"))
            .attributes(Map.of("registration_id", "keycloak", "code_verifier", "v-1"))
            .build();
        assertThat(roundTrip(authorization).getState()).isEqualTo("state-1");
        assertThat(roundTrip(1_761_588_000_000L)).isEqualTo(1_761_588_000_000L);
    }

    @Test
    void aClassOutsideTheAllowListIsNeverRead() throws Exception {
        assertRefused(new ObjectName("tinfirm:type=Gadget"));
        // Spring's core is not on the list: its serialisable classes carry known gadget chains.
        LinkedMultiValueMap<String, String> springCore = new LinkedMultiValueMap<>();
        springCore.add("a", "b");
        assertRefused(springCore);
        // Nested inside an allowed one, too.
        assertRefused(new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                new ObjectName("tinfirm:type=Nested"), null, AuthorityUtils.NO_AUTHORITIES)));
    }

    private void assertRefused(Serializable value) {
        byte[] bytes = (byte[]) conversions.convert(value, TypeDescriptor.valueOf(Object.class),
                TypeDescriptor.valueOf(byte[].class));
        assertThatThrownBy(() -> conversions.convert(bytes, TypeDescriptor.valueOf(byte[].class),
                TypeDescriptor.valueOf(Object.class)))
            .isInstanceOf(ConversionFailedException.class)
            .hasRootCauseInstanceOf(java.io.InvalidClassException.class);
    }

    /** Spring Session's round trip: an attribute to bytes and back, as JdbcIndexedSessionRepository converts it. */
    @SuppressWarnings("unchecked")
    private <T> T roundTrip(T value) {
        byte[] bytes = (byte[]) conversions.convert(value, TypeDescriptor.valueOf(Object.class),
                TypeDescriptor.valueOf(byte[].class));
        return (T) conversions.convert(bytes, TypeDescriptor.valueOf(byte[].class), TypeDescriptor.valueOf(Object.class));
    }
}
