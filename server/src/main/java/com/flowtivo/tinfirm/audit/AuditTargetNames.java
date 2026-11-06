package com.flowtivo.tinfirm.audit;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Port: the names of the targets of audit events, resolved when a chain is read and never stored in it (CONVENTIONS
 * §8). The module that keeps a kind of target implements it for its types (company, agent, system, runner, trigger,
 * conversation); the audit asks each once per page and type, in the reader's transaction and tenant context, so a page
 * costs one query per type whatever its length. A name is answered only as the reader may see it elsewhere: a target
 * that is gone, or whose name the reader may not see, is absent.
 */
public interface AuditTargetNames {

    /** The target types it names, as the events record them ({@code system}, {@code runner_host}). */
    Set<String> targetTypes();

    /** The names of these targets of one of its types, by their ids as the events record them; others are absent. */
    Map<String, String> names(Reader reader, String targetType, Set<String> targetIds);

    /** The ids that are UUIDs, as most targets' are; any other is left out. */
    static Set<UUID> uuids(Collection<String> ids) {
        Set<UUID> uuids = new LinkedHashSet<>();
        for (String id : ids) {
            try {
                uuids.add(UUID.fromString(id));
            }
            catch (IllegalArgumentException ex) {
                // Not one of a UUID's: no target of this type has it.
            }
        }
        return uuids;
    }

    /**
     * Who reads a chain.
     *
     * @param companyId   the chain's company; null for the instance chain, which only instance admins read
     * @param userId      the reader
     * @param principalId the reader's principal in the company; null for an instance admin who is not a member, and in
     *                    the instance chain
     * @param owner       whether the reader is the company's OWNER
     */
    record Reader(@Nullable UUID companyId, UUID userId, @Nullable UUID principalId, boolean owner) {
    }
}
