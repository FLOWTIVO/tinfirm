package com.flowtivo.tinfirm.company.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.company.MemberEvents;
import com.flowtivo.tinfirm.company.MemberPrincipals;
import com.flowtivo.tinfirm.company.scim.ScimMembers;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * SCIM's seat fallback on a reactivation, without a database: the member made VIEWER is announced as every other role
 * change is ({@code MemberRoleChanged}), so that the trigger module pauses their triggers at once (the security review
 * of 2026-10-27, M7).
 */
class ScimMembersImplTest {

    private final UUID companyId = UUID.randomUUID();

    private final UUID userId = UUID.randomUUID();

    private final MembershipRepository memberships = mock(MembershipRepository.class);

    private final AuditLog auditLog = mock(AuditLog.class);

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final TimeSource time = new TimeSource(Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    private final ScimMembersImpl members = new ScimMembersImpl(memberships, mock(CompanyQueries.class),
            mock(MemberService.class), mock(MemberPrincipals.class), mock(ObjectProvider.class),
            mock(Licensing.class), auditLog, time, mock(JdbcClient.class), mock(MembershipRoles.class), events);

    @Test
    void aMemberWithoutASeatFallsBackToViewerAndTheChangeIsPublished() {
        MembershipEntity membership = member(CompanyRole.MEMBER);

        ScimMembers.Given given = fallBack();

        assertThat(given.role()).isEqualTo(CompanyRole.VIEWER);
        assertThat(membership.getRole()).isEqualTo(CompanyRole.VIEWER);
        verify(events).publishEvent(new MemberEvents.MemberRoleChanged(companyId, userId, CompanyRole.MEMBER,
                CompanyRole.VIEWER));
    }

    @Test
    void anOwnerOrAViewerKeepsTheRoleAndNothingIsPublished() {
        for (CompanyRole role : new CompanyRole[] { CompanyRole.OWNER, CompanyRole.VIEWER }) {
            MembershipEntity membership = member(role);

            assertThat(fallBack().role()).isEqualTo(role);
            assertThat(membership.getRole()).isEqualTo(role);
        }
        verify(events, never()).publishEvent(any(Object.class));
        verify(auditLog, never()).record(any());
    }

    private ScimMembers.Given fallBack() {
        return SystemContext.callInCompany(companyId, () -> members.fallBackToViewer(companyId, userId));
    }

    private MembershipEntity member(CompanyRole role) {
        MembershipEntity membership = new MembershipEntity(companyId, userId, role, RoleSource.SCIM, time.now());
        when(memberships.findByCompanyIdAndUserId(companyId, userId)).thenReturn(Optional.of(membership));
        return membership;
    }
}
