package com.flowtivo.tinfirm.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google for the Gmail add-on's tests (TEST FIXTURE ONLY, ADR-0083): one WireMock server stands in for Google's keys of
 * its ID tokens (a test JWK set) and for the Gmail API's messages, their attachments and drafts in their threads, which
 * answer only the user's OAuth token and the message's token of the test. The ID tokens of a request are signed with the test key, as Google signs them: the
 * deployment's service account's, whose audience is the endpoint, and the person's, whose audience is the add-on's
 * OAuth client. Every key and token is a test value.
 */
public final class FakeGoogle {

    /** The add-on's service account in the tests ({@code tinfirm.gmail.service-account}). */
    public static final String SERVICE_ACCOUNT = "service-123456789@gcp-sa-gsuiteaddons.iam.gserviceaccount.com";

    /** The add-on's OAuth client in the tests ({@code tinfirm.gmail.client-id}). */
    public static final String CLIENT_ID = "123456789-test.apps.googleusercontent.com";

    public static final String ISSUER = "https://accounts.google.com";

    private static final WireMockServer SERVER = new WireMockServer(StubServers.options().dynamicPort());

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final RSAKey KEY = key("test-google-key");

    /** Signs, with a kid the JWK set does not name. */
    public static final RSAKey UNKNOWN_KEY = key("test-unknown-google-key");

    static {
        SERVER.start();
        SERVER.stubFor(get(urlEqualTo("/oauth2/v3/certs"))
            .willReturn(json(Map.of("keys", List.of(KEY.toPublicJWK().toJSONObject())))));
    }

    private FakeGoogle() {
    }

    private static RSAKey key(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        }
        catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static WireMockServer server() {
        return SERVER;
    }

    /** The key Google's tokens are signed with in the tests, for tokens a test makes wrong in another way. */
    public static RSAKey signingKey() {
        return KEY;
    }

    public static String url() {
        return "http://localhost:" + SERVER.port();
    }

    public static String keysUrl() {
        return url() + "/oauth2/v3/certs";
    }

    /** A Google account of a test. */
    public record Account(String subject, String email) {

        public static Account create() {
            String subject = String.valueOf(100_000_000_000L + (long) (Math.random() * 1_000_000_000L));
            return new Account(subject, "person-" + subject + "@gmail.test");
        }
    }

    /** The deployment's ID token for the endpoint, as Google sends it in the Authorization header. */
    public static String systemToken(String endpoint) {
        return systemToken(endpoint, SERVICE_ACCOUNT, KEY, Duration.ofHours(1));
    }

    public static String systemToken(String audience, String serviceAccount, RSAKey key, Duration lifetime) {
        return sign(key, new JWTClaimsSet.Builder().issuer(ISSUER)
            .audience(audience)
            .subject("1" + Math.abs(serviceAccount.hashCode()))
            .claim("email", serviceAccount)
            .claim("email_verified", true)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plus(lifetime)))
            .build());
    }

    /** The person's ID token for the add-on's OAuth client. */
    public static String userToken(Account account) {
        return userToken(account, CLIENT_ID, KEY);
    }

    public static String userToken(Account account, String audience, RSAKey key) {
        return sign(key, new JWTClaimsSet.Builder().issuer(ISSUER)
            .audience(audience)
            .subject(account.subject())
            .claim("email", account.email())
            .claim("email_verified", true)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
            .build());
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        }
        catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** An email in a test's mailbox: Gmail's ids, and the tokens the event carries for it. */
    public record Message(String id, String threadId, String userOAuthToken, String messageToken) {
    }

    /** An attachment of a test's email, which Gmail lists with an id and answers for the message's tokens only. */
    public record File(String name, String mimeType, byte[] content) {

        public static File text(String name, String content) {
            return new File(name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * A message the Gmail API answers with {@code format=full} for its tokens only: a {@code multipart/alternative}
     * with a plain and an HTML part, and an attachment sent with the message ({@code secrets.txt}).
     */
    public static Message message(String threadId, String subject, String from, String plainBody) {
        return create(threadId, subject, from, plainBody, null);
    }

    /**
     * As {@link #message(String, String, String, String)}, with the files as its attachments, each read by its id, and
     * an image of its HTML body (a {@code Content-ID}, inline), which is no attachment. Its headers answer {@code
     * format=metadata} too, with a {@code Message-ID} and {@code References}; a draft in its thread is saved.
     */
    public static Message message(String threadId, String subject, String from, String plainBody,
            List<File> files) {
        return create(threadId, subject, from, plainBody, files);
    }

    private static Message create(String threadId, String subject, String from, String plainBody,
            @Nullable List<File> files) {
        Message message = new Message(Long.toHexString(System.nanoTime()) + Long.toHexString(UUID.randomUUID()
            .getMostSignificantBits() & 0xffffffL), threadId, "test-user-oauth-" + UUID.randomUUID(),
                "test-message-token-" + UUID.randomUUID());
        List<Map<String, Object>> headers = List.of(header("Subject", subject), header("From", from),
                header("To", "Support <support@example.test>"), header("Date", "Mon, 19 Oct 2026 09:30:00 +0200"),
                header("Message-ID", "<" + message.id() + "@mail.customer.test>"),
                header("References", "<first@mail.customer.test>"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mimeType", "multipart/mixed");
        payload.put("headers", headers);
        List<Object> parts = new ArrayList<>();
        parts.add(Map.of("mimeType", "multipart/alternative", "filename", "", "parts", List.of(
                part("text/plain", plainBody), part("text/html", "<p>" + plainBody + "</p>"))));
        if (files != null) {
            parts.add(Map.of("mimeType", "image/png", "filename", "logo.png", "headers", List.of(header("Content-ID",
                    "<logo@mail.customer.test>"), header("Content-Disposition", "inline; filename=\"logo.png\"")),
                    "body", Map.of("attachmentId", "logo-" + message.id(), "size", 4)));
            int index = 0;
            for (File file : files) {
                String attachmentId = "att-" + index++ + "-" + message.id();
                parts.add(Map.of("mimeType", file.mimeType(), "filename", file.name(), "headers",
                        List.of(header("Content-Disposition", "attachment; filename=\"" + file.name() + "\"")),
                        "body", Map.of("attachmentId", attachmentId, "size", file.content().length)));
                SERVER.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/" + message.id() + "/attachments/"
                        + attachmentId))
                    .withHeader("Authorization", equalTo("Bearer " + message.userOAuthToken()))
                    .withHeader("X-Goog-Gmail-Access-Token", equalTo(message.messageToken()))
                    .willReturn(json(Map.of("size", file.content().length, "data",
                            Base64.getUrlEncoder().encodeToString(file.content())))));
            }
        }
        else {
            parts.add(Map.of("mimeType", "text/plain", "filename", "secrets.txt", "body",
                    Map.of("data", base64("the attachment's text"), "size", 21)));
        }
        payload.put("parts", parts);
        SERVER.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/" + message.id()))
            .withQueryParam("format", equalTo("full"))
            .withHeader("Authorization", equalTo("Bearer " + message.userOAuthToken()))
            .withHeader("X-Goog-Gmail-Access-Token", equalTo(message.messageToken()))
            .willReturn(json(Map.of("id", message.id(), "threadId", threadId, "payload", payload))));
        SERVER.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/" + message.id()))
            .withQueryParam("format", equalTo("metadata"))
            .withHeader("Authorization", equalTo("Bearer " + message.userOAuthToken()))
            .withHeader("X-Goog-Gmail-Access-Token", equalTo(message.messageToken()))
            .willReturn(json(Map.of("id", message.id(), "threadId", threadId, "payload", Map.of("mimeType",
                    "multipart/mixed", "headers", headers)))));
        SERVER.stubFor(post(urlPathEqualTo("/gmail/v1/users/me/drafts"))
            .withHeader("Authorization", equalTo("Bearer " + message.userOAuthToken()))
            .withHeader("X-Goog-Gmail-Access-Token", equalTo(message.messageToken()))
            .willReturn(json(Map.of("id", "r-" + message.id(), "message", Map.of("id", "d" + message.id(),
                    "threadId", threadId)))));
        return message;
    }

    /** The drafts saved with the message's tokens: each the whole RFC 2822 message, and the thread it was saved in. */
    public record Draft(String raw, String threadId) {
    }

    public static List<Draft> drafts(Message message) {
        List<Draft> drafts = new ArrayList<>();
        SERVER.findAll(postRequestedFor(urlPathEqualTo("/gmail/v1/users/me/drafts"))
            .withHeader("X-Goog-Gmail-Access-Token", equalTo(message.messageToken()))).forEach(request -> {
                JsonNode body = JSON.readTree(request.getBodyAsString()).get("message");
                drafts.add(new Draft(new String(Base64.getUrlDecoder().decode(body.get("raw").asString()),
                        StandardCharsets.UTF_8), body.get("threadId").asString()));
            });
        return drafts;
    }

    private static Map<String, Object> header(String name, String value) {
        return Map.of("name", name, "value", value);
    }

    private static Map<String, Object> part(String mimeType, String text) {
        return Map.of("mimeType", mimeType, "filename", "", "headers",
                List.of(header("Content-Type", mimeType + "; charset=\"UTF-8\"")), "body",
                Map.of("data", base64(text)));
    }

    private static String base64(String text) {
        return Base64.getUrlEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseDefinitionBuilder json(Object body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(JSON.writeValueAsString(body));
    }
}
