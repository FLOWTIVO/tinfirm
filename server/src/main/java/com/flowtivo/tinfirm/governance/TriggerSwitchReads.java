package com.flowtivo.tinfirm.governance;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What the kill switches' page and a leaver's checklist read of package 4's triggers (the package 6 contract, §5.4,
 * §5.5). The governance module implements it itself over the trigger module's public API
 * ({@code internal.TriggerSwitchReadsImpl}), so that the trigger module never depends on governance. Runs in the
 * caller's transaction and tenant context.
 */
public interface TriggerSwitchReads {

    /** The company's paused triggers, newest pause first, with their reason ({@code SYSTEM_PAUSED}, …). */
    default List<PausedTrigger> paused(UUID companyId) {
        return List.of();
    }

    /** The triggers a system's pause paused ({@code paused_reason SYSTEM_PAUSED} on the system): its resume dialog. */
    default List<PausedTrigger> pausedBySystem(UUID companyId, UUID systemId) {
        return List.of();
    }

    /** The triggers paused {@code AUTHOR_LEFT} whose author was the principal: item 1 of the leaver's checklist. */
    default List<PausedTrigger> pausedAuthorLeft(UUID companyId, UUID authorPrincipalId) {
        return List.of();
    }

    /** How many live triggers the principal authors: what pauses {@code AUTHOR_LEFT} when they leave. */
    default int authoredBy(UUID companyId, UUID authorPrincipalId) {
        return 0;
    }

    /** A paused trigger. */
    record PausedTrigger(UUID id, String name, @Nullable UUID authorPrincipalId, String reason,
            java.time.@Nullable Instant pausedAt, @Nullable UUID pausedBy, int waitingConversations) {
    }
}
