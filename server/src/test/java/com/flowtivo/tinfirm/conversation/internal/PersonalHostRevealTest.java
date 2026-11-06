package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.run.RunPlacementStale;
import com.flowtivo.tinfirm.run.TurnSource;
import com.flowtivo.tinfirm.runner.HostScope;

/**
 * The personal-host rule at the reveal (the security review of 2026-10-27, M2 and D6) as a unit: a run placed on a
 * personal host reveals a git token only while every binding it reveals is the payer's own account; a credential a
 * grant lends, or another member's account, takes the run back ({@link RunPlacementStale}); a company host carries any.
 */
class PersonalHostRevealTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID PAYER = UUID.randomUUID();

    private static final TurnSource.Placement PERSONAL = new TurnSource.Placement(UUID.randomUUID(),
            HostScope.PERSONAL, 1, null);

    private static final TurnSource.Placement COMPANY_HOST = new TurnSource.Placement(UUID.randomUUID(),
            HostScope.COMPANY, 1, null);

    @Test
    void aPersonalHostCarriesThePayersOwnAccountsOnly() {
        assertThatCode(() -> TurnSourceImpl.requireCarried(PERSONAL, PAYER, List.of(own(PAYER), own(PAYER))))
            .doesNotThrowAnyException();
        assertThatCode(() -> TurnSourceImpl.requireCarried(PERSONAL, PAYER, List.of())).doesNotThrowAnyException();
    }

    @Test
    void aGrantsCredentialApprovedSinceThePlanTakesTheRunBack() {
        assertThatThrownBy(() -> TurnSourceImpl.requireCarried(PERSONAL, PAYER, List.of(own(PAYER), granted())))
            .isInstanceOf(RunPlacementStale.class);
    }

    @Test
    void anotherMembersAccountTakesTheRunBack() {
        assertThatThrownBy(() -> TurnSourceImpl.requireCarried(PERSONAL, PAYER, List.of(own(UUID.randomUUID()))))
            .isInstanceOf(RunPlacementStale.class);
    }

    @Test
    void aCompanyHostCarriesAnyAccount() {
        assertThatCode(() -> TurnSourceImpl.requireCarried(COMPANY_HOST, PAYER, List.of(own(UUID.randomUUID()),
                granted()))).doesNotThrowAnyException();
    }

    private static BindingInfo own(UUID holder) {
        return new BindingInfo(UUID.randomUUID(), COMPANY, UUID.randomUUID(), UUID.randomUUID(),
                BindingInfo.State.BOUND, BindingInfo.How.OWN, UUID.randomUUID(), holder, null, null, null, List.of(),
                null, Instant.now());
    }

    /** A credential a group's grant lends: no holder, its id stands for it. */
    private static BindingInfo granted() {
        return new BindingInfo(UUID.randomUUID(), COMPANY, UUID.randomUUID(), UUID.randomUUID(),
                BindingInfo.State.BOUND, BindingInfo.How.GRANTED, UUID.randomUUID(), null, null, UUID.randomUUID(),
                null, List.of(), null, Instant.now());
    }
}
