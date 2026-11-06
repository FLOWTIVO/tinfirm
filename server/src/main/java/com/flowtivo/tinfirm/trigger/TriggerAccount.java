package com.flowtivo.tinfirm.trigger;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A row of a trigger's account table (the package 4 contract, §3.2): the account its conversations bind on one system.
 *
 * @param accountId         the holder's account on the system; null once it went (the trigger is paused until its author
 *                          or an admin names another or removes the row)
 * @param holderPrincipalId the trigger's author, or a service identity the author may use (T1)
 * @param externalId        the account's identity on the system when saved
 * @param filledFrom        {@code CHOICE}: the editor's one choice filled it; {@code ROW}: the author changed it
 */
public record TriggerAccount(UUID systemId, @Nullable UUID accountId, UUID holderPrincipalId,
        @Nullable String externalId, FilledFrom filledFrom, Instant savedAt) {

    public enum FilledFrom {
        CHOICE, ROW
    }
}
