package com.flowtivo.tinfirm.chat.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The platforms' own requests (the package 5 contract, §5.2): outside {@code /api/companies} and the session chain
 * ({@link ChatConfiguration}), not in the OpenAPI document, since a generated interface would parse a body before its
 * signature is checked. A Slack endpoint first answers {@code 429} to a client address with more than 60 refused
 * requests for the system in the last minute ({@link InboundRefusals}, L6). Each reads at most
 * {@code tinfirm.chat.max-body} raw ({@code 413}), finds its system through
 * {@code chat_system_by_id} (C2), verifies (C1: {@code 401} without a body, nothing stored, counted per system), and
 * only then parses; then {@link ChatReceiver} stores the event and the answer is {@code 200} at once, {@code 503}
 * when the database fails.
 */
@RestController
class ChatInboundController {

    private static final Logger log = LoggerFactory.getLogger(ChatInboundController.class);

    static final String SLACK_EVENTS = "/api/chat/slack/{systemId}/events";

    static final String SLACK_INTERACTIONS = "/api/chat/slack/{systemId}/interactions";

    static final String TEAMS_MESSAGES = "/api/chat/teams/{systemId}/messages";

    /** The inbound bodies' parser: at most 32 levels deep (the package 5 contract, open point 11). */
    static final JsonMapper INBOUND = JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build())
        .build()).build();

    private final ChatStore store;

    private final ChatSystems chatSystems;

    private final SlackVerifier slack;

    private final TeamsVerifier teams;

    private final ChatReceiver receiver;

    private final ChatProperties properties;

    private final SystemTransactions transactions;

    private final InboundRefusals refusals;

    private final TimeSource time;

    ChatInboundController(ChatStore store, ChatSystems chatSystems, SlackVerifier slack, TeamsVerifier teams,
            ChatReceiver receiver, ChatProperties properties, SystemTransactions transactions,
            InboundRefusals refusals, TimeSource time) {
        this.store = store;
        this.chatSystems = chatSystems;
        this.slack = slack;
        this.teams = teams;
        this.receiver = receiver;
        this.properties = properties;
        this.transactions = transactions;
        this.refusals = refusals;
        this.time = time;
    }

    /** Slack's Events API: {@code url_verification} and {@code event_callback}. */
    @PostMapping(SLACK_EVENTS)
    ResponseEntity<?> slackEvents(@PathVariable("systemId") String systemId, HttpServletRequest request)
            throws IOException {
        UUID id = canonicalId(systemId);
        if (id == null) {
            return status(HttpStatus.NOT_FOUND);
        }
        String address = address(request);
        if (refusals.flooded(id, address, time.now().toEpochMilli())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").build();
        }
        Optional<byte[]> body = body(request);
        if (body.isEmpty()) {
            return status(HttpStatus.CONTENT_TOO_LARGE);
        }
        ChatSystemInfo system = system(id, "SLACK").orElse(null);
        if (system == null) {
            return status(HttpStatus.NOT_FOUND);
        }
        if (!slack.verify(system, request.getHeader("X-Slack-Request-Timestamp"),
                request.getHeader("X-Slack-Signature"), body.get())) {
            refusals.refused(id, address, time.now().toEpochMilli());
            receiver.refused(system, "SIGNATURE");
            return status(HttpStatus.UNAUTHORIZED);
        }
        JsonNode envelope = json(body.get());
        if (envelope == null) {
            return status(HttpStatus.BAD_REQUEST);
        }
        if ("url_verification".equals(SlackEvents.text(envelope, "type"))) {
            String challenge = SlackEvents.text(envelope, "challenge");
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("challenge", challenge != null ? challenge : ""));
        }
        Optional<Inbound> inbound = SlackEvents.event(envelope, retryNum(request));
        return inbound.isEmpty() ? status(HttpStatus.OK) : slackAnswer(receiver.receive(system, inbound.get(), true));
    }

    /** Slack's interactivity: a {@code block_actions} press, form-encoded as {@code payload={json}}. */
    @PostMapping(SLACK_INTERACTIONS)
    ResponseEntity<?> slackInteractions(@PathVariable("systemId") String systemId, HttpServletRequest request)
            throws IOException {
        UUID id = canonicalId(systemId);
        if (id == null) {
            return status(HttpStatus.NOT_FOUND);
        }
        String address = address(request);
        if (refusals.flooded(id, address, time.now().toEpochMilli())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").build();
        }
        Optional<byte[]> body = body(request);
        if (body.isEmpty()) {
            return status(HttpStatus.CONTENT_TOO_LARGE);
        }
        ChatSystemInfo system = system(id, "SLACK").orElse(null);
        if (system == null) {
            return status(HttpStatus.NOT_FOUND);
        }
        if (!slack.verify(system, request.getHeader("X-Slack-Request-Timestamp"),
                request.getHeader("X-Slack-Signature"), body.get())) {
            refusals.refused(id, address, time.now().toEpochMilli());
            receiver.refused(system, "SIGNATURE");
            return status(HttpStatus.UNAUTHORIZED);
        }
        String payload = formField(new String(body.get(), StandardCharsets.UTF_8), "payload");
        JsonNode interaction = payload != null ? json(payload.getBytes(StandardCharsets.UTF_8)) : null;
        if (interaction == null) {
            return status(HttpStatus.BAD_REQUEST);
        }
        Optional<Inbound> inbound = SlackEvents.action(interaction, retryNum(request));
        return inbound.isEmpty() ? status(HttpStatus.OK) : slackAnswer(receiver.receive(system, inbound.get(), true));
    }

    /** The Bot Framework's activities, its bearer token verified first, then the activity's own checks. */
    @PostMapping(TEAMS_MESSAGES)
    ResponseEntity<?> teamsMessages(@PathVariable("systemId") String systemId, HttpServletRequest request)
            throws IOException {
        Optional<byte[]> body = body(request);
        if (body.isEmpty()) {
            return status(HttpStatus.CONTENT_TOO_LARGE);
        }
        UUID id = canonicalId(systemId);
        ChatSystemInfo system = id != null ? system(id, "TEAMS").orElse(null) : null;
        if (system == null) {
            return status(HttpStatus.NOT_FOUND);
        }
        String authorization = request.getHeader("Authorization");
        String problem = teams.problem(system, authorization);
        if (problem != null) {
            log.info("An activity for the Teams system {} was refused: {}", system.id(), problem);
            receiver.refused(system, "TOKEN");
            return status(HttpStatus.UNAUTHORIZED);
        }
        JsonNode activity = json(body.get());
        if (activity == null) {
            return status(HttpStatus.BAD_REQUEST);
        }
        String activityProblem = teams.activityProblem(system, java.util.Objects.requireNonNull(authorization),
                activity);
        if (activityProblem != null) {
            log.info("An activity for the Teams system {} was refused: {}", system.id(), activityProblem);
            receiver.refused(system, "ACTIVITY");
            return status(HttpStatus.UNAUTHORIZED);
        }
        Optional<Inbound> inbound = TeamsActivities.activity(activity);
        if (inbound.isEmpty()) {
            return status(HttpStatus.OK);
        }
        return switch (receiver.receive(system, inbound.get(), true)) {
            case STORED, OTHER_WORKSPACE -> status(HttpStatus.OK);
            case FAILED -> status(HttpStatus.SERVICE_UNAVAILABLE);
        };
    }

    private static ResponseEntity<?> slackAnswer(ChatReceiver.Received received) {
        return switch (received) {
            case STORED -> status(HttpStatus.OK);
            // C2: answered so that Slack does not deliver it again, stored nowhere.
            case OTHER_WORKSPACE -> ResponseEntity.ok().header("X-Slack-No-Retry", "1").build();
            case FAILED -> status(HttpStatus.SERVICE_UNAVAILABLE);
        };
    }

    private static ResponseEntity<?> status(HttpStatus status) {
        return ResponseEntity.status(status).build();
    }

    /**
     * The system id of the path in its canonical 36-character form (any case), or null: {@link UUID#fromString} also
     * takes short forms, which would name a system under other spellings.
     */
    static @Nullable UUID canonicalId(String systemId) {
        if (systemId.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(systemId);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String address(HttpServletRequest request) {
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    /** The chat system of the path, of the kind: through the definer lookup, then in its company. */
    private Optional<ChatSystemInfo> system(UUID id, String kind) {
        Optional<ChatStore.SystemRef> ref = transactions.unscoped(() -> store.system(id))
            .filter(found -> kind.equals(found.kind()));
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        UUID companyId = ref.get().companyId();
        return transactions.inCompany(companyId, () -> chatSystems.find(companyId, id));
    }

    /** At most the operator's limit, read raw; empty beyond it. */
    private Optional<byte[]> body(HttpServletRequest request) throws IOException {
        long max = properties.maxBody().toBytes();
        long length = request.getContentLengthLong();
        if (length > max) {
            return Optional.empty();
        }
        InputStream in = request.getInputStream();
        if (length >= 0) {
            return Optional.of(in.readNBytes((int) length));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
            total += read;
            if (total > max) {
                return Optional.empty();
            }
            out.write(buffer, 0, read);
        }
        return Optional.of(out.toByteArray());
    }

    private static @Nullable JsonNode json(byte[] body) {
        try {
            JsonNode node = INBOUND.readTree(body);
            return node != null && node.isObject() ? node : null;
        }
        catch (JacksonException ex) {
            return null;
        }
    }

    /** A field of a form-encoded body. */
    static @Nullable String formField(String form, String name) {
        for (String pair : form.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                return equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int retryNum(HttpServletRequest request) {
        String retry = request.getHeader("X-Slack-Retry-Num");
        if (retry == null || !retry.matches("[0-9]{1,3}")) {
            return 0;
        }
        return Integer.parseInt(retry);
    }
}
