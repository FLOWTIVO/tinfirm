package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.PrincipalSwitches;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.system.Systems;

/**
 * The card and the revocation's words of a binding whose holder is suspended, for each holder and cause: who is
 * suspended, why when it is a deactivation, and what lifts it (an admin's resume, the user's reactivation by an
 * instance admin, the identity provider).
 */
class BindingTextsSuspendedTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private final Principals principals = mock(Principals.class);

    private final PrincipalSwitches switches = mock(PrincipalSwitches.class);

    private final Systems systems = mock(Systems.class);

    private final BindingTexts texts = new BindingTexts(systems, mock(Accounts.class), mock(GroupMembership.class),
            principals, switches);

    private BindingInfo heldBy(PrincipalKind kind, String name, PrincipalSwitches.@Nullable Cause cause) {
        UUID holder = UUID.randomUUID();
        when(principals.find(COMPANY, holder)).thenReturn(Optional.of(new PrincipalInfo(holder, COMPANY, kind,
                kind == PrincipalKind.MEMBER ? UUID.randomUUID() : null, kind == PrincipalKind.SERVICE ? name : null,
                PrincipalStatus.SUSPENDED)));
        when(principals.displayNames(any(), anyCollection())).thenReturn(Map.of(holder, name));
        when(switches.suspendedFor(COMPANY, holder)).thenReturn(Optional.ofNullable(cause));
        return binding(holder);
    }

    private static BindingInfo binding(@Nullable UUID holder) {
        return new BindingInfo(UUID.randomUUID(), COMPANY, UUID.randomUUID(), UUID.randomUUID(),
                BindingInfo.State.WAITING, BindingInfo.How.OWN, UUID.randomUUID(), holder, null, null,
                BindingInfo.Reason.SUSPENDED, List.of(), null, null);
    }

    @Test
    void aServiceAccountIsResumedByAnAdmin() {
        BindingInfo binding = heldBy(PrincipalKind.SERVICE, "svc-reader", PrincipalSwitches.Cause.ADMIN);
        when(systems.find(COMPANY, binding.systemId())).thenReturn(Optional.empty());
        assertThat(texts.card(binding)).isEqualTo("A removed system: the service account svc-reader it acts through "
                + "is suspended; an admin resumes it.");
        assertThat(texts.revoked(binding)).isEqualTo("the account's holder was suspended");
    }

    @Test
    void aMemberAnAdminSuspendedIsResumedByAnAdmin() {
        BindingInfo binding = heldBy(PrincipalKind.MEMBER, "Ana", PrincipalSwitches.Cause.ADMIN);
        assertThat(texts.suspended(binding)).isEqualTo("Ana, whose account it acts through, is suspended in this "
                + "company; an admin resumes them.");
        assertThat(texts.revoked(binding)).isEqualTo("the account's holder was suspended");
    }

    @Test
    void aDeactivatedMemberIsResumedByTheUsersReactivation() {
        BindingInfo binding = heldBy(PrincipalKind.MEMBER, "Ana", PrincipalSwitches.Cause.USER_DEACTIVATED);
        assertThat(texts.suspended(binding)).isEqualTo("Ana, whose account it acts through, is suspended because their "
                + "Tinfirm account was deactivated; an instance admin reactivates the user, which resumes them.");
        assertThat(texts.revoked(binding)).isEqualTo("the account's holder was suspended because their Tinfirm account "
                + "was deactivated");
    }

    @Test
    void aMemberTheIdentityProviderDeactivatedIsResumedByIt() {
        BindingInfo binding = heldBy(PrincipalKind.MEMBER, "Ana", PrincipalSwitches.Cause.SCIM);
        assertThat(texts.suspended(binding)).isEqualTo("Ana, whose account it acts through, is suspended because the "
                + "company's identity provider deactivated them; the identity provider reactivates them, which "
                + "resumes them.");
        assertThat(texts.revoked(binding)).isEqualTo("the account's holder was suspended because the company's "
                + "identity provider deactivated them");
    }

    @Test
    void aHolderNoLongerSuspendedOrUnknownPromisesNobody() {
        assertThat(texts.suspended(heldBy(PrincipalKind.MEMBER, "Ana", null)))
            .isEqualTo("the account's holder is suspended; it goes on once their suspension is lifted.");
        assertThat(texts.suspended(binding(null)))
            .isEqualTo("the account's holder is suspended; it goes on once their suspension is lifted.");
    }
}
