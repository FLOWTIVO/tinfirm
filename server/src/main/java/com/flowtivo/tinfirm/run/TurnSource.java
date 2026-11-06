package com.flowtivo.tinfirm.run;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;

/**
 * Port: what a turn needs of its conversation, implemented by the conversation module (package 3c), which owns the
 * conversation, its bindings, its workspace, its tools, its prompt and its files (the package 3 contract, §5.3 to
 * §5.9). The dispatcher asks twice:
 * <ol>
 * <li>{@link #plan} while it decides, in its transaction: whether the turn may go on now, and what the host rules need
 * to know;</li>
 * <li>{@link #content} once the run is assigned to a host with a new epoch, outside any transaction: the content of
 * {@code run.assign}, git tokens included.</li>
 * </ol>
 * The agent's side the run module reads itself from the agent module before it asks: the agent paused
 * ({@code AGENT_PAUSED}), deleted (the run is cancelled) or no longer listing the conversation's runtime
 * ({@code RUNTIME_REMOVED}), from {@code Agents.find}; and the settings in effect for the turn
 * ({@code Settings.effective} of the conversation's person), which it hands to both calls so that one turn uses one
 * reading of them. From those it takes {@code run.assign.agent} (the agent's name, the settings' model and effort) and
 * the agent's network hosts. It adds what is its own: the run and its epoch, {@code workItem} and {@code job} (the
 * conversation's id and key), the branch {@code agent/{key}} and each repository's directory,
 * {@code resumeSessionId}, the model access (the relay's one-run token or the subscription's label) and the limits.
 */
public interface TurnSource {

    /**
     * The conversation's checks of the turn, step 2 of the contract's order (§5.7), once the agent is live, active and
     * still lists the runtime: a workspace binding that waits ({@code ACCOUNT_REQUIRED}), a conversation that no longer
     * takes the turn. Runs in the dispatcher's transaction and the run's company's tenant context, as actor SYSTEM; it
     * reads, calls nothing outside the server and reveals nothing. The dispatcher locked the conversation's row before
     * the run's ({@link ConversationLock}, CONVENTIONS §10): binding a workspace system writes the conversation.
     *
     * @param settings the settings in effect for the turn ({@code Settings.effective} of the conversation's person):
     *                 the workspace's systems are those they leave on
     */
    TurnPlan plan(TurnRef turn, EffectiveSettings settings);

    /**
     * The name of a paused system one of whose repositories the turn's workspace holds (the package 6 contract,
     * §5.4): the dispatcher's step 0 holds the turn ({@code SYSTEM_PAUSED}) until the system is resumed. Runs in the
     * dispatcher's transaction, after the conversation's row is locked.
     */
    default java.util.Optional<String> pausedWorkspaceSystem(TurnRef turn) {
        return java.util.Optional.empty();
    }

    /**
     * The content of the turn's {@code run.assign}, once the run is assigned to its host with a new epoch. Runs outside
     * any transaction, as actor SYSTEM, on a thread of its own (it may open its own short transactions in the run's
     * company): the workspace's git tokens come from {@code ConversationBindings.use} and {@code Accounts.reveal} with
     * the purpose {@code run:{runId}}, and stay on the host (I14). Answers {@link TurnContent.NotReady} when the turn
     * cannot go on after all, e.g. a binding that started to wait, and {@link TurnContent.Later} when it cannot go on
     * right now for a passing failure, e.g. a token that could not be got while its binding holds, or a kill switch
     * that went on since the plan. Throws {@link RunPlacementStale}, before it reveals anything more, when the run is
     * placed on a personal host and a workspace account it would reveal is not the payer's own
     * ({@code Hosts.personalHostMayRun}; the security review of 2026-10-27, M2): the run goes back to the queue.
     *
     * @param settings the settings {@link #plan} was given: the tools are theirs ({@code EffectiveSettings.tools},
     *                 named with {@code ToolNames.of}), and the prompt adds their instructions after the agent's
     */
    TurnContent content(TurnRef turn, Placement placement, EffectiveSettings settings);

    /** The turn the dispatcher asks about: its run, as the conversation started it ({@link NewTurn}). */
    record TurnRef(UUID companyId, UUID runId, UUID conversationId, String conversationKey, UUID agentId,
            RuntimeKind runtime, TurnReason reason, int turnNumber, UUID actingPrincipalId, UUID payerPrincipalId,
            ModelAccessChoice modelAccess) {
    }

    /**
     * Where the run goes: the host, its kind, the run's new epoch, and the conversation's previous run that left a
     * session, which this turn resumes on the host (null for none: a full prompt only).
     */
    record Placement(UUID runnerHostId, HostScope hostKind, long epoch, @Nullable PreviousRun previous) {
    }

    /** The conversation's latest earlier run that reported a session: the turn resumes it ({@code resumeSessionId}). */
    record PreviousRun(UUID runId, String sessionId, Instant createdAt, @Nullable UUID runnerHostId) {
    }

    /** The answer of {@link #plan}. */
    sealed interface TurnPlan permits TurnPlan.Ready, TurnPlan.AccountRequired, TurnPlan.TriggerPaused,
            TurnPlan.AgentNotUsable, TurnPlan.Cancel {

        /**
         * The turn may go on.
         *
         * @param workspaceAccountHolders the principals whose accounts the workspace's git tokens are (each
         *                                repository's binding's holder), for the host scope rule (open point 7; M4);
         *                                empty without a workspace
         */
        record Ready(Set<UUID> workspaceAccountHolders) implements TurnPlan {

            public Ready {
                workspaceAccountHolders = Set.copyOf(workspaceAccountHolders);
            }
        }

        /**
         * A binding of the workspace waits for the person or an admin: the run stays queued with
         * {@link QueueReasonCode#ACCOUNT_REQUIRED} and this text, and is looked at again on the dispatcher's next pass.
         */
        record AccountRequired(String message) implements TurnPlan {

            public AccountRequired {
                Objects.requireNonNull(message, "message");
            }
        }

        /**
         * The conversation's trigger is paused (the package 4 contract, §5.11 and §11): the run stays queued with
         * {@link QueueReasonCode#TRIGGER_PAUSED} and this text, and is looked at again on the dispatcher's next pass.
         */
        record TriggerPaused(String message) implements TurnPlan {

            public TriggerPaused {
                Objects.requireNonNull(message, "message");
            }
        }

        /**
         * The conversation's person may no longer use its agent (the security review of 2026-10-27, L22: the share
         * that let them was removed, or they left its group): the run stays queued with {@link QueueReasonCode#OTHER}
         * and this text, which the conversation shows, and is looked at again on the dispatcher's next pass, so that
         * it goes on once the agent is shared with them again.
         */
        record AgentNotUsable(String message) implements TurnPlan {

            public AgentNotUsable {
                Objects.requireNonNull(message, "message");
            }
        }

        /** The run ends CANCELLED: the conversation no longer takes the turn (it ended). */
        record Cancel(String message) implements TurnPlan {

            public Cancel {
                Objects.requireNonNull(message, "message");
            }
        }
    }

    /** The answer of {@link #content}. */
    sealed interface TurnContent permits TurnContent.Content, TurnContent.NotReady, TurnContent.Later {

        /**
         * What {@code run.assign} carries of the conversation (the contract's table in §5.7).
         *
         * @param title                   the conversation's title ({@code workItem.title})
         * @param prompt                  the full prompt (§5.9)
         * @param resumePrompt            what is new since the previous run, for a resumed session; used only when the
         *                                turn has a {@link Placement#previous()}
         * @param workspaces              the workspace in order (§5.6), each with its git token
         * @param requestableRepositories the agent's repositories the workspace lacks, then the others of its systems
         *                                (§5.5), at most 200
         * @param tools                   the run's server-executed tools (§5.4), at most 200, each named
         *                                {@code ToolNames.of(handle, tool)}
         * @param platformTools           the platform tools the host lists, by the host's names; null for all
         * @param grantedHosts            the hosts approved {@code request}s gave this conversation
         *                                ({@code conversation.granted_hosts}), normalized, at most 100; the run module
         *                                adds the agent's own and sends them sorted and distinct, without wildcards
         *                                over a public suffix
         * @param attachments             the conversation's files
         */
        record Content(String title, String prompt, @Nullable String resumePrompt, List<Workspace> workspaces,
                List<RunnerMessages.RequestableRepository> requestableRepositories, List<RunnerMessages.ToolSpec> tools,
                @Nullable List<String> platformTools, List<String> grantedHosts,
                List<RunnerMessages.AssignedAttachment> attachments) implements TurnContent {

            /** The protocol's limits of {@code run.assign} ({@code $defs/RunAssign}). */
            public static final int MAX_WORKSPACES = 20;

            public static final int MAX_TOOLS = 200;

            public static final int MAX_ALLOWED_HOSTS = 200;

            /** A conversation's granted hosts ({@code conversation.granted_hosts}, contract §3.8). */
            public static final int MAX_GRANTED_HOSTS = 100;

            public static final int MAX_PLATFORM_TOOLS = 20;

            public static final int MAX_ATTACHMENTS = 1000;

            public Content {
                Objects.requireNonNull(title, "title");
                Objects.requireNonNull(prompt, "prompt");
                if (workspaces.size() > MAX_WORKSPACES || tools.size() > MAX_TOOLS
                        || grantedHosts.size() > MAX_GRANTED_HOSTS || attachments.size() > MAX_ATTACHMENTS
                        || platformTools != null && platformTools.size() > MAX_PLATFORM_TOOLS) {
                    throw new IllegalArgumentException("A run.assign holds at most " + MAX_WORKSPACES
                            + " repositories, " + MAX_TOOLS + " tools, " + MAX_GRANTED_HOSTS + " granted hosts, "
                            + MAX_PLATFORM_TOOLS + " platform tools and " + MAX_ATTACHMENTS + " files");
                }
                workspaces = List.copyOf(workspaces);
                requestableRepositories = List.copyOf(requestableRepositories);
                tools = List.copyOf(tools);
                platformTools = platformTools != null ? List.copyOf(platformTools) : null;
                grantedHosts = List.copyOf(grantedHosts);
                attachments = List.copyOf(attachments);
            }
        }

        /**
         * The turn cannot go on after all ({@link TurnPlan.AccountRequired}, {@link TurnPlan.TriggerPaused},
         * {@link TurnPlan.AgentNotUsable} or {@link TurnPlan.Cancel}): the run goes back to the queue with the reason,
         * or ends.
         */
        record NotReady(TurnPlan outcome) implements TurnContent {

            public NotReady {
                if (outcome instanceof TurnPlan.Ready) {
                    throw new IllegalArgumentException("A turn that is ready has content");
                }
            }
        }

        /**
         * The turn cannot go on right now for a passing failure that nobody needs to act on (a workspace's token that
         * could not be got while its binding holds): the run goes back to the queue with {@code OTHER} and this
         * message, and is dispatched again after the back-off.
         */
        record Later(String message) implements TurnContent {

            public Later {
                Objects.requireNonNull(message, "message");
            }
        }
    }

    /**
     * One repository of the workspace (§5.6), cloned into {@code /workspace/<dir>}, the directory the run module
     * makes of its name.
     *
     * @param bindingId     the conversation's binding whose account it is cloned with
     * @param readOnly      cloned, never pushed, no change request (I9)
     * @param readOnlyCause why, when read-only: the agent's access, the binding's read-only grant, or an approval that
     *                      gave {@code READ}
     * @param git           the token of the binding's account, for clone and push; null for a public repository
     * @param authorName    the commits' author: {@code {Person} via Tinfirm agent {agent}}
     */
    record Workspace(UUID repositoryId, UUID systemId, @Nullable UUID bindingId, String name, String url,
            String defaultBranch, boolean readOnly, @Nullable ReadOnlyCause readOnlyCause,
            RunnerMessages.@Nullable GitCredentials git, String authorName, String authorEmail) {

        public Workspace {
            if (readOnly != (readOnlyCause != null)) {
                throw new IllegalArgumentException("A read-only repository says why, and only one");
            }
        }
    }

    /** Why a repository of a workspace is read-only (contract §3.11). */
    enum ReadOnlyCause {
        AGENT, GRANT, REQUEST
    }
}
