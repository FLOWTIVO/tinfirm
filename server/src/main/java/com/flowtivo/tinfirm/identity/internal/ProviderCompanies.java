package com.flowtivo.tinfirm.identity.internal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.identity.ProvisionedMemberships;

/**
 * The declared companies a provider fills, resolved from the deployment's settings by the server, never from the
 * request: the provider's own company ({@code _COMPANY}, ADR-0075) and the further companies its role claim fills,
 * each with the claim's values that give each role there ({@code _COMPANIES_<KEY>_ROLE_MAP_*}, ADR-0089). A declared
 * company that does not exist yet (it waits for a licence) is left out until it does.
 *
 * @param home   the provider's own company, or null for none
 * @param mapped the further companies, by id in their order, with their role maps; never the home company. The
 *               order is the same on every replica, so that sign-ins take the companies' locks in one order
 */
record ProviderCompanies(@Nullable UUID home, Map<UUID, ProviderRoles.RoleMap> mapped) {

    static final ProviderCompanies NONE = new ProviderCompanies(null, Map.of());

    ProviderCompanies {
        mapped = Collections.unmodifiableMap(new TreeMap<>(mapped));
    }

    /** A provider of one company, or of none. */
    static ProviderCompanies of(@Nullable UUID home) {
        return home == null ? NONE : new ProviderCompanies(home, Map.of());
    }

    /**
     * Resolves a provider's companies.
     *
     * @param homeKey the provider's {@code _COMPANY}, or null for the only declared company when exactly one is
     */
    static ProviderCompanies resolve(ProvisionedMemberships memberships, @Nullable String homeKey,
            ProviderRoles roles) {
        UUID home = memberships.companyFor(homeKey).orElse(null);
        Map<UUID, ProviderRoles.RoleMap> mapped = new TreeMap<>();
        roles.companies().forEach((key, map) -> memberships.companyFor(key).ifPresent(id -> {
            if (!id.equals(home)) {
                mapped.put(id, map);
            }
        }));
        return new ProviderCompanies(home, mapped);
    }

    /** Whether the provider fills no company: its users get no membership from it. */
    boolean isEmpty() {
        return home == null && mapped.isEmpty();
    }

    /** Every company the provider fills, the home company first. */
    Set<UUID> all() {
        Set<UUID> all = new LinkedHashSet<>();
        if (home != null) {
            all.add(home);
        }
        all.addAll(mapped.keySet());
        return all;
    }

    /** Whether the provider fills this company. */
    boolean fills(UUID companyId) {
        return Objects.equals(home, companyId) || mapped.containsKey(companyId);
    }

    /** The company whose tenant context a sign-in through the provider starts in: the home company, else the first. */
    @Nullable UUID context() {
        return all().stream().findFirst().orElse(null);
    }
}
