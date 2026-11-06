package com.flowtivo.tinfirm.system.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.flowtivo.tinfirm.system.internal.http.ChatEndpoints;
import com.flowtivo.tinfirm.system.internal.http.GmailEndpoints;

/**
 * The chat platforms' and Google's endpoints are fixed, and the server refuses to start with one changed outside the
 * {@code test} and {@code e2e} profiles, also when a variable changes it through Spring's relaxed binding. Otherwise a
 * setting meant for the tests could point Google's keys or the Bot Framework's OpenID metadata at keys another party
 * holds, and Tinfirm would trust the tokens they sign.
 */
class FixedEndpointsTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
        .withUserConfiguration(SystemConfiguration.class);

    /** The process environment holds only this variable: what an operator would set. */
    private static ApplicationContextRunner withVariable(ApplicationContextRunner runner, String name, String value) {
        return runner.withInitializer(context -> context.getEnvironment()
            .getPropertySources()
            .replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of(name, value))));
    }

    @Test
    void theServerStartsWithTheFixedEndpoints() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GmailEndpoints.class).googleKeys())
                .isEqualTo("https://www.googleapis.com/oauth2/v3/certs");
            assertThat(context.getBean(ChatEndpoints.class).slackSocketHosts()).containsExactly(".slack.com");
        });
        // A default written out is no change.
        contexts.withPropertyValues("tinfirm.gmail.endpoints.gmail-api=https://gmail.googleapis.com",
                "tinfirm.chat.endpoints.slack-socket-hosts=.slack.com")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theServerRefusesToStartWithOneChangedOutsideTheTestsProfiles() {
        contexts.withPropertyValues("tinfirm.gmail.endpoints.google-keys=https://keys.example/certs",
                "tinfirm.chat.endpoints.teams-open-id=https://login.example/openid").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("tinfirm.chat.endpoints.teams-open-id, "
                                + "tinfirm.gmail.endpoints.google-keys")
                        .hasMessageContaining("only the tests point them at their stubs");
                });
        // Spring's relaxed binding takes one from a variable.
        for (String variable : new String[] { "TINFIRM_GMAIL_ENDPOINTS_GMAILAPI", "TINFIRM_CHAT_ENDPOINTS_SLACKAPI" }) {
            withVariable(contexts, variable, "https://api.example").run(context -> {
                assertThat(context).as(variable).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(variable.startsWith("TINFIRM_GMAIL") ? "tinfirm.gmail.endpoints.gmail-api"
                            : "tinfirm.chat.endpoints.slack-api");
            });
        }
        contexts.withPropertyValues("tinfirm.chat.endpoints.slack-socket-hosts=.slack.com,localhost")
            .run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("tinfirm.chat.endpoints.slack-socket-hosts"));
    }

    @Test
    void theTestsProfilesPointThemAtTheirStubs() {
        for (String profile : new String[] { "test", "e2e" }) {
            withVariable(contexts, "TINFIRM_CHAT_ENDPOINTS_SLACKAPI", "http://127.0.0.1:18092/slack/api")
                .withPropertyValues("tinfirm.gmail.endpoints.google-keys=http://localhost:9/certs")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .run(context -> {
                    assertThat(context).as(profile).hasNotFailed();
                    assertThat(context.getBean(ChatEndpoints.class).slackApi())
                        .isEqualTo("http://127.0.0.1:18092/slack/api");
                });
        }
    }
}
