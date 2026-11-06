package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.directory.AgentDisplayNames;
import com.flowtivo.tinfirm.shared.directory.UserDisplayNames;
import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.vault.VaultService;

/**
 * {@code StagedFiles.release} (ADR-0084) as a unit: the caller's own principal scopes the delete, after the access
 * check, with the ids once each; a caller who is no member of the company deletes nothing, and neither does one the
 * company refuses.
 */
class StagedFileReleaseTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID USER = UUID.randomUUID();

    private static final UUID PERSON = UUID.randomUUID();

    private static final UUID FIRST = UUID.randomUUID();

    private static final UUID SECOND = UUID.randomUUID();

    private final CompanyAccessGuard access = mock(CompanyAccessGuard.class);

    private final Principals principals = mock(Principals.class);

    private final StagedFileStore store = mock(StagedFileStore.class);

    private final AttachmentService service = new AttachmentService(mock(AttachmentStore.class),
            mock(Conversations.class), access, mock(VaultService.class), mock(Agents.class),
            mock(UserDisplayNames.class), mock(AgentDisplayNames.class), mock(AuditLog.class),
            mock(AttachmentProperties.class), mock(TimeSource.class), mock(OwnerReads.class),
            mock(SubmissionKeys.class), principals, store);

    @BeforeEach
    void signedIn() {
        SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(new AuthenticatedUser(USER,
                "ann@example.test", "Ann Example", false)));
        when(access.require(COMPANY, CompanyPermission.WORK)).thenReturn(new CompanyAccess(COMPANY,
                CompanyRole.MEMBER, false));
    }

    @AfterEach
    void signedOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aReleaseDeletesWithinTheCallersOwnPrincipalEachIdOnce() {
        when(principals.ofMember(COMPANY, USER)).thenReturn(Optional.of(new PrincipalInfo(PERSON, COMPANY,
                PrincipalKind.MEMBER, USER, "Ann Example", PrincipalStatus.ACTIVE)));

        service.release(COMPANY, List.of(FIRST, SECOND, FIRST));

        verify(access).require(COMPANY, CompanyPermission.WORK);
        verify(store).delete(PERSON, List.of(FIRST, SECOND));
    }

    @Test
    void aCallerWhoIsNoMemberOfTheCompanyDeletesNothing() {
        when(principals.ofMember(COMPANY, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.release(COMPANY, List.of(FIRST)))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(store, never()).delete(any(), any());
    }

    @Test
    void aCallerTheCompanyRefusesDeletesNothing() {
        when(access.require(COMPANY, CompanyPermission.WORK)).thenThrow(ApiException.notFound("Company not found"));

        assertThatThrownBy(() -> service.release(COMPANY, List.of(FIRST))).isInstanceOf(ApiException.class);

        verify(principals, never()).ofMember(any(), any());
        verify(store, never()).delete(any(), any());
    }
}
