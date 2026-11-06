package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * L33: the email's attempt is taken before the password is checked and given back when it was right, so parallel
 * attempts against one email cannot all pass during the hashing; an IPv6 client counts by its /64.
 */
class LoginThrottleTest {

    private static final String EMAIL = "victim@example.com";

    private final LoginThrottle throttle = new LoginThrottle(properties(3, 1000));

    @Test
    void parallelAttemptsAgainstOneEmailStopAtItsLimitBeforeAnyPasswordIsChecked() {
        // Three attempts admitted, none of them checked yet: the fourth is refused at once.
        throttle.admit(EMAIL, "192.0.2.1");
        throttle.admit(EMAIL, "192.0.2.2");
        throttle.admit(EMAIL, "192.0.2.3");

        assertThatThrownBy(() -> throttle.admit(EMAIL, "192.0.2.4")).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ProblemCode.RATE_LIMITED));
    }

    @Test
    void aRightPasswordGivesTheAttemptBack() {
        for (int i = 0; i < 10; i++) {
            throttle.admit(EMAIL, "192.0.2.1");
            throttle.refund(EMAIL);
        }
        throttle.admit(EMAIL, "192.0.2.1");
    }

    @Test
    void anIpv6ClientCountsByItsSlash64() {
        LoginThrottle perIp = new LoginThrottle(properties(1000, 2));
        perIp.admit("a@example.com", "2001:db8:1:2::1");
        perIp.admit("b@example.com", "2001:db8:1:2:ffff:ffff:ffff:fffe");

        assertThatThrownBy(() -> perIp.admit("c@example.com", "2001:0db8:0001:0002:0:0:0:abcd"))
            .isInstanceOf(ApiException.class);
        // Another /64 is another client.
        perIp.admit("d@example.com", "2001:db8:1:3::1");
    }

    @Test
    void theKeyOfAnAddress() {
        assertThat(LoginThrottle.ipKey("192.0.2.7")).isEqualTo("192.0.2.7");
        assertThat(LoginThrottle.ipKey("2001:db8:1:2:3:4:5:6")).isEqualTo("2001:db8:1:2::/64");
        assertThat(LoginThrottle.ipKey("[2001:db8:1:2::9]")).isEqualTo("2001:db8:1:2::/64");
        assertThat(LoginThrottle.ipKey("fe80::1%eth0")).isEqualTo("fe80:0:0:0::/64");
        assertThat(LoginThrottle.ipKey("::ffff:192.0.2.7")).isEqualTo("192.0.2.7");
        assertThat(LoginThrottle.ipKey("not:an.address!")).isEqualTo("not:an.address!");
    }

    private static AuthProperties properties(int perEmail, int perIp) {
        return new AuthProperties(true, true,
                new AuthProperties.LoginThrottleLimits(new AuthProperties.Limit(perEmail, Duration.ofMinutes(1)),
                        new AuthProperties.Limit(perIp, Duration.ofMinutes(1))),
                new AuthProperties.Oidc(Map.of()));
    }
}
