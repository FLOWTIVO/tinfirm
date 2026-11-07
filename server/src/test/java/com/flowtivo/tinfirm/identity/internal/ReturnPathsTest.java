package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The return path of a sign-in through an identity provider (threat model 3.4, Q44): only a same-origin path of a
 * page of the web app, every open-redirect form refused, plain or encoded.
 */
class ReturnPathsTest {

    @ParameterizedTest
    @ValueSource(strings = { "/", "/me", "/me?tab=tokens", "/c/0190a1b2-0000-7000-8000-000000000001/inbox",
            "/c/1/conversations/2?filter=open&sort=-updated", "/c/1/search?q=a%2Fb", "/c/1/search?q=100%25",
            "/c/1/search?q=caf%C3%A9", "/admin/users", "/loginhelp", "/apis", "/c/1/x+y" })
    void acceptsAPageOfTheWebApp(String path) {
        assertThat(ReturnPaths.check(path)).isEqualTo(path);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            // Absolute URLs, any scheme.
            "https://evil.example/", "http://evil.example", "javascript:alert(1)", "data:text/html,x",
            "evil.example/me", "me",
            // Scheme-relative and its look-alikes.
            "//evil.example", "//evil.example/me", "///evil.example", "/\\evil.example", "\\\\evil.example",
            "\\/evil.example",
            // Backslashes anywhere.
            "/me\\..\\api", "/c\\evil.example",
            // Control characters and whitespace, raw.
            "/me\r\nSet-Cookie: x=y", "/me\n", "/me\t", "/me\u0000", "/me\u007f", "/me ", " /me", "/m e",
            // Non-ASCII, raw: browsers send it encoded.
            "/caf\u00e9", "/\u2028", "/me\u202e",
            // Encoded variants of the above.
            "/%2Fevil.example", "/%2F%2Fevil.example", "/%5Cevil.example", "/%5cevil.example", "/me%5C..%5Capi",
            "/%0d%0aSet-Cookie:%20x=y", "/me%00", "/me%7F", "/me%C2%85", "/me%E2%80%A8", "/me%E2%80%AE",
            "/%252F%252Fevil.example", "/%25252F%25252Fevil.example", "/%2525252Fevil.example",
            "%2F%2Fevil.example", "%2Fme", "/me?next=%0d%0a", "/me?x=%5C",
            // Malformed encodings.
            "/me%", "/me%2", "/me%zz", "/me%C3", "/me%FF",
            // Dot segments, plain and encoded, which a server or browser resolves to another path.
            "/.", "/..", "/./api/me", "/c/../api/me", "/c/%2e%2e/api/me", "/c/%2E%2E/api/me", "/c/.%2e/api/me",
            "/%2e/api/me", "/c/%252e%252e/api/me",
            // Path parameters, which the servlet container strips.
            "/api;x/me", "/me;jsessionid=1",
            // A fragment.
            "/me#x", "/me%23x/..",
            // The server's own paths, not pages.
            "/api", "/api/", "/api/me", "/API/me", "/api/companies/1/mcp", "/%61pi/me", "/%2561pi/me",
            "/oauth2/authorize?client_id=x", "/oauth2/authorization/idp", "/login", "/login?error=X",
            "/login/oauth2/code/idp", "/login/saml2/sso/kc", "/saml2/authenticate/kc", "/actuator/health",
            "/.well-known/oauth-authorization-server", "/scim/v2/Users" })
    void refusesAnythingElse(String value) {
        assertThat(ReturnPaths.check(value)).as("%s", value).isNull();
    }

    @org.junit.jupiter.api.Test
    void refusesAnOverlongPath() {
        assertThat(ReturnPaths.check("/" + "a".repeat(ReturnPaths.MAX_LENGTH))).isNull();
        assertThat(ReturnPaths.check("/" + "a".repeat(ReturnPaths.MAX_LENGTH - 1))).isNotNull();
    }
}
