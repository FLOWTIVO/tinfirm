package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.system.WebhookSystems;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The webhook's verification and mapping (the package 5 contract, §5.12, §9's {@code WebhookVerifierTest}): a
 * {@code STANDARD} signature keyed with the decoded secret, Standard Webhooks' own vector, the previous secret, the
 * window of 300 seconds either way, the secret header; the mapping's pointers and limits, a trigger's events and
 * conditions.
 */
class WebhookVerifierTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Standard Webhooks' published vector. */
    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"; // gitleaks:allow (the published vector)

    private static final String ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";

    private static final String TIMESTAMP = "1614265330";

    private static final byte[] BODY = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);

    private static final String SIGNATURE = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

    private static final Instant AT = Instant.ofEpochSecond(1614265330);

    @Test
    void aStandardSignatureIsKeyedWithTheDecodedSecret() {
        WebhookSystems.Secrets secrets = new WebhookSystems.Secrets(SECRET, null);
        assertThat(WebhookVerifier.standard(secrets, ID, TIMESTAMP, SIGNATURE, BODY, AT))
            .isInstanceOf(WebhookVerifier.Outcome.Verified.class);
        // Another listed signature first, then the right one.
        assertThat(WebhookVerifier.standard(secrets, ID, TIMESTAMP, "v1,AAAA " + SIGNATURE, BODY, AT))
            .isInstanceOf(WebhookVerifier.Outcome.Verified.class);
        // Keyed with the secret's string instead of its bytes: refused.
        String wrongKey = Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8));
        assertThat(WebhookVerifier.standard(new WebhookSystems.Secrets("whsec_" + wrongKey, null), ID, TIMESTAMP,
                SIGNATURE, BODY, AT)).isEqualTo(new WebhookVerifier.Outcome.Refused(WebhookVerifier.Refusal.SIGNATURE));
        // An altered body, another id, another version.
        assertThat(WebhookVerifier.standard(secrets, ID, TIMESTAMP, SIGNATURE, "{\"test\": 1}".getBytes(), AT))
            .isInstanceOf(WebhookVerifier.Outcome.Refused.class);
        assertThat(WebhookVerifier.standard(secrets, "msg_other", TIMESTAMP, SIGNATURE, BODY, AT))
            .isInstanceOf(WebhookVerifier.Outcome.Refused.class);
        assertThat(WebhookVerifier.standard(secrets, ID, TIMESTAMP, SIGNATURE.replace("v1,", "v2,"), BODY, AT))
            .isInstanceOf(WebhookVerifier.Outcome.Refused.class);
        assertThat(WebhookVerifier.standard(secrets, null, TIMESTAMP, SIGNATURE, BODY, AT))
            .isInstanceOf(WebhookVerifier.Outcome.Refused.class);
    }

    @Test
    void theWindowIsThreeHundredSecondsEitherWay() {
        WebhookSystems.Secrets secrets = new WebhookSystems.Secrets(SECRET, null);
        assertThat(WebhookVerifier.standard(secrets, ID, TIMESTAMP, SIGNATURE, BODY, AT.plusSeconds(300)))
            .isInstanceOf(WebhookVerifier.Outcome.Verified.class);
        assertThat(WebhookVerifier.standard(secrets, ID, TIMESTAMP, SIGNATURE, BODY, AT.minusSeconds(300)))
            .isInstanceOf(WebhookVerifier.Outcome.Verified.class);
        assertThat(WebhookVerifier.standard(secrets, ID, TIMESTAMP, SIGNATURE, BODY, AT.plusSeconds(301)))
            .isEqualTo(new WebhookVerifier.Outcome.Refused(WebhookVerifier.Refusal.WINDOW));
        assertThat(WebhookVerifier.standard(secrets, ID, TIMESTAMP, SIGNATURE, BODY, AT.minusSeconds(301)))
            .isEqualTo(new WebhookVerifier.Outcome.Refused(WebhookVerifier.Refusal.WINDOW));
    }

    @Test
    void thePreviousSecretCountsWhileItIsKept() {
        WebhookSystems.Secrets rotated = new WebhookSystems.Secrets("whsec_" + Base64.getEncoder()
            .encodeToString(new byte[32]), SECRET);
        assertThat(WebhookVerifier.standard(rotated, ID, TIMESTAMP, SIGNATURE, BODY, AT))
            .isInstanceOf(WebhookVerifier.Outcome.Verified.class);
        assertThat(WebhookVerifier.standard(new WebhookSystems.Secrets(rotated.current(), null), ID, TIMESTAMP,
                SIGNATURE, BODY, AT)).isInstanceOf(WebhookVerifier.Outcome.Refused.class);
        assertThat(WebhookVerifier.secretHeader(new WebhookSystems.Secrets("new", "old"), "old"))
            .isInstanceOf(WebhookVerifier.Outcome.Verified.class);
        assertThat(WebhookVerifier.secretHeader(new WebhookSystems.Secrets("new", null), "old"))
            .isInstanceOf(WebhookVerifier.Outcome.Refused.class);
        assertThat(WebhookVerifier.secretHeader(new WebhookSystems.Secrets("new", null), null))
            .isInstanceOf(WebhookVerifier.Outcome.Refused.class);
    }

    @Test
    void theMappingReadsTextCutToItsLimitsAndTheTriggersMatch() {
        WebhookSystems.WebhookSystem system = new WebhookSystems.WebhookSystem(UUID.randomUUID(), UUID.randomUUID(),
                "Desk", "desk", false, WebhookSystems.Scheme.STANDARD, WebhookSystems.DEFAULT_SECRET_HEADER,
                Map.of("event", "/type", "subject.key", "/issue/id", "subject.url", "/issue/url", "actor.name",
                        "/user/name", "text", "/items", "deliveryId", "/delivery"), null, null, null);
        JsonNode body = JSON.readTree("{\"type\":\"created\",\"issue\":{\"id\":42,\"url\":\"ftp://x\"},"
                + "\"user\":{\"name\":\"" + "a".repeat(300) + "\"},\"items\":[1,2],\"delivery\":\"d-1\","
                + "\"a~b\":{\"c/d\":true}}");
        WebhookMapping.Mapped mapped = ((WebhookMapping.Result.Read) WebhookMapping.map(system, body)).mapped();
        assertThat(mapped.event()).isEqualTo("created");
        assertThat(mapped.subjectKey()).isEqualTo("42");
        assertThat(mapped.subjectUrl()).isNull();
        assertThat(mapped.actorName()).hasSize(WebhookMapping.ACTOR);
        assertThat(mapped.text()).isNull();
        assertThat(mapped.deliveryId()).isEqualTo("d-1");
        assertThat(WebhookMapping.text(body, "/a~0b/c~1d", 10)).isEqualTo("true");

        assertThat(WebhookMapping.failed(List.of(new WebhookCondition("/type", WebhookCondition.Op.EQUALS,
                List.of("created")), new WebhookCondition("/issue/id", WebhookCondition.Op.IN, List.of("41", "42")),
                new WebhookCondition("/user", WebhookCondition.Op.PRESENT, List.of()),
                new WebhookCondition("/none", WebhookCondition.Op.ABSENT, List.of())), body)).isNull();
        // The first condition that fails, with what the body holds there (what the try answers).
        WebhookMapping.Mismatch.Condition closed = WebhookMapping.failed(List.of(new WebhookCondition("/user",
                WebhookCondition.Op.PRESENT, List.of()), new WebhookCondition("/type", WebhookCondition.Op.EQUALS,
                        List.of("closed")), new WebhookCondition("/none", WebhookCondition.Op.PRESENT, List.of())),
                body);
        assertThat(closed).isNotNull();
        assertThat(closed.index()).isEqualTo(1);
        assertThat(closed.actual()).isEqualTo("created");
        WebhookMapping.Mismatch.Condition array = WebhookMapping.failed(List.of(new WebhookCondition("/items",
                WebhookCondition.Op.IN, List.of("[1,2]"))), body);
        assertThat(array).isNotNull();
        assertThat(array.actual()).isNull();
        assertThat(WebhookMapping.failed(List.of(new WebhookCondition("no-slash", WebhookCondition.Op.ABSENT,
                List.of())), body)).isNotNull();
        WebhookMapping.Mismatch.Condition named = WebhookMapping.failed(List.of(new WebhookCondition("/user/name",
                WebhookCondition.Op.EQUALS, List.of("b"))), body);
        assertThat(named).isNotNull();
        assertThat(named.actual()).hasSize(300);

        // A mapped delivery id that is not printable fails the mapping.
        JsonNode spaced = JSON.readTree("{\"delivery\":\"a b\"}");
        assertThat(WebhookMapping.map(system, spaced)).isInstanceOf(WebhookMapping.Result.Failed.class);

        assertThat(new TriggerEventSpec(TriggerEvent.DELIVERED, null).value()).isNull();
        assertThat(WebhookInbox.parse("[1]".getBytes())).isNull();
        assertThat(WebhookInbox.parse("{\"a\":".getBytes())).isNull();
        assertThat(WebhookInbox.parse(("{\"a\":[" + "1,".repeat(10_000) + "1]}").getBytes())).isNull();
        assertThat(WebhookInbox.parse(("{\"a\":" + "[".repeat(40) + "]".repeat(40) + "}").getBytes())).isNull();
        assertThat(WebhookInbox.parse("{\"a\":[1,2,3]}".getBytes())).isNotNull();
    }

    /** Review of package 5b: {@code jsonb} refuses {@code U+0000}, in a name or a string at any depth: a bad body. */
    @Test
    void aBodyWithANulEscapeAnywhereIsNotParsed() {
        assertThat(WebhookInbox.parse("{\"a\":\"x\\u0000y\"}".getBytes())).isNull();
        assertThat(WebhookInbox.parse("{\"a\\u0000\":1}".getBytes())).isNull();
        assertThat(WebhookInbox.parse("{\"a\":[{\"b\":[\"ok\",\"\\u0000\"]}]}".getBytes())).isNull();
        // Another control character's escape is kept, as jsonb keeps it.
        assertThat(WebhookInbox.parse("{\"a\":\"x\\u0001y\"}".getBytes())).isNotNull();
    }

    /** Review of package 5b: a cut text never ends in the first half of a surrogate pair. */
    @Test
    void aCutNeverSplitsASurrogatePair() {
        String smile = "\uD83D\uDE00";
        assertThat(WebhookMapping.cut("ab" + smile, 3)).isEqualTo("ab");
        assertThat(WebhookMapping.cut("ab" + smile, 4)).isEqualTo("ab" + smile);
        assertThat(WebhookMapping.cut("abc" + smile, 3)).isEqualTo("abc");
        String body = "a".repeat(16 * 1024 - 1) + smile + "tail";
        String cut = WebhookMapping.cutBody(body.getBytes(StandardCharsets.UTF_8));
        assertThat(cut).startsWith("a".repeat(16 * 1024 - 1) + "\n");
        assertThat(cut.codePoints().noneMatch(point -> Character.isSurrogate((char) point))).isTrue();
    }

    /** Review of package 5b: a kept previous secret counts until its own {@code previousUntil}, not 10 minutes on. */
    @Test
    void aKeptPreviousSecretExpiresAtItsOwnPreviousUntil() {
        Instant until = Instant.parse("2026-10-13T10:00:00Z");
        WebhookSystems.Secrets kept = new WebhookSystems.Secrets("whsec_new", "whsec_old");
        assertThat(WebhookInbox.unexpired(kept, system(until), until.minusSeconds(1))).isEqualTo(kept);
        assertThat(WebhookInbox.unexpired(kept, system(until), until))
            .isEqualTo(new WebhookSystems.Secrets("whsec_new", null));
        assertThat(WebhookInbox.unexpired(kept, system(null), until.minusSeconds(1)).previous()).isNull();
    }

    private static WebhookSystems.WebhookSystem system(@Nullable Instant previousUntil) {
        return new WebhookSystems.WebhookSystem(UUID.randomUUID(), UUID.randomUUID(), "Desk", "desk", false,
                WebhookSystems.Scheme.STANDARD, WebhookSystems.DEFAULT_SECRET_HEADER, Map.of(), null, null,
                previousUntil);
    }
}
