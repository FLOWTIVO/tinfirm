package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.BearerGrant;
import com.flowtivo.tinfirm.shared.security.BearerGrantHolder;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.web.ApiException;

class BearerAuthenticationFilterTest {

    private static final AuthenticatedUser USER = new AuthenticatedUser(UUID.randomUUID(), "u@example.test", "U",
            false);

    private final List<String> asked = new ArrayList<>();

    /** Captures the problem a filter writes instead of rendering it. */
    private final AtomicReference<ApiException> written = new AtomicReference<>();

    private final HandlerExceptionResolver resolver = (request, response, handler, ex) -> {
        written.set((ApiException) ex);
        response.setStatus(((ApiException) ex).status().value());
        return new ModelAndView();
    };

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private BearerCredentialResolver resolver(String name, String prefix, Optional<ResolvedBearer> result) {
        return new BearerCredentialResolver() {
            @Override
            public boolean supports(String token) {
                asked.add(name);
                return token.startsWith(prefix);
            }

            @Override
            public Optional<ResolvedBearer> resolve(String token) {
                asked.add(name + ":resolve");
                return result;
            }
        };
    }

    private static ResolvedBearer bearer(BearerGrant.Kind kind, ApiScope scope) {
        return new ResolvedBearer(USER, new BearerGrant(kind, UUID.randomUUID(), scope));
    }

    @Test
    void theFirstResolverThatSupportsTheTokenDecides() throws Exception {
        ResolvedBearer pat = bearer(BearerGrant.Kind.PERSONAL_ACCESS_TOKEN, ApiScope.WRITE);
        BearerAuthenticationFilter filter = new BearerAuthenticationFilter(List.of(
                resolver("pat", "tfp_", Optional.of(pat)),
                resolver("jwt", "ey", Optional.of(bearer(BearerGrant.Kind.OAUTH_GRANT, ApiScope.READ)))),
                new ProblemWriter(resolver));

        AtomicReference<Object> principal = new AtomicReference<>();
        AtomicReference<BearerGrant> grant = new AtomicReference<>();
        AtomicReference<TenantContext> tenant = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/companies/c/mcp");
        request.addHeader("Authorization", "Bearer tfp_x");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            principal.set(SecurityContextHolder.getContext().getAuthentication());
            grant.set(BearerGrantHolder.current().orElse(null));
            tenant.set(TenantContextHolder.current());
        });

        assertThat(asked).containsExactly("pat", "pat:resolve");
        assertThat(principal.get()).isInstanceOfSatisfying(ActorAuthentication.class,
                auth -> assertThat(auth.getPrincipal()).isEqualTo(USER));
        assertThat(grant.get()).isEqualTo(pat.grant());
        assertThat(grant.get().auditDetailKey()).isEqualTo("tokenId");
        assertThat(grant.get().allows(ApiScope.READ)).isTrue();
        assertThat(grant.get().allows(ApiScope.DECIDE)).isFalse();
        assertThat(tenant.get()).isEqualTo(TenantContext.forUser(USER.id(), false));
        // Nothing leaks past the request.
        assertThat(BearerGrantHolder.current()).isEmpty();
        assertThat(TenantContextHolder.current()).isEqualTo(TenantContext.EMPTY);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        asked.clear();
        MockHttpServletRequest jwt = new MockHttpServletRequest("POST", "/api/companies/c/mcp");
        jwt.addHeader("Authorization", "Bearer eyJ.x.y");
        filter.doFilter(jwt, new MockHttpServletResponse(), (req, res) -> grant.set(BearerGrantHolder.current()
            .orElseThrow()));
        assertThat(asked).containsExactly("pat", "jwt", "jwt:resolve");
        assertThat(grant.get().kind()).isEqualTo(BearerGrant.Kind.OAUTH_GRANT);
        assertThat(grant.get().auditDetailKey()).isEqualTo("grantId");
    }

    @Test
    void aRefusedOrUnsupportedTokenIsTokenInvalid() throws Exception {
        BearerAuthenticationFilter filter = new BearerAuthenticationFilter(
                List.of(resolver("pat", "tfp_", Optional.empty())), new ProblemWriter(resolver));
        for (String header : List.of("Bearer tfp_revoked", "Bearer opaque-value", "Bearer ")) {
            written.set(null);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/companies/c/mcp");
            request.addHeader("Authorization", header);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<Boolean> passed = new AtomicReference<>(false);
            filter.doFilter(request, response, (req, res) -> passed.set(true));

            assertThat(passed.get()).as(header).isFalse();
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
            assertThat(written.get().code()).hasToString("TOKEN_INVALID");
        }
    }

    @Test
    void aRequestWithoutABearerContinuesUnauthenticated() throws Exception {
        BearerAuthenticationFilter filter = new BearerAuthenticationFilter(
                List.of(resolver("pat", "tfp_", Optional.empty())), new ProblemWriter(resolver));
        for (String header : new String[] { null, "Basic dXNlcjpwYXNz" }) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/companies/c/mcp");
            if (header != null) {
                request.addHeader("Authorization", header);
            }
            AtomicReference<Object> principal = new AtomicReference<>("unset");
            filter.doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> principal.set(SecurityContextHolder.getContext().getAuthentication()));
            assertThat(principal.get()).isNull();
        }
        assertThat(asked).isEmpty();
    }

    @Test
    void theOriginFilterRefusesBrowsersButTinfirmsOwnPages() throws Exception {
        OriginRefusalFilter filter = new OriginRefusalFilter(new ProblemWriter(resolver),
                URI.create("https://tinfirm.example"));
        for (String origin : new String[] { "https://evil.example", "https://tinfirm.example.evil.example",
                "http://tinfirm.example", "https://tinfirm.example:8443", "null" }) {
            MockHttpServletRequest browser = new MockHttpServletRequest("POST", "/api/companies/c/mcp");
            browser.addHeader("Origin", origin);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<Boolean> passed = new AtomicReference<>(false);
            filter.doFilter(browser, response, (req, res) -> passed.set(true));
            assertThat(passed.get()).as(origin).isFalse();
            assertThat(response.getStatus()).as(origin).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        }

        // No Origin: a client outside the browser. Tinfirm's own origin: the Outlook add-in's task pane (ADR-0074).
        MockHttpServletRequest taskPane = new MockHttpServletRequest("POST", "/api/companies/c/mcp");
        taskPane.addHeader("Origin", "https://Tinfirm.example");
        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
                new MockHttpServletRequest("POST", "/api/companies/c/mcp"), taskPane }) {
            AtomicReference<Boolean> passed = new AtomicReference<>(false);
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(true));
            assertThat(passed.get()).isTrue();
        }
    }
}
