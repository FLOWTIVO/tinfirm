/**
 * Triggers (the package 4 contract, docs/reviews/2026-10-12-package-4-contract.md, decided on review in its §11):
 * company automation. A trigger is a standing rule on a tracker system, or a schedule: when an event happens to an
 * issue or a change request, or the schedule is due, its agent does its instruction with one account per system (its
 * table) and the model access its author chose; its team sees its conversations and decides their approvals; its
 * results go back to the place.
 * <p>
 * API: {@link com.flowtivo.tinfirm.trigger.Triggers} (lookups), {@link com.flowtivo.tinfirm.trigger.TriggerGate} (the
 * entitlement at every use, §5.3), the records {@link com.flowtivo.tinfirm.trigger.TriggerInfo} and
 * {@link com.flowtivo.tinfirm.trigger.TriggerAccount} with their enums, and the events of
 * {@link com.flowtivo.tinfirm.trigger.TriggerEvents}. It implements the conversation module's
 * {@link com.flowtivo.tinfirm.conversation.TriggerAudience} and {@link com.flowtivo.tinfirm.conversation.TriggerTurns},
 * the principal module's {@link com.flowtivo.tinfirm.principal.PrincipalDependents} (an author who leaves, a service
 * identity in use) and the company module's {@link com.flowtivo.tinfirm.company.GroupDependents} (a team's group
 * deleted) and {@link com.flowtivo.tinfirm.company.CompanyRunsDependents} (package 6's stop of the company's runs), and
 * the system module's {@link com.flowtivo.tinfirm.system.SystemSwitchDependents} (a system's pause and resume). The
 * agent module's trigger settings ({@code SettingsOwner.Trigger}) live in the agent module.
 * <p>
 * The module is built in three parts: part a, the trigger core (the editor, its checks, entitlement, a trigger's
 * conversations, kill switches, visibility, the inbox); part b, polling, events, firings and schedules
 * ({@code TriggerPolls}, {@code SubjectHandling}, {@code EventDetector}, {@code TriggerEvaluator}, {@code Firings},
 * {@code ScheduleFirings}, {@code ManualFiringsImpl}, {@code SystemWriteRecorder}, {@code PollKeeper}); part c, the
 * write-back outbox and the review of results ({@code OutboxQueue}, {@code OutboxDelivery}, {@code TrackerWriter}, and
 * the registry of {@link com.flowtivo.tinfirm.trigger.PlaceWriter} by place). The parts meet at the interfaces of the
 * {@code internal} package that say so.
 * <p>
 * Package 5 adds two sources (the package 5 contract, §5.7, §5.12): {@code CHANNEL}, mentions of a chat system's bot,
 * which the {@code chat} module receives and fires through {@link com.flowtivo.tinfirm.trigger.ChannelTriggers}, the
 * editor and the gate asking it through the port {@link com.flowtivo.tinfirm.trigger.ChannelSources}; and
 * {@code WEBHOOK}, a generic sender's deliveries, which this module receives itself ({@code WebhookInbox},
 * {@code WebhookVerifier}, {@code WebhookMapping}, {@code WebhookReceiver}, {@code WebhookFirings}) on the system
 * module's {@link com.flowtivo.tinfirm.system.WebhookSystems}; it implements that module's
 * {@link com.flowtivo.tinfirm.system.WebhookLimits} and the conversation module's
 * {@link com.flowtivo.tinfirm.conversation.PlacePrompts} for the {@code WEBHOOK} place. The chat's rows of the outbox
 * are queued through {@link com.flowtivo.tinfirm.trigger.PlaceOutbox} and written by the place's
 * {@link com.flowtivo.tinfirm.trigger.PlaceWriter}, the {@code BOT} writer. Lock order (CONVENTIONS §10, extended): a poll's row or a subject's snapshot
 * first, then a trigger's row (several in the order of their ids), then its conversations in the order of their ids,
 * then their runs, bindings, decisions and outbox rows, and the company's audit chain head last; trigger rows are locked
 * {@code FOR NO KEY UPDATE}. A poll's row is never locked after a trigger's: a trigger's change makes or wakes its poll
 * after its commit, and a system's resume moves its polls' windows before it locks a trigger.
 * <p>
 * For people's AI clients (the {@code mcp} module): {@link com.flowtivo.tinfirm.trigger.TriggerReads}, the Triggers
 * API's reads, and {@link com.flowtivo.tinfirm.trigger.WebhookReads}, a webhook system's deliveries and the try of its
 * mapping, which names for each trigger the first event or condition a body fails (the receiver's own rule). Neither
 * changes, pauses or fires anything.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Triggers")
package com.flowtivo.tinfirm.trigger;
