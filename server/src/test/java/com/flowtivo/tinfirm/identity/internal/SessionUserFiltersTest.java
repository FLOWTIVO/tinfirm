package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.flowtivo.tinfirm.shared.security.ActorAuthentication;

/**
 * M17 of the security review of 2026-10-27: the session filters of the web app ({@link ActiveUserFilter}) and of the
 * authorization server ({@link AuthorizationServerUserFilter}) end a session whose principal is not a Tinfirm user,
 * such as the plain OAuth 2.0 user a registration without {@code openid} would leave, instead of passing it on.
 */
class SessionUserFiltersTest {

    private final UserRepository users = mock(UserRepository.class);

    private final OAuthCompanyAccess access = mock(OAuthCompanyAccess.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aPrincipalThatIsNoTinfirmUserLosesTheSessionOnTheWebApp() throws Exception {
        assertEnded(new ActiveUserFilter(users));
        verifyNoInteractions(users);
    }

    @Test
    void aPrincipalThatIsNoTinfirmUserLosesTheSessionOnTheAuthorizationServer() throws Exception {
        assertEnded(new AuthorizationServerUserFilter(access));
        verifyNoInteractions(access);
    }

    @Test
    void anActiveSessionUserActsAndAnAnonymousRequestPasses() throws Exception {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(UserEntity.local(userId, "a@example.com", "A", "hash",
                false, Instant.now())));
        MockHttpSession session = new MockHttpSession();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new SessionPrincipal(userId), null, List.of(new SimpleGrantedAuthority(ActorAuthentication.ROLE_USER))));

        Authentication seen = run(new ActiveUserFilter(users), session);

        assertThat(session.isInvalid()).isFalse();
        assertThat(seen).isInstanceOf(ActorAuthentication.class);

        MockHttpSession anonymous = new MockHttpSession();
        AnonymousAuthenticationToken token = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(token);
        assertThat(run(new ActiveUserFilter(users), anonymous)).isSameAs(token);
        assertThat(anonymous.isInvalid()).isFalse();
    }

    /** A session of a plain OAuth 2.0 sign-in, whose name is a member's id: ended, and the request is anonymous. */
    private static void assertEnded(Filter filter) throws Exception {
        MockHttpSession session = new MockHttpSession();
        DefaultOAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
                Map.of("id", UUID.randomUUID().toString()), "id");
        SecurityContextHolder.getContext()
            .setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "plainoauth"));

        Authentication seen = run(filter, session);

        assertThat(session.isInvalid()).isTrue();
        assertThat(seen).isNull();
    }

    /** Runs the filter; the authentication the rest of the chain sees. */
    private static Authentication run(Filter filter, MockHttpSession session) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setSession(session);
        AtomicReference<Authentication> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
        return seen.get();
    }
}
