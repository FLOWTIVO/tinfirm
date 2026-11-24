package com.flowtivo.tinfirm.shared.tx;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.session.jdbc.config.annotation.web.http.JdbcHttpSessionConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The application's transaction manager ({@link ReleasingTransactionManager}): Spring Boot's JPA transaction manager,
 * built as its JPA auto-configuration builds it (with the customizers it applies, the transaction execution listeners
 * and the transaction properties among them), wrapped. The only transaction manager bean: Spring Data, Spring Session,
 * {@code @Transactional} and every {@code TransactionTemplate} of the application use it.
 */
@Configuration(proxyBeanMethods = false)
class TransactionManagerConfiguration {

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        JpaTransactionManager jpa = new JpaTransactionManager(entityManagerFactory);
        customizers.ifAvailable(each -> each.customize(jpa));
        jpa.afterPropertiesSet();
        return new ReleasingTransactionManager(jpa);
    }

    /**
     * Spring Session's transactions, as Spring Session makes them (each of its own), named {@link
     * AfterTransaction#BOOKKEEPING}: the session's reads and saves are not the request's change, so their commits leave
     * {@link AfterTransaction#savedInThisWork} as it is. Once the request found no free connection, they are not begun
     * again ({@link SessionTransactions}). Set on Spring Session's configuration before it makes its repository; no
     * {@code TransactionOperations} bean, which would take the place of Spring Boot's.
     */
    @Bean
    static BeanPostProcessor bookkeepingSessionTransactions(ObjectProvider<PlatformTransactionManager> managers) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof JdbcHttpSessionConfiguration sessions) {
                    TransactionTemplate template = new SessionTransactions(managers.getObject());
                    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    template.setName(AfterTransaction.BOOKKEEPING);
                    sessions.setTransactionOperations(template);
                }
                return bean;
            }
        };
    }
}
