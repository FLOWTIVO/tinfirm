package com.flowtivo.tinfirm.runner.internal;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.runner.CredentialKind;
import com.flowtivo.tinfirm.runner.RunnerMessages;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON encoding of runner protocol frames and validation of inbound ones, per protocol version (CONVENTIONS
 * §14.10).
 * <p>
 * A plain mapper, independent of the web configuration, so the wire format never changes by accident. Unknown
 * fields are ignored, as the protocol requires. Problems are reported with field names only, never with values. A
 * frame of the other version (a v1 {@code run.ask} from a v2 host, a {@code run.request} from a v1 host) is an
 * unknown type, as the schemas demand; before {@code hello} the version is not known yet and every inbound type
 * decodes ({@link #ANY_VERSION}).
 */
@Component
class RunnerMessageCodec {

    /** Decode the inbound frames of every version: before {@code hello}, and in tests. */
    static final int ANY_VERSION = 0;

    /** What hosts of every version send. */
    private static final Set<String> COMMON_INBOUND_TYPES = Set.of("hello", "run.accepted", "run.rejected",
            "run.event", "run.heartbeat", "run.finished", "credential.status", "host.config.applied", "error");

    /** What only a v1 host sends. */
    private static final Set<String> V1_INBOUND_TYPES = Set.of("run.repository_request", "run.task_create",
            "run.ask");

    /** What only a v2 host sends. */
    private static final Set<String> V2_INBOUND_TYPES = Set.of("run.request", "run.steer.ack", "host.pool.status");

    private static final Set<String> OUTBOUND_TYPES = Set.of("welcome", "run.assign", "run.cancel",
            "run.repository_request.ack", "run.task_create.ack", "run.ask.ack", "run.request.ack", "run.steer",
            "host.config", "psl.update");

    /** The credential kinds this server knows ({@link CredentialKind}); any other is dropped from a hello. */
    private static final Set<String> CREDENTIAL_KINDS = Arrays.stream(CredentialKind.values())
        .map(Enum::name)
        .collect(Collectors.toUnmodifiableSet());

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    private final JsonMapper json = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private final Validator validator;

    RunnerMessageCodec(Validator validator) {
        this.validator = validator;
    }

    String encode(RunnerMessages.Outbound message) {
        return json.writerFor(RunnerMessages.Message.class).writeValueAsString(message);
    }

    /** Decodes a frame of any version: see {@link #decode(String, int)}. */
    RunnerMessages.Inbound decode(String text) {
        return decode(text, ANY_VERSION);
    }

    /**
     * Decodes and validates one inbound frame of a host that speaks {@code version} ({@link #ANY_VERSION} before
     * its {@code hello}).
     *
     * @throws BadFrame for a frame that cannot be processed: answered with {@code error} and otherwise ignored
     */
    RunnerMessages.Inbound decode(String text, int version) {
        return decodeFrame(text, version).message();
    }

    /** A decoded inbound frame, with the labels of the credentials a {@code hello} lost to {@link #decodeFrame}. */
    record Decoded(RunnerMessages.Inbound message, List<String> unknownCredentialKinds) {
    }

    /**
     * {@link #decode(String, int)}, and for a {@code hello} the credentials of a kind this server does not know
     * dropped first: a host of another version's (a {@code setup-token} token of a host before ADR-0085, or a kind a
     * newer host has). The host is told ({@code UNKNOWN_CREDENTIAL_KIND}) and its other credentials and runtimes
     * serve, instead of the whole hello being refused and the host kept offline until it is upgraded.
     */
    Decoded decodeFrame(String text, int version) {
        JsonNode tree;
        try {
            tree = json.readTree(text);
        }
        catch (JacksonException ex) {
            throw new BadFrame(RunnerMessages.BAD_MESSAGE, "The frame is not valid JSON", null);
        }
        if (tree == null || !tree.isObject()) {
            throw new BadFrame(RunnerMessages.BAD_MESSAGE, "The frame is not a JSON object", null);
        }
        UUID runId = runId(tree);
        JsonNode type = tree.get("type");
        if (type == null || !type.isString()) {
            throw new BadFrame(RunnerMessages.BAD_MESSAGE, "The frame has no type", runId);
        }
        String name = type.stringValue();
        if (OUTBOUND_TYPES.contains(name)) {
            throw new BadFrame(RunnerMessages.BAD_MESSAGE, "A runner host does not send " + name, runId);
        }
        if (!inbound(name, version)) {
            throw new BadFrame(RunnerMessages.UNKNOWN_TYPE, version == ANY_VERSION || !known(name)
                    ? "Unknown message type" : name + " is not a message of runner protocol " + version, runId);
        }
        List<String> unknownCredentialKinds = name.equals("hello") ? dropUnknownCredentialKinds(tree) : List.of();
        RunnerMessages.Message message;
        try {
            message = json.treeToValue(tree, RunnerMessages.Message.class);
        }
        catch (JacksonException ex) {
            throw new BadFrame(RunnerMessages.BAD_MESSAGE, "Malformed " + name + " message", runId);
        }
        Set<ConstraintViolation<RunnerMessages.Message>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            ConstraintViolation<RunnerMessages.Message> first = violations.stream()
                .min(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .orElseThrow();
            throw new BadFrame(RunnerMessages.BAD_MESSAGE,
                    "Invalid " + name + " message: " + first.getPropertyPath() + " " + first.getMessage(), runId);
        }
        switch (message) {
            case RunnerMessages.RunEvent event -> {
                requireInstant(event.at(), "at", name, runId);
                String problem = RunEventPayloads.problem(event.eventType(), event.payload());
                if (problem != null) {
                    throw new BadFrame(RunnerMessages.BAD_MESSAGE, "Invalid run.event payload: " + problem, runId);
                }
            }
            case RunnerMessages.CredentialStatus status when status.cooldownUntil() != null ->
                requireInstant(status.cooldownUntil(), "cooldownUntil", name, runId);
            // A v1 host has at most 16 slots (runner-v1 $defs/Capacity); 1000 is v2's bound (D12).
            case RunnerMessages.Hello hello when hello.protocolVersion() == RunnerMessages.PROTOCOL_V1
                    && hello.maxConcurrentRuns() > RunnerMessages.MAX_CAPACITY ->
                throw new BadFrame(RunnerMessages.BAD_MESSAGE, "Invalid " + name
                        + " message: maxConcurrentRuns must be at most " + RunnerMessages.MAX_CAPACITY
                        + " in runner protocol 1", null);
            case RunnerMessages.HostConfigApplied applied when version == RunnerMessages.PROTOCOL_V1
                    && applied.maxConcurrentRunsCeiling() > RunnerMessages.MAX_CAPACITY ->
                throw new BadFrame(RunnerMessages.BAD_MESSAGE, "Invalid " + name
                        + " message: maxConcurrentRunsCeiling must be at most " + RunnerMessages.MAX_CAPACITY
                        + " in runner protocol 1", null);
            // The schema's conditions: a draining host has 0 slots, any other host at least 1.
            case RunnerMessages.HostConfigApplied applied when applied.draining() != (applied.maxConcurrentRuns() == 0) ->
                throw new BadFrame(RunnerMessages.BAD_MESSAGE, "Invalid " + name
                        + " message: maxConcurrentRuns must be 0 exactly while draining", null);
            default -> {
                // No checks beyond the constraints.
            }
        }
        return new Decoded((RunnerMessages.Inbound) message, unknownCredentialKinds);
    }

    /**
     * Takes the credentials of an unknown kind out of a hello's tree.
     *
     * @return their labels as reported, in order; {@code ?} for one without a well-formed label
     */
    private List<String> dropUnknownCredentialKinds(JsonNode tree) {
        if (!(tree instanceof ObjectNode object) || !(object.get("credentials") instanceof ArrayNode credentials)) {
            return List.of();
        }
        List<String> dropped = new ArrayList<>();
        ArrayNode kept = json.createArrayNode();
        for (JsonNode credential : credentials) {
            JsonNode kind = credential.get("kind");
            if (kind != null && kind.isString() && !CREDENTIAL_KINDS.contains(kind.stringValue())) {
                JsonNode label = credential.get("label");
                dropped.add(label != null && label.isString() && LABEL.matcher(label.stringValue()).matches()
                        ? label.stringValue() : "?");
            }
            else {
                kept.add(credential);
            }
        }
        if (!dropped.isEmpty()) {
            object.set("credentials", kept);
        }
        return dropped;
    }

    private static boolean inbound(String type, int version) {
        if (COMMON_INBOUND_TYPES.contains(type)) {
            return true;
        }
        return switch (version) {
            case ANY_VERSION -> V1_INBOUND_TYPES.contains(type) || V2_INBOUND_TYPES.contains(type);
            case RunnerMessages.PROTOCOL_V1 -> V1_INBOUND_TYPES.contains(type);
            default -> V2_INBOUND_TYPES.contains(type);
        };
    }

    private static boolean known(String type) {
        return V1_INBOUND_TYPES.contains(type) || V2_INBOUND_TYPES.contains(type);
    }

    private static void requireInstant(String value, String field, String type, @Nullable UUID runId) {
        try {
            RunnerMessages.parseInstant(value);
        }
        catch (IllegalArgumentException ex) {
            throw new BadFrame(RunnerMessages.BAD_MESSAGE, "Invalid " + type + " message: " + field
                    + " is not an RFC 3339 timestamp", runId);
        }
    }

    private static @Nullable UUID runId(JsonNode tree) {
        JsonNode runId = tree.get("runId");
        if (runId == null || !runId.isString()) {
            return null;
        }
        try {
            return UUID.fromString(runId.stringValue());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** A frame that cannot be processed; answered with an {@code error} frame and otherwise ignored. */
    static final class BadFrame extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String code;

        private final @Nullable UUID runId;

        BadFrame(String code, String message, @Nullable UUID runId) {
            super(message, null, false, false);
            this.code = code;
            this.runId = runId;
        }

        RunnerMessages.ProtocolError toError() {
            return new RunnerMessages.ProtocolError(code, getMessage(), runId);
        }
    }
}
