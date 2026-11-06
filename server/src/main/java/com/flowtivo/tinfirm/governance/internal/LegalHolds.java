package com.flowtivo.tinfirm.governance.internal;

import java.util.UUID;

import com.flowtivo.tinfirm.shared.api.model.HoldConversationRef;
import com.flowtivo.tinfirm.shared.api.model.LegalHold;
import com.flowtivo.tinfirm.shared.api.model.LegalHoldRequest;

/**
 * Setting legal holds, Enterprise's (ADR-0077, decision 4, {@code LEGAL_HOLDS}): a paid feature's code, under
 * {@code ee/} (decision 1), which checks the licence in force. The core finds it when used ({@link HoldService}) and
 * never names its implementation: without {@code ee/} the server answers as Community, {@code 403 EDITION_REQUIRED}.
 * The holds that exist are the core's in every edition: the sweep and erasures keep what they cover, and they are
 * listed and lifted.
 * <p>
 * Each method runs in the caller's transaction and tenant context, after its access check ({@code MANAGE_RETENTION}).
 */
interface LegalHolds {

    /**
     * A conversation to hold, by its key, to set a hold on, so Enterprise's too; its id and key alone, so not a read of
     * it; an unknown key is 404.
     */
    HoldConversationRef conversation(UUID companyId, String key);

    /** Sets a hold on a conversation or on a member's principal, audited {@code hold.set}. */
    LegalHold set(UUID companyId, LegalHoldRequest request);
}
