package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.trigger.ConditionGroup;
import com.flowtivo.tinfirm.trigger.TriggerAction;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;
import com.flowtivo.tinfirm.trigger.TriggerSubject;

/**
 * The rules of a trigger's definition (the package 4 contract, §3.1 and §5.2 step 2; ADR-0043): per kind of tracker,
 * the events it has, the value an event takes, the subjects, the conditions and actions it supports and the scope's
 * form; that every event can fire under the conditions ("could never fire"); and a schedule's cron, at most every 15
 * minutes, in a known IANA zone. A rule that fails is {@code 400 INVALID_TRIGGER} with the reason. Pure: no database,
 * no system.
 */
final class TriggerRules {

    static final int MAX_NAME = 100;

    static final int MAX_SCOPE = 140;

    static final int MAX_VALUE = 255;

    static final int MAX_INSTRUCTION = 20_000;

    static final int MAX_CONDITION_NAMES = 10;

    static final int MAX_EVENTS = 5;

    static final int MAX_CONDITION_GROUPS = 5;

    static final int MAX_ACTIONS = 10;

    /** The kinds of system a tracker trigger belongs to (§3.1). */
    static final Set<SystemKind> TRACKERS = EnumSet.of(SystemKind.JIRA_DC, SystemKind.JIRA_CLOUD, SystemKind.GITHUB,
            SystemKind.GITLAB);

    private static final Pattern JIRA_PROJECT = Pattern.compile("^[A-Z][A-Z0-9_]{0,49}$");

    private static final Pattern GITHUB_REPOSITORY = Pattern
        .compile("^([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)/([A-Za-z0-9._-]+)$");

    private static final Pattern GITLAB_SEGMENT = Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.-]*$");

    private static final int GITLAB_MAX_SEGMENTS = 10;

    private TriggerRules() {
    }

    static boolean jira(SystemKind kind) {
        return kind == SystemKind.JIRA_DC || kind == SystemKind.JIRA_CLOUD;
    }

    /** Whether a tracker of the kind has the event (ADR-0043): statuses on Jira, change requests on code hosts. */
    static boolean available(TriggerEvent event, SystemKind kind) {
        return switch (event) {
            case STATUS_ENTERED -> jira(kind);
            case CHANGE_REQUEST_OPENED, CHANGE_REQUEST_UPDATED -> !jira(kind);
            case SCHEDULE -> false;
            default -> true;
        };
    }

    /** Whether a tracker of the kind does the action (§3.1): Jira transitions and attaches; code hosts label, close. */
    static boolean available(TriggerAction.Kind action, SystemKind kind) {
        return switch (action) {
            case TRANSITION, ATTACH_DOCUMENTS -> jira(kind);
            case LABEL_ADD, LABEL_REMOVE, CLOSE -> !jira(kind);
        };
    }

    static String name(@Nullable String requested) {
        String name = requested != null ? requested.strip() : "";
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw ApiException.validation("name must have 1 to " + MAX_NAME + " characters");
        }
        return name;
    }

    static String instruction(@Nullable String requested) {
        String instruction = requested != null ? requested.strip() : "";
        if (instruction.isEmpty() || instruction.length() > MAX_INSTRUCTION) {
            throw ApiException.validation("instruction must have 1 to " + MAX_INSTRUCTION + " characters");
        }
        return instruction;
    }

    /**
     * One to five events the kind has, each once (its value ignoring case), with a value where it takes one and none
     * where it does not.
     */
    static List<TriggerEventSpec> events(SystemKind kind, List<TriggerEventSpec> requested) {
        if (requested.isEmpty()) {
            throw invalid("a trigger on a tracker needs at least one event");
        }
        if (requested.size() > MAX_EVENTS) {
            throw invalid("a trigger has at most " + MAX_EVENTS + " events");
        }
        List<TriggerEventSpec> events = new ArrayList<>();
        for (TriggerEventSpec spec : requested) {
            TriggerEvent event = spec.event();
            if (!available(event, kind)) {
                throw invalid("a " + kind + " system has no " + event + " event");
            }
            String value = blankToNull(spec.value());
            if (event.takesValue()) {
                if (value == null) {
                    throw invalid(event + " needs a value: the " + (event == TriggerEvent.STATUS_ENTERED ? "status"
                            : "label"));
                }
                if (value.length() > MAX_VALUE) {
                    throw invalid("the value must be at most " + MAX_VALUE + " characters");
                }
            }
            else if (value != null) {
                throw invalid(event + " takes no value; only STATUS_ENTERED, LABEL_ADDED and LABEL_REMOVED do");
            }
            for (TriggerEventSpec other : events) {
                if (other.event() == event && (value == null || value.equalsIgnoreCase(other.value()))) {
                    throw invalid("the event " + event + (value != null ? " \"" + value + "\"" : "")
                            + " is listed twice");
                }
            }
            events.add(new TriggerEventSpec(event, value));
        }
        return events;
    }

    /** The subjects the kind has: Jira has issues only; CHANGE_REQUEST_* events need change requests. */
    static TriggerSubject subject(SystemKind kind, @Nullable TriggerSubject requested, List<TriggerEventSpec> events) {
        TriggerSubject subject = requested != null ? requested : TriggerSubject.ANY;
        if (subject == TriggerSubject.ANY) {
            return subject;
        }
        if (jira(kind) && subject == TriggerSubject.CHANGE_REQUEST) {
            throw invalid("a " + kind + " system has no change requests; its subjects are issues");
        }
        if (subject == TriggerSubject.ISSUE) {
            events.stream().filter(spec -> spec.event().changeRequestsOnly()).findFirst().ifPresent(spec -> {
                throw invalid(spec.event() + " happens to change requests only, which the subject ISSUE leaves out");
            });
        }
        return subject;
    }

    /**
     * The groups of conditions, at most five, any of which must hold: each with its names trimmed, deduplicated
     * ignoring case, at most 10 of 255 characters; statuses on Jira only. A single empty group is no condition, like
     * none; an empty group among others would always hold and is refused.
     */
    static List<ConditionGroup> conditions(SystemKind kind, List<ConditionGroup> requested) {
        if (requested.size() > MAX_CONDITION_GROUPS) {
            throw invalid("conditions has more than " + MAX_CONDITION_GROUPS + " groups");
        }
        List<ConditionGroup> groups = new ArrayList<>();
        boolean empty = false;
        for (ConditionGroup group : requested) {
            ConditionGroup conditions = new ConditionGroup(names("statuses", group.statuses()),
                    names("notStatuses", group.notStatuses()), names("labels", group.labels()),
                    names("notLabels", group.notLabels()), names("types", group.types()),
                    names("notTypes", group.notTypes()), group.authors());
            if ((!conditions.statuses().isEmpty() || !conditions.notStatuses().isEmpty()) && !jira(kind)) {
                throw invalid("a " + kind + " system has no statuses; conditions on statuses are for Jira");
            }
            if (conditions.empty()) {
                empty = true;
                continue;
            }
            requireDisjoint("statuses", conditions.statuses(), "notStatuses", conditions.notStatuses());
            requireDisjoint("labels", conditions.labels(), "notLabels", conditions.notLabels());
            requireDisjoint("types", conditions.types(), "notTypes", conditions.notTypes());
            groups.add(conditions);
        }
        if (empty && !groups.isEmpty()) {
            throw invalid("conditions has an empty group, which always holds, so the other groups would never "
                    + "matter; fill it in or remove it");
        }
        return groups;
    }

    /**
     * Refuses an event that could never fire: a STATUS_ENTERED whose status every group excludes or does not allow; a
     * LABEL_ADDED whose label every group excludes; a LABEL_REMOVED whose label every group requires; a MENTIONED or
     * COMMENTED, always a person's comment (§5.5), when every group wants authors TINFIRM. Without groups every event
     * fires.
     */
    static void requireSatisfiable(List<TriggerEventSpec> events, List<ConditionGroup> groups) {
        if (groups.isEmpty()) {
            return;
        }
        for (TriggerEventSpec spec : events) {
            String value = spec.value();
            String reason = switch (spec.event()) {
                case STATUS_ENTERED -> groups.stream().allMatch(group -> value == null
                        || containsIgnoringCase(group.notStatuses(), value)
                        || (!group.statuses().isEmpty() && !containsIgnoringCase(group.statuses(), value)))
                                ? "no group of conditions allows that status" : null;
                case LABEL_ADDED -> groups.stream().allMatch(group -> value == null
                        || containsIgnoringCase(group.notLabels(), value))
                                ? "every group of conditions excludes that label" : null;
                case LABEL_REMOVED -> groups.stream().allMatch(group -> value == null
                        || containsIgnoringCase(group.labels(), value))
                                ? "every group of conditions requires that label, which was just removed" : null;
                case MENTIONED, COMMENTED -> groups.stream()
                    .allMatch(group -> group.authors() == ConditionGroup.Authors.TINFIRM)
                            ? "a comment that raises an event is always a person's, and every group wants authors "
                                    + "TINFIRM"
                            : null;
                default -> null;
            };
            if (reason != null) {
                throw invalid(spec.event() + (value != null ? " \"" + value + "\"" : "") + " could never fire: "
                        + reason);
            }
        }
    }

    /** The scope as the kind stores it: a Jira project key, {@code owner/repo}, a GitLab path; null for the whole. */
    static @Nullable String scope(SystemKind kind, @Nullable String requested) {
        String scope = blankToNull(requested);
        if (scope == null) {
            return null;
        }
        if (scope.length() > MAX_SCOPE) {
            throw invalid("the scope must be at most " + MAX_SCOPE + " characters");
        }
        switch (kind) {
            case JIRA_DC, JIRA_CLOUD -> {
                if (!JIRA_PROJECT.matcher(scope).matches()) {
                    throw invalid("the scope of a Jira trigger is a project key: upper-case letters, digits and "
                            + "underscores, such as SHOP");
                }
            }
            case GITHUB -> {
                Matcher matcher = GITHUB_REPOSITORY.matcher(scope);
                if (!matcher.matches() || matcher.group(2).equals(".") || matcher.group(2).equals("..")) {
                    throw invalid("the scope of a GitHub trigger is a repository owner/repo, such as acme/web");
                }
            }
            case GITLAB -> {
                String[] segments = scope.split("/", -1);
                boolean valid = segments.length <= GITLAB_MAX_SEGMENTS;
                for (String segment : segments) {
                    valid &= GITLAB_SEGMENT.matcher(segment).matches() && !segment.equals(".")
                            && !segment.equals("..");
                }
                if (!valid) {
                    throw invalid("the scope of a GitLab trigger is a group or project path of up to "
                            + GITLAB_MAX_SEGMENTS + " segments, such as acme/web");
                }
            }
            default -> throw invalid("a " + kind + " system has no scopes; leave the scope empty");
        }
        return scope;
    }

    /** At most ten actions the kind can do, each with a value where its kind takes one. */
    static List<TriggerAction> actions(SystemKind kind, String field, List<TriggerAction> requested) {
        if (requested.size() > MAX_ACTIONS) {
            throw invalid(field + " has more than " + MAX_ACTIONS + " actions");
        }
        List<TriggerAction> actions = new ArrayList<>();
        for (TriggerAction action : requested) {
            if (!available(action.kind(), kind)) {
                throw invalid("a " + kind + " system cannot " + action.kind() + " (" + field + ")");
            }
            String value = blankToNull(action.value());
            if (action.kind().takesValue()) {
                if (value == null) {
                    throw invalid(action.kind() + " needs a value: the " + (action.kind()
                            == TriggerAction.Kind.TRANSITION ? "status" : "label") + " (" + field + ")");
                }
                if (value.length() > MAX_VALUE) {
                    throw invalid("the value of " + action.kind() + " must be at most " + MAX_VALUE + " characters");
                }
            }
            else if (value != null) {
                throw invalid(action.kind() + " takes no value (" + field + ")");
            }
            actions.add(new TriggerAction(action.kind(), value));
        }
        return actions;
    }

    /** A schedule's cron and zone, at most one firing per 15 minutes; answers the schedule. */
    static Schedule schedule(@Nullable String cron, @Nullable String zone, Instant now) {
        if (cron == null || zone == null) {
            throw invalid("a schedule needs its cron and its time zone");
        }
        try {
            Schedule schedule = Schedule.parse(cron, zone);
            schedule.requireMinInterval(now);
            return schedule;
        }
        catch (InvalidScheduleException ex) {
            throw invalid(ex.getMessage());
        }
    }

    private static void requireDisjoint(String field, List<String> required, String notField,
            List<String> excluded) {
        for (String name : required) {
            if (containsIgnoringCase(excluded, name)) {
                throw invalid("conditions." + field + " and conditions." + notField + " both name \"" + name
                        + "\": the group could never hold");
            }
        }
    }

    private static boolean containsIgnoringCase(List<String> names, String value) {
        return names.stream().anyMatch(name -> name.equalsIgnoreCase(value));
    }

    private static List<String> names(String field, List<String> requested) {
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String name : requested) {
            String trimmed = name.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_VALUE) {
                throw invalid("a name in conditions." + field + " is longer than " + MAX_VALUE + " characters");
            }
            distinct.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
        if (distinct.size() > MAX_CONDITION_NAMES) {
            throw invalid("conditions." + field + " has more than " + MAX_CONDITION_NAMES + " names");
        }
        return List.copyOf(distinct.values());
    }

    static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static ApiException invalid(String reason) {
        return ApiException.badRequest(ProblemCode.INVALID_TRIGGER, "The trigger cannot work: " + reason);
    }
}
