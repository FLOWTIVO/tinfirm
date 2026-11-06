package com.flowtivo.tinfirm.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeGoogle;
import com.flowtivo.tinfirm.support.FakeGoogle.Account;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Gmail add-on (ADR-0083) against the running server, with Google's keys and the Gmail API stubbed
 * ({@link FakeGoogle}): Google's requests verified, a Google account linked through its sign-in link, a conversation
 * started about the open email as the person's own (origin {@code CLIENT}, the audit naming the link), found again
 * from the email and from its thread, answered, and the link's end; the email's attachments as files of its
 * conversation (ADR-0084), and an answer saved as a draft reply in its thread; the deployment file. Under an Enterprise licence:
 * the add-on is Pro's and Enterprise's (ADR-0077; Community's refusal is {@code EditionGatesIntegrationTest}'s).
 */
@ExtendWith(EnterpriseLicence.class)
class GmailAddOnIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Duration WAIT = Duration.ofSeconds(20);

    /** The public URL of the tests, the audience of the deployment's tokens. */
    private static final String PUBLIC = "http://localhost:8080";

    private final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    private RunnerConnections connections;

    private record Bench(TestCompany company, ApiClient owner, ApiClient member, UUID memberPrincipal) {

        UUID id() {
            return company.id();
        }
    }

    /** A company whose MEMBER has a usable key of the bundled gateway. */
    private Bench bench() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        ModelGateways.configureBundled(owner, company);
        ModelGateways.awaitKey(litellm, company, member);
        ModelGateways.awaitStatus(owner, company, member, "OK");
        return new Bench(company, owner, loginAs(company.email("MEMBER")), member);
    }

    private FakeRunner companyHost(Bench bench) {
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.helloV2AndWelcome(List.of(), List.of(), "0.2.0", 4);
        await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
        return runner;
    }

    /** An agent the member creates and may use, with a model named for them. */
    private UUID agent(Bench bench, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique(name));
        body.put("runtimes", List.of("CLAUDE_CODE"));
        body.put("instructions", "You help.");
        body.put("systems", List.of());
        body.put("repositories", List.of());
        body.put("shareGroupIds", List.of());
        ApiResponse agent = bench.member().post(bench.company().path("/agents"), body);
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        UUID agentId = UUID.fromString(agent.json().get("id").asString());
        TestSettings.nameModel(bench.id(), agentId, bench.memberPrincipal());
        return agentId;
    }

    // ---- Google's requests ---------------------------------------------------------------------------------------------

    private static String endpoint(UUID companyId, String trigger) {
        return PUBLIC + "/api/gmail/" + companyId + "/" + trigger;
    }

    /** An event as Google sends it, for the account, about the message (or none), with a button's parameters. */
    private static Map<String, Object> event(Account account, FakeGoogle.@Nullable Message message,
            Map<String, String> parameters, Map<String, String> inputs, @Nullable String userToken) {
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("hostApp", "GMAIL");
        common.put("platform", "WEB");
        if (!parameters.isEmpty()) {
            common.put("parameters", parameters);
        }
        if (!inputs.isEmpty()) {
            Map<String, Object> formInputs = new LinkedHashMap<>();
            inputs.forEach((name, value) -> formInputs.put(name, Map.of("stringInputs", Map.of("value",
                    List.of(value)))));
            common.put("formInputs", formInputs);
        }
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("userIdToken", userToken != null ? userToken : FakeGoogle.userToken(account));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("commonEventObject", common);
        if (message != null) {
            authorization.put("userOAuthToken", message.userOAuthToken());
            event.put("gmail", Map.of("messageId", message.id(), "threadId", message.threadId(), "accessToken",
                    message.messageToken()));
        }
        event.put("authorizationEventObject", authorization);
        return event;
    }

    private ApiResponse google(UUID companyId, String trigger, Map<String, Object> event, @Nullable String token) {
        Map<String, String> headers = token != null ? Map.of("Authorization", "Bearer " + token) : Map.of();
        return client().send("POST", "/api/gmail/" + companyId + "/" + trigger, event, headers);
    }

    private ApiResponse open(UUID companyId, Account account, FakeGoogle.Message message) {
        return google(companyId, "open", event(account, message, Map.of(), Map.of(), null),
                FakeGoogle.systemToken(endpoint(companyId, "open")));
    }

    private ApiResponse act(UUID companyId, Account account, FakeGoogle.@Nullable Message message,
            Map<String, String> parameters, Map<String, String> inputs) {
        return google(companyId, "act", event(account, message, parameters, inputs, null),
                FakeGoogle.systemToken(endpoint(companyId, "act")));
    }

    /** The card a trigger pushed, or a button put in place. */
    private static JsonNode card(ApiResponse response) {
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        JsonNode navigation = response.json().get("action").get("navigations").get(0);
        return navigation.has("pushCard") ? navigation.get("pushCard") : navigation.get("updateCard");
    }

    /** Every text the card shows, its buttons' and inputs' included, in one string. */
    private static String texts(JsonNode card) {
        return String.join("\n", card.findValuesAsString("text")) + "\n" + String.join("\n",
                card.findValuesAsString("title")) + "\n" + String.join("\n", card.findValuesAsString("subtitle"))
                + "\n" + String.join("\n", card.findValuesAsString("label"));
    }

    /** The start card's own id, which each of its starts carries. */
    private static String cardId(JsonNode card) {
        return card.findValues("parameters").stream()
            .flatMap(JsonNode::valueStream)
            .filter(parameter -> "card".equals(parameter.path("key").asString()))
            .map(parameter -> parameter.get("value").asString())
            .findFirst()
            .orElseThrow();
    }

    private static int conversations(Bench bench) {
        return bench.member().get(bench.company().path("/conversations")).json().get("items").size();
    }

    /** The sign-in link the card opens, and its token. */
    private static String signInToken(JsonNode card, UUID companyId) {
        String url = card.findValues("openLink").getFirst().get("url").asString();
        assertThat(url).startsWith(PUBLIC + "/gmail/link/" + companyId + "#");
        assertThat(card.findValues("openLink").getFirst().get("onClose").asString()).isEqualTo("RELOAD");
        return url.substring(url.indexOf('#') + 1);
    }

    private void link(Bench bench, Account account, FakeGoogle.Message message) {
        String token = signInToken(card(open(bench.id(), account, message)), bench.id());
        ApiResponse confirmed = bench.member().post(bench.company().path("/gmail/links/confirm"),
                Map.of("token", token));
        assertThat(confirmed.status()).as("%s", confirmed).isEqualTo(200);
    }

    // ---- tests ---------------------------------------------------------------------------------------------------------

    @Test
    void aGoogleAccountLinkedToAMemberAsksAnAgentAboutTheOpenEmailAsThem() throws Exception {
        Bench bench = bench();
        UUID agentId = agent(bench, "Support");
        Account account = Account.create();
        FakeGoogle.Message email = FakeGoogle.message("thread-" + UUID.randomUUID(), "Checkout fails",
                "Ann Customer <ann@customer.test>", "Hello,\nthe checkout fails with error 500 since Monday.");

        // An unlinked account gets the sign-in card; its link names the account and works once.
        JsonNode signIn = card(open(bench.id(), account, email));
        assertThat(texts(signIn)).contains("Sign in to Tinfirm").contains(account.email());
        String token = signInToken(signIn, bench.id());
        ApiResponse read = bench.member().post(bench.company().path("/gmail/links/read"), Map.of("token", token));
        assertThat(read.status()).as("%s", read).isEqualTo(200);
        assertThat(read.json().get("googleEmail").asString()).isEqualTo(account.email());
        assertThat(read.json().get("ownerNotice").asString()).startsWith("Conversations you start through the Gmail "
                + "add-on are personal");
        ApiResponse confirmed = bench.member().post(bench.company().path("/gmail/links/confirm"),
                Map.of("token", token));
        assertThat(confirmed.status()).as("%s", confirmed).isEqualTo(200);
        String linkId = confirmed.json().get("id").asString();
        assertThat(bench.member().post(bench.company().path("/gmail/links/confirm"), Map.of("token", token))
            .problemCode()).isEqualTo("GMAIL_LINK_USED");
        assertThat(auditEvents(bench.owner(), bench.company(), "gmail.linked")).singleElement()
            .satisfies(event -> {
                assertThat(event.get("details").get("googleSubject").asString()).isEqualTo(account.subject());
                assertThat(event.get("details").has("googleEmail")).isFalse();
            });

        // Linked, the open email offers the member's agents and the actions; opening it read nothing from Gmail.
        JsonNode start = card(open(bench.id(), account, email));
        assertThat(texts(start)).contains("Summarise").contains("Investigate").contains("Draft a reply")
            .contains("Signed in as User " + bench.company().email("MEMBER"));
        assertThat(start.findValues("value").stream().map(JsonNode::asString)).contains(agentId.toString());
        FakeGoogle.server().verify(0, getRequestedFor(urlPathEqualTo("/gmail/v1/users/me/messages/" + email.id())));

        try (FakeRunner runner = companyHost(bench)) {
            // Summarise: the email is read with the event's tokens, and the conversation is the member's own.
            JsonNode conversationCard = card(act(bench.id(), account, email, Map.of("do", "start", "action",
                    "SUMMARISE"), Map.of("agent", agentId.toString())));
            assertThat(texts(conversationCard)).contains("Summarise").contains("With this email’s text")
                .contains("Refresh").contains("Open in Tinfirm");
            FakeGoogle.server().verify(1, getRequestedFor(urlPathEqualTo("/gmail/v1/users/me/messages/"
                    + email.id())));
            runner.expect("run.assign");
            JsonNode conversations = bench.member().get(bench.company().path("/conversations")).json().get("items");
            assertThat(conversations).hasSize(1);
            String conversationId = conversations.get(0).get("id").asString();
            JsonNode conversation = bench.member().get(bench.company().path("/conversations/" + conversationId))
                .json();
            assertThat(conversation.get("origin").asString()).isEqualTo("CLIENT");
            assertThat(conversation.get("personPrincipalId").asString()).isEqualTo(bench.memberPrincipal()
                .toString());
            String first = conversation.get("messages").get(0).get("body").asString();
            assertThat(first).startsWith("Summarise this email for me")
                .contains("The email below is one I opened in Gmail.")
                .contains("Subject: Checkout fails")
                .contains("From: Ann Customer <ann@customer.test>")
                .contains("the checkout fails with error 500 since Monday.")
                .doesNotContain("the attachment's text");
            assertThat(auditEvents(bench.owner(), bench.company(), "conversation.started")).singleElement()
                .satisfies(event -> {
                    assertThat(event.get("details").get("origin").asString()).isEqualTo("CLIENT");
                    assertThat(event.get("details").get("gmailLinkId").asString()).isEqualTo(linkId);
                });

            // Opening the email again shows its conversation; another message of its thread does too.
            assertThat(texts(card(open(bench.id(), account, email)))).contains("With this email’s text")
                .doesNotContain("About an earlier email");
            FakeGoogle.Message reply = FakeGoogle.message(email.threadId(), "Re: Checkout fails",
                    "Ann Customer <ann@customer.test>", "Any news?");
            assertThat(texts(card(open(bench.id(), account, reply)))).contains("About an earlier email of this "
                    + "thread");

            // A follow-up from the card is the member's message in the conversation.
            JsonNode followed = card(act(bench.id(), account, email, Map.of("do", "send", "conversationId",
                    conversationId), Map.of("reply", "Also check the logs of Monday.")));
            assertThat(texts(followed)).contains("Also check the logs of Monday.");
            assertThat(bench.member().get(bench.company().path("/conversations/" + conversationId)).json()
                .get("messages").findValuesAsString("body")).contains("Also check the logs of Monday.");

            // Ask something else: the email starts afresh.
            JsonNode fresh = card(act(bench.id(), account, email, Map.of("do", "new"), Map.of()));
            assertThat(texts(fresh)).contains("Summarise").doesNotContain("With this email’s text");

            // A click sent again (Google's retry, a double click) starts one conversation, whenever it comes; a new
            // card's click starts another, the same action in the same minute included.
            Map<String, String> click = Map.of("do", "start", "action", "SUMMARISE", "card", cardId(fresh));
            card(act(bench.id(), account, email, click, Map.of("agent", agentId.toString())));
            card(act(bench.id(), account, email, click, Map.of("agent", agentId.toString())));
            assertThat(conversations(bench)).isEqualTo(2);
            JsonNode another = card(act(bench.id(), account, email, Map.of("do", "new"), Map.of()));
            assertThat(cardId(another)).isNotEqualTo(cardId(fresh));
            card(act(bench.id(), account, email, Map.of("do", "start", "action", "SUMMARISE", "card",
                    cardId(another)), Map.of("agent", agentId.toString())));
            assertThat(conversations(bench)).isEqualTo(3);
        }

        // The member's links, and the add-on's Sign out.
        JsonNode links = bench.member().get("/api/me/gmail/links").json().get("items");
        assertThat(links.findValuesAsString("id")).contains(linkId);
        JsonNode signedOut = card(act(bench.id(), account, email, Map.of("do", "signout"), Map.of()));
        assertThat(texts(signedOut)).contains("Sign in to Tinfirm");
        assertThat(bench.member().get("/api/me/gmail/links").json().get("items").findValuesAsString("id"))
            .doesNotContain(linkId);
        assertThat(auditEvents(bench.owner(), bench.company(), "gmail.unlinked")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("reason").asString()).isEqualTo("SIGN_OUT"));
    }

    @Test
    void theEmailsAttachmentsAreFilesOfItsConversationAndAnAnswerIsADraftReply() throws Exception {
        Bench bench = bench();
        UUID agentId = agent(bench, "Support");
        Account account = Account.create();
        FakeGoogle.Message email = FakeGoogle.message("thread-" + UUID.randomUUID(), "Invoice wrong",
                "Ann Customer <ann@customer.test>", "Hello,\nthe invoices attached are wrong.", List.of(
                        new FakeGoogle.File("invoice.pdf", "application/pdf", "%PDF-1.4 one".getBytes()),
                        new FakeGoogle.File("invoice.pdf", "application/pdf", "%PDF-1.4 two".getBytes()),
                        FakeGoogle.File.text("deploy.yaml", "key: ghp_" + "Ab1".repeat(12))));
        link(bench, account, email);
        assertThat(texts(card(open(bench.id(), account, email)))).contains("Send its attachments too");

        try (FakeRunner runner = companyHost(bench)) {
            card(act(bench.id(), account, email, Map.of("do", "start", "action", "TRIAGE"),
                    Map.of("agent", agentId.toString(), "attachments", "yes")));
            JsonNode assign = runner.expect("run.assign");
            String conversationId = bench.member().get(bench.company().path("/conversations")).json().get("items")
                .get(0).get("id").asString();

            // Both invoices, the second renamed; the credential left out, with why; the body's image no attachment.
            JsonNode files = bench.member().get(bench.company().path("/conversations/" + conversationId
                    + "/attachments")).json();
            assertThat(files.findValuesAsString("fileName")).containsExactly("invoice.pdf", "invoice (2).pdf");
            String first = bench.member().get(bench.company().path("/conversations/" + conversationId)).json()
                .get("messages").get(0).get("body").asString();
            assertThat(first).contains("Attachments (files of this conversation): invoice.pdf; invoice (2).pdf\n")
                .contains("Attachments left out: deploy.yaml (it holds a credential)\n")
                .doesNotContain("logo.png")
                .doesNotContain("ghp_");
            assertThat(auditEvents(bench.owner(), bench.company(), "attachment.uploaded")).hasSize(2);

            // The turn's answer: Use as reply saves it as a draft in the thread, to the sender, and links to it.
            UUID runId = UUID.fromString(assign.get("runId").asString());
            runner.accepted(runId, assign.get("epoch").asLong());
            runner.send(Map.of("type", "run.finished", "runId", runId.toString(), "epoch", assign.get("epoch")
                .asLong(), "outcome", "SUCCEEDED", "lastSeq", 0, "summary", "Dear Ann,\n\nthe **invoices** are "
                        + "fixed:\n\n- one\n- two", "sessionId", "session-1", "git", List.of()));
            await().atMost(WAIT).until(() -> texts(card(act(bench.id(), account, email, Map.of("do", "refresh",
                    "conversationId", conversationId), Map.of()))).contains("Use as reply"));
            JsonNode drafted = card(act(bench.id(), account, email, Map.of("do", "draft", "conversationId",
                    conversationId, "runId", runId.toString()), Map.of()));
            assertThat(texts(drafted)).contains("The answer is a draft reply in this thread");
            assertThat(drafted.findValues("openLink").stream().map(link -> link.get("url").asString()))
                .contains("https://mail.google.com/mail/?authuser=" + java.net.URLEncoder.encode(account.email(),
                        java.nio.charset.StandardCharsets.UTF_8) + "#drafts?compose=d" + email.id());
            assertThat(FakeGoogle.drafts(email)).singleElement().satisfies(draft -> {
                assertThat(draft.threadId()).isEqualTo(email.threadId());
                assertThat(draft.raw()).contains("To: \"Ann Customer\" <ann@customer.test>\r\n")
                    .contains("Subject: Re: Invoice wrong\r\n")
                    .contains("In-Reply-To: <" + email.id() + "@mail.customer.test>\r\n")
                    .contains("References: <first@mail.customer.test> <" + email.id() + "@mail.customer.test>\r\n")
                    .contains("Content-Type: multipart/alternative;");
                String html = new String(java.util.Base64.getMimeDecoder().decode(draft.raw()
                    .split("Content-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")[1]
                    .split("\r\n--")[0]), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(html).isEqualTo("<div><p>Dear Ann,</p><p>the <b>invoices</b> are fixed:</p>"
                        + "<ul><li>one</li><li>two</li></ul></div>\n");
            });
            // An answer that is no turn's is no reply.
            assertThat(texts(card(act(bench.id(), account, email, Map.of("do", "draft", "conversationId",
                    conversationId, "runId", UUID.randomUUID().toString()), Map.of()))))
                .contains("This answer cannot be a reply.");
            assertThat(FakeGoogle.drafts(email)).hasSize(1);
        }
    }

    /**
     * Review finding F18: a click sent again (Google's retry, a double click) with attachments read, scanned and held
     * them again before the replay answered the first conversation, and the new holds sat on the person's limits for
     * an hour; enough retries changed the request's "left out" line, and so the key's hash. The start now asks for
     * its conversation before it reads the email (ADR-0084, decision 9), and a start that takes no files, replayed or
     * refused, releases those it held.
     */
    @Test
    void theSameClickWithAttachmentsSentAgainReadsAndHoldsNothingTwiceAndARefusedStartHoldsNothing()
            throws Exception {
        Bench bench = bench();
        UUID agentId = agent(bench, "Support");
        Account account = Account.create();
        FakeGoogle.Message email = FakeGoogle.message("thread-" + UUID.randomUUID(), "Invoice wrong",
                "Ann Customer <ann@customer.test>", "Hello,\nthe invoice attached is wrong.", List.of(
                        new FakeGoogle.File("invoice.pdf", "application/pdf", "%PDF-1.4 one".getBytes())));
        link(bench, account, email);
        String attachment = "/gmail/v1/users/me/messages/" + email.id() + "/attachments/att-0-" + email.id();
        String message = "/gmail/v1/users/me/messages/" + email.id();
        JsonNode start = card(open(bench.id(), account, email));
        Map<String, String> click = Map.of("do", "start", "action", "TRIAGE", "card", cardId(start));
        Map<String, String> inputs = Map.of("agent", agentId.toString(), "attachments", "yes");

        try (FakeRunner runner = companyHost(bench)) {
            JsonNode first = card(act(bench.id(), account, email, click, inputs));
            runner.expect("run.assign");
            JsonNode again = card(act(bench.id(), account, email, click, inputs));
            assertThat(texts(again)).contains("With this email’s text").contains("Refresh");
            assertThat(again.get("header")).isEqualTo(first.get("header"));
            assertThat(conversations(bench)).isEqualTo(1);
            String conversationId = bench.member().get(bench.company().path("/conversations")).json().get("items")
                .get(0).get("id").asString();
            // The email and its attachment were read once; the conversation has the file once; nothing is held.
            FakeGoogle.server().verify(1, getRequestedFor(urlPathEqualTo(message)));
            FakeGoogle.server().verify(1, getRequestedFor(urlPathEqualTo(attachment)));
            assertThat(bench.member().get(bench.company().path("/conversations/" + conversationId + "/attachments"))
                .json().findValuesAsString("fileName")).containsExactly("invoice.pdf");
            assertThat(auditEvents(bench.owner(), bench.company(), "attachment.uploaded")).hasSize(1);
            try (TenantSql sql = TenantSql.in(bench.id())) {
                assertThat(sql.count("SELECT count(*) FROM staged_file WHERE principal_id = ?",
                        bench.memberPrincipal())).isZero();
            }

            // A start the core refuses after the attachment was held (an agent that is not the member's): the start
            // card says so, and the held file is released rather than left on the member's limits.
            JsonNode refused = card(act(bench.id(), account, email, Map.of("do", "start", "action", "TRIAGE",
                    "card", cardId(card(act(bench.id(), account, email, Map.of("do", "new"), Map.of())))),
                    Map.of("agent", UUID.randomUUID().toString(), "attachments", "yes")));
            assertThat(texts(refused)).contains("Summarise").doesNotContain("With this email’s text");
            FakeGoogle.server().verify(2, getRequestedFor(urlPathEqualTo(attachment)));
            assertThat(conversations(bench)).isEqualTo(1);
            try (TenantSql sql = TenantSql.in(bench.id())) {
                assertThat(sql.count("SELECT count(*) FROM staged_file WHERE principal_id = ?",
                        bench.memberPrincipal())).isZero();
            }
        }
    }

    @Test
    void googlesRequestsAreVerifiedBeforeAnythingIsRead() {
        TestCompany company = companyWithRoles();
        Account account = Account.create();
        Map<String, Object> event = event(account, null, Map.of(), Map.of(), null);
        String home = endpoint(company.id(), "home");

        assertThat(google(company.id(), "home", event, FakeGoogle.systemToken(home)).status()).isEqualTo(200);
        // No token of the deployment, one for another endpoint, another project's, an unknown key, expired.
        assertThat(google(company.id(), "home", event, null).status()).isEqualTo(401);
        assertThat(google(company.id(), "home", event, FakeGoogle.systemToken(endpoint(company.id(), "open")))
            .status()).isEqualTo(401);
        assertThat(google(company.id(), "home", event, FakeGoogle.systemToken(home,
                "service-999@gcp-sa-gsuiteaddons.iam.gserviceaccount.com", FakeGoogle.UNKNOWN_KEY,
                Duration.ofHours(1))).status()).isEqualTo(401);
        assertThat(google(company.id(), "home", event, FakeGoogle.systemToken(home, FakeGoogle.SERVICE_ACCOUNT,
                FakeGoogle.UNKNOWN_KEY, Duration.ofHours(1))).status()).isEqualTo(401);
        assertThat(google(company.id(), "home", event, FakeGoogle.systemToken(home, FakeGoogle.SERVICE_ACCOUNT,
                signingKey(), Duration.ofMinutes(-10))).status()).isEqualTo(401);
        // The person's token for another OAuth client, or none.
        assertThat(google(company.id(), "home", event(account, null, Map.of(), Map.of(),
                FakeGoogle.userToken(account, "another-client", signingKey())), FakeGoogle.systemToken(home))
            .status()).isEqualTo(401);
        Map<String, Object> anonymous = new LinkedHashMap<>(event);
        anonymous.put("authorizationEventObject", Map.of());
        assertThat(google(company.id(), "home", anonymous, FakeGoogle.systemToken(home)).status()).isEqualTo(401);
        // The token may stand in the event instead of the header.
        Map<String, Object> inBody = new LinkedHashMap<>(event);
        inBody.put("authorizationEventObject", Map.of("userIdToken", FakeGoogle.userToken(account), "systemIdToken",
                FakeGoogle.systemToken(home)));
        assertThat(google(company.id(), "home", inBody, null).status()).isEqualTo(200);
        // No such trigger, no such company, not JSON.
        assertThat(google(company.id(), "other", event, FakeGoogle.systemToken(endpoint(company.id(), "other")))
            .status()).isEqualTo(404);
        UUID unknown = UUID.randomUUID();
        assertThat(google(unknown, "home", event, FakeGoogle.systemToken(endpoint(unknown, "home"))).status())
            .isEqualTo(404);
        assertThat(client().send("POST", "/api/gmail/" + company.id() + "/home", "not json",
                Map.of("Authorization", "Bearer " + FakeGoogle.systemToken(home))).status()).isEqualTo(400);
        // At most 256 KiB, counted in bytes and never read further: 150,000 characters of "é" are 300,000 bytes.
        String large = "{\"padding\":\"" + "é".repeat(150_000) + "\"}";
        assertThat(client().send("POST", "/api/gmail/" + company.id() + "/home", large,
                Map.of("Authorization", "Bearer " + FakeGoogle.systemToken(home))).status()).isEqualTo(413);
    }

    @Test
    void aLinkIsForActiveMembersAndAnAccountIsOneMembers() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        Account account = Account.create();

        // Someone outside the company cannot read a link of it.
        String first = linkTokenFor(company.id(), account);
        String outsiderEmail = uniqueEmail("outsider");
        createUser(admin(), outsiderEmail, false);
        assertThat(loginAs(outsiderEmail).post(company.path("/gmail/links/read"), Map.of("token", first)).status())
            .isEqualTo(404);
        // A new offer replaces the account's unused link.
        String newer = linkTokenFor(company.id(), account);
        assertThat(member.post(company.path("/gmail/links/read"), Map.of("token", first)).status()).isEqualTo(404);
        assertThat(member.post(company.path("/gmail/links/confirm"), Map.of("token", newer)).status())
            .isEqualTo(200);

        // The account is the member's: another member's confirmation of a link for it is refused, and used up.
        String forAdmin = "test-link-token-" + UUID.randomUUID();
        String expired = "test-link-token-" + UUID.randomUUID();
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update("INSERT INTO gmail_link_request (token_hash, company_id, google_subject, google_email,"
                    + " created_at, expires_at) VALUES (?, ?, ?, ?, now(), now() + interval '10 minutes')",
                    sha256(forAdmin), company.id(), account.subject(), account.email());
            sql.update("INSERT INTO gmail_link_request (token_hash, company_id, google_subject, google_email,"
                    + " created_at, expires_at) VALUES (?, ?, ?, ?, now() - interval '1 hour',"
                    + " now() - interval '1 minute')", sha256(expired), company.id(), "another-subject",
                    "another@gmail.test");
            sql.commit();
        }
        assertThat(admin.post(company.path("/gmail/links/confirm"), Map.of("token", forAdmin)).problemCode())
            .isEqualTo("IDENTITY_IN_USE");
        assertThat(admin.post(company.path("/gmail/links/read"), Map.of("token", forAdmin)).problemCode())
            .isEqualTo("GMAIL_LINK_USED");
        assertThat(admin.post(company.path("/gmail/links/read"), Map.of("token", expired)).problemCode())
            .isEqualTo("GMAIL_LINK_EXPIRED");

        // Disconnect: only one's own link.
        String linkId = member.get("/api/me/gmail/links").json().get("items").get(0).get("id").asString();
        assertThat(admin.delete(company.path("/gmail/links/" + linkId)).status()).isEqualTo(404);
        assertThat(member.delete(company.path("/gmail/links/" + linkId)).status()).isEqualTo(204);
        assertThat(member.get("/api/me/gmail/links").json().get("items")).isEmpty();
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, "gmail.unlinked")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("reason").asString()).isEqualTo("DISCONNECTED"));
        // The account gets the sign-in card again.
        assertThat(texts(card(google(company.id(), "home", event(account, null, Map.of(), Map.of(), null),
                FakeGoogle.systemToken(endpoint(company.id(), "home")))))).contains("Sign in to Tinfirm");
    }

    /** A sign-in link offered to the account through Google's homepage request. */
    private String linkTokenFor(UUID companyId, Account account) {
        return signInToken(card(google(companyId, "home", event(account, null, Map.of(), Map.of(), null),
                FakeGoogle.systemToken(endpoint(companyId, "home")))), companyId);
    }

    private static String sha256(String token) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void everyMemberDownloadsTheDeploymentOfTheirCompany() throws Exception {
        TestCompany company = companyWithRoles();
        ApiResponse response = loginAs(company.email("MEMBER")).get(company.path("/gmail/deployment"));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.json().get("fileName").asString()).isEqualTo("tinfirm-gmail-deployment.json");
        assertThat(response.json().get("configured").asBoolean()).isTrue();
        JsonNode deployment = JSON.readTree(response.json().get("deployment").asString());
        assertThat(deployment.get("oauthScopes").valueStream().map(JsonNode::asString)).containsExactly(
                "https://www.googleapis.com/auth/gmail.addons.execute",
                "https://www.googleapis.com/auth/gmail.addons.current.message.readonly",
                "https://www.googleapis.com/auth/gmail.addons.current.action.compose",
                "https://www.googleapis.com/auth/userinfo.email");
        JsonNode common = deployment.get("addOns").get("common");
        assertThat(common.get("homepageTrigger").get("runFunction").asString())
            .isEqualTo(endpoint(company.id(), "home"));
        assertThat(common.get("openLinkUrlPrefixes").valueStream().map(JsonNode::asString))
            .containsExactly(PUBLIC + "/", "https://mail.google.com/");
        assertThat(deployment.get("addOns").get("gmail").get("contextualTriggers").get(0).get("onTriggerFunction")
            .asString()).isEqualTo(endpoint(company.id(), "open"));
        String outsiderEmail = uniqueEmail("outsider");
        createUser(admin(), outsiderEmail, false);
        assertThat(loginAs(outsiderEmail).get(company.path("/gmail/deployment")).status()).isEqualTo(404);
    }

    private static com.nimbusds.jose.jwk.RSAKey signingKey() {
        return FakeGoogle.signingKey();
    }
}
