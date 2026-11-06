package com.flowtivo.tinfirm.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.application.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.flowtivo.tinfirm.shared.tenant.DatabaseRoleCheck;

/**
 * The readiness probe includes the database, the liveness probe does not (CONVENTIONS §4,
 * docs/operations/helm.md). With {@code application.yaml}'s management settings and a database that does not answer,
 * readiness is {@code DOWN}, so the replica takes no traffic, and liveness stays {@code UP}, so it is not restarted.
 * That both are {@code UP} with the database is {@code SystemIntegrationTest}'s.
 */
class ReadinessProbeTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(context -> applicationYaml().forEach(source -> context.getEnvironment().getPropertySources()
            .addLast(source)))
        .withConfiguration(AutoConfigurations.of(ApplicationAvailabilityAutoConfiguration.class,
                EndpointAutoConfiguration.class, HealthContributorRegistryAutoConfiguration.class,
                HealthContributorAutoConfiguration.class, HealthEndpointAutoConfiguration.class,
                AvailabilityHealthContributorAutoConfiguration.class, AvailabilityProbesAutoConfiguration.class,
                DataSourceHealthContributorAutoConfiguration.class))
        .withBean(DataSource.class, ReadinessProbeTest::unreachable)
        .withBean(DatabaseRoleCheck.NAME, DatabaseRoleCheck.class, () -> new DatabaseRoleCheck(unreachable()));

    @Test
    void readinessIsDownWithoutTheDatabaseAndLivenessStaysUp() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);

            HealthEndpointGroups groups = context.getBean(HealthEndpointGroups.class);
            assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
            assertThat(groups.get("readiness").isMember("db")).isTrue();
            assertThat(groups.get("readiness").isMember(DatabaseRoleCheck.NAME)).isTrue();
            assertThat(groups.get("liveness").isMember(DatabaseRoleCheck.NAME)).isFalse();
            assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
            assertThat(groups.get("liveness").isMember("db")).isFalse();

            HealthEndpoint health = context.getBean(HealthEndpoint.class);
            assertThat(health.healthForPath("readiness").getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.healthForPath("liveness").getStatus()).isEqualTo(Status.UP);
        });
    }

    /** The server's own {@code application.yaml}, as the packaged application reads it. */
    private static List<PropertySource<?>> applicationYaml() {
        try {
            return new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"));
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A PostgreSQL that does not answer: nothing listens on port 1. */
    private static DataSource unreachable() {
        return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/tinfirm?connectTimeout=2", "tinfirm",
                "unused");
    }
}
