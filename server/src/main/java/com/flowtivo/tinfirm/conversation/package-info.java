/**
 * Conversations (the package 3 contract, docs/reviews/2026-10-12-package-3-contract.md, §3.8 to §3.13, §5.3 to §5.10
 * and §11): the one object of work, one agent in one place for one person, who pays for it and alone sees it with
 * OWNER. Its turns are runs of the run module; its accounts are bound once per system and checked at every use.
 * <p>
 * API: {@link com.flowtivo.tinfirm.conversation.ConversationReads} and
 * {@link com.flowtivo.tinfirm.conversation.ConversationCommands} (the reads and writes of the REST API and of the MCP
 * endpoint, by {@link com.flowtivo.tinfirm.conversation.ConversationView}; the package 6 contract, open point 20),
 * {@link com.flowtivo.tinfirm.conversation.Conversations} (lookups and who sees a conversation),
 * {@link com.flowtivo.tinfirm.conversation.ConversationBindings} (the binding of a conversation: resolve, check and
 * use, the only path to a token), the records {@link com.flowtivo.tinfirm.conversation.ConversationInfo} and
 * {@link com.flowtivo.tinfirm.conversation.BindingInfo}, and the events of
 * {@link com.flowtivo.tinfirm.conversation.ConversationEvents}. It implements the run module's ports
 * {@link com.flowtivo.tinfirm.run.TurnSource}, {@link com.flowtivo.tinfirm.run.TurnEnded},
 * {@link com.flowtivo.tinfirm.run.RunVisibility} and (part 2) {@link com.flowtivo.tinfirm.run.RunRequests}, and listens
 * to the events of the account, principal, system, agent and run modules.
 * <p>
 * The module is built in three parts: part 1, the conversation core (conversations, messages, turns, bindings, the
 * workspace, the prompt, visibility); part 2, decisions, the platform requests, the tools of a run, parked calls and the
 * inbox; part 3, outputs and attachments. The parts meet at the interfaces of the {@code internal} package that say so.
 * <p>
 * Package 6: the port {@link com.flowtivo.tinfirm.conversation.InboxItems} (inbox items other modules compute, the
 * governance module's {@code DEPARTURE}), {@link com.flowtivo.tinfirm.conversation.PersonConversations} (a person's
 * conversations by counts, for a leaver's checklist); it implements {@code system.SystemSwitchDependents}. The port
 * {@link com.flowtivo.tinfirm.conversation.ConversationCosts} (what a conversation cost, the governance module's
 * {@code run_spend} as the caller sees spend) fills {@code Conversation.cost} and {@code ConversationSummary.costUsd}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Conversations")
package com.flowtivo.tinfirm.conversation;
