package com.flowtivo.tinfirm.shared.web;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Registers {@link RequestInputFilter} for every request. */
@Configuration(proxyBeanMethods = false)
class RequestInputConfiguration {

    @Bean
    FilterRegistrationBean<RequestInputFilter> requestInputFilter(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        FilterRegistrationBean<RequestInputFilter> registration = new FilterRegistrationBean<>(
                new RequestInputFilter(resolver));
        registration.addUrlPatterns("/*");
        // Right after the character encoding filter (HIGHEST_PRECEDENCE), outside Spring Session's filter, Spring
        // Security's chains (-100) and the MCP endpoint's filter (0), so that it sees what each of them throws.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
