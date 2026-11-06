package com.flowtivo.tinfirm.modelapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * The model port (ADR-0069): the bundled LiteLLM's model API and nothing else, for the company's services. LiteLLM
 * serves every route on one port, its admin API (keys, teams, budgets, spend, models) beside its model API, and takes
 * fields of a request's body as its own (where the request goes and with which credentials, what it costs and logs);
 * this port, beside it in its pod, passes on its model routes only ({@link Routes}), without those fields
 * ({@code LiteLlmRequests}), and streams the answer back as it arrives. The caller's LiteLLM key is checked by LiteLLM.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ModelApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModelApiApplication.class, args);
    }

    /**
     * Every path of the application port goes to the model port's servlet, which answers {@code 404} for any but its
     * routes. The health probes are on the management port, a context of its own.
     */
    @Bean
    ServletRegistrationBean<ModelPort> modelPort(ModelApiProperties properties) {
        ServletRegistrationBean<ModelPort> registration = new ServletRegistrationBean<>(new ModelPort(properties),
                "/*");
        registration.setName("modelPort");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
