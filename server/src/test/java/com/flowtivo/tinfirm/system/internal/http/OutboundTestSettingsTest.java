package com.flowtivo.tinfirm.system.internal.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.flowtivo.tinfirm.system.CallResult;

/**
 * The test-only {@code tinfirm.connection.allowed-private-hosts} (CONVENTIONS §4): the server refuses to
 * start with it outside the {@code test} profile, also when a variable sets it through Spring's relaxed binding; and a
 * personal MCP server never honours it, whatever the operator allows, while a company system does in the tests.
 */
class OutboundTestSettingsTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
        .withUserConfiguration(Outbound.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboundProperties.class)
    @Import(OutboundHttp.class)
    static class Outbound {
    }

    /** The process environment holds only this variable: what an operator would set. */
    private static ApplicationContextRunner withVariable(ApplicationContextRunner runner, String name) {
        return runner.withInitializer(context -> context.getEnvironment()
            .getPropertySources()
            .replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of(name, "jira.internal.example"))));
    }

    @Test
    void theServerRefusesToStartWithItOutsideTheTestProfile() {
        contexts.run(context -> assertThat(context).hasNotFailed().hasSingleBean(OutboundHttp.class));
        contexts.withPropertyValues(OutboundHttp.ALLOWED_PRIVATE_HOSTS + "=localhost").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tinfirm.connection.allowed-private-hosts")
                .hasMessageContaining("for the tests only");
        });
        // Spring's relaxed binding takes it from a variable, as the record binds it and as an @Value resolves it.
        for (String variable : new String[] { "TINFIRM_CONNECTION_ALLOWEDPRIVATEHOSTS",
                "TINFIRM_CONNECTION_ALLOWED_PRIVATE_HOSTS" }) {
            withVariable(contexts, variable).run(context -> {
                assertThat(context).as(variable).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TINFIRM_CONNECTION_ALLOWEDPRIVATEHOSTS");
            });
        }
        // The test profile takes it.
        withVariable(contexts, "TINFIRM_CONNECTION_ALLOWEDPRIVATEHOSTS")
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
            .run(context -> assertThat(context).hasNotFailed());
        // So is the tests' bean for personal servers' stubs refused outside the test profile.
        contexts.withBean(PersonalStubHosts.class, () -> new PersonalStubHosts(Set.of("localhost"))).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("PersonalStubHosts");
        });
    }

    @Test
    void aPersonalServerNeverReachesAPrivateAddressThroughIt() {
        for (boolean operatorAllows : new boolean[] { false, true }) {
            OutboundHttp company = new OutboundHttp(properties(operatorAllows, Set.of("localhost")),
                    OutboundHttp.Sleeper.REAL, Set.of());
            String url = "http://localhost:9/mcp";
            assertThat(company.urlProblem(url)).as("a company system, the test's host").isNull();
            OutboundHttp personal = company.personal();
            assertThat(personal.urlProblem(url)).as("a personal server, operator allows: %s", operatorAllows)
                .contains("resolves to a loopback address");
            CallResult<OutboundHttp.Response> sent = personal.send("http://localhost:9",
                    personal.request(url).GET().build());
            assertThat(sent.failure()).isNotNull();
            assertThat(sent.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
            assertThat(sent.failureMessage()).startsWith("The request was not sent");
        }
        // Only the tests' own bean lets a personal server reach a stub, and only on the host it names.
        OutboundHttp stubs = new OutboundHttp(properties(false, Set.of()), OutboundHttp.Sleeper.REAL,
                Set.of("localhost"));
        assertThat(stubs.personal().urlProblem("http://localhost:9/mcp")).isNull();
        assertThat(stubs.personal().urlProblem("http://127.0.0.1:9/mcp")).contains("resolves to a loopback address");
    }

    private static OutboundProperties properties(boolean allowPrivateAddresses, Set<String> allowedPrivateHosts) {
        return new OutboundProperties(true, allowPrivateAddresses, Duration.ofSeconds(5), Duration.ofSeconds(10),
                allowedPrivateHosts, Set.of());
    }
}
