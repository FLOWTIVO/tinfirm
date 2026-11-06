package com.flowtivo.tinfirm.audit;

/**
 * Appends events to the hash-chained audit log.
 * <p>
 * {@link #record} joins the caller's transaction, so an event commits or rolls back with the change it
 * describes. Without a transaction it runs in its own. The events of a transaction are appended when it commits, in
 * the order they were recorded, each with the time it was recorded: the chain's head is the last row a transaction
 * locks (CONVENTIONS §10), whenever it records its events. So a transaction does not read its own events: they are
 * not in the chain before it commits.
 */
public interface AuditLog {

    void record(AuditRecord record);
}
