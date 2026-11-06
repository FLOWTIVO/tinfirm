package com.flowtivo.tinfirm.system.internal;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.GmailMessages;
import com.flowtivo.tinfirm.system.internal.http.GmailEndpoints;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;

import tools.jackson.databind.JsonNode;

/**
 * {@link GmailMessages}: the Gmail API, at {@link GmailEndpoints}. The Gmail add-on's, a paid feature's code under
 * {@code ee/} (ADR-0077, decision 1).
 */
@Component
class GmailMessagesImpl implements GmailMessages {

    /** Gmail's ids of messages: hex digits today; never anything that leaves the path. */
    private static final Pattern MESSAGE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Gmail's ids of attachments: long base64url strings; never anything that leaves the path. */
    private static final Pattern ATTACHMENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,4096}");

    private static final String REPLY_HEADERS = Stream.of("Subject", "From", "Reply-To", "To", "Cc", "Message-ID",
            "References").map(name -> "metadataHeaders=" + name).collect(Collectors.joining("&"));

    private final OutboundHttp http;

    private final GmailEndpoints endpoints;

    GmailMessagesImpl(OutboundHttp http, GmailEndpoints endpoints) {
        this.http = http;
        this.endpoints = endpoints;
    }

    @Override
    public CallResult<String> message(String userToken, String messageToken, String messageId) {
        NoTransaction.require("A call to Gmail");
        if (!MESSAGE_ID.matcher(messageId).matches()) {
            return CallResult.failed(CallResult.Kind.INVALID, "Not a Gmail message id");
        }
        HttpRequest request = gmail(userToken, messageToken, "/gmail/v1/users/me/messages/" + messageId
                + "?format=full").GET().build();
        return body(http.send(endpoints.gmailApi(), request), "Gmail");
    }

    @Override
    public CallResult<String> messageHeaders(String userToken, String messageToken, String messageId) {
        NoTransaction.require("A call to Gmail");
        if (!MESSAGE_ID.matcher(messageId).matches()) {
            return CallResult.failed(CallResult.Kind.INVALID, "Not a Gmail message id");
        }
        HttpRequest request = gmail(userToken, messageToken, "/gmail/v1/users/me/messages/" + messageId
                + "?format=metadata&" + REPLY_HEADERS).GET().build();
        return body(http.send(endpoints.gmailApi(), request), "Gmail");
    }

    @Override
    public CallResult<byte[]> attachment(String userToken, String messageToken, String messageId, String attachmentId,
            long maxBytes) {
        NoTransaction.require("A call to Gmail");
        if (!MESSAGE_ID.matcher(messageId).matches() || !ATTACHMENT_ID.matcher(attachmentId).matches()) {
            return CallResult.failed(CallResult.Kind.INVALID, "Not a Gmail attachment id");
        }
        HttpRequest request = gmail(userToken, messageToken, "/gmail/v1/users/me/messages/" + messageId
                + "/attachments/" + attachmentId).GET().build();
        // base64url in JSON: four characters for three bytes, and the answer's few other fields.
        CallResult<OutboundHttp.BytesResponse> answer = http.sendForBytes(endpoints.gmailApi(), request,
                maxBytes / 3 * 4 + 8192);
        if (answer.failure() != null) {
            return answer.failedAs();
        }
        OutboundHttp.BytesResponse response = Objects.requireNonNull(answer.value());
        if (response.status() < 200 || response.status() > 299) {
            return CallResult.failed(OutboundHttp.kindOf(response.status()), "Gmail answered " + response.status());
        }
        JsonNode json = Json.parse(new String(response.body(), StandardCharsets.UTF_8));
        String data = Json.text(json, "data");
        if (data == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "Gmail's attachment has no data");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(data.replace("=", ""));
            return bytes.length > maxBytes ? CallResult.failed(CallResult.Kind.INVALID, "The attachment is larger "
                    + "than " + maxBytes + " bytes") : CallResult.ok(bytes);
        }
        catch (IllegalArgumentException ex) {
            return CallResult.failed(CallResult.Kind.INVALID, "Gmail's attachment is not base64url");
        }
    }

    @Override
    public CallResult<Draft> createDraft(String userToken, String messageToken, String threadId, byte[] rfc2822) {
        NoTransaction.require("A call to Gmail");
        if (!MESSAGE_ID.matcher(threadId).matches()) {
            return CallResult.failed(CallResult.Kind.INVALID, "Not a Gmail thread id");
        }
        String body = Json.write(Map.of("message", Map.of("raw",
                Base64.getUrlEncoder().withoutPadding().encodeToString(rfc2822), "threadId", threadId)));
        HttpRequest request = gmail(userToken, messageToken, "/gmail/v1/users/me/drafts")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        CallResult<String> answer = body(http.send(endpoints.gmailApi(), request), "Gmail");
        if (answer.failure() != null) {
            return answer.failedAs();
        }
        JsonNode draft = Json.parse(Objects.requireNonNull(answer.value()));
        String id = Json.text(draft, "id");
        JsonNode message = draft != null ? draft.path("message") : null;
        String messageId = Json.text(message, "id");
        if (id == null || messageId == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "Gmail's draft has no id");
        }
        String thread = Json.text(message, "threadId");
        return CallResult.ok(new Draft(id, messageId, thread != null ? thread : threadId));
    }

    /** A request to the Gmail API with the person's token and the message's. */
    private HttpRequest.Builder gmail(String userToken, String messageToken, String path) {
        return http.request(endpoints.gmailApi() + path)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + userToken)
            .header("X-Goog-Gmail-Access-Token", messageToken);
    }

    private static CallResult<String> body(CallResult<OutboundHttp.Response> answer, String what) {
        return GmailCallsImpl.body(answer, what);
    }
}
