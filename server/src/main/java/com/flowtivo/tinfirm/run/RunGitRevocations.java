package com.flowtivo.tinfirm.run;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The revocation of runs' git tokens on their hosts (ADR-0116, decisions 1 and 2), for the conversation module, which
 * knows when a binding fails its check. Each method runs in the caller's transaction (a mandatory one) and its tenant
 * context, writes a durable {@code REVOKE_GIT} command per run, audits {@code run.git_revoked}, and sends
 * {@code run.git.revoke} after the commit to a host that lists the feature {@code run.git.revoke}; the command goes
 * again at every {@code hello} that lists the run, until the run ends.
 * <p>
 * The runs concerned are those assigned (awaiting {@code run.accepted}) or RUNNING, and those LOST within the resume
 * window, for the repositories of their current assignment ({@code run_repository} of the run's epoch). A repository
 * that an earlier revocation of the same assignment named is not named again, so a run once revoked stays revoked,
 * and a cause that names nothing new writes, audits and sends nothing.
 * <p>
 * Locks: each run's conversation before the run (CONVENTIONS §10); a caller that holds the conversation's lock pays
 * nothing for it.
 */
public interface RunGitRevocations {

    /** The reason of a revocation for a paused system. */
    String SYSTEM_PAUSED = "SYSTEM_PAUSED";

    /** The reason of a revocation for a trigger's binding that follows another credential (ADR-0072). */
    String CREDENTIAL_REPLACED = "CREDENTIAL_REPLACED";

    /** The reason of a revocation for a renewal that the gate refused for good (decision 9). */
    String REFRESH_REFUSED = "REFRESH_REFUSED";

    /**
     * The runs whose repositories the binding's account cloned.
     *
     * @param reason a code: the binding's reason, or {@link #CREDENTIAL_REPLACED}
     * @param why    the cause in words, which ends the message ("Tinfirm revoked this run's access to acme/shop: why.")
     */
    List<Revoked> ofBinding(UUID companyId, UUID bindingId, String reason, String why);

    /**
     * The conversations of the runs {@link #ofSystem} would concern now, so that a caller that locks other
     * conversations in the same transaction can lock them all in the order of their ids first (CONVENTIONS §10).
     * Reads only.
     */
    Set<UUID> conversationsOfSystem(UUID companyId, UUID systemId);

    /** The runs with repositories of the paused system, reason {@link #SYSTEM_PAUSED}. */
    List<Revoked> ofSystem(UUID companyId, UUID systemId, String why);

    /** One run's repositories, e.g. those whose renewal the gate refused for good. */
    List<Revoked> ofRun(UUID companyId, UUID runId, Collection<UUID> componentIds, String reason, String why);

    /** How the host of a revoked run hears of it. */
    enum Delivery {
        /** The frame goes after the commit: the host is connected and lists {@code run.git.revoke}. */
        SENT,
        /** The host is not connected now: the frame goes at its {@code hello}, if it lists the feature then. */
        ON_RECONNECT,
        /** The host does not list {@code run.git.revoke}: the run keeps its tokens until it ends. */
        UNSUPPORTED
    }

    /**
     * One run's revocation.
     *
     * @param repositories the names of the repositories revoked, as the run was assigned them
     * @param message      the text the host gets
     */
    record Revoked(UUID runId, UUID conversationId, @Nullable UUID runnerHostId, List<UUID> componentIds,
            List<String> repositories, String reason, String message, Delivery delivery) {

        public Revoked {
            componentIds = List.copyOf(componentIds);
            repositories = List.copyOf(repositories);
        }
    }
}
