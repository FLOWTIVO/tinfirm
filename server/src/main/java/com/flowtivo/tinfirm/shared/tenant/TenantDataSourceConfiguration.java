package com.flowtivo.tinfirm.shared.tenant;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps the application {@link DataSource} bean in a {@link TenantAwareDataSource}, so every consumer (JPA,
 * {@code JdbcClient}, Spring Session) gets tenant-aware transactions. Flyway builds its own DataSource for the
 * owner role and is not affected.
 */
@Configuration(proxyBeanMethods = false)
class TenantDataSourceConfiguration {

    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
