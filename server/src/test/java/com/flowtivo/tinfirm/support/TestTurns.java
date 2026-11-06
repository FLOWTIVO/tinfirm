package com.flowtivo.tinfirm.support;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.RunRequests;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.run.TurnSource;
import com.flowtivo.tinfirm.runner.RunnerMessages;

import tools.jackson.databind.JsonNode;

/**
 * The conversation module as the run module's tests stand it in (TEST FIXTURE ONLY): its three ports,
 * {@link TurnSource}, {@link TurnEnded} and {@link RunRequests}, scripted per conversation. Picked up by the
 * application's component scan in every integration test's context, as {@link RecordedEvents} is, and the primary bean
 * of the three ports there: it answers for the conversations the run module's tests script (those named with
 * {@link #conversation}, which {@code RunTestBench} does for every turn it starts), and hands every other conversation
 * to the conversation module's own implementation, so that the conversation module's tests run on the real ports. A
 * scripted conversation is ready with no workspace, its requests answered as the run module's stand-ins answer them,
 * its ends recorded and its lost runs taken back.
 */
@Component
@Primary
public class TestTurns implements TurnSource, TurnEnded, RunRequests {

    private static final Map<UUID, Script> SCRIPTS = new ConcurrentHashMap<>();

    private final ApplicationContext context;

    public TestTurns(ApplicationContext context) {
        this.context = context;
    }

    /** The conversation module's own implementation of the port, if any: not this stand-in. */
    private <T> @org.jspecify.annotations.Nullable T real(Class<T> port) {
        return context.getBeansOfType(port).values().stream()
            .filter(bean -> !(bean instanceof TestTurns))
            .findFirst()
            .orElse(null);
    }

    private static boolean scripted(UUID conversationId) {
        return SCRIPTS.containsKey(conversationId);
    }

    /** What the conversation answers, and what it was told. */
    public static final class Script {

        public volatile TurnPlan plan = new TurnPlan.Ready(Set.of());

        public volatile Function<TurnRef, TurnContent> content = TestTurns::content;

        public volatile boolean takesBack = true;

        /** Answers {@code ASK}, {@code ACCESS}, {@code ARTIFACT} and {@code NETWORK}; null: the stand-in's. */
        public volatile RequestHandler requests;

        /** Answers {@code TOOL}; null: the stand-in's. */
        public volatile RequestHandler tools;

        public final List<TurnRef> planned = new CopyOnWriteArrayList<>();

        /** The settings each call was given, in order: plan's and content's. */
        public final List<EffectiveSettings> settings = new CopyOnWriteArrayList<>();

        public final List<Placement> placements = new CopyOnWriteArrayList<>();

        public final List<TurnEnd> ends = new CopyOnWriteArrayList<>();

        public final List<ActiveRun> resumed = new CopyOnWriteArrayList<>();

        /** Whether each answer of a request or a tool call ran inside a transaction. */
        public final List<Boolean> requestsInTransaction = new CopyOnWriteArrayList<>();

        public final List<Boolean> toolsInTransaction = new CopyOnWriteArrayList<>();

        public final List<Boolean> contentInTransaction = new CopyOnWriteArrayList<>();
    }

    /** An answer to a platform request of a run. */
    @FunctionalInterface
    public interface RequestHandler {

        RunnerMessages.RunRequestAck answer(ActiveRun run, String kind, UUID requestId, JsonNode payload);
    }

    /** The conversation's script, made when it has none. */
    public static Script conversation(UUID conversationId) {
        return SCRIPTS.computeIfAbsent(conversationId, id -> new Script());
    }

    /** A turn's content: a prompt, no workspace, nothing else. */
    public static TurnContent content(TurnRef turn) {
        return content(turn, List.of());
    }

    /** A turn's content with this workspace. */
    public static TurnContent content(TurnRef turn, List<Workspace> workspaces) {
        return content(turn, workspaces, List.of());
    }

    /** A turn's content with this workspace and the hosts approved requests gave the conversation. */
    public static TurnContent content(TurnRef turn, List<Workspace> workspaces, List<String> grantedHosts) {
        return new TurnContent.Content("Test conversation " + turn.conversationKey(), "Do turn " + turn.turnNumber()
                + ".", "Go on with turn " + turn.turnNumber() + ".", workspaces, List.of(), List.of(), null,
                grantedHosts, List.of());
    }

    @Override
    public java.util.Optional<String> pausedWorkspaceSystem(TurnRef turn) {
        TurnSource real = real(TurnSource.class);
        return !scripted(turn.conversationId()) && real != null ? real.pausedWorkspaceSystem(turn)
                : java.util.Optional.empty();
    }

    @Override
    public TurnPlan plan(TurnRef turn, EffectiveSettings settings) {
        TurnSource real = real(TurnSource.class);
        if (!scripted(turn.conversationId()) && real != null) {
            return real.plan(turn, settings);
        }
        Script script = conversation(turn.conversationId());
        script.planned.add(turn);
        script.settings.add(settings);
        return script.plan;
    }

    @Override
    public TurnContent content(TurnRef turn, Placement placement, EffectiveSettings settings) {
        TurnSource real = real(TurnSource.class);
        if (!scripted(turn.conversationId()) && real != null) {
            return real.content(turn, placement, settings);
        }
        Script script = conversation(turn.conversationId());
        script.contentInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        script.placements.add(placement);
        script.settings.add(settings);
        return script.content.apply(turn);
    }

    @Override
    public void turnEnded(TurnEnd end) {
        TurnEnded real = real(TurnEnded.class);
        if (!scripted(end.conversationId()) && real != null) {
            real.turnEnded(end);
            return;
        }
        conversation(end.conversationId()).ends.add(end);
    }

    @Override
    public boolean turnResumed(ActiveRun run) {
        TurnEnded real = real(TurnEnded.class);
        if (!scripted(run.conversationId()) && real != null) {
            return real.turnResumed(run);
        }
        Script script = conversation(run.conversationId());
        script.resumed.add(run);
        return script.takesBack;
    }

    @Override
    public RunnerMessages.RunRequestAck request(ActiveRun run, RunnerMessages.RequestKind kind, UUID requestId,
            JsonNode payload) {
        RunRequests real = real(RunRequests.class);
        if (!scripted(run.conversationId()) && real != null) {
            return real.request(run, kind, requestId, payload);
        }
        Script script = conversation(run.conversationId());
        script.requestsInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        RequestHandler handler = script.requests;
        return handler != null ? handler.answer(run, kind.name(), requestId, payload)
                : RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, "REQUEST_UNAVAILABLE",
                        "Tinfirm does not take " + kind.name() + " requests on this server.");
    }

    @Override
    public RunnerMessages.RunRequestAck call(ActiveRun run, UUID requestId, JsonNode payload) {
        RunRequests real = real(RunRequests.class);
        if (!scripted(run.conversationId()) && real != null) {
            return real.call(run, requestId, payload);
        }
        Script script = conversation(run.conversationId());
        script.toolsInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        RequestHandler handler = script.tools;
        return handler != null ? handler.answer(run, "TOOL", requestId, payload)
                : RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, "UNKNOWN_TOOL",
                        "This run has no server-executed tools.");
    }
}
