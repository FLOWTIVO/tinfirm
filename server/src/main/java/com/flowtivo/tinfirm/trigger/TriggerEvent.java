package com.flowtivo.tinfirm.trigger;

/**
 * What happens to a subject that a trigger may fire on (the package 4 contract, §5.5; ADR-0043), and a schedule's
 * {@code SCHEDULE}.
 */
public enum TriggerEvent {
    /** The subject was created at or after the window's bound. */
    CREATED,
    /** Its title, description or type changed. */
    UPDATED,
    /** It entered a status: the value. */
    STATUS_ENTERED,
    /** It got a label: the value. */
    LABEL_ADDED,
    /** It lost a label: the value. */
    LABEL_REMOVED,
    /** The poll's account was newly among its assignees. */
    ASSIGNED,
    /** A person's new comment mentions the poll's account. */
    MENTIONED,
    /** A person's new comment, not mentioning the account. */
    COMMENTED,
    /** A change request was opened. */
    CHANGE_REQUEST_OPENED,
    /** A change request got a new head commit that is not a recorded push of Tinfirm's. */
    CHANGE_REQUEST_UPDATED,
    /** It was closed. */
    CLOSED,
    /** A schedule's firing. */
    SCHEDULE,
    /** A webhook's delivery (package 5): the mapped event, as the value when the trigger names one. */
    DELIVERED;

    /** Whether the event names a value: a status or a label. */
    public boolean takesValue() {
        return this == STATUS_ENTERED || this == LABEL_ADDED || this == LABEL_REMOVED;
    }

    /** Whether only change requests have it (a subject {@code ISSUE} refuses it). */
    public boolean changeRequestsOnly() {
        return this == CHANGE_REQUEST_OPENED || this == CHANGE_REQUEST_UPDATED;
    }

    /** Whether a comment raises it: always a person's (§5.5). */
    public boolean ofComment() {
        return this == MENTIONED || this == COMMENTED;
    }
}
