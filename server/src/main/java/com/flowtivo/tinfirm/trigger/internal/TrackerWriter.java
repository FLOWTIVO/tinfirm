package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationEvents.WrittenValues;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.SystemTools;
import com.flowtivo.tinfirm.system.SystemWrites;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.system.ToolOutcome;
import com.flowtivo.tinfirm.trigger.OutboxKind;
import com.flowtivo.tinfirm.trigger.PlaceWriter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tracker's {@link PlaceWriter} ({@code TRACKER_ISSUE}, the package 4 contract, §5.9): a comment, a transition,
 * labels and the close through the fixed tools ({@link SystemTools#call}, the comment labelled
 * {@code {agent} (Tinfirm agent):}), an attachment and a remote link through {@link SystemWrites}, each with the token
 * of the conversation's binding the delivery revealed for the row.
 * <p>
 * <b>Idempotency</b> (T13): a comment on GitHub and GitLab carries the row's marker, an HTML comment the page does not
 * show ({@code <!-- tinfirm-outbox:{id} -->}); Jira's markup has none, so there the text identifies it. Before a
 * repeated attempt of a row whose last one may have reached the system ({@link #written}): a comment is looked for
 * among the issue's newest comments written since the row was queued, by its marker or its text; an attachment by its
 * name and size; a transition is taken as done when the issue is in that status already. Labels, the close and a
 * remote link (by its global id) are idempotent on the system.
 */
@Component
class TrackerWriter implements PlaceWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A comment found by its text is one written at most this long before its row was queued (clocks differ). */
    private static final Duration SKEW = Duration.ofMinutes(2);

    private final SystemTools tools;

    private final SystemWrites writes;

    private final Systems systems;

    private final TriggerConversations conversations;

    private final SystemTransactions transactions;

    TrackerWriter(SystemTools tools, SystemWrites writes, Systems systems, TriggerConversations conversations,
            SystemTransactions transactions) {
        this.tools = tools;
        this.writes = writes;
        this.systems = systems;
        this.conversations = conversations;
        this.transactions = transactions;
    }

    @Override
    public ConversationInfo.Place place() {
        return ConversationInfo.Place.TRACKER_ISSUE;
    }

    /** The marker of a row's comment on a code host. */
    static String marker(Row row) {
        return "<!-- tinfirm-outbox:" + row.id() + " -->";
    }

    @Override
    public Outcome write(Write write) {
        Row row = write.row();
        CallCredential credential = Objects.requireNonNull(write.credential(), "credential");
        SystemKind kind = kind(row);
        if (kind == null) {
            return new Outcome.Dropped("the system does not exist any more");
        }
        String key = row.subjectKey();
        String value = row.value() != null ? row.value() : "";
        return switch (row.kind()) {
            case COMMENT -> {
                String body = Objects.requireNonNullElse(row.body(), "");
                if (kind == SystemKind.GITHUB || kind == SystemKind.GITLAB) {
                    body = body + "\n\n" + marker(row);
                }
                yield tool(row, credential, FixedTools.COMMENT, object(key).put("body", body),
                        OutboxPlans.label(write.agentName()),
                        done -> new Outcome.Delivered(done.written() != null ? done.written().externalId() : null,
                                "COMMENT"));
            }
            case TRANSITION -> tool(row, credential, FixedTools.TRANSITION, object(key).put("transition", value),
                    null, done -> {
                        JsonNode status = done.result().structuredContent() != null
                                ? done.result().structuredContent().get("status") : null;
                        String entered = done.written() != null && done.written().status() != null
                                ? done.written().status()
                                : status != null && status.isString() ? status.stringValue() : value;
                        return new Outcome.Delivered(null, "TRANSITION", new WrittenValues(entered, List.of(),
                                List.of()));
                    });
            case LABEL_ADD -> {
                ObjectNode arguments = object(key);
                arguments.putArray("add").add(value);
                yield tool(row, credential, FixedTools.LABEL, arguments, null,
                        done -> new Outcome.Delivered(null, "LABEL", new WrittenValues(null, List.of(value),
                                List.of())));
            }
            case LABEL_REMOVE -> {
                ObjectNode arguments = object(key);
                arguments.putArray("remove").add(value);
                yield tool(row, credential, FixedTools.LABEL, arguments, null,
                        done -> new Outcome.Delivered(null, "LABEL", new WrittenValues(null, List.of(),
                                List.of(value))));
            }
            case CLOSE -> tool(row, credential, FixedTools.CLOSE, object(key), null,
                    done -> new Outcome.Delivered(null, "CLOSE"));
            case ATTACHMENT -> attach(row, credential, write.conversation());
            case REMOTE_LINK -> {
                if (row.url() == null) {
                    yield new Outcome.Dropped("nothing to link");
                }
                yield outcome(writes.remoteLink(row.companyId(), row.systemId(), credential, key, row.url(),
                        row.value() != null ? row.value() : row.url()),
                        id -> new Outcome.Delivered(id.isEmpty() ? null : id, "REMOTE_LINK"));
            }
            case CHAT_POST, CHAT_UPDATE, CHAT_FILE, CHAT_REACTION -> new Outcome.Dropped("a tracker takes no "
                    + row.kind());
        };
    }

    @Override
    public Optional<Outcome.Delivered> written(Write write) {
        Row row = write.row();
        CallCredential credential = Objects.requireNonNull(write.credential(), "credential");
        SystemKind kind = kind(row);
        if (kind == null) {
            return Optional.empty();
        }
        return switch (row.kind()) {
            case COMMENT -> subject(row, credential).flatMap(subject -> comment(row, kind, subject));
            case TRANSITION -> subject(row, credential).flatMap(subject -> {
                JsonNode status = subject.get("status");
                return status != null && status.isString() && row.value() != null
                        && status.stringValue().equalsIgnoreCase(row.value().strip())
                                ? Optional.of(new Outcome.Delivered(null, "TRANSITION",
                                        new WrittenValues(status.stringValue(), List.of(), List.of())))
                                : Optional.empty();
            });
            case ATTACHMENT -> {
                TriggerConversations.Document document = document(row, write.conversation()).orElse(null);
                CallResult<List<SystemWrites.Attachment>> attached = writes.attachments(row.companyId(),
                        row.systemId(), credential, row.subjectKey());
                if (document == null || attached.value() == null) {
                    yield Optional.empty();
                }
                String name = fileName(document);
                yield attached.value().stream()
                    .filter(found -> found.fileName().equals(name) && found.size() == document.sizeBytes())
                    .findFirst()
                    .map(found -> new Outcome.Delivered(found.id(), "ATTACHMENT"));
            }
            case LABEL_ADD, LABEL_REMOVE, CLOSE, REMOTE_LINK, CHAT_POST, CHAT_UPDATE, CHAT_FILE, CHAT_REACTION ->
                Optional.empty();
        };
    }

    // ---- the parts -------------------------------------------------------------------------------------------------

    private Outcome attach(Row row, CallCredential credential, ConversationInfo conversation) {
        TriggerConversations.Document document = document(row, conversation).orElse(null);
        if (document == null) {
            return new Outcome.Dropped("the document does not exist any more");
        }
        String name = fileName(document);
        return outcome(writes.attach(row.companyId(), row.systemId(), credential, row.subjectKey(), name,
                document.contentType(), document.content()),
                id -> new Outcome.Delivered(id, "ATTACHMENT"));
    }

    private Optional<TriggerConversations.Document> document(Row row, ConversationInfo conversation) {
        if (row.attachmentId() == null) {
            return Optional.empty();
        }
        return transactions.inCompany(row.companyId(), () -> conversations.document(row.companyId(),
                conversation.id(), Objects.requireNonNull(row.attachmentId())));
    }

    /** A document from a run's {@code /output} is named by its path there ({@code reports/q3.md}): one segment. */
    private static String fileName(TriggerConversations.Document document) {
        return document.fileName().replace('/', '_');
    }

    /** The comment the row wrote: by its marker on a code host, else by its text, written since it was queued. */
    private static Optional<Outcome.Delivered> comment(Row row, SystemKind kind, JsonNode subject) {
        JsonNode comments = subject.get("comments");
        if (comments == null || !comments.isArray() || row.body() == null) {
            return Optional.empty();
        }
        boolean marked = kind == SystemKind.GITHUB || kind == SystemKind.GITLAB;
        String marker = marker(row);
        String text = fingerprint(row.body());
        for (JsonNode comment : comments) {
            String body = comment.path("body").isString() ? comment.get("body").stringValue() : "";
            boolean same = marked ? body.contains(marker)
                    : !text.isEmpty() && fingerprint(body).contains(text) && since(comment, row.createdAt());
            if (same) {
                JsonNode id = comment.get("id");
                return Optional.of(new Outcome.Delivered(id != null && !id.isNull() ? id.asString() : null,
                        "COMMENT"));
            }
        }
        return Optional.empty();
    }

    private static boolean since(JsonNode comment, Instant queued) {
        JsonNode created = comment.get("created");
        if (created == null || !created.isString()) {
            return true;
        }
        try {
            return !Instant.parse(created.stringValue()).isBefore(queued.minus(SKEW));
        }
        catch (DateTimeParseException ex) {
            return true;
        }
    }

    /** The letters and digits of a text, ignoring case: what a round trip through a tracker's markup keeps. */
    static String fingerprint(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** The subject as {@code get} reads it ({@code structuredContent}), or empty when it cannot be read now. */
    private Optional<JsonNode> subject(Row row, CallCredential credential) {
        ToolOutcome read = tools.call(row.companyId(), row.systemId(), credential, FixedTools.GET,
                object(row.subjectKey()));
        if (read instanceof ToolOutcome.Done done && done.failure() == null
                && done.result().structuredContent() != null) {
            return Optional.of(done.result().structuredContent());
        }
        return Optional.empty();
    }

    /** A fixed tool's outcome as the row's. */
    private Outcome tool(Row row, CallCredential credential, String tool, ObjectNode arguments,
            @Nullable String label, Function<ToolOutcome.Done, Outcome> delivered) {
        ToolOutcome outcome = tools.call(row.companyId(), row.systemId(), credential, tool, arguments, label);
        return switch (outcome) {
            case ToolOutcome.Done done when done.failure() == null && !done.result().isError() -> delivered.apply(done);
            case ToolOutcome.Done done -> failed(row, done.failure() != null ? done.failure()
                    : CallResult.Failure.of(CallResult.Kind.OTHER, done.result().text()));
            case ToolOutcome.Reconnect reconnect -> new Outcome.Reconnect(reconnect.message());
            case ToolOutcome.Refused refused -> refused.code() == ToolOutcome.Code.TOOL_FAILED
                    // A timeout: the request may have reached the system.
                    ? new Outcome.Failed(refused.message(), true, true, null)
                    : new Outcome.Dropped(refused.message());
        };
    }

    /** A {@link SystemWrites} call's result as the row's outcome. */
    private static <T> Outcome outcome(CallResult<T> result, Function<T, Outcome> delivered) {
        if (result.value() != null) {
            return delivered.apply(result.value());
        }
        CallResult.Failure failure = Objects.requireNonNull(result.failure());
        if (failure.kind() == CallResult.Kind.UNAUTHORIZED) {
            return new Outcome.Reconnect(failure.message());
        }
        return failure(failure);
    }

    /** A system's refusal: a transition its status does not offer is said so; the rest as {@link #failure}. */
    private static Outcome failed(Row row, CallResult.Failure failure) {
        if (row.kind() == OutboxKind.TRANSITION && failure.kind() == CallResult.Kind.NOT_FOUND) {
            return new Outcome.Dropped("the status of " + row.subjectKey() + " offers no transition named or leading "
                    + "to “" + row.value() + "”");
        }
        return failure(failure);
    }

    /**
     * The rate limit is tried again after its wait; no answer, a {@code 5xx} or a timeout too, and may have written
     * ({@code ambiguous}); anything else drops the row with the system's answer.
     */
    private static Outcome failure(CallResult.Failure failure) {
        return switch (failure.kind()) {
            case RATE_LIMITED -> new Outcome.Failed(failure.message(), true, false, failure.retryAfter());
            case UNAVAILABLE -> new Outcome.Failed(failure.message(), true, true, failure.retryAfter());
            case UNAUTHORIZED -> new Outcome.Reconnect(failure.message());
            default -> new Outcome.Dropped(failure.message());
        };
    }

    private @Nullable SystemKind kind(Row row) {
        return transactions.inCompany(row.companyId(), () -> systems.find(row.companyId(), row.systemId())
            .map(SystemInfo::kind)
            .orElse(null));
    }

    private static ObjectNode object(String key) {
        return JSON.createObjectNode().put("key", key);
    }
}
