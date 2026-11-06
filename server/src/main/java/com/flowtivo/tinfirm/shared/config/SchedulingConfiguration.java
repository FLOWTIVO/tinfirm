package com.flowtivo.tinfirm.shared.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;

/**
 * Enables {@code @Scheduled} jobs: the runner keep-alive (every replica with the {@code api} role) and the
 * dispatcher and reaper (only with the {@code worker} role, see {@link TinfirmProperties#roles()}). A job that one
 * replica at a time runs takes its ShedLock lock (table {@code shedlock}) from the {@link LockProvider}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {

    /**
     * ShedLock's locks in {@code shedlock}, on the database's clock, so that the replicas' clocks do not matter. Each
     * take, extension and release is a short transaction of its own; a lock is held by the replica's host name.
     */
    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .usingDbTime()
            .build());
    }
}
