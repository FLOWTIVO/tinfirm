/**
 * Governance (the package 6 contract, docs/reviews/2026-10-12-package-6-contract.md, §2.3, §5.4, §5.5, §5.9, §5.11 and
 * §11): what makes the platform operable by a company.
 * <p>
 * <p>
 * It also holds spend and budget alerts (§3.1, §3.2, §5.1 to §5.3, §5.11; G1 to G3):
 * <ul>
 * <li>{@code run_spend}, the module's own: what each run spent on each model, written once, only by the spend sync,
 * from LiteLLM's spend logs of the requests the model relay tagged with the run ({@code GATEWAY}), else from the
 * relay's own count at LiteLLM's prices ({@code RELAY}); a subscription's run keeps the tokens its sandbox reported,
 * without a cost ({@code SUBSCRIPTION}). The sandbox's report sets no cost (G2). The sync hears
 * {@link com.flowtivo.tinfirm.run.RunEvents.RunEnded} in the run's transaction ({@code spend_settlement}), and the
 * model module's {@link com.flowtivo.tinfirm.model.ModelEvents.RelayRequestCounted} outside any transaction, for a
 * run whose last answer was still under way when it ended (its settlement, when it has none yet); it settles
 * on {@code worker} replicas, reading LiteLLM through {@link com.flowtivo.tinfirm.model.SpendLogs} outside any
 * transaction, under a lease (G3).</li>
 * <li>The spend views ({@code GET /spend}): the caller's scope before any filter, grouping or label (G1); the CSV
 * audited as {@code spend.exported}. A conversation's cost, in the same scope over all time, is served to the
 * conversation module's port {@link com.flowtivo.tinfirm.conversation.ConversationCosts}.</li>
 * <li>The company's monthly alert amount ({@code company_spend_alert}), and the inbox items of budgets, the company's
 * total and the sync's fallback, served through the conversation module's port
 * {@link com.flowtivo.tinfirm.conversation.InboxItems}; the budget alerts themselves are the model module's
 * ({@link com.flowtivo.tinfirm.model.BudgetAlerts}).</li>
 * </ul>
 * <p>
 * <p>
 * It also holds the kill switches on one page and a member who leaves (§5.4, §5.5): {@code GET /kill-switches}, the
 * company's stop, a system's pause and a member's suspension through their owners' public APIs
 * ({@code company.CompanySwitches}, {@code system.SystemSwitches}, {@code principal.PrincipalSwitches}), the checklist
 * of a member who left and the admins' {@code DEPARTURE} inbox item (the port
 * {@link com.flowtivo.tinfirm.conversation.InboxItems}). Each switch's state stays with its data, and what a switch does
 * above its owner is done by the higher module in the owner's port ({@code CompanyRunsDependents},
 * {@code SystemSwitchDependents}) or listener. Package 4's triggers are read through
 * {@link com.flowtivo.tinfirm.governance.TriggerSwitchReads}, which it implements over {@code trigger.Triggers}.
 * <p>
 * It stands on {@code conversation}, {@code run}, {@code agent}, {@code model}, {@code principal}, {@code system},
 * {@code trigger}, {@code audit} and {@code shared}, through their public types only (CONVENTIONS §10); no lower module
 * depends on it. Triggers (package 4) reach its spend through one seam, {@code internal.TriggerSpend}, answered over the
 * trigger module's {@link com.flowtivo.tinfirm.trigger.TriggerDirectory}. API:
 * {@link com.flowtivo.tinfirm.governance.Spend}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Governance")
package com.flowtivo.tinfirm.governance;
