package com.flowtivo.tinfirm.account;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port: what acts with a system's credential through the service accounts that borrow it (ADR-0072), answered by the
 * module that keeps it: the trigger module counts the live triggers whose rows name a credential through a service
 * account, which pause when it is withdrawn or replaced, and names those open to anyone (the security review of
 * 2026-10-27, D2); the account module does not depend on it. Runs in the caller's transaction and tenant context, with
 * a fixed number of queries whatever the credentials.
 */
public interface CredentialUses {

    /** A credential lent to a service account. */
    record Lending(UUID credentialId, UUID servicePrincipalId) {
    }

    /**
     * A live trigger open to anyone (its source is a tracker, a chat's channels or a webhook, never a schedule) that
     * acts with a credential through a service account (D2, the owner's decision Q3).
     *
     * @param source the trigger's source, as {@code TriggerSource} names it
     */
    record OpenTrigger(UUID triggerId, String name, String source, UUID servicePrincipalId) {
    }

    /** The live triggers whose rows act with each of the credentials, by credential and service account; none absent. */
    Map<Lending, Integer> liveTriggers(UUID companyId, Collection<UUID> credentialIds);

    /** The live triggers open to anyone that act with each of the credentials, by credential, by name; none absent. */
    default Map<UUID, List<OpenTrigger>> openTriggers(UUID companyId, Collection<UUID> credentialIds) {
        return Map.of();
    }
}
