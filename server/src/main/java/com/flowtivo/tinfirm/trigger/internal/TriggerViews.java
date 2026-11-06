package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.shared.api.model.TriggerAction;
import com.flowtivo.tinfirm.shared.api.model.TriggerConditionGroup;
import com.flowtivo.tinfirm.shared.api.model.TriggerEvent;
import com.flowtivo.tinfirm.shared.api.model.TriggerEventSpec;
import com.flowtivo.tinfirm.shared.api.model.TriggerFiring;
import com.flowtivo.tinfirm.shared.api.model.TriggerFiringOutcome;
import com.flowtivo.tinfirm.shared.api.model.TriggerPayer;
import com.flowtivo.tinfirm.shared.api.model.TriggerRepeat;
import com.flowtivo.tinfirm.shared.api.model.TriggerResults;
import com.flowtivo.tinfirm.shared.api.model.TriggerScheduleInput;
import com.flowtivo.tinfirm.shared.api.model.TriggerSubject;
import com.flowtivo.tinfirm.trigger.ConditionGroup;
import com.flowtivo.tinfirm.trigger.TriggerInfo;

/**
 * The trigger API's models and the trigger module's types, both ways (OpenAPI-first, ADR-0013): the request's parts as
 * the editor checks them, and a trigger's parts as the API shows them. The model's and the module's types share names;
 * the module's are named in full here.
 */
final class TriggerViews {

    private TriggerViews() {
    }

    // ---- requests to the module ------------------------------------------------------------------------------------

    static List<com.flowtivo.tinfirm.trigger.TriggerEventSpec> events(@Nullable List<TriggerEventSpec> events) {
        List<com.flowtivo.tinfirm.trigger.TriggerEventSpec> specs = new ArrayList<>();
        if (events != null) {
            for (TriggerEventSpec event : events) {
                if (event == null || event.getEvent() == null) {
                    throw TriggerRules.invalid("every event names its kind");
                }
                specs.add(new com.flowtivo.tinfirm.trigger.TriggerEventSpec(
                        com.flowtivo.tinfirm.trigger.TriggerEvent.valueOf(event.getEvent().getValue()),
                        event.getValue()));
            }
        }
        return specs;
    }

    static List<ConditionGroup> conditions(@Nullable List<TriggerConditionGroup> groups) {
        List<ConditionGroup> conditions = new ArrayList<>();
        if (groups != null) {
            for (TriggerConditionGroup group : groups) {
                conditions.add(new ConditionGroup(names(group.getStatuses()), names(group.getNotStatuses()),
                        names(group.getLabels()), names(group.getNotLabels()), names(group.getTypes()),
                        names(group.getNotTypes()), group.getAuthors() != null
                                ? ConditionGroup.Authors.valueOf(group.getAuthors().getValue()) : null));
            }
        }
        return conditions;
    }

    private static List<String> names(@Nullable List<String> names) {
        return names != null ? names.stream().filter(name -> name != null).toList() : List.of();
    }

    static com.flowtivo.tinfirm.trigger.@Nullable TriggerSubject subject(@Nullable TriggerSubject subject) {
        return subject != null ? com.flowtivo.tinfirm.trigger.TriggerSubject.valueOf(subject.getValue()) : null;
    }

    static com.flowtivo.tinfirm.trigger.@Nullable TriggerRepeat repeat(@Nullable TriggerRepeat repeat) {
        return repeat != null ? com.flowtivo.tinfirm.trigger.TriggerRepeat.valueOf(repeat.getValue()) : null;
    }

    static TriggerEditor.@Nullable ScheduleInput schedule(@Nullable TriggerScheduleInput schedule) {
        if (schedule == null) {
            return null;
        }
        return new TriggerEditor.ScheduleInput(schedule.getCron(), schedule.getZone(),
                schedule.getOverlap() != null
                        ? com.flowtivo.tinfirm.trigger.TriggerSchedule.Overlap.valueOf(schedule.getOverlap().getValue())
                        : com.flowtivo.tinfirm.trigger.TriggerSchedule.Overlap.SKIP,
                schedule.getCatchUp() != null
                        ? com.flowtivo.tinfirm.trigger.TriggerSchedule.CatchUp.valueOf(schedule.getCatchUp().getValue())
                        : com.flowtivo.tinfirm.trigger.TriggerSchedule.CatchUp.NONE);
    }

    static com.flowtivo.tinfirm.trigger.@Nullable TriggerResults results(@Nullable TriggerResults results) {
        if (results == null) {
            return null;
        }
        return new com.flowtivo.tinfirm.trigger.TriggerResults(Boolean.TRUE.equals(results.getComment()),
                Boolean.TRUE.equals(results.getReview()), actions(results.getOnSuccess()),
                actions(results.getOnFailure()));
    }

    private static List<com.flowtivo.tinfirm.trigger.TriggerAction> actions(@Nullable List<TriggerAction> actions) {
        List<com.flowtivo.tinfirm.trigger.TriggerAction> list = new ArrayList<>();
        if (actions != null) {
            for (TriggerAction action : actions) {
                if (action == null || action.getKind() == null) {
                    throw TriggerRules.invalid("every action names its kind");
                }
                list.add(new com.flowtivo.tinfirm.trigger.TriggerAction(
                        com.flowtivo.tinfirm.trigger.TriggerAction.Kind.valueOf(action.getKind().getValue()),
                        action.getValue()));
            }
        }
        return list;
    }

    // ---- the module to responses -----------------------------------------------------------------------------------

    static List<TriggerEventSpec> events(TriggerInfo trigger) {
        return new ArrayList<>(trigger.events().stream()
            .map(spec -> new TriggerEventSpec(TriggerEvent.fromValue(spec.event().name())).value(spec.value()))
            .toList());
    }

    static List<TriggerConditionGroup> conditions(TriggerInfo trigger) {
        return new ArrayList<>(trigger.conditions().stream()
            .map(group -> new TriggerConditionGroup().statuses(new ArrayList<>(group.statuses()))
                .notStatuses(new ArrayList<>(group.notStatuses()))
                .labels(new ArrayList<>(group.labels()))
                .notLabels(new ArrayList<>(group.notLabels()))
                .types(new ArrayList<>(group.types()))
                .notTypes(new ArrayList<>(group.notTypes()))
                .authors(group.authors() != null
                        ? TriggerConditionGroup.AuthorsEnum.fromValue(group.authors().name()) : null))
            .toList());
    }

    static TriggerResults results(com.flowtivo.tinfirm.trigger.TriggerResults results) {
        return new TriggerResults(results.comment(), results.review(), actionViews(results.onSuccess()),
                actionViews(results.onFailure()));
    }

    private static List<TriggerAction> actionViews(List<com.flowtivo.tinfirm.trigger.TriggerAction> actions) {
        return new ArrayList<>(actions.stream()
            .map(action -> new TriggerAction(TriggerAction.KindEnum.fromValue(action.kind().name()))
                .value(action.value()))
            .toList());
    }

    static TriggerFiring firing(FiringView firing) {
        return new TriggerFiring(firing.id(), firing.triggerId(), TriggerEvent.fromValue(firing.event().name()),
                firing.once(), TriggerFiringOutcome.fromValue(firing.outcome().name()), firing.chainDepth(),
                ts(firing.createdAt()))
            .subjectKey(firing.subjectKey())
            .value(firing.value())
            .changedAt(ts(firing.changedAt()))
            .dueAt(ts(firing.dueAt()))
            .manualBy(firing.manualBy())
            .reason(firing.reason())
            .conversationId(firing.conversationId())
            .runId(firing.runId());
    }

    /** What package 2 answers for the payer, as the editor and the trigger show it. */
    static TriggerPayer payer(java.util.UUID principalId, @Nullable String name, boolean service,
            com.flowtivo.tinfirm.model.ModelAccessChoice choice, @Nullable RunModelAccess payment) {
        TriggerPayer payer = new TriggerPayer().principalId(principalId)
            .name(name)
            .kind(service ? TriggerPayer.KindEnum.SERVICE : TriggerPayer.KindEnum.MEMBER)
            .modelAccess(choice instanceof com.flowtivo.tinfirm.model.ModelAccessChoice.Subscription
                    ? com.flowtivo.tinfirm.shared.api.model.RunModelAccess.SUBSCRIPTION
                    : com.flowtivo.tinfirm.shared.api.model.RunModelAccess.BUDGET);
        switch (payment) {
            case RunModelAccess.Wait wait -> payer.state(TriggerPayer.StateEnum.WAITING).reason(wait.reason().name())
                .detail(wait.detail())
                .until(ts(wait.until()));
            case RunModelAccess.Refuse refuse -> payer.state(TriggerPayer.StateEnum.REFUSED)
                .reason(refuse.reason().name())
                .detail(refuse.detail());
            case null -> payer.state(TriggerPayer.StateEnum.READY);
            default -> payer.state(TriggerPayer.StateEnum.READY);
        }
        return payer;
    }

    // ---- the trigger in words --------------------------------------------------------------------------------------

    /**
     * The trigger as one sentence (§5.2's preview): "Builder runs when an issue in SHOP is assigned to svc-ba", "BA runs
     * on the schedule 0 9 1 * * (Europe/Prague)".
     */
    static String sentence(TriggerInfo trigger, @Nullable String systemName, @Nullable String agentName) {
        String agent = agentName != null ? agentName : "The agent";
        if (trigger.source() == com.flowtivo.tinfirm.trigger.TriggerSource.SCHEDULE && trigger.schedule() != null) {
            return agent + " runs on the schedule " + trigger.schedule().cron() + " (" + trigger.schedule().zone()
                    + ").";
        }
        if (trigger.source() == com.flowtivo.tinfirm.trigger.TriggerSource.CHANNEL) {
            return agent + " answers mentions of the bot in its channels of " + (systemName != null ? systemName
                    : "its chat system") + ".";
        }
        if (trigger.source() == com.flowtivo.tinfirm.trigger.TriggerSource.WEBHOOK) {
            List<String> values = trigger.events().stream()
                .map(com.flowtivo.tinfirm.trigger.TriggerEventSpec::value)
                .filter(java.util.Objects::nonNull)
                .toList();
            return agent + " runs when " + (systemName != null ? systemName : "its webhook") + " delivers "
                    + (values.isEmpty() ? "anything" : String.join(" or ", values)) + ".";
        }
        StringBuilder sentence = new StringBuilder(agent).append(" runs when ");
        String subject = switch (trigger.subject()) {
            case ISSUE -> "an issue";
            case CHANGE_REQUEST -> "a change request";
            case ANY -> "an issue or change request";
        };
        sentence.append(subject).append(" in ").append(trigger.scope() != null ? trigger.scope()
                : systemName != null ? systemName : "its system").append(' ');
        List<String> events = new ArrayList<>();
        for (com.flowtivo.tinfirm.trigger.TriggerEventSpec spec : trigger.events()) {
            events.add(event(spec));
        }
        sentence.append(String.join(" or ", events));
        if (trigger.repeat() == com.flowtivo.tinfirm.trigger.TriggerRepeat.ONCE) {
            sentence.append(", once per subject");
        }
        return sentence.append('.').toString();
    }

    private static String event(com.flowtivo.tinfirm.trigger.TriggerEventSpec spec) {
        String value = spec.value() != null ? " " + spec.value() : "";
        return switch (spec.event()) {
            case CREATED -> "is created";
            case UPDATED -> "is updated";
            case STATUS_ENTERED -> "enters the status" + value;
            case LABEL_ADDED -> "gets the label" + value;
            case LABEL_REMOVED -> "loses the label" + value;
            case ASSIGNED -> "is assigned to the trigger's account";
            case MENTIONED -> "mentions the trigger's account";
            case COMMENTED -> "gets a comment";
            case CHANGE_REQUEST_OPENED -> "is opened";
            case CHANGE_REQUEST_UPDATED -> "gets new commits";
            case CLOSED -> "is closed";
            case SCHEDULE -> "is due";
            case DELIVERED -> "is delivered" + value;
        };
    }

    /** A trigger's audit details (§8): never the instruction. */
    static Map<String, Object> auditDetails(TriggerInfo trigger) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("name", trigger.name());
        details.put("source", trigger.source().name());
        details.put("systemId", trigger.systemId() != null ? trigger.systemId().toString() : null);
        details.put("agentId", trigger.agentId().toString());
        details.put("events", trigger.events().stream()
            .map(spec -> spec.event().name() + (spec.value() != null ? ":" + spec.value() : ""))
            .toList());
        details.put("subject", trigger.subject().name());
        details.put("scope", trigger.scope());
        details.put("repeat", trigger.repeat().name());
        details.put("schedule", trigger.schedule() != null
                ? trigger.schedule().cron() + " " + trigger.schedule().zone() : null);
        details.put("teamGroupId", trigger.teamGroupId() != null ? trigger.teamGroupId().toString() : null);
        details.put("results", Map.of("comment", trigger.results().comment(), "review", trigger.results().review(),
                "onSuccess", trigger.results().onSuccess().size(), "onFailure", trigger.results().onFailure().size()));
        details.put("maxFiringsPerHour", trigger.maxFiringsPerHour());
        return details;
    }

    static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
