package com.flowtivo.tinfirm.trigger;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One of a trigger's at most five condition groups (ADR-0043, disjunctive normal form): it holds when every clause of
 * it does. Each list empty means no clause; a name in a list and its negative within a group is refused.
 *
 * @param statuses    the status is one of
 * @param notStatuses the status is none of
 * @param labels      the subject has every one of
 * @param notLabels   the subject has none of
 * @param types       the type is one of
 * @param notTypes    the type is none of
 * @param authors     who made the subject (§5.5): {@code PEOPLE}, or {@code TINFIRM} for a subject Tinfirm created;
 *                    null: anyone
 */
public record ConditionGroup(List<String> statuses, List<String> notStatuses, List<String> labels,
        List<String> notLabels, List<String> types, List<String> notTypes, @Nullable Authors authors) {

    public ConditionGroup {
        statuses = List.copyOf(statuses);
        notStatuses = List.copyOf(notStatuses);
        labels = List.copyOf(labels);
        notLabels = List.copyOf(notLabels);
        types = List.copyOf(types);
        notTypes = List.copyOf(notTypes);
    }

    /** Whether the group has no clause. */
    public boolean empty() {
        return statuses.isEmpty() && notStatuses.isEmpty() && labels.isEmpty() && notLabels.isEmpty()
                && types.isEmpty() && notTypes.isEmpty() && authors == null;
    }

    /** Who made the subject (§5.5). */
    public enum Authors {
        PEOPLE, TINFIRM
    }
}
