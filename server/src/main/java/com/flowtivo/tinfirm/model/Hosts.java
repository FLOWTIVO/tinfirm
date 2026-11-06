package com.flowtivo.tinfirm.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.runner.RunnerHostInfo;

/**
 * Where a run may execute (the package 2 contract, §5.2; M4, M5), for package 3's dispatcher: which runner hosts are
 * eligible for it. Among them the dispatcher keeps its own choice (free slots, capacity settings, queue reasons).
 * Runs in the caller's transaction and tenant context.
 */
public interface Hosts {

    /**
     * The eligible hosts of the run:
     * <ul>
     * <li>{@code Budget(P)} with a workspace account that is not P's own: the company hosts that serve the
     * runtime;</li>
     * <li>{@code Budget(P)} with every workspace account P's own (or no workspace): the company hosts and P's personal
     * hosts that serve the runtime;</li>
     * <li>{@code Subscription(host, label)} with every workspace account the host owner's own: that host only;</li>
     * <li>{@code Subscription(...)} with a workspace account that is not the owner's: {@link Eligibility.Nowhere}
     * ({@code CANNOT_RUN_ANYWHERE}), to be shown before the conversation starts.</li>
     * </ul>
     * A host's runner version decides nothing (ADR-0109): every host of runner protocol 2 has the always-on model
     * proxy, and dispatch takes only such hosts.
     */
    Eligibility eligible(RunModelContext context);

    /**
     * The personal-host rule (M4; the security review of 2026-10-27, M2 and D6), the one predicate that both the
     * eligibility above and the reveal of a turn's git tokens ({@code TurnSource.content}, for a run placed on a
     * personal host) ask: a personal host of the payer may carry the run's workspace accounts only when every one of
     * them is the payer's own. A credential a grant lends is no one's: its id stands for it, which is no payer.
     *
     * @param payerPrincipalId        the principal whose model access pays, the personal host's owner
     * @param workspaceAccountHolders each repository's binding's holder, or the lent credential's id; empty without a
     *                                workspace
     */
    static boolean personalHostMayRun(UUID payerPrincipalId, Collection<UUID> workspaceAccountHolders) {
        return workspaceAccountHolders.stream().allMatch(payerPrincipalId::equals);
    }

    /** The answer of {@link #eligible}. */
    sealed interface Eligibility {

        /**
         * The hosts that may take the run, by name; when empty, {@code whyNone} says why (a queue reason for
         * people).
         */
        record Eligible(List<RunnerHostInfo> hosts, @Nullable String whyNone) implements Eligibility {

            public Eligible {
                hosts = List.copyOf(hosts);
            }
        }

        /** No host may ever take the run as it is: {@code CANNOT_RUN_ANYWHERE}. */
        record Nowhere(RunModelAccess.Refuse refusal) implements Eligibility {
        }
    }
}
