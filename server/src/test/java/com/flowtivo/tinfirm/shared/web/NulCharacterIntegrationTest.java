package com.flowtivo.tinfirm.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.RawHttp;
import com.flowtivo.tinfirm.support.TestConversations;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * U+0000 (NUL), which PostgreSQL refuses in {@code text} and {@code jsonb}, is refused at the web boundary in every
 * text of a request that may reach SQL: a query or form parameter, a multipart part's name, file name and fields, and
 * a string of a JSON body, also of one that its endpoint reads itself. {@code 400 VALIDATION_FAILED}, never an
 * unhandled 500. A path cannot hold it (Tomcat refuses {@code %00} in a path), and the webhook inbox and the model
 * relay keep their own rules for their bodies.
 */
@ExtendWith(OutputCaptureExtension.class)
class NulCharacterIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aQueryParameterWithNulIsRefused(CapturedOutput output) {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiClient owner = loginAs(company.email("OWNER"));
        int before = output.getOut().length();
        // The text searches of the lists: the skills' (q), the spend's model and the conversations' subject.
        Map<String, ApiClient> queries = Map.of("/skills?q=%00", member, "/skills?q=a%00b", member,
                "/spend?model=%00", owner, "/conversations?subjectKey=%00", member);
        queries.forEach((query, client) -> {
            RawHttp.Answer answer = RawHttp.send(port, "GET", company.path(query), Map.of("Cookie", session(client),
                    "Accept", "application/json"), null);
            assertRefused(answer.status(), answer.header("Content-Type"), answer.body(), query);
        });
        assertThat(output.getOut().substring(before)).doesNotContain("Unhandled exception");
        // A path: Tomcat's own 400, before any filter.
        RawHttp.Answer path = RawHttp.send(port, "GET", company.path("/agents/a%00b"),
                Map.of("Cookie", session(member)), null);
        assertThat(path.status()).isEqualTo(400);
    }

    @Test
    void aStringOfAJsonBodyWithNulIsRefused(CapturedOutput output) {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        int before = output.getOut().length();
        Map<String, String> bodies = Map.of(
                "/groups", "{\"name\":\"a\\u0000b\",\"memberIds\":[]}",
                "/service-identities", "{\"name\":\"a\\u0000b\"}",
                "/agents", "{\"name\":\"Agent \\u0000\",\"runtimes\":[\"CLAUDE_CODE\"],\"systems\":[]}",
                "/secrets", "{\"name\":\"NUL_VALUE\",\"value\":\"a\\u0000b\",\"description\":\"d\"}");
        bodies.forEach((path, body) -> {
            ApiResponse answer = owner.post(company.path(path), body);
            assertRefused(answer.status(), answer.contentType(), answer.body(), path);
        });
        // In a description, and in the name of an object's field.
        assertRefused(owner.post(company.path("/agents"), "{\"name\":\"" + unique("Agent")
                + "\",\"description\":\"x\\u0000\",\"runtimes\":[\"CLAUDE_CODE\"],\"systems\":[]}"), "description");
        assertRefused(owner.post(company.path("/groups"), "{\"name\":\"g\",\"memberIds\":[],\"\\u0000\":1}"),
                "field name");
        // UTF-16 holds NUL bytes: RFC 8259 asks for UTF-8 between systems, and such a body is refused.
        String xsrf = owner.cookie("XSRF-TOKEN").orElseThrow().getValue();
        RawHttp.Answer utf16 = RawHttp.send(port, "POST", company.path("/groups"), Map.of("Cookie", session(owner)
                + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf, "Content-Type", "application/json",
                "Accept", "application/json"), "{\"name\":\"utf16\",\"memberIds\":[]}".getBytes(StandardCharsets.UTF_16BE));
        assertRefused(utf16.status(), utf16.header("Content-Type"), utf16.body(), "UTF-16");
        // The overlong forms of U+0000 are no UTF-8, and the parser decodes them to U+0000 all the same.
        for (byte[] overlong : List.of(new byte[] {(byte) 0xC0, (byte) 0x80},
                new byte[] {(byte) 0xE0, (byte) 0x80, (byte) 0x80},
                new byte[] {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80})) {
            RawHttp.Answer answer = RawHttp.send(port, "POST", company.path("/groups"), Map.of("Cookie",
                    session(owner) + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf, "Content-Type", "application/json",
                    "Accept", "application/json"), concat("{\"name\":\"a".getBytes(StandardCharsets.US_ASCII), overlong,
                            "b\",\"memberIds\":[]}".getBytes(StandardCharsets.US_ASCII)));
            assertRefused(answer.status(), answer.header("Content-Type"), answer.body(),
                    "overlong UTF-8 of " + overlong.length + " bytes");
        }
        // A charset whose bytes of the escape are not ASCII's: the converter reads the body as its text.
        Charset ebcdic = Charset.forName("IBM037");
        RawHttp.Answer ibm037 = RawHttp.send(port, "POST", company.path("/groups"), Map.of("Cookie", session(owner)
                + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf, "Content-Type", "application/json; charset=IBM037",
                "Accept", "application/json"), "{\"name\":\"a\\u0000b\",\"memberIds\":[]}".getBytes(ebcdic));
        assertRefused(ibm037.status(), ibm037.header("Content-Type"), ibm037.body(), "IBM037");
        assertThat(output.getOut().substring(before)).doesNotContain("Unhandled exception");
        // Without U+0000, a body in that charset is read as before.
        String ebcdicName = unique("ebcdic");
        RawHttp.Answer ibm037Plain = RawHttp.send(port, "POST", company.path("/groups"), Map.of("Cookie",
                session(owner) + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf, "Content-Type",
                "application/json; charset=IBM037", "Accept", "application/json"),
                ("{\"name\":\"" + ebcdicName + "\",\"memberIds\":[]}").getBytes(ebcdic));
        assertThat(ibm037Plain.status()).as("%s", ibm037Plain.body()).isEqualTo(201);
        assertThat(JSON.readTree(ibm037Plain.body()).get("name").asString()).isEqualTo(ebcdicName);

        // A backslash before u0000 is text, not U+0000: the group is created.
        String name = unique("g") + "\\\\u0000";
        ApiResponse escaped = owner.post(company.path("/groups"), "{\"name\":\"" + name + "\",\"memberIds\":[]}");
        assertThat(escaped.status()).as("%s", escaped).isEqualTo(201);
        assertThat(escaped.json().get("name").asString()).endsWith("\\u0000");
    }

    @Test
    void aJsonBodyWithNulThatItsEndpointReadsItselfIsRefused(CapturedOutput output) {
        int before = output.getOut().length();
        // The chats' endpoints and the Gmail add-on's read the body raw, before they look anything up or check a
        // signature, and anyone may call them: no converter refuses the body for them.
        String slack = "/api/chat/slack/" + UUID.randomUUID();
        for (String path : List.of("/api/chat/teams/x/messages", slack + "/events", slack + "/interactions",
                "/api/gmail/" + UUID.randomUUID() + "/home")) {
            assertRefused(client().post(path, "{\"text\":\"a\\u0000b\"}"), path);
            RawHttp.Answer raw = RawHttp.send(port, "POST", path, Map.of("Content-Type", "application/json",
                    "Accept", "application/json"), "{\"text\":\"a\u0000b\"}".getBytes(StandardCharsets.UTF_8));
            assertRefused(raw.status(), raw.header("Content-Type"), raw.body(), path + " with a raw NUL byte");
        }
        assertThat(output.getOut().substring(before)).doesNotContain("Unhandled exception");
        // Without U+0000 they answer as before: an unknown system is not found.
        assertThat(client().post("/api/chat/teams/x/messages", "{\"text\":\"ab\"}").status()).isEqualTo(404);
    }

    @Test
    void aFormParameterWithNulIsRefused() {
        // The authorization server's token endpoint looks the refresh token up in SQL.
        RawHttp.Answer answer = RawHttp.send(port, "POST", "/oauth2/token",
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"),
                "grant_type=refresh_token&client_id=mcp-inspector&refresh_token=%00".getBytes(StandardCharsets.US_ASCII));
        assertRefused(answer.status(), answer.header("Content-Type"), answer.body(), "refresh_token");
    }

    @Test
    void aPartsNameOrFieldWithNulIsRefused(CapturedOutput output) {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiResponse agent = member.post(company.path("/agents"), Map.of("name", unique("Agent"), "runtimes",
                List.of("CLAUDE_CODE"), "systems", List.of()));
        UUID conversationId = UUID.randomUUID();
        TestConversations.bare(company.id(), conversationId, "conv-" + conversationId.toString().substring(0, 8),
                UUID.fromString(agent.json().get("id").asString()),
                memberPrincipal(company, company.userId("MEMBER")), "CLAUDE_CODE");
        String files = company.path("/conversations/" + conversationId + "/attachments");
        int before = output.getOut().length();
        // A raw NUL in the file name, and %00 in the RFC 5987 form that Spring MVC decodes.
        for (String disposition : List.of("filename=\"a\u0000b.txt\"", "filename*=UTF-8''a%00b.txt",
                "filename=\"ok.txt\"; filename*=UTF-8''a%00c.txt")) {
            String boundary = "b" + UUID.randomUUID();
            byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; " + disposition
                    + "\r\nContent-Type: text/plain\r\n\r\nhello\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
            String xsrf = member.cookie("XSRF-TOKEN").orElseThrow().getValue();
            RawHttp.Answer answer = RawHttp.send(port, "POST", files, Map.of("Cookie", session(member)
                    + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf, "Content-Type",
                    "multipart/form-data; boundary=" + boundary, "Accept", "application/json"), body);
            assertRefused(answer.status(), answer.header("Content-Type"), answer.body(), disposition);
        }
        // A body Tomcat cannot parse at all (no part header ends): 400 as well, not an unhandled MultipartException.
        String boundary = "b" + UUID.randomUUID();
        String xsrf = member.cookie("XSRF-TOKEN").orElseThrow().getValue();
        RawHttp.Answer broken = RawHttp.send(port, "POST", files, Map.of("Cookie", session(member)
                + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf, "Content-Type",
                "multipart/form-data; boundary=" + boundary, "Accept", "application/json"),
                ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"").getBytes(StandardCharsets.US_ASCII));
        assertThat(broken.status()).as("%s", broken).isEqualTo(400);
        assertThat(JSON.readTree(broken.body()).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        ApiResponse note = member.upload(files, new ApiClient.Multipart("note.txt", "text/plain",
                "x".getBytes(StandardCharsets.UTF_8), Map.of("note", "a\u0000b")));
        assertRefused(note.status(), note.contentType(), note.body(), "note");
        assertThat(output.getOut().substring(before)).doesNotContain("Unhandled exception");
        // A file's content is bytes, not text: NUL in it is taken.
        ApiResponse binary = member.upload(files, new ApiClient.Multipart("binary.bin", "application/octet-stream",
                new byte[] { 0, 1, 0, 2 }));
        assertThat(binary.status()).as("%s", binary).isEqualTo(201);
    }

    @Test
    void theMcpEndpointRefusesNulInAMessage() {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiResponse token = member.post("/api/me/tokens", Map.of("currentPassword", PASSWORD, "name", "nul",
                "scope", "WRITE"));
        assertThat(token.status()).as("%s", token).isEqualTo(201);
        ApiResponse answer = client().send("POST", company.path("/mcp"),
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"list_agents\","
                        + "\"arguments\":{\"cursor\":\"a\\u0000b\"}}}",
                Map.of("Authorization", "Bearer " + token.json().get("token").asString(), "Accept",
                        "application/json, text/event-stream"));
        assertThat(answer.status()).as("%s", answer).isEqualTo(400);
        JsonNode error = answer.json().get("error");
        assertThat(error.get("data").get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.get("message").asString()).isEqualTo(NulCharacterException.DETAIL);
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    @Test
    void theWebhookInboxAndTheModelRelayKeepTheirOwnRulesForTheirBodies() {
        String nul = "{\"text\":\"a\\u0000b\"}";
        // The inbox answers an unknown system 404 before it reads the body; the relay checks its token first.
        ApiResponse hook = client().post("/api/hooks/" + UUID.randomUUID(), nul);
        assertThat(hook.status()).as("%s", hook).isEqualTo(404);
        ApiResponse relay = client().post("/api/runner/v1/model/anthropic/v1/messages", nul);
        assertThat(relay.status()).as("%s", relay).isEqualTo(401);
    }

    private static void assertRefused(ApiResponse answer, String what) {
        assertRefused(answer.status(), answer.contentType(), answer.body(), what);
    }

    private static void assertRefused(int status, String contentType, String body, String what) {
        assertThat(status).as("%s: %s", what, body).isEqualTo(400);
        assertThat(contentType).as(what).startsWith("application/problem+json");
        JsonNode problem = JSON.readTree(body);
        assertThat(problem.get("code").asString()).as(what).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("detail").asString()).as(what).isEqualTo(NulCharacterException.DETAIL);
    }

    private static String session(ApiClient client) {
        return "TINFIRM_SESSION=" + client.cookie("TINFIRM_SESSION").orElseThrow().getValue();
    }
}
