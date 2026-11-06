package com.flowtivo.tinfirm.system.internal.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.ToolCallResult;
import com.flowtivo.tinfirm.system.ToolOutcome;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A call of a code host's {@code label} tool: the subject's key, the labels to add and the labels to remove, as the
 * agent gave them and checked: each label stripped, 1 to {@value FixedTools#MAX_LABEL_LENGTH} characters, at most
 * {@value FixedTools#MAX_LABELS} per list, each once, none both added and removed, and at least one of them. The call
 * runs as one add of every label (the system creates unknown ones where it can), then one removal per label; the result
 * names what was added, what was removed, what was not on the subject, and a failure of the system.
 *
 * @param key the subject's key in the system's format
 */
record LabelCall(String key, List<String> add, List<String> remove) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    LabelCall {
        add = List.copyOf(add);
        remove = List.copyOf(remove);
    }

    /** The call of these arguments, or {@link ToolArguments.Invalid} with the message for the agent. */
    static LabelCall parse(String tool, String key, JsonNode arguments) {
        List<String> add = labels(tool, arguments, "add");
        List<String> remove = labels(tool, arguments, "remove");
        if (add.isEmpty() && remove.isEmpty()) {
            throw new ToolArguments.Invalid("`" + tool + "` takes `add`, the labels to put on the subject, or "
                    + "`remove`, the labels to take off it, or both: name at least one label.");
        }
        for (String label : add) {
            if (remove.contains(label)) {
                throw new ToolArguments.Invalid("`" + tool + "` names `" + label + "` both to add and to remove; name "
                        + "it in one of them.");
            }
        }
        return new LabelCall(key, add, remove);
    }

    private static List<String> labels(String tool, JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ToolArguments.Invalid("The `" + field + "` of `" + tool + "` is an array of label names.");
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode entry : node) {
            String label = entry.isString() ? entry.stringValue().strip() : "";
            if (label.isEmpty() || label.length() > FixedTools.MAX_LABEL_LENGTH) {
                throw new ToolArguments.Invalid("Each label in the `" + field + "` of `" + tool + "` is a text of 1 to "
                        + FixedTools.MAX_LABEL_LENGTH + " characters.");
            }
            if (!labels.contains(label)) {
                labels.add(label);
            }
        }
        if (labels.size() > FixedTools.MAX_LABELS) {
            throw new ToolArguments.Invalid("The `" + field + "` of `" + tool + "` names at most "
                    + FixedTools.MAX_LABELS + " labels.");
        }
        return labels;
    }

    /** The checked arguments as JSON: {@code {key, add, remove}}, the lists present when they name a label. */
    ObjectNode toJson() {
        ObjectNode json = JSON.createObjectNode();
        json.put("key", key);
        if (!add.isEmpty()) {
            add.forEach(json.putArray("add")::add);
        }
        if (!remove.isEmpty()) {
            remove.forEach(json.putArray("remove")::add);
        }
        return json;
    }

    /** What the call does, in words: {@code acme/shop#57: add `bug`, `triage-done`; remove `needs-triage`}. */
    String describe() {
        StringBuilder text = new StringBuilder(key).append(':');
        if (!add.isEmpty()) {
            text.append(" add ").append(quoted(add));
        }
        if (!remove.isEmpty()) {
            text.append(add.isEmpty() ? " remove " : "; remove ").append(quoted(remove));
        }
        return text.toString();
    }

    /**
     * Runs the call: the add first (a failure ends the call, since the removals would fail the same way), then each
     * removal. A credential the system refused ends it as {@link ToolOutcome.Reconnect} when nothing was written yet;
     * any other failure is a result with {@code isError} that says what happened, as text and as structured content
     * {@code {key, added, removed, notOn, failed}}.
     */
    ToolOutcome run(String tool, String system, Tracker tracker) {
        List<String> lines = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> notOn = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        boolean added = false;
        CallResult.@Nullable Failure failure = null;
        if (!add.isEmpty()) {
            CallResult<Boolean> result = tracker.addLabels(key, add);
            if (result.succeeded()) {
                added = true;
                lines.add("Added " + quoted(add) + " to " + key + ".");
            }
            else {
                failure = Objects.requireNonNull(result.failure());
                if (failure.kind() == CallResult.Kind.UNAUTHORIZED) {
                    return new ToolOutcome.Reconnect(failure.message());
                }
                failed.addAll(add);
                failed.addAll(remove);
                lines.add(ToolResults.failureText(tool, system, failure));
            }
        }
        if (failure == null) {
            for (String label : remove) {
                CallResult<Boolean> result = tracker.removeLabel(key, label);
                if (result.succeeded()) {
                    if (Boolean.TRUE.equals(result.value())) {
                        removed.add(label);
                        lines.add("Removed `" + label + "` from " + key + ".");
                    }
                    else {
                        notOn.add(label);
                        lines.add("`" + label + "` was not on " + key + ".");
                    }
                    continue;
                }
                failure = Objects.requireNonNull(result.failure());
                if (failure.kind() == CallResult.Kind.UNAUTHORIZED && !added && removed.isEmpty()) {
                    return new ToolOutcome.Reconnect(failure.message());
                }
                failed.add(label);
                lines.add("Removing `" + label + "`: " + ToolResults.failureText(tool, system, failure));
                if (failure.kind() != CallResult.Kind.NOT_FOUND && failure.kind() != CallResult.Kind.INVALID) {
                    // The account or the system, not this label: the other removals would fail the same way.
                    break;
                }
            }
        }
        ObjectNode structured = JSON.createObjectNode();
        structured.put("key", key);
        ArrayNode addedNode = structured.putArray("added");
        if (added) {
            add.forEach(addedNode::add);
        }
        removed.forEach(structured.putArray("removed")::add);
        notOn.forEach(structured.putArray("notOn")::add);
        List<String> notTried = remove.stream().filter(label -> !removed.contains(label) && !notOn.contains(label)
                && !failed.contains(label)).toList();
        ArrayNode failedNode = structured.putArray("failed");
        failed.forEach(failedNode::add);
        notTried.forEach(failedNode::add);
        boolean wrote = added || !removed.isEmpty();
        return new ToolOutcome.Done(ToolCallResult.text(String.join("\n", lines), structured, failure != null),
                failure, wrote ? new ToolOutcome.Written(ToolOutcome.WriteKind.LABELS, key, null, null,
                        added ? add : List.of(), removed) : null);
    }

    private static String quoted(List<String> labels) {
        return String.join(", ", labels.stream().map(label -> "`" + label + "`").toList());
    }
}
