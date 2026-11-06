package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.identity.MembershipDirectory;
import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * D9, L34 (CONVENTIONS §5): a user without a password creates a token only from a session that signed in less than
 * 10 minutes ago; a token expires 90 days after its creation by default, never later; a token that names its
 * companies reaches only those.
 */
class ApiTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    private final UUID userId = UUID.randomUUID();

    private final ApiTokenRepository tokens = mock(ApiTokenRepository.class);

    private final UserRepository users = mock(UserRepository.class);

    private final ApiTokenService service = new ApiTokenService(tokens, users, mock(AuditLog.class),
            new TimeSource(Clock.fixed(NOW, ZoneOffset.UTC)), mock(PasswordHasher.class),
            mock(MembershipDirectory.class));

    @BeforeEach
    void signedIn() {
        SecurityContextHolder.getContext()
            .setAuthentication(new ActorAuthentication(new AuthenticatedUser(userId, "sso@example.com", "Sso", false)));
        when(users.lockById(userId)).thenReturn(Optional.of(userId));
        when(users.findById(userId))
            .thenReturn(Optional.of(UserEntity.oidc(userId, "sso@example.com", "Sso", "https://idp", "sub", NOW)));
        when(tokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void signedOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anIdentityProvidersUserNeedsARecentSignIn() {
        ApiTokenService.CreateRequest stale = request(NOW.minus(Duration.ofMinutes(11)));
        assertThatThrownBy(() -> service.create(stale)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ProblemCode.RECENT_SIGN_IN_REQUIRED));
        assertThatThrownBy(() -> service.create(request(null))).isInstanceOf(ApiException.class);

        ApiTokenService.Created created = service.create(request(NOW.minus(Duration.ofMinutes(9))));
        assertThat(created.token().getExpiresAt()).isEqualTo(NOW.plus(ApiTokenService.LIFETIME));
    }

    @Test
    void aTokenExpiresAtMost90DaysAhead() {
        ApiTokenService.CreateRequest tooLate = new ApiTokenService.CreateRequest("x", ApiScope.READ,
                NOW.plus(Duration.ofDays(91)), null, null, NOW);
        assertThatThrownBy(() -> service.create(tooLate)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ProblemCode.VALIDATION_FAILED));
    }

    @Test
    void aTokenThatNamesItsCompaniesReachesOnlyThose() {
        UUID named = UUID.randomUUID();
        ApiTokenEntity token = new ApiTokenEntity(UUID.randomUUID(), userId, "x", ApiScope.READ,
                ApiTokens.hash("tfp_x"), NOW, NOW.plus(Duration.ofDays(1)), java.util.Set.of(named));
        assertThat(token.reaches(named)).isTrue();
        assertThat(token.reaches(UUID.randomUUID())).isFalse();
        assertThat(token.names(named)).isTrue();
        assertThat(token.getCompanyIds()).containsExactly(named);

        ApiTokenEntity everywhere = new ApiTokenEntity(UUID.randomUUID(), userId, "y", ApiScope.READ,
                ApiTokens.hash("tfp_y"), NOW, NOW.plus(Duration.ofDays(1)), null);
        assertThat(everywhere.reaches(UUID.randomUUID())).isTrue();
        assertThat(everywhere.names(named)).isFalse();
        assertThat(everywhere.getCompanyIds()).isNull();
    }

    private static ApiTokenService.CreateRequest request(Instant sessionCreatedAt) {
        return new ApiTokenService.CreateRequest("laptop", ApiScope.READ, null, null, null, sessionCreatedAt);
    }
}
