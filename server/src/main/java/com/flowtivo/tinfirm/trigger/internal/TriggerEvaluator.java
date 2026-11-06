package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.trigger.ConditionGroup;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerRepeat;
import com.flowtivo.tinfirm.trigger.TriggerSource;
import com.flowtivo.tinfirm.trigger.TriggerSubject;

/**
 * The trigger evaluator (the package 4 contract, §5.5; ADR-0043; pure): which of a poll's triggers fire on one
 * subject's events. Each trigger, in the order of their names: its scope and its
 * subject kind cover the subject; one of its events happened, with its value (ignoring case), the first in its own
 * list being the one it fires with; its conditions hold on the read; {@code ONCE} has not fired on the subject; and the
 * change is at or after the trigger's {@code armed_at} (T16) and within the catch-up. A trigger fires once per subject
 * per pass.
 */
final class TriggerEvaluator {

    private TriggerEvaluator() {
    }

    /**
     * The subject as the conditions read it.
     *
     * @param createdByTinfirm the subject is one Tinfirm created ({@code system_write} {@code ISSUE} or
     *                         {@code CHANGE_REQUEST})
     */
    record Facts(SystemPolls.SubjectKind kind, @Nullable String status, List<String> labels, @Nullable String type,
            boolean createdByTinfirm) {

        Facts {
            labels = List.copyOf(labels);
        }

        static Facts of(SystemPolls.Subject subject, boolean createdByTinfirm) {
            return new Facts(subject.kind(), subject.status(), subject.labels(), subject.type(), createdByTinfirm);
        }
    }

    /** A trigger that fires on the subject, with its event. */
    record Match(TriggerInfo trigger, EventDetector.Event event) {
    }

    /**
     * What the evaluation found.
     *
     * @param fires  the triggers that fire, in the order of their names
     * @param missed the triggers whose event happened, under their conditions, longer ago than the catch-up
     */
    record Outcome(List<Match> fires, List<TriggerInfo> missed) {

        Outcome {
            fires = List.copyOf(fires);
            missed = List.copyOf(missed);
        }
    }

    /**
     * @param triggers  the poll's triggers, in the order of their names
     * @param onceFired the {@code ONCE} triggers that fired on the subject already
     * @param now       the pass's time, for the catch-up
     */
    static Outcome evaluate(List<TriggerInfo> triggers, SystemPolls.Subject subject, List<EventDetector.Event> events,
            Facts facts, Set<UUID> onceFired, Instant now, Duration catchUp) {
        List<Match> fires = new ArrayList<>();
        List<TriggerInfo> missed = new ArrayList<>();
        for (TriggerInfo trigger : triggers) {
            if (trigger.source() != TriggerSource.TRACKER || !SystemPolls.inScope(subject, trigger.scope())
                    || !covers(trigger.subject(), facts.kind())
                    || trigger.repeat() == TriggerRepeat.ONCE && onceFired.contains(trigger.id())) {
                continue;
            }
            Optional<EventDetector.Event> event = first(trigger, events, facts);
            if (event.isEmpty()) {
                continue;
            }
            if (event.get().changedAt().isBefore(now.minus(catchUp))) {
                missed.add(trigger);
                continue;
            }
            fires.add(new Match(trigger, event.get()));
        }
        return new Outcome(fires, missed);
    }

    /**
     * The trigger's first event (in its own list) that happened at or after its {@code armed_at}, with its value, and
     * under which its conditions hold.
     */
    static Optional<EventDetector.Event> first(TriggerInfo trigger, List<EventDetector.Event> events, Facts facts) {
        for (TriggerEventSpec spec : trigger.events()) {
            for (EventDetector.Event event : events) {
                if (matches(spec, event) && !event.changedAt().isBefore(trigger.armedAt())
                        && conditionsHold(trigger.conditions(), event.event(), facts)) {
                    return Optional.of(event);
                }
            }
        }
        return Optional.empty();
    }

    static boolean covers(TriggerSubject subject, SystemPolls.SubjectKind kind) {
        return switch (subject) {
            case ANY -> true;
            case ISSUE -> kind == SystemPolls.SubjectKind.ISSUE;
            case CHANGE_REQUEST -> kind == SystemPolls.SubjectKind.CHANGE_REQUEST;
        };
    }

    private static boolean matches(TriggerEventSpec spec, EventDetector.Event event) {
        if (event.event() != spec.event()) {
            return false;
        }
        if (spec.event().takesValue()) {
            return spec.value() != null && event.value() != null && spec.value().equalsIgnoreCase(event.value());
        }
        return true;
    }

    /** No groups, or any group whose every clause holds (ADR-0043). */
    static boolean conditionsHold(List<ConditionGroup> groups, TriggerEvent event, Facts facts) {
        return groups.isEmpty() || groups.stream().anyMatch(group -> holds(group, event, facts));
    }

    /**
     * A group's clauses: the status one of {@code statuses} and none of {@code notStatuses}; every one of
     * {@code labels} and none of {@code notLabels} on the subject; the type one of {@code types} and none of
     * {@code notTypes} (each ignoring case; an empty list is no clause; a subject without a status or a type is "none
     * of" anything); {@code authors}: {@code TINFIRM} for a subject Tinfirm created, else {@code PEOPLE}, and a comment
     * that raises an event is always a person's.
     */
    static boolean holds(ConditionGroup group, TriggerEvent event, Facts facts) {
        if (!oneOf(group.statuses(), facts.status()) || among(group.notStatuses(), facts.status())
                || !oneOf(group.types(), facts.type()) || among(group.notTypes(), facts.type())) {
            return false;
        }
        if (!group.labels().isEmpty() || !group.notLabels().isEmpty()) {
            Set<String> labels = facts.labels().stream()
                .map(label -> label.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
            Predicate<String> present = label -> labels.contains(label.toLowerCase(Locale.ROOT));
            if (!group.labels().stream().allMatch(present) || group.notLabels().stream().anyMatch(present)) {
                return false;
            }
        }
        if (group.authors() == null) {
            return true;
        }
        boolean byTinfirm = !event.ofComment() && facts.createdByTinfirm();
        return (group.authors() == ConditionGroup.Authors.TINFIRM) == byTinfirm;
    }

    /** The clauses of the conditions that fail on the read, in words, for {@code TRIGGER_CONDITIONS_FAIL}. */
    static List<String> failing(TriggerInfo trigger, SystemPolls.Subject subject, Facts facts) {
        List<String> failing = new ArrayList<>();
        if (!SystemPolls.inScope(subject, trigger.scope())) {
            failing.add(subject.key() + " is not in the scope " + trigger.scope());
        }
        if (!covers(trigger.subject(), facts.kind())) {
            failing.add("the trigger watches " + (trigger.subject() == TriggerSubject.ISSUE ? "issues"
                    : "change requests") + " only");
        }
        TriggerEvent event = trigger.events().isEmpty() ? TriggerEvent.UPDATED : trigger.events().getFirst().event();
        if (!conditionsHold(trigger.conditions(), event, facts)) {
            for (int i = 0; i < trigger.conditions().size(); i++) {
                ConditionGroup group = trigger.conditions().get(i);
                failing.add("condition group " + (i + 1) + ": " + String.join(", ", clauses(group, event, facts)));
            }
        }
        return failing;
    }

    private static List<String> clauses(ConditionGroup group, TriggerEvent event, Facts facts) {
        List<String> clauses = new ArrayList<>();
        if (!oneOf(group.statuses(), facts.status())) {
            clauses.add("the status is not one of " + group.statuses());
        }
        if (among(group.notStatuses(), facts.status())) {
            clauses.add("the status is " + facts.status());
        }
        if (!oneOf(group.types(), facts.type())) {
            clauses.add("the type is not one of " + group.types());
        }
        if (among(group.notTypes(), facts.type())) {
            clauses.add("the type is " + facts.type());
        }
        Set<String> labels = facts.labels().stream().map(label -> label.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        group.labels().stream().filter(label -> !labels.contains(label.toLowerCase(Locale.ROOT)))
            .forEach(label -> clauses.add("the label " + label + " is missing"));
        group.notLabels().stream().filter(label -> labels.contains(label.toLowerCase(Locale.ROOT)))
            .forEach(label -> clauses.add("the label " + label + " is on it"));
        if (group.authors() != null && (group.authors() == ConditionGroup.Authors.TINFIRM)
                != (!event.ofComment() && facts.createdByTinfirm())) {
            clauses.add("it was not created by " + (group.authors() == ConditionGroup.Authors.TINFIRM ? "Tinfirm"
                    : "people"));
        }
        return clauses.isEmpty() ? List.of("does not hold") : clauses;
    }

    private static boolean oneOf(List<String> names, @Nullable String value) {
        return names.isEmpty() || among(names, value);
    }

    private static boolean among(List<String> names, @Nullable String value) {
        return value != null && names.stream().anyMatch(name -> name.equalsIgnoreCase(value));
    }
}
