/**
 * Append-only, hash-chained audit log (CONVENTIONS §8).
 * <p>
 * API: {@link com.flowtivo.tinfirm.audit.AuditLog} to record events, {@link com.flowtivo.tinfirm.audit.AuditTrail}
 * to read and verify the instance chain. Actor display names are resolved at read time through
 * {@link com.flowtivo.tinfirm.shared.directory.UserDisplayNames} and, for agent actors,
 * {@link com.flowtivo.tinfirm.shared.directory.AgentDisplayNames}, which the agents of package 3 of the platform
 * redesign implement. Targets' names are resolved at read time as well, through the ports this module declares and the
 * modules above it implement, looked up when used: {@link com.flowtivo.tinfirm.audit.AuditTargetNames} (each module's
 * own targets), {@link com.flowtivo.tinfirm.audit.AuditPrincipals} (the principal module) and, for the
 * {@code conversationKey} filter, {@link com.flowtivo.tinfirm.audit.AuditConversationKeys} (the conversation module).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit")
package com.flowtivo.tinfirm.audit;
