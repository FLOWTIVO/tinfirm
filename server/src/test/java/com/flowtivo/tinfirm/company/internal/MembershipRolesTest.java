package com.flowtivo.tinfirm.company.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.MemberEvents;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/** The rules of a role a provider or SCIM decides (ADR-0088), without a database. */
class MembershipRolesTest {

    private final UUID companyId = UUID.randomUUID();

    private final UUID userId = UUID.randomUUID();

    private final MembershipRepository memberships = mock(MembershipRepository.class);

    private final CompanyQueries queries = mock(CompanyQueries.class);

    private final Licensing licensing = mock(Licensing.class);

    private final AuditLog auditLog = mock(AuditLog.class);

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final TimeSource time = new TimeSource(Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC));

    private final MembershipRoles roles = new MembershipRoles(memberships, queries, licensing, auditLog, events, time,
            mock(JdbcClient.class));

    private final RoleSource provider = RoleSource.provider("entra");

    @Test
    void aPromotionIsAppliedAndAuditedWithTheProvider() {
        MembershipEntity membership = member(CompanyRole.MEMBER, RoleSource.APP);
        when(licensing.seatAvailableFor(userId, CompanyRole.ADMIN)).thenReturn(true);

        assertThat(apply(CompanyRole.ADMIN, provider)).isEqualTo(MembershipRoles.Outcome.APPLIED);

        assertThat(membership.getRole()).isEqualTo(CompanyRole.ADMIN);
        assertThat(membership.getRoleSource()).isEqualTo(provider);
        AuditRecord changed = audited();
        assertThat(changed.action()).isEqualTo(AuditAction.MEMBER_ROLE_CHANGED);
        assertThat(changed.details()).containsEntry("previousRole", "MEMBER")
            .containsEntry("role", "ADMIN")
            .containsEntry("roleSource", "IDP")
            .containsEntry("provider", "entra");
        verify(events).publishEvent(new MemberEvents.MemberRoleChanged(companyId, userId, CompanyRole.MEMBER,
                CompanyRole.ADMIN));
    }

    @Test
    void anOwnerIsKeptWhateverTheProviderSays() {
        MembershipEntity membership = member(CompanyRole.OWNER, RoleSource.APP);

        assertThat(apply(CompanyRole.VIEWER, provider)).isEqualTo(MembershipRoles.Outcome.KEPT);
        assertThat(apply(null, provider)).isEqualTo(MembershipRoles.Outcome.KEPT);
        assertThat(apply(null, RoleSource.SCIM)).isEqualTo(MembershipRoles.Outcome.KEPT);

        assertThat(membership.getRole()).isEqualTo(CompanyRole.OWNER);
        verify(auditLog, never()).record(any());
    }

    @Test
    void scimWinsOverASignIn() {
        MembershipEntity membership = member(CompanyRole.MEMBER, RoleSource.SCIM);

        assertThat(apply(CompanyRole.ADMIN, provider)).isEqualTo(MembershipRoles.Outcome.KEPT);
        assertThat(apply(null, provider)).isEqualTo(MembershipRoles.Outcome.KEPT);

        assertThat(membership.getRole()).isEqualTo(CompanyRole.MEMBER);
        assertThat(membership.getRoleSource()).isEqualTo(RoleSource.SCIM);
    }

    @Test
    void noRoleMappedIsRefusedAndChangesNothing() {
        MembershipEntity membership = member(CompanyRole.ADMIN, provider);

        assertThat(apply(null, provider)).isEqualTo(MembershipRoles.Outcome.NOT_MAPPED);

        assertThat(membership.getRole()).isEqualTo(CompanyRole.ADMIN);
        verify(auditLog, never()).record(any());
    }

    @Test
    void aNonMemberIsGrantedNothing() {
        when(memberships.findByCompanyIdAndUserId(companyId, userId)).thenReturn(Optional.empty());

        assertThat(apply(CompanyRole.MEMBER, provider)).isEqualTo(MembershipRoles.Outcome.NOT_A_MEMBER);
        assertThat(apply(null, provider)).isEqualTo(MembershipRoles.Outcome.NOT_MAPPED);
        verify(memberships, never()).save(any());
    }

    @Test
    void aSeatRoleWithoutAFreeSeatGivesViewerAndTellsTheAdmins() {
        MembershipEntity membership = member(CompanyRole.MEMBER, provider);
        when(licensing.seatAvailableFor(userId, CompanyRole.ADMIN)).thenReturn(false);

        assertThat(apply(CompanyRole.ADMIN, provider)).isEqualTo(MembershipRoles.Outcome.APPLIED);

        assertThat(membership.getRole()).isEqualTo(CompanyRole.VIEWER);
        assertThat(audited().details()).containsEntry("seatFallback", true).containsEntry("providerRole", "ADMIN");
        // They held a seat role before: the admins' item starts again.
        verify(queries).seatFallbackAgain(companyId, userId, CompanyRole.ADMIN, "entra", time.now(), true);
    }

    @Test
    void aViewerStillWaitingForASeatKeepsTheirItem() {
        member(CompanyRole.VIEWER, provider);
        when(licensing.seatAvailableFor(userId, CompanyRole.MEMBER)).thenReturn(false);

        assertThat(apply(CompanyRole.MEMBER, provider)).isEqualTo(MembershipRoles.Outcome.APPLIED);

        verify(queries).seatFallbackAgain(eq(companyId), eq(userId), eq(CompanyRole.MEMBER), eq("entra"), any(),
                eq(false));
        verify(auditLog, never()).record(any());
    }

    @Test
    void theRoleGivenInFullClearsTheAdminsItem() {
        // A fallback earlier, then the role once a seat was free: a later VIEWER the provider means is no fallback.
        member(CompanyRole.VIEWER, provider);
        when(licensing.seatAvailableFor(userId, CompanyRole.MEMBER)).thenReturn(true);

        assertThat(apply(CompanyRole.MEMBER, provider)).isEqualTo(MembershipRoles.Outcome.APPLIED);

        verify(queries).clearSeatFallback(companyId, userId);
        verify(queries, never()).seatFallbackAgain(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aProviderTakingTheSameRoleIsAuditedAsTheSourceChanging() {
        MembershipEntity membership = member(CompanyRole.MEMBER, RoleSource.APP);
        when(licensing.seatAvailableFor(userId, CompanyRole.MEMBER)).thenReturn(true);

        assertThat(apply(CompanyRole.MEMBER, provider)).isEqualTo(MembershipRoles.Outcome.APPLIED);

        assertThat(membership.getRoleSource()).isEqualTo(provider);
        AuditRecord changed = audited();
        assertThat(changed.action()).isEqualTo(AuditAction.MEMBER_ROLE_SOURCE_CHANGED);
        assertThat(changed.details()).containsEntry("userId", userId.toString())
            .containsEntry("role", "MEMBER")
            .containsEntry("previousRoleSource", "APP")
            .containsEntry("roleSource", "IDP")
            .containsEntry("provider", "entra");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void anUnchangedRoleIsNotAudited() {
        member(CompanyRole.MEMBER, provider);
        when(licensing.seatAvailableFor(userId, CompanyRole.MEMBER)).thenReturn(true);

        assertThat(apply(CompanyRole.MEMBER, provider)).isEqualTo(MembershipRoles.Outcome.APPLIED);

        verify(auditLog, never()).record(any());
        verify(queries, never()).seatFallbackAgain(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void neitherOwnerNorTheAppIsAProvidersToGive() {
        assertThatIllegalArgumentException().isThrownBy(() -> apply(CompanyRole.OWNER, provider));
        assertThatIllegalArgumentException().isThrownBy(() -> apply(CompanyRole.MEMBER, RoleSource.APP));
    }

    @Test
    void anOwnersRoleSourceIsAlwaysTheApps() {
        MembershipEntity membership = new MembershipEntity(companyId, userId, CompanyRole.MEMBER, provider,
                time.now());
        membership.changeRole(CompanyRole.OWNER, provider, time.now());

        assertThat(membership.getRoleSource()).isEqualTo(RoleSource.APP);
    }

    private MembershipRoles.Outcome apply(CompanyRole role, RoleSource source) {
        return SystemContext.callInCompany(companyId, () -> roles.apply(companyId, userId, role, source));
    }

    private MembershipEntity member(CompanyRole role, RoleSource source) {
        MembershipEntity membership = new MembershipEntity(companyId, userId, role, source, time.now());
        when(memberships.findByCompanyIdAndUserId(companyId, userId)).thenReturn(Optional.of(membership));
        return membership;
    }

    private AuditRecord audited() {
        ArgumentCaptor<AuditRecord> audited = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditLog).record(audited.capture());
        return audited.getValue();
    }
}
