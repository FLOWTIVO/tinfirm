package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerSource;

/**
 * The lender is told (the security review of 2026-10-27, D2; the owner's decision Q3). A trigger on a source anyone may
 * write to (a tracker, a chat's channels, a webhook: every source but a schedule) whose row acts with a credential lent
 * to a service account exposes that credential to whoever writes there. The first time a save names it (a create, a
 * change of the table, a reassignment, a lending the trigger follows), the exposure is recorded once per trigger and
 * credential ({@code trigger_credential_exposure}, V077) with who lent it, and {@link TriggerInboxImpl} puts it in
 * their inbox ({@code CREDENTIAL_EXPOSED_TO_OPEN_SOURCE}), or in the OWNERs' and ADMINs' when the lending's creator was
 * not recorded or is no member any more. No flag on a lending allows or forbids it: the author's acknowledgement of the
 * open writes is the consent, the lender's notice and the credential's page are the oversight.
 */
@Component
class TriggerExposures {

    private final TriggerStore store;

    private final Accounts accounts;

    private final TimeSource time;

    TriggerExposures(TriggerStore store, Accounts accounts, TimeSource time) {
        this.store = store;
        this.accounts = accounts;
        this.time = time;
    }

    /** Records the credentials the trigger's table exposes to its open source, once each, in the save's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    void record(TriggerInfo trigger) {
        if (trigger.source() == TriggerSource.SCHEDULE || !trigger.live()) {
            return;
        }
        Instant now = time.now();
        for (TriggerAccount row : store.accounts(trigger.companyId(), trigger.id())) {
            UUID accountId = row.accountId();
            // The author's own account is theirs to expose, by their acknowledgement; a credential's lender is told.
            if (accountId == null || row.holderPrincipalId().equals(trigger.authorPrincipalId())) {
                continue;
            }
            store.recordExposure(trigger.companyId(), trigger.id(), accountId, row.holderPrincipalId(),
                    accounts.lentBy(trigger.companyId(), row.systemId(), row.holderPrincipalId()).orElse(null), now);
        }
    }
}
