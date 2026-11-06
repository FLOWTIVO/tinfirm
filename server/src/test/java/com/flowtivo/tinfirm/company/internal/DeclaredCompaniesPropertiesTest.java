package com.flowtivo.tinfirm.company.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** The declared companies bind from the environment variables that Helm and Compose set (ADR-0075). */
class DeclaredCompaniesPropertiesTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void withoutDeclarationsThereIsNoCompany() {
        context.run(app -> {
            DeclaredCompaniesProperties properties = app.getBean(DeclaredCompaniesProperties.class);
            assertThat(properties.companies()).isEmpty();
            assertThat(properties.onlyKey()).isEmpty();
        });
    }

    @Test
    void bindsTheCompaniesFromEnvironmentVariablesInKeyOrder() {
        UUID id = UUID.randomUUID();
        context
            .withInitializer(app -> app.getEnvironment()
                .getPropertySources()
                .addFirst(environment(Map.of("TINFIRM_COMPANIES_MAIN_NAME", "My Company",
                        // Compose passes an unset variable as an empty one.
                        "TINFIRM_COMPANIES_MAIN_OWNER", "", "TINFIRM_COMPANIES_ACME_NAME", "Acme s.r.o.",
                        "TINFIRM_COMPANIES_ACME_OWNER", "Jane@Acme.example", "TINFIRM_COMPANIES_ACME_ID",
                        id.toString()))))
            .run(app -> {
                DeclaredCompaniesProperties properties = app.getBean(DeclaredCompaniesProperties.class);
                assertThat(properties.keys()).containsExactly("acme", "main");
                assertThat(properties.onlyKey()).isEmpty();
                DeclaredCompaniesProperties.Declaration main = properties.companies().get("main");
                assertThat(main.name()).isEqualTo("My Company");
                assertThat(main.ownerOr("admin@example.com")).isEqualTo("admin@example.com");
                assertThat(main.ownerOr("")).isNull();
                assertThat(main.id()).isNull();
                DeclaredCompaniesProperties.Declaration acme = properties.companies().get("acme");
                assertThat(acme.ownerOr("admin@example.com")).isEqualTo("jane@acme.example");
                assertThat(acme.id()).isEqualTo(id);
            });
    }

    @Test
    void bindsTheDeclaredRunnerHostAndNeverShowsItsToken() {
        String token = "tfr_" + "a".repeat(43);
        context
            .withInitializer(app -> app.getEnvironment()
                .getPropertySources()
                .addFirst(environment(Map.of("TINFIRM_COMPANIES_MAIN_NAME", "My Company",
                        "TINFIRM_COMPANIES_MAIN_RUNNER_HOST_NAME", "chart-runner",
                        "TINFIRM_COMPANIES_MAIN_RUNNER_HOST_TOKEN", token, "TINFIRM_COMPANIES_ACME_NAME", "Acme"))))
            .run(app -> {
                DeclaredCompaniesProperties properties = app.getBean(DeclaredCompaniesProperties.class);
                DeclaredCompaniesProperties.RunnerHost host = properties.companies().get("main").runnerHost();
                assertThat(host).isNotNull();
                assertThat(host.name()).isEqualTo("chart-runner");
                assertThat(host.token()).isEqualTo(token);
                assertThat(properties.companies().get("main").toString()).doesNotContain(token);
                assertThat(properties.companies().get("acme").runnerHost()).isNull();
            });
    }

    @Test
    void aCompanyWithoutANameStopsTheStart() {
        context.withPropertyValues("tinfirm.companies.acme.owner=jane@acme.example")
            .run(app -> assertThat(app).hasFailed());
    }

    @Test
    void aKeyThatIsNotLowerCaseLettersAndDigitsStopsTheStart() {
        context.withPropertyValues("tinfirm.companies.acme-co.name=Acme")
            .run(app -> assertThat(app).hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("lower-case letters and digits"));
    }

    /** Variables as the operating system passes them, with Spring's relaxed binding for environment variables. */
    private static SystemEnvironmentPropertySource environment(Map<String, Object> variables) {
        return new SystemEnvironmentPropertySource("test-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                variables);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DeclaredCompaniesProperties.class)
    static class Config {
    }
}
