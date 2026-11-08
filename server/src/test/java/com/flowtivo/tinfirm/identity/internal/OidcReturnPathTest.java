package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * An OIDC login's return path (Q44): taken from the start only when it passes {@link ReturnPaths}, kept with the
 * authorization request in the session, and handed to the callback that removes that request, found by its state.
 */
class OidcReturnPathTest {

    private static OAuth2AuthorizationRequest request(String state) {
        return OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://idp.example/authorize")
            .clientId("tinfirm")
            .redirectUri("https://tinfirm.example/login/oauth2/code/idp")
            .state(state)
            .attributes(Map.of("registration_id", "idp"))
            .build();
    }

    private static OAuth2AuthorizationRequestResolver resolving(OAuth2AuthorizationRequest request) {
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(jakarta.servlet.http.HttpServletRequest http) {
                return request;
            }

            @Override
            public OAuth2AuthorizationRequest resolve(jakarta.servlet.http.HttpServletRequest http, String id) {
                return request;
            }
        };
    }

    private static MockHttpServletRequest start(String returnTo) {
        MockHttpServletRequest start = new MockHttpServletRequest("GET", "/oauth2/authorization/idp");
        if (returnTo != null) {
            start.setParameter(ReturnPaths.PARAMETER, returnTo);
        }
        return start;
    }

    @Test
    void theStartsCheckedReturnPathBecomesAnAttributeOfTheAuthorizationRequest() {
        OAuth2AuthorizationRequestResolver resolver = OidcReturnPath.resolver(resolving(request("s1")));

        OAuth2AuthorizationRequest resolved = resolver.resolve(start("/me?tab=tokens"));

        assertThat(resolved.<String>getAttribute(OidcReturnPath.ATTRIBUTE)).isEqualTo("/me?tab=tokens");
        assertThat(resolved.<String>getAttribute("registration_id")).isEqualTo("idp");
        assertThat(resolved.getState()).isEqualTo("s1");
    }

    @Test
    void anUncheckedReturnPathIsDropped() {
        OAuth2AuthorizationRequestResolver resolver = OidcReturnPath.resolver(resolving(request("s1")));
        for (String unsafe : new String[] { "https://evil.example", "//evil.example", "/\\evil.example",
                "/%2F%2Fevil.example", "/api/me", "/me\r\nx" }) {
            assertThat(resolver.resolve(start(unsafe), "idp").<String>getAttribute(OidcReturnPath.ATTRIBUTE))
                .as(unsafe)
                .isNull();
        }
        assertThat(resolver.resolve(start(null)).<String>getAttribute(OidcReturnPath.ATTRIBUTE)).isNull();
    }

    @Test
    void noAuthorizationRequestStaysNone() {
        assertThat(OidcReturnPath.resolver(resolving(null)).resolve(start("/me"))).isNull();
    }

    @Test
    void theCallbackOfThatLoginOnlyReceivesThePathOnce() {
        AuthorizationRequestRepository<OAuth2AuthorizationRequest> repository = OidcReturnPath.repository();
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest started = start("/me");
        started.setSession(session);
        OAuth2AuthorizationRequest saved = OidcReturnPath.resolver(resolving(request("state-1"))).resolve(started);
        repository.saveAuthorizationRequest(saved, started, new MockHttpServletResponse());

        // A callback with another state finds nothing and gets no path.
        MockHttpServletRequest forged = callback(session, "state-2");
        assertThat(repository.removeAuthorizationRequest(forged, new MockHttpServletResponse())).isNull();
        assertThat(forged.getAttribute(ReturnPaths.REQUEST_ATTRIBUTE)).isNull();

        MockHttpServletRequest callback = callback(session, "state-1");
        assertThat(repository.removeAuthorizationRequest(callback, new MockHttpServletResponse())).isNotNull();
        assertThat(callback.getAttribute(ReturnPaths.REQUEST_ATTRIBUTE)).isEqualTo("/me");

        MockHttpServletRequest again = callback(session, "state-1");
        assertThat(repository.removeAuthorizationRequest(again, new MockHttpServletResponse())).isNull();
        assertThat(again.getAttribute(ReturnPaths.REQUEST_ATTRIBUTE)).isNull();
    }

    private static MockHttpServletRequest callback(MockHttpSession session, String state) {
        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/idp");
        callback.setSession(session);
        callback.setParameter("state", state);
        callback.setParameter("code", "code");
        // What the IdP sends is never read for the destination.
        callback.setParameter(ReturnPaths.PARAMETER, "/c/elsewhere");
        return callback;
    }
}
