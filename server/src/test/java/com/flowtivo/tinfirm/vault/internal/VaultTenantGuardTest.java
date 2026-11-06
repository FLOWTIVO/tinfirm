package com.flowtivo.tinfirm.vault.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;

/**
 * The note of the security review of 2026-10-27 on {@code VaultService.reveal} and {@code scan}: they enter the company
 * they are handed only from no company's context, the system's, or that same company's.
 */
class VaultTenantGuardTest {

    private final UUID company = UUID.randomUUID();

    @Test
    void entersFromNoCompanyTheSystemOrTheSameCompany() {
        VaultServiceImpl.requireNoOtherCompany(company);
        try (TenantContextHolder.Scope ignored = TenantContextHolder.open(TenantContext.SYSTEM)) {
            VaultServiceImpl.requireNoOtherCompany(company);
        }
        try (TenantContextHolder.Scope ignored = TenantContextHolder
            .open(TenantContext.forUser(UUID.randomUUID(), false))) {
            VaultServiceImpl.requireNoOtherCompany(company);
        }
        try (TenantContextHolder.Scope ignored = TenantContextHolder.open(TenantContext.forCompany(company))) {
            VaultServiceImpl.requireNoOtherCompany(company);
        }
    }

    @Test
    void neverEntersAnotherCompanyFromOnesContext() {
        try (TenantContextHolder.Scope ignored = TenantContextHolder
            .open(TenantContext.forUser(UUID.randomUUID(), true).withCompany(UUID.randomUUID()))) {
            assertThatThrownBy(() -> VaultServiceImpl.requireNoOtherCompany(company))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(company.toString());
        }
    }
}
