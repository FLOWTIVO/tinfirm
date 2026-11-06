package com.flowtivo.tinfirm.trigger;

import java.util.List;

/**
 * Where a trigger's results go (the package 4 contract, §3.1, §5.8, §5.9).
 *
 * @param comment   whether a turn's result and a failure are written to the issue as comments
 * @param review    whether the team reviews a result before anything of it is written (T14)
 * @param onSuccess at most 10 actions after a succeeded turn with a completed result
 * @param onFailure at most 10 actions after a failed or lost turn
 */
public record TriggerResults(boolean comment, boolean review, List<TriggerAction> onSuccess,
        List<TriggerAction> onFailure) {

    public static final TriggerResults DEFAULT = new TriggerResults(true, false, List.of(), List.of());

    public TriggerResults {
        onSuccess = List.copyOf(onSuccess);
        onFailure = List.copyOf(onFailure);
    }
}
