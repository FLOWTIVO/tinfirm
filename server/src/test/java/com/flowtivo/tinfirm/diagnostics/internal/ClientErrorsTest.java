package com.flowtivo.tinfirm.diagnostics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedFrame;
import com.flowtivo.tinfirm.shared.api.model.ClientError;
import com.flowtivo.tinfirm.shared.api.model.ClientErrorFrame;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/** ADR-0070, §1 and §2: a web app's error keeps names, patterns and paths only. */
class ClientErrorsTest {

    private static final String CHROME = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/141.0.0.0 Safari/537.36";

    private final ClientErrors errors = new ClientErrors(
            new TinfirmProperties(URI.create("https://tinfirm.acme.example"), Set.of("api")),
            new TimeSource(Clock.fixed(Instant.parse("2026-10-16T12:00:30Z"), ZoneOffset.UTC)), properties(3));

    @Test
    void keepsTheTypeMaskedMessageRouteAndItsOwnBundlesWithoutFunctionNames() {
        CapturedError error = errors.of(new ClientError("TypeError")
            .message("Cannot read 'name' of undefined for bob@acme.example")
            .route("/c/:companyId/conversations/:conversationId")
            .frames(List.of(
                    new ClientErrorFrame().function("ConversationPage.send").file(
                            "https://tinfirm.acme.example/chunk-w4QG-jIO.js?v=1").line(1).column(4211),
                    new ClientErrorFrame().function("inject").file("chrome-extension://abcdef/content.js").line(9),
                    new ClientErrorFrame().function("x").file("/main-KASMKSG6.js").line(2),
                    new ClientErrorFrame().file("/main.js").line(3))),
                CHROME);

        assertThat(error.source()).isEqualTo(CapturedError.Source.WEB);
        assertThat(error.title()).isEqualTo("TypeError");
        assertThat(error.message()).isEqualTo("Cannot read <str> of undefined for <email>");
        assertThat(error.route()).isEqualTo("/c/:companyId/conversations/:conversationId");
        assertThat(error.runtime()).isEqualTo("Chrome 141");
        assertThat(error.exceptions().getFirst().frames()).containsExactly(
                new CapturedFrame(null, null, "/chunk-w4QG-jIO.js", 1, 4211, true),
                new CapturedFrame(null, null, "<external>", 9, null, false),
                new CapturedFrame(null, null, "/main-KASMKSG6.js", 2, null, true),
                new CapturedFrame(null, null, "/main.js", 3, null, true));
    }

    @Test
    void dropsWhatHasNotTheShapeOfCode() {
        CapturedError error = errors.of(new ClientError("not a type!")
            .route("https://tinfirm.acme.example/c/0192/inbox")
            .frames(List.of(new ClientErrorFrame().function("alice's salary is 9000").file("data:text/js,alert(1)"))),
                null);

        assertThat(error.title()).isEqualTo("Error");
        assertThat(error.route()).isNull();
        assertThat(error.runtime()).isNull();
        assertThat(error.exceptions().getFirst().frames()).containsExactly(
                new CapturedFrame(null, null, "<external>", null, null, false));
    }

    @Test
    void aRouteOrAFileCarriesNoTextOfTheCompany() {
        for (String route : List.of("/customers/Acme/payroll", "/c/:companyId/Alice Smith", "/c/:companyId/9000",
                "/c/:companyId/inbox?x=1", "/c/:companyId/inbox_salary", "//")) {
            assertThat(errors.of(new ClientError("TypeError").route(route), null).route()).as(route).isNull();
        }
        assertThat(errors.of(new ClientError("TypeError").route("/"), null).route()).isEqualTo("/");
        assertThat(errors.of(new ClientError("TypeError").route("/c/:companyId/kill-switches"), null).route())
            .isEqualTo("/c/:companyId/kill-switches");
        for (String file : List.of("/Alice-Smith-salary-9000.js", "/chunk-w4QG-jIO.js/payroll.js", "/main-KASM.js",
                "/assets/main-KASMKSG6.js", "/chunk-w4QG-jIO.css")) {
            assertThat(errors.of(new ClientError("TypeError").frames(List.of(new ClientErrorFrame().file(file))),
                    null).exceptions().getFirst().frames().getFirst().file()).as(file).isEqualTo("<external>");
        }
    }

    @Test
    void groupsByTypeMessageAndRouteSinceMinifiedFramesChange() {
        ClientError first = new ClientError("TypeError").message("x is undefined").route("/c/:companyId/inbox")
            .frames(List.of(new ClientErrorFrame().function("a").file("/main-AAAAAAAA.js").line(1)));
        ClientError second = new ClientError("TypeError").message("x is undefined").route("/c/:companyId/inbox")
            .frames(List.of(new ClientErrorFrame().function("b").file("/main-BBBBBBBB.js").line(7)));

        assertThat(errors.of(first, CHROME).fingerprint()).isEqualTo(errors.of(second, null).fingerprint())
            .hasSize(64);
        assertThat(errors.of(first.route("/c/:companyId/agents"), CHROME).fingerprint())
            .isNotEqualTo(errors.of(second, CHROME).fingerprint());
    }

    @Test
    void takesAtMostTheLimitAMinuteFromOneUser() {
        UUID user = UUID.randomUUID();
        assertThat(List.of(errors.allow(user), errors.allow(user), errors.allow(user), errors.allow(user)))
            .containsExactly(true, true, true, false);
        assertThat(errors.allow(UUID.randomUUID())).isTrue();
    }

    @Test
    void takesAtMostTheLimitOfDistinctKindsAnHourFromOneUser() {
        // L18: a message varied at will makes a new kind each time; a user brings at most 50 kinds an hour.
        MutableClock clock = new MutableClock(Instant.parse("2026-10-27T12:00:00Z"));
        ClientErrors limited = new ClientErrors(
                new TinfirmProperties(URI.create("https://tinfirm.acme.example"), Set.of("api")), new TimeSource(clock),
                properties(1000, 50));
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 50; i++) {
            assertThat(limited.allowKind(user, "kind-" + i)).as("kind %s", i).isTrue();
        }
        assertThat(limited.allowKind(user, "kind-50")).isFalse();
        // A kind already taken keeps counting; another user has their own hour.
        assertThat(limited.allowKind(user, "kind-7")).isTrue();
        assertThat(limited.allowKind(UUID.randomUUID(), "kind-50")).isTrue();
        // An hour later, new kinds again.
        clock.advance(Duration.ofMinutes(61));
        assertThat(limited.allowKind(user, "kind-50")).isTrue();
    }

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void namesTheBrowserByItsMajorVersion() {
        assertThat(ClientErrors.browser("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101"
                + " Firefox/140.0")).isEqualTo("Firefox 140");
        assertThat(ClientErrors.browser(CHROME + " Edg/141.0.3537.57")).isEqualTo("Edge 141");
        assertThat(ClientErrors.browser("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15"
                + " (KHTML, like Gecko) Version/18.6 Safari/605.1.15")).isEqualTo("Safari 18");
        assertThat(ClientErrors.browser("curl/8.5.0")).isNull();
    }

    static ErrorReportProperties properties(int perUserMinute) {
        return properties(perUserMinute, 50);
    }

    static ErrorReportProperties properties(int perUserMinute, int fingerprintsPerUserHour) {
        return new ErrorReportProperties(null, null, null, null, 1000, 250, Duration.ofDays(90), 200, perUserMinute,
                fingerprintsPerUserHour, 100, Duration.ofMinutes(10), Duration.ofSeconds(20));
    }
}
