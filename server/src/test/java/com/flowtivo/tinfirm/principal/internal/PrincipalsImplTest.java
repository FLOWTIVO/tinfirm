package com.flowtivo.tinfirm.principal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.directory.UserDisplayNames;

/**
 * The use right on service accounts by role (the package 1 contract, §3.2; the security review of 2026-10-27, M7):
 * OWNER uses every one, a MEMBER or an ADMIN those of their groups, a VIEWER none, whatever their groups, so that a
 * member demoted to VIEWER keeps no service account for their triggers.
 */
class PrincipalsImplTest {

    static final UUID COMPANY = UUID.randomUUID();

    static final UUID USER = UUID.randomUUID();

    static final UUID GROUP = UUID.randomUUID();

    static final UUID SERVICE = UUID.randomUUID();

    static final UUID OTHER_SERVICE = UUID.randomUUID();

    final PrincipalStore store = mock(PrincipalStore.class);

    final CompanyDirectory companies = mock(CompanyDirectory.class);

    final GroupMembership groups = mock(GroupMembership.class);

    final PrincipalsImpl principals = new PrincipalsImpl(store, companies, groups, mock(UserDisplayNames.class));

    @BeforeEach
    void aServiceAccountTheUsersGroupMayUse() {
        when(groups.groupsOf(COMPANY, USER)).thenReturn(Set.of(GROUP));
        when(store.usableByGroups(COMPANY, Set.of(GROUP))).thenReturn(Set.of(SERVICE));
        when(store.services(COMPANY)).thenReturn(Set.of(SERVICE, OTHER_SERVICE));
        when(store.find(COMPANY, SERVICE)).thenReturn(Optional.of(new PrincipalStore.PrincipalRow(SERVICE, COMPANY,
                PrincipalKind.SERVICE, null, "svc-dev", null, PrincipalStatus.ACTIVE, Instant.now(), Instant.now(),
                null)));
    }

    void role(CompanyRole role) {
        when(companies.roleOf(COMPANY, USER)).thenReturn(Optional.of(role));
    }

    @Test
    void aMemberOrAnAdminUsesTheServiceAccountsOfTheirGroups() {
        for (CompanyRole role : Set.of(CompanyRole.MEMBER, CompanyRole.ADMIN)) {
            role(role);
            assertThat(principals.usableServiceIdentities(COMPANY, USER)).as("%s", role).containsExactly(SERVICE);
            assertThat(principals.mayUse(COMPANY, USER, SERVICE)).as("%s", role).isTrue();
        }
    }

    @Test
    void theOwnerUsesEveryServiceAccount() {
        role(CompanyRole.OWNER);
        assertThat(principals.usableServiceIdentities(COMPANY, USER)).containsExactlyInAnyOrder(SERVICE,
                OTHER_SERVICE);
    }

    @Test
    void aViewerUsesNoServiceAccountWhateverTheirGroups() {
        role(CompanyRole.VIEWER);
        assertThat(principals.usableServiceIdentities(COMPANY, USER)).isEmpty();
        assertThat(principals.mayUse(COMPANY, USER, SERVICE)).isFalse();
    }

    @Test
    void someoneWhoIsNoMemberUsesNone() {
        when(companies.roleOf(COMPANY, USER)).thenReturn(Optional.empty());
        assertThat(principals.usableServiceIdentities(COMPANY, USER)).isEmpty();
    }
}
