package com.flowtivo.tinfirm.bootstrap.internal;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.company.DeclaredCompanies;

/**
 * Once the application has started (after Flyway migrations): the bootstrap of the first instance admin, then the
 * deployment's declared companies (ADR-0075), which may name that admin as their owner.
 */
@Component
class BootstrapRunner implements ApplicationRunner {

    private final BootstrapService bootstrap;

    private final DeclaredCompanies companies;

    BootstrapRunner(BootstrapService bootstrap, DeclaredCompanies companies) {
        this.bootstrap = bootstrap;
        this.companies = companies;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap.bootstrapIfNeeded();
        companies.apply();
    }
}
