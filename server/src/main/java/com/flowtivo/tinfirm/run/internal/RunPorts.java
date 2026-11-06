package com.flowtivo.tinfirm.run.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.ConversationLock;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunRequests;
import com.flowtivo.tinfirm.run.RunVisibility;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.run.TurnSteered;
import com.flowtivo.tinfirm.run.TurnSource;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyRole;

import tools.jackson.databind.JsonNode;

/**
 * The ports the conversation module (package 3c) implements, resolved when used: that module depends on this one
 * ({@link com.flowtivo.tinfirm.run.Turns}), so its beans are looked up lazily, never injected. For a port it does not
 * implement, a stand-in answers:
 * <ul>
 * <li>{@link TurnSource}: none; a queued run waits ({@code OTHER}), and nothing creates runs anyway;</li>
 * <li>{@link RunRequests}: every request {@code REJECTED}: {@code UNKNOWN_TOOL} for a tool (a run then lists none),
 * {@code REQUEST_UNAVAILABLE} for the others;</li>
 * <li>{@link TurnEnded}: nobody is told of a run's end, and a lost run that its host still runs is resumed;</li>
 * <li>{@link RunVisibility}: a run is its person's: its person and OWNER see and cancel it (I17);</li>
 * <li>{@link ConversationLock}: nothing is locked, as no conversation is written either;</li>
 * <li>{@link TurnSteered}: nobody hears a steer's answer, as nothing sends a steer either.</li>
 * </ul>
 */
@Component
class RunPorts {

    /** The code of a platform request nothing serves. */
    static final String REQUEST_UNAVAILABLE = "REQUEST_UNAVAILABLE";

    private final ObjectProvider<TurnSource> source;

    private final ObjectProvider<RunRequests> requests;

    private final ObjectProvider<TurnEnded> ended;

    private final ObjectProvider<RunVisibility> visibility;

    private final ObjectProvider<ConversationLock> conversationLock;

    private final ObjectProvider<TurnSteered> steered;

    private final RunRequests rejecting = new RejectingRequests();

    private final TurnEnded nobody = new NobodyTold();

    private final RunVisibility personal;

    RunPorts(ObjectProvider<TurnSource> source, ObjectProvider<RunRequests> requests,
            ObjectProvider<TurnEnded> ended, ObjectProvider<RunVisibility> visibility,
            ObjectProvider<ConversationLock> conversationLock, ObjectProvider<TurnSteered> steered,
            Principals principals) {
        this.source = source;
        this.requests = requests;
        this.ended = ended;
        this.visibility = visibility;
        this.conversationLock = conversationLock;
        this.steered = steered;
        this.personal = new PersonalRuns(principals);
    }

    Optional<TurnSource> source() {
        return Optional.ofNullable(source.getIfUnique());
    }

    RunRequests requests() {
        RunRequests found = requests.getIfUnique();
        return found != null ? found : rejecting;
    }

    TurnEnded ended() {
        TurnEnded found = ended.getIfUnique();
        return found != null ? found : nobody;
    }

    RunVisibility visibility() {
        RunVisibility found = visibility.getIfUnique();
        return found != null ? found : personal;
    }

    /** Who hears the host's answer to a steer; nobody without the conversation module, which sends none. */
    TurnSteered steered() {
        TurnSteered found = steered.getIfUnique();
        return found != null ? found : (run, steerId, delivered, code) -> {
        };
    }

    /** The lock of a run's conversation, taken before the run's (CONVENTIONS §10); nothing without the module. */
    ConversationLock conversationLock() {
        ConversationLock found = conversationLock.getIfUnique();
        return found != null ? found : NO_CONVERSATIONS;
    }

    /** Locks nothing: without the conversation module no conversation is written either. */
    static final ConversationLock NO_CONVERSATIONS = (companyId, conversationId) -> {
    };

    /** Answers every platform request {@code REJECTED} when no conversation module takes them. */
    static final class RejectingRequests implements RunRequests {

        @Override
        public RunnerMessages.RunRequestAck request(ActiveRun run, RunnerMessages.RequestKind kind, UUID requestId,
                JsonNode payload) {
            return RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, REQUEST_UNAVAILABLE,
                    "Tinfirm does not take " + kind.name() + " requests on this server. Go on without it, and say "
                            + "in your summary what you needed.");
        }

        @Override
        public RunnerMessages.RunRequestAck call(ActiveRun run, UUID requestId, JsonNode payload) {
            return RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, "UNKNOWN_TOOL",
                    "This run has no server-executed tools.");
        }
    }

    /** Tells nobody of a run's end; takes every lost run back. */
    static final class NobodyTold implements TurnEnded {

        @Override
        public void turnEnded(TurnEnd end) {
        }

        @Override
        public boolean turnResumed(ActiveRun run) {
            return true;
        }
    }

    /**
     * A run is its person's (the acting principal's member): its person and OWNER see and cancel it; nobody else,
     * ADMIN and an instance admin under break-glass included (I17; the package 6 contract, §5.10).
     */
    static final class PersonalRuns implements RunVisibility {

        private final Principals principals;

        PersonalRuns(Principals principals) {
            this.principals = principals;
        }

        @Override
        public boolean maySee(CompanyAccess access, UUID userId, RunInfo run) {
            if (access.role() == CompanyRole.OWNER) {
                return true;
            }
            return principals.ofMember(access.companyId(), userId)
                .map(principal -> principal.id().equals(run.actingPrincipalId()))
                .orElse(false);
        }

        /** Without the conversation module there is no personal conversation whose read is audited. */
        @Override
        public boolean read(CompanyAccess access, UUID userId, RunInfo run, Read what) {
            return maySee(access, userId, run);
        }

        @Override
        public boolean mayCancel(CompanyAccess access, UUID userId, RunInfo run) {
            return maySee(access, userId, run);
        }
    }
}
