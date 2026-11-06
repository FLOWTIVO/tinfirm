package com.flowtivo.tinfirm.audit;

import java.util.Optional;
import java.util.UUID;

/**
 * Port: a conversation's id by its key, for the {@code conversationKey} filter of the company's audit (CONVENTIONS
 * §15.11), implemented by the conversation module. Any conversation of the company, whoever's: the filter shows no more
 * than {@code conversationId} would. Runs in the reader's transaction and tenant context.
 */
public interface AuditConversationKeys {

    /** The id of the company's conversation with this key ({@code shop-123-dev-1}); empty for none. */
    Optional<UUID> idOfKey(UUID companyId, String key);
}
