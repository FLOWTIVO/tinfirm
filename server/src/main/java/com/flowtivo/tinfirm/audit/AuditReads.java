package com.flowtivo.tinfirm.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads of the company's chain for other modules (the package 6 contract, §5.6). Runs in the caller's transaction and
 * tenant context: row-level security limits it to the company of that context.
 */
public interface AuditReads {

    /** The most readers {@link #conversationReads} answers. */
    int MAX_READERS = 100;

    /**
     * Who read the conversation other than its person, from its {@code conversation.read} events: one entry per
     * reader, the most recent reader first, at most {@value #MAX_READERS}.
     */
    List<ConversationReader> conversationReads(UUID companyId, UUID conversationId);

    /**
     * How many deliveries to the webhook system were refused since the time, from its {@code webhook.refused} events:
     * the sum of their {@code count}s. Each replica records a reason's refusals at most once a minute, with the count
     * since its last record, so the refusals of the last minute may not be counted yet.
     */
    long webhookRefusals(UUID companyId, UUID systemId, Instant since);

    /**
     * One reader of a conversation.
     *
     * @param kinds what was read (the events' {@code what}), sorted
     */
    record ConversationReader(UUID userId, Instant firstReadAt, Instant lastReadAt, long reads, List<String> kinds) {

        public ConversationReader {
            kinds = List.copyOf(kinds);
        }
    }
}
