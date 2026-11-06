package com.flowtivo.tinfirm.trigger.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.CredentialUses;

/**
 * The live triggers that act with a credential through a service account, for the credential's page and the service
 * account's (ADR-0072): how many, and those open to anyone by name (the security review of 2026-10-27, D2).
 */
@Component
class TriggerCredentialUses implements CredentialUses {

    private final TriggerStore store;

    TriggerCredentialUses(TriggerStore store) {
        this.store = store;
    }

    @Override
    public Map<Lending, Integer> liveTriggers(UUID companyId, Collection<UUID> credentialIds) {
        return store.liveTriggersByLending(companyId, credentialIds);
    }

    @Override
    public Map<UUID, List<OpenTrigger>> openTriggers(UUID companyId, Collection<UUID> credentialIds) {
        return store.liveOpenByCredential(companyId, credentialIds);
    }
}
