package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * Where an OIDC login ends: on the OAuth authorization request it interrupted (ADR-0045), else on the start's checked
 * return path (Q44), else on "/".
 */
class OidcLoginSuccessHandlerTest {

    private static final String PUBLIC_URL = "https://tinfirm.example";

    private final HttpSessionRequestCache cache = AuthorizationServerConfiguration.authorizationRequestCache();

    private final OidcLoginSuccessHandler handler = new OidcLoginSuccessHandler(PUBLIC_URL, cache);

    private MockHttpServletResponse login(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken("user", null));
        return response;
    }

    @Test
    void withoutASavedRequestTheLoginEndsOnTheRoot() throws Exception {
        assertThat(login(new MockHttpServletRequest("GET", "/login/oauth2/code/idp")).getRedirectedUrl())
            .isEqualTo(PUBLIC_URL + "/");
    }

    @Test
    void anInterruptedAuthorizationRequestIsResumedOnTheIssuerOnce() throws Exception {
        MockHttpServletRequest authorize = new MockHttpServletRequest("GET", "/oauth2/authorize");
        authorize.setQueryString("response_type=code&client_id=mcp-inspector&state=a%20b");
        authorize.setServerName("internal-host");
        authorize.setServerPort(8080);
        cache.saveRequest(authorize, new MockHttpServletResponse());

        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/idp");
        callback.setSession(authorize.getSession());
        assertThat(login(callback).getRedirectedUrl())
            .isEqualTo(PUBLIC_URL + "/oauth2/authorize?response_type=code&client_id=mcp-inspector&state=a%20b");
        assertThat(login(callback).getRedirectedUrl()).isEqualTo(PUBLIC_URL + "/");
    }

    @Test
    void theStartsReturnPathIsTheDestinationWhenNoAuthorizationRequestWaits() throws Exception {
        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/idp");
        callback.setAttribute(ReturnPaths.REQUEST_ATTRIBUTE, "/me?tab=tokens");

        assertThat(login(callback).getRedirectedUrl()).isEqualTo(PUBLIC_URL + "/me?tab=tokens");
        // Used once: the attribute is gone.
        assertThat(callback.getAttribute(ReturnPaths.REQUEST_ATTRIBUTE)).isNull();
    }

    @Test
    void theReturnPathIsCheckedAgainAtTheEnd() throws Exception {
        for (String unsafe : new String[] { "//evil.example", "https://evil.example/", "/api/me", "/%2F%2Fevil" }) {
            MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/idp");
            callback.setAttribute(ReturnPaths.REQUEST_ATTRIBUTE, unsafe);
            assertThat(login(callback).getRedirectedUrl()).as(unsafe).isEqualTo(PUBLIC_URL + "/");
        }
    }

    @Test
    void anInterruptedAuthorizationRequestWinsOverTheReturnPath() throws Exception {
        MockHttpServletRequest authorize = new MockHttpServletRequest("GET", "/oauth2/authorize");
        authorize.setQueryString("response_type=code&client_id=mcp-inspector");
        cache.saveRequest(authorize, new MockHttpServletResponse());

        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/idp");
        callback.setSession(authorize.getSession());
        callback.setAttribute(ReturnPaths.REQUEST_ATTRIBUTE, "/me");
        assertThat(login(callback).getRedirectedUrl())
            .isEqualTo(PUBLIC_URL + "/oauth2/authorize?response_type=code&client_id=mcp-inspector");
    }

    @Test
    void onlyTheAuthorizationEndpointIsHonoured() {
        assertThat(OidcLoginSuccessHandler.authorizationRequest("http://x/oauth2/authorize?a=1"))
            .isEqualTo("/oauth2/authorize?a=1");
        assertThat(OidcLoginSuccessHandler.authorizationRequest("http://x/oauth2/authorize")).isEqualTo("/oauth2/authorize");
        assertThat(OidcLoginSuccessHandler.authorizationRequest("http://x/api/me")).isNull();
        assertThat(OidcLoginSuccessHandler.authorizationRequest("http://x/oauth2/authorize/../../evil")).isNull();
        assertThat(OidcLoginSuccessHandler.authorizationRequest("https://evil.example//oauth2/authorize")).isNull();
        assertThat(OidcLoginSuccessHandler.authorizationRequest("not a url")).isNull();
        assertThat(OidcLoginSuccessHandler.authorizationRequest(null)).isNull();
    }

    @Test
    void theCacheSavesOnlyAuthorizationRequests() {
        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/oauth2/token");
        cache.saveRequest(other, new MockHttpServletResponse());
        assertThat(cache.getRequest(other, new MockHttpServletResponse())).isNull();
    }
}
