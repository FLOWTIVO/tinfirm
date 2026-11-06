package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationConsentAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

class ScopeWideningRequestConverterTest {

    private static final String RESOURCE = "http://localhost:8080/api/companies/c/mcp";

    private static final Authentication PERSON = new TestingAuthenticationToken("person", null);

    /** What Spring's converter makes of an authorization request for {@code scope}. */
    private static OAuth2AuthorizationCodeRequestAuthenticationToken request(String scope) {
        return new OAuth2AuthorizationCodeRequestAuthenticationToken("http://localhost:8080/oauth2/authorize",
                "client", PERSON, "http://localhost/callback", "s", new HashSet<>(Arrays.asList(scope.split(" "))),
                Map.of("resource", RESOURCE, "code_challenge", "challenge"));
    }

    private static OAuth2AuthorizationCodeRequestAuthenticationToken convert(String scope) {
        Authentication converted = new ScopeWideningRequestConverter(http -> request(scope))
            .convert(new MockHttpServletRequest());
        assertThat(converted).isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationToken.class);
        return (OAuth2AuthorizationCodeRequestAuthenticationToken) converted;
    }

    @Test
    void aRequestForAnyLevelBecomesARequestForAllThreeAndKeepsWhatWasSent() {
        OAuth2AuthorizationCodeRequestAuthenticationToken read = convert("tinfirm:read");
        assertThat(read.getScopes()).containsExactlyInAnyOrder("tinfirm:read", "tinfirm:write", "tinfirm:decide");
        assertThat(read.getAdditionalParameters())
            .containsEntry(ScopeWideningRequestConverter.REQUESTED_SCOPE, "tinfirm:read")
            .containsEntry("resource", RESOURCE)
            .containsEntry("code_challenge", "challenge");
        assertThat(read.getAuthorizationUri()).isEqualTo("http://localhost:8080/oauth2/authorize");
        assertThat(read.getClientId()).isEqualTo("client");
        assertThat(read.getPrincipal()).isSameAs(PERSON);
        assertThat(read.getRedirectUri()).isEqualTo("http://localhost/callback");
        assertThat(read.getState()).isEqualTo("s");

        assertThat(convert("tinfirm:decide tinfirm:write").getAdditionalParameters())
            .containsEntry(ScopeWideningRequestConverter.REQUESTED_SCOPE, "tinfirm:write tinfirm:decide");
    }

    @Test
    void otherScopesStayForTheValidatorsToRefuse() {
        assertThat(convert("openid tinfirm:read").getScopes()).containsExactlyInAnyOrder("tinfirm:read",
                "tinfirm:write", "tinfirm:decide", "openid");
        OAuth2AuthorizationCodeRequestAuthenticationToken unknown = convert("tinfirm:admin");
        assertThat(unknown.getScopes()).containsExactly("tinfirm:admin");
        assertThat(unknown.getAdditionalParameters()).doesNotContainKey(ScopeWideningRequestConverter.REQUESTED_SCOPE);
    }

    @Test
    void anythingElseIsPassedThrough() {
        assertThat(new ScopeWideningRequestConverter(http -> null).convert(new MockHttpServletRequest())).isNull();
        assertThat(new ScopeWideningRequestConverter(http -> PERSON).convert(new MockHttpServletRequest()))
            .isSameAs(PERSON);
    }

    @Test
    void onlySpringsAuthorizationRequestConverterIsWrapped() {
        List<AuthenticationConverter> converters = new ArrayList<>(List.of(
                new OAuth2AuthorizationCodeRequestAuthenticationConverter(),
                new OAuth2AuthorizationConsentAuthenticationConverter()));
        ScopeWideningRequestConverter.wrap(converters);
        assertThat(converters.get(0)).isInstanceOf(ScopeWideningRequestConverter.class);
        assertThat(converters.get(1)).isInstanceOf(OAuth2AuthorizationConsentAuthenticationConverter.class);
    }
}
