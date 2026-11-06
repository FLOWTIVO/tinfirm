package com.flowtivo.tinfirm.shared.retention;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The words of someone outside Tinfirm, a chat user or a tracker commenter, held by a content module (the package 6
 * contract, §5.9; open point 17): the port through which the governance module erases them on request, implemented by
 * the modules of packages 4 and 5 that store such words ({@code EXTERNAL} messages with their {@code external_author},
 * {@code decided_by_name} and {@code decided_by_external_id}, a firing's actor name, a {@code chat_user}). A content
 * module never depends on the governance module. Runs in the caller's transaction and tenant context, which locked the
 * conversations {@link #conversationsOf} named first (CONVENTIONS §10, the lock order).
 */
public interface IdentityContent {

    /** The conversations that hold the identity's words, kept: for the legal holds' check and the locks. */
    Set<UUID> conversationsOf(UUID companyId, UUID systemId, String externalId);

    /**
     * Erases the identity's words now, in open conversations too (a message's body replaced by the removal marker).
     *
     * @return what was removed, by kind, and the runs whose prompts quoted the words, whose events the caller purges
     */
    Erased erase(UUID companyId, UUID systemId, String externalId, Instant at);

    /**
     * What {@link #erase} would remove now, changing nothing: its counts by kind, as it would answer them, by its own
     * selection, and the runs whose events the caller would purge. The erasure's preview answers with it.
     */
    Erased count(UUID companyId, UUID systemId, String externalId);

    /**
     * What an erasure removed, or would remove.
     *
     * @param counts by kind ({@code messages}, {@code decisions}, {@code firings}, {@code chatUsers}, …)
     * @param runIds the runs that took the words, whose transcripts quoted them
     */
    record Erased(Map<String, Long> counts, Set<UUID> runIds) {

        public Erased {
            counts = Map.copyOf(counts);
            runIds = Set.copyOf(runIds);
        }
    }
}
