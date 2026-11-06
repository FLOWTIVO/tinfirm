package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

/** A provider's claim maps to a role as ADR-0088 decision 1 says. */
class ProviderRolesTest {

    private final ProviderRoles roles = new ProviderRoles(null, "groups", List.of("Tinfirm.Admin"),
            List.of("Tinfirm.Member", "staff"), List.of("Tinfirm.Viewer"));

    @Test
    void theHighestMappedRoleWins() {
        assertThat(roles.mapped(List.of("Tinfirm.Viewer", "staff", "Tinfirm.Admin"))).isEqualTo(CompanyRole.ADMIN);
        assertThat(roles.mapped(List.of("Tinfirm.Viewer", "staff"))).isEqualTo(CompanyRole.MEMBER);
        assertThat(roles.mapped(List.of("other", "Tinfirm.Viewer"))).isEqualTo(CompanyRole.VIEWER);
    }

    @Test
    void aSingleStringIsOneValue() {
        assertThat(roles.mapped("Tinfirm.Member")).isEqualTo(CompanyRole.MEMBER);
    }

    @Test
    void valuesMatchExactly() {
        assertThat(roles.mapped(List.of("tinfirm.admin", "Tinfirm.Admin ", "Staff"))).isNull();
    }

    @Test
    void aMissingClaimOrOneOfAnotherTypeMapsToNone() {
        assertThat(roles.mapped(null)).isNull();
        assertThat(roles.mapped(42)).isNull();
        assertThat(roles.mapped(List.of())).isNull();
    }

    @Test
    void aFurtherCompanysMapGivesItsOwnRole() {
        ProviderRoles several = new ProviderRoles(null, "groups", new ProviderRoles.RoleMap(List.of(), List.of("staff"),
                List.of()), java.util.Map.of("globex", new ProviderRoles.RoleMap(List.of("globex-admins"),
                        List.of("globex-users"), List.of()), "Initech ", new ProviderRoles.RoleMap(List.of(),
                                List.of("staff"), List.of())));

        assertThat(several.fillsCompanies()).isTrue();
        assertThat(several.companies()).containsOnlyKeys("globex", "initech");
        assertThat(several.mapped(List.of("staff", "globex-admins"))).isEqualTo(CompanyRole.MEMBER);
        assertThat(several.mappedIn("globex", List.of("staff", "globex-admins"))).isEqualTo(CompanyRole.ADMIN);
        assertThat(several.mappedIn("globex", List.of("staff"))).isNull();
        // One value may give a role in two companies.
        assertThat(several.mappedIn("initech", List.of("staff"))).isEqualTo(CompanyRole.MEMBER);
        assertThat(several.mappedIn("unknown", List.of("staff"))).isNull();
        assertThat(several.problems()).isEmpty();
    }

    @Test
    void aFurtherCompanysMapNeedsTheClaimAKeyAndAValue() {
        assertThat(new ProviderRoles(null, null, ProviderRoles.RoleMap.NONE, java.util.Map.of("globex",
                new ProviderRoles.RoleMap(List.of("a"), List.of(), List.of()))).problems())
            .anyMatch(problem -> problem.startsWith("COMPANIES_<KEY>_ROLE_MAP_* needs ROLE_CLAIM"));
        assertThat(new ProviderRoles(null, "groups", ProviderRoles.RoleMap.NONE, java.util.Map.of("globex",
                ProviderRoles.RoleMap.NONE)).problems())
            .contains("COMPANIES_GLOBEX_ROLE_MAP_*: at least one of ROLE_MAP_ADMIN, ROLE_MAP_MEMBER and "
                    + "ROLE_MAP_VIEWER is needed");
        assertThat(new ProviderRoles(null, "groups", ProviderRoles.RoleMap.NONE, java.util.Map.of("Glob-ex",
                new ProviderRoles.RoleMap(List.of("a"), List.of("a"), List.of()))).problems())
            .anyMatch(problem -> problem.contains("'glob-ex' is not a company's key"))
            .contains("COMPANIES_GLOB-EX_ROLE_MAP_MEMBER lists 'a', which COMPANIES_GLOB-EX_ROLE_MAP_ADMIN lists too");
        // The claim is satisfied by a further company's map alone.
        assertThat(new ProviderRoles(null, "groups", ProviderRoles.RoleMap.NONE, java.util.Map.of("globex",
                new ProviderRoles.RoleMap(List.of("a"), List.of(), List.of()))).problems()).isEmpty();
    }

    @Test
    void ownerIsNeverARole() {
        assertThat(new ProviderRoles(null, "groups", List.of(), List.of("staff"), List.of()).mapped("OWNER"))
            .isNull();
    }
}
