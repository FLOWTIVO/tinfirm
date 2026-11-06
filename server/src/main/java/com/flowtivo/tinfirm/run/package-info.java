/**
 * Runs as the turns of conversations (the package 3 contract, docs/reviews/2026-10-12-package-3-contract.md §3.11 and
 * §5.7; PLATFORM.md §11): a turn is one run, executed in a sandbox on a runner host.
 * <ul>
 * <li>{@link com.flowtivo.tinfirm.run.Turns}: the conversation module starts a turn's run (one at a time per
 * conversation, I11; conversations in parallel) and cancels it.</li>
 * <li>The dispatcher, in the contract's order: the agent ({@link com.flowtivo.tinfirm.agent.Agents#find}: paused,
 * deleted, a runtime it dropped) and the settings in effect for the turn
 * ({@link com.flowtivo.tinfirm.agent.Settings#effective}), the conversation's checks
 * ({@link com.flowtivo.tinfirm.run.TurnSource#plan}: a waiting workspace binding), the payer's model access
 * ({@link com.flowtivo.tinfirm.model.ModelAccess#forRun}), the hosts that may carry the run's credentials
 * ({@link com.flowtivo.tinfirm.model.Hosts#eligible}), connected, configured and speaking protocol v2, a subscription's
 * pool and the hosts' free slots, the conversation's previous host first (open point 13); leases, epochs, the reaper,
 * the reconnect grace, cancel, and the resumption of a run lost by lease expiry.</li>
 * <li>The assignment: {@code run.assign} from the conversation ({@link com.flowtivo.tinfirm.run.TurnSource#content},
 * outside any transaction) and the settings (the agent's name, the model, the effort, the agent's network hosts),
 * {@code workItem.id} and {@code job.id} the conversation's id, the branch {@code agent/{key}}, and
 * {@code modelAccess} from the model relay ({@link com.flowtivo.tinfirm.model.ModelRelay#open}: a one-run token) or a
 * subscription's {@code credentialLabel}. The token is revoked when the run ends, and is active only while its run is
 * RUNNING on its host with its epoch: this module implements {@link com.flowtivo.tinfirm.model.RunModelTokens}.</li>
 * <li>The run side of runner protocol v2 ({@link com.flowtivo.tinfirm.runner.RunnerSessionListener},
 * {@link com.flowtivo.tinfirm.runner.RunnerActivity}): run events stored and streamed, every {@code run.request}
 * answered with exactly one {@code run.request.ack} through {@link com.flowtivo.tinfirm.run.RunRequests}, and the end
 * of a run handed to {@link com.flowtivo.tinfirm.run.TurnEnded}.</li>
 * <li>The Runs API: a run, its events and their live stream, cancel; who sees a run is
 * {@link com.flowtivo.tinfirm.run.RunVisibility}'s.</li>
 * <li>Session bundles (ADR-0097): the session folders of a conversation's agent, which a company host uploads when a
 * run ends and the next run's host restores, kept encrypted in the bundle store under a data key of the vault's
 * ({@link com.flowtivo.tinfirm.vault.DataKeys}), and swept with the conversation. Keeping them is Pro's and
 * Enterprise's, under {@code ee/} (ADR-0100); deleting them is the core's, in every edition.</li>
 * </ul>
 * API: {@link com.flowtivo.tinfirm.run.Turns}, {@link com.flowtivo.tinfirm.run.RunReads} and the events of
 * {@link com.flowtivo.tinfirm.run.RunEvents}. It implements the agent module's
 * {@link com.flowtivo.tinfirm.agent.AgentRuns} (an agent with active runs is not deleted; pausing it with
 * {@code stopRunning} cancels them). Ports the conversation module (package 3c) implements:
 * {@link com.flowtivo.tinfirm.run.TurnSource}, {@link com.flowtivo.tinfirm.run.RunRequests},
 * {@link com.flowtivo.tinfirm.run.TurnEnded}, {@link com.flowtivo.tinfirm.run.RunVisibility} and
 * {@link com.flowtivo.tinfirm.run.ConversationLock} (a conversation's row is locked before its runs', CONVENTIONS
 * §10). Without them: no turn source (a run waits with {@code OTHER}), every platform request answered
 * {@code REJECTED}, nobody told of a run's end, no conversation locked, and a run seen and cancelled by its person,
 * OWNER and an instance admin who is not a member (the audited break-glass) only.
 * <p>
 * Package 6: {@link com.flowtivo.tinfirm.run.RunCancellations}, the kill switches' cancels of running turns; the
 * dispatcher's step 0 (the company's stop, a paused system's repository); the run module implements
 * {@code company.CompanyRunsDependents} and cancels a suspended principal's turns after the commit.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Runs")
package com.flowtivo.tinfirm.run;
