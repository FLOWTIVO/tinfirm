package com.flowtivo.tinfirm.system;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.JiraCloudStub;
import com.flowtivo.tinfirm.support.JiraStub;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Jira's fixed tools through {@link SystemTools} against stubs of Jira Data Center (REST API v2, wiki markup, a
 * bearer token) and of a Jira Cloud site (REST API v3, the document format, {@code email:token} as Basic): each tool's
 * request and result, and the system's refusals: {@code 401} asks for the account to be connected again, {@code 403},
 * {@code 404} and the rate limit are results the agent sees. TEST FIXTURE ONLY: the tokens are test values.
 */
class JiraToolsTest extends ToolsTestSupport {

    private static final String TOKEN = "test-jira-token-0001";

    /** A Jira Cloud site's base URL is the site, without a path: a server of its own. */
    private static final WireMockServer CLOUD = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        CLOUD.start();
    }

    private static final String CLOUD_CREDENTIAL = "ana@example.test:test-cloud-token-0001";

    private static String issue(String key, String description, String comments) {
        return """
                {"id":"10001","key":"%s","fields":{"summary":"Checkout fails","description":%s,
                 "status":{"name":"In Progress"},"issuetype":{"name":"Bug"},
                 "assignee":{"name":"alice","displayName":"Alice Adams","accountId":"acc-alice"},
                 "reporter":{"name":"bob","displayName":"Bob Brown","accountId":"acc-bob"},
                 "labels":["web","checkout"],"created":"2026-10-01T10:00:00.000+0200",
                 "updated":"2026-10-02T10:00:00.000+0200","resolutiondate":null,"comment":%s}}"""
            .formatted(key, description, comments);
    }

    @Test
    void getReadsAnIssueWithItsCommentsAsMarkdown() {
        JiraStub.Instance jira = JiraStub.newInstance();
        Setup setup = setup("JIRA_DC", jira.baseUrl());
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.url("/rest/api/2/issue/SHOP-1")))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(issue("SHOP-1", "\"h2. Steps\\n*Pay* with {{VISA}}\"", """
                        {"total":1,"comments":[{"id":"200","author":{"name":"carol","displayName":"Carol"},
                         "body":"Seen on *prod*","created":"2026-10-02T09:00:00.000+0200"}]}"""))));

        ToolOutcome outcome = call(setup, token(TOKEN), "get", "{\"key\":\" SHOP-1 \"}");

        assertThat(text(outcome)).contains("# SHOP-1: Checkout fails", "Status: In Progress", "Type: Bug",
                "Labels: web, checkout", "Assignees: Alice Adams (alice)", "Author: Bob Brown (bob)",
                "Link: " + jira.baseUrl() + "/browse/SHOP-1", "## Steps", "**Pay** with `VISA`", "**Carol** · ",
                "> Seen on **prod**");
        assertThat(structured(outcome).get("key").asString()).isEqualTo("SHOP-1");
        assertThat(structured(outcome).get("comments")).hasSize(1);
        assertThat(done(outcome).result().isError()).isFalse();
        assertThat(done(outcome).written()).isNull();
        JiraStub.server().verify(getRequestedFor(urlPathEqualTo(jira.url("/rest/api/2/issue/SHOP-1")))
            .withQueryParam("fields", equalTo("summary,description,status,issuetype,assignee,reporter,labels,created,"
                    + "updated,comment,resolution,resolutiondate"))
            .withHeader("X-Atlassian-Token", equalTo("no-check")));
    }

    @Test
    void getReadsTheNewestTwentyCommentsWhenTheIssueCutThem() {
        JiraStub.Instance jira = JiraStub.newInstance();
        Setup setup = setup("JIRA_DC", jira.baseUrl());
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.url("/rest/api/2/issue/SHOP-2")))
            .willReturn(JiraStub.json(200, JSON.readTree(issue("SHOP-2", "null", """
                    {"total":45,"comments":[{"id":"1","body":"first","created":"2026-10-01T09:00:00.000+0200"}]}""")))));
        StringBuilder newest = new StringBuilder("{\"comments\":[");
        for (int id = 26; id <= 45; id++) {
            newest.append(id > 26 ? "," : "").append("{\"id\":\"").append(id).append("\",\"body\":\"comment ")
                .append(id).append("\",\"created\":\"2026-10-02T09:00:00.000+0200\"}");
        }
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.url("/rest/api/2/issue/SHOP-2/comment")))
            .withQueryParam("startAt", equalTo("25"))
            .withQueryParam("maxResults", equalTo("20"))
            .willReturn(aResponse().withStatus(200).withBody(newest.append("]}").toString())));

        ToolOutcome outcome = call(setup, token(TOKEN), "get", "{\"key\":\"SHOP-2\"}");

        assertThat(text(outcome)).contains("## Comments (the newest 20 of 45, oldest first)", "> comment 26",
                "> comment 45", "(no description)").doesNotContain("> first");
        assertThat(structured(outcome).get("commentTotal").asInt()).isEqualTo(45);
    }

    @Test
    void searchRunsTheJqlForAtMostTheLimitWithoutDescriptions() {
        JiraStub.Instance jira = JiraStub.newInstance();
        Setup setup = setup("JIRA_DC", jira.baseUrl());
        String jql = "project = SHOP AND status = \"In Progress\"";
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.url("/rest/api/2/search")))
            .withQueryParam("jql", equalTo(jql))
            .withQueryParam("maxResults", equalTo("5"))
            .willReturn(JiraStub.json(200, Map.of("startAt", 0, "total", 2, "issues", List.of(
                    JiraStub.issue("1", "SHOP-1", "Checkout fails", "", "alice", List.of("web"), "In Progress",
                            "2026-10-02T10:00:00.000+0200", List.of()),
                    JiraStub.issue("2", "SHOP-2", "Cart empty", "", null, List.of(), "Open",
                            "2026-10-02T11:00:00.000+0200", List.of()))))));

        ToolOutcome outcome = call(setup, token(TOKEN), "search", "{\"query\":" + JSON.writeValueAsString(jql)
                + ",\"limit\":5}");

        assertThat(text(outcome)).isEqualTo("""
                2 results

                SHOP-1 Checkout fails (In Progress; labels: web; assignees: Display alice)
                SHOP-2 Cart empty (Open; assignees: none)""");
        assertThat(structured(outcome).get("count").asInt()).isEqualTo(2);
        JiraStub.server().verify(getRequestedFor(urlPathEqualTo(jira.url("/rest/api/2/search")))
            .withQueryParam("fields", equalTo("summary,status,issuetype,assignee,reporter,labels,created,updated,"
                    + "resolutiondate")));
    }

    @Test
    void aCommentIsWikiMarkupWithTheAgentsLabelInFront() {
        JiraStub.Instance jira = JiraStub.newInstance();
        Setup setup = setup("JIRA_DC", jira.baseUrl());
        JiraStub.server().stubFor(post(urlEqualTo(jira.url("/rest/api/2/issue/SHOP-1/comment")))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .willReturn(JiraStub.json(201, Map.of("id", "10500"))));

        ToolOutcome outcome = call(setup, token(TOKEN), "comment", "{\"key\":\"SHOP-1\",\"body\":\"Fixed: the "
                + "**VAT** rule.\"}", "Analyst (Tinfirm agent) for Alice:");

        assertThat(text(outcome)).isEqualTo("Commented on SHOP-1 (comment 10500). Link: " + jira.baseUrl()
                + "/browse/SHOP-1?focusedCommentId=10500");
        assertThat(done(outcome).written()).isEqualTo(new ToolOutcome.Written(ToolOutcome.WriteKind.COMMENT,
                "SHOP-1", "10500"));
        JiraStub.server().verify(postRequestedFor(urlEqualTo(jira.url("/rest/api/2/issue/SHOP-1/comment")))
            .withRequestBody(equalToJson("{\"body\":\"*Analyst (Tinfirm agent) for Alice:* Fixed: the *VAT* "
                    + "rule.\"}")));
    }

    @Test
    void aTransitionIsChosenByItsNameOrTheStatusItLeadsTo() {
        JiraStub.Instance jira = JiraStub.newInstance();
        Setup setup = setup("JIRA_DC", jira.baseUrl());
        String transitions = jira.url("/rest/api/2/issue/SHOP-1/transitions");
        JiraStub.server().stubFor(get(urlEqualTo(transitions)).willReturn(aResponse().withStatus(200).withBody("""
                {"transitions":[{"id":"11","name":"Start Progress","to":{"name":"In Progress"}},
                                {"id":"31","name":"Resolve","to":{"name":"Done"}}]}""")));
        JiraStub.server().stubFor(post(urlEqualTo(transitions)).willReturn(aResponse().withStatus(204)));

        ToolOutcome byStatus = call(setup, token(TOKEN), "transition", "{\"key\":\"SHOP-1\",\"transition\":\"done\"}");
        assertThat(text(byStatus)).isEqualTo("Moved SHOP-1 through the transition `Resolve`; its status is Done "
                + "now.");
        assertThat(done(byStatus).written().kind()).isEqualTo(ToolOutcome.WriteKind.TRANSITION);
        JiraStub.server().verify(postRequestedFor(urlEqualTo(transitions))
            .withRequestBody(equalToJson("{\"transition\":{\"id\":\"31\"}}")));

        ToolOutcome byName = call(setup, token(TOKEN), "transition", "{\"key\":\"SHOP-1\",\"transition\":\"start "
                + "progress\"}");
        assertThat(structured(byName).get("status").asString()).isEqualTo("In Progress");

        // None of that name: the result lists what the status offers, and nothing is applied.
        ToolOutcome none = call(setup, token(TOKEN), "transition", "{\"key\":\"SHOP-1\",\"transition\":\"Reopen\"}");
        assertThat(done(none).result().isError()).isTrue();
        assertThat(text(none)).isEqualTo("The status of SHOP-1 offers no transition named or leading to `Reopen`. "
                + "It offers: `Start Progress` (to In Progress), `Resolve` (to Done).");
        assertThat(done(none).written()).isNull();
        JiraStub.server().verify(2, postRequestedFor(urlEqualTo(transitions)));
    }

    @Test
    void theSystemsRefusalsAreAnswersTheAgentSeesButARefusedCredentialAsksForTheAccount() {
        JiraStub.Instance jira = JiraStub.newInstance();
        Setup setup = setup("JIRA_DC", jira.baseUrl());
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.url("/rest/api/2/issue/SHOP-401"))).willReturn(aResponse()
            .withStatus(401)
            .withHeader("X-Seraph-LoginReason", "AUTHENTICATED_FAILED")
            .withBody("{\"errorMessages\":[\"You are not authenticated.\"]}")));
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.url("/rest/api/2/issue/SHOP-403"))).willReturn(aResponse()
            .withStatus(403).withBody("{\"errorMessages\":[\"You do not have the permission to see the issue.\"]}")));
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.url("/rest/api/2/issue/SHOP-404"))).willReturn(aResponse()
            .withStatus(404).withBody("{\"errorMessages\":[\"Issue does not exist\"]}")));
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.url("/rest/api/2/issue/SHOP-429"))).willReturn(aResponse()
            .withStatus(429).withHeader("Retry-After", "30")));
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.url("/rest/api/2/search"))).willReturn(aResponse()
            .withStatus(400).withBody("{\"errorMessages\":[\"Token " + TOKEN + " cannot search: bad JQL\"]}")));

        assertThat(call(setup, token(TOKEN), "get", "{\"key\":\"SHOP-401\"}")).isEqualTo(new ToolOutcome.Reconnect(
                "Jira answered 401: (AUTHENTICATED_FAILED); You are not authenticated."));

        ToolOutcome forbidden = call(setup, token(TOKEN), "get", "{\"key\":\"SHOP-403\"}");
        assertThat(done(forbidden).failure().kind()).isEqualTo(CallResult.Kind.FORBIDDEN);
        assertThat(done(forbidden).result().isError()).isTrue();
        assertThat(text(forbidden)).isEqualTo("`get` on Support JIRA_DC failed: Jira answered 403: You do not have "
                + "the permission to see the issue. The account the conversation uses on Support JIRA_DC may not do "
                + "this there.");

        ToolOutcome missing = call(setup, token(TOKEN), "get", "{\"key\":\"SHOP-404\"}");
        assertThat(done(missing).failure().kind()).isEqualTo(CallResult.Kind.NOT_FOUND);
        assertThat(text(missing)).endsWith("Check the key, or search for it.");

        ToolOutcome limited = call(setup, token(TOKEN), "get", "{\"key\":\"SHOP-429\"}");
        assertThat(done(limited).failure().kind()).isEqualTo(CallResult.Kind.RATE_LIMITED);
        assertThat(done(limited).failure().retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(text(limited)).contains("Jira answered 429, the rate limit is exhausted; try again in 30 s",
                "try again later");

        // A system that quotes the token: it never reaches the agent.
        ToolOutcome quoted = call(setup, token(TOKEN), "search", "{\"query\":\"project = SHOP\"}");
        assertThat(text(quoted)).contains("Token *** cannot search: bad JQL", "Check the arguments")
            .doesNotContain(TOKEN);
        assertThat(done(quoted).failure().message()).doesNotContain(TOKEN);
    }

    @Test
    void jiraTakesNoLabelsAndNoCloseAndTheArgumentsAreChecked() {
        JiraStub.Instance jira = JiraStub.newInstance();
        Setup setup = setup("JIRA_DC", jira.baseUrl());

        assertThat(call(setup, token(TOKEN), "label", "{\"key\":\"SHOP-1\",\"add\":[\"x\"]}"))
            .isEqualTo(new ToolOutcome.Refused(ToolOutcome.Code.UNKNOWN_TOOL, "`label` is not a tool of a JIRA_DC "
                    + "system."));
        assertThat(call(setup, token(TOKEN), "close", "{\"key\":\"SHOP-1\"}"))
            .isInstanceOf(ToolOutcome.Refused.class);
        assertThat(call(setup, token(TOKEN), "comment", "{\"key\":\"SHOP-1\"}")).isEqualTo(new ToolOutcome.Refused(
                ToolOutcome.Code.INVALID_REQUEST, "`comment` takes `body`, the comment in Markdown, a text of 1 to "
                        + "30000 characters."));
        assertThat(call(setup, token(TOKEN), "get", "{\"key\":\"SHOP 1\"}")).isEqualTo(new ToolOutcome.Refused(
                ToolOutcome.Code.INVALID_REQUEST, "`get` takes the subject's `key`: the issue key, e.g. SHOP-241."));
        // Nothing reached Jira.
        assertThat(JiraStub.server().findAll(getRequestedFor(urlPathEqualTo(jira.url("/rest/api/2/issue/SHOP%201")))))
            .isEmpty();
    }

    // ---- Jira Cloud --------------------------------------------------------------------------------------------------

    @Test
    void jiraCloudReadsTheDocumentFormatAsMarkdownWithBasicAuthentication() {
        Setup setup = setup("JIRA_CLOUD", "http://localhost:" + CLOUD.port());
        String key = "CLD" + Long.toHexString(System.nanoTime()).substring(0, 6).toUpperCase() + "-1";
        Map<String, Object> issue = JiraCloudStub.issue("10001", key, "Totals wrong",
                JiraCloudStub.doc(List.of(JiraCloudStub.paragraph(List.of(JiraCloudStub.textNode("Totals are "),
                        JiraCloudStub.strong("wrong"))))), "acc-alice", "acc-bob", List.of("billing"), "To Do", "Bug",
                "2026-10-01T10:00:00.000+0200", "2026-10-02T10:00:00.000+0200", null,
                List.of(JiraCloudStub.comment("300", "acc-carol", JiraCloudStub.adf("Confirmed"),
                        "2026-10-02T09:00:00.000+0200")));
        CLOUD.stubFor(get(urlPathEqualTo("/rest/api/3/issue/" + key))
            .withHeader("Authorization", equalTo(JiraCloudStub.basic(CLOUD_CREDENTIAL)))
            .willReturn(JiraStub.json(200, issue)));

        ToolOutcome outcome = call(setup, token(CLOUD_CREDENTIAL), "get", "{\"key\":\"" + key + "\"}");

        assertThat(text(outcome)).contains("# " + key + ": Totals wrong", "Status: To Do",
                "Assignees: Display acc-alice (acc-alice)", "Totals are **wrong**", "> Confirmed",
                "Link: http://localhost:" + CLOUD.port() + "/browse/" + key);
    }

    @Test
    void jiraCloudCommentsAreDocumentsAndItsSearchIsSearchJql() {
        Setup setup = setup("JIRA_CLOUD", "http://localhost:" + CLOUD.port());
        String project = "CLC" + Long.toHexString(System.nanoTime()).substring(0, 6).toUpperCase();
        CLOUD.stubFor(post(urlEqualTo("/rest/api/3/issue/" + project + "-1/comment"))
            .withHeader("Authorization", equalTo(JiraCloudStub.basic(CLOUD_CREDENTIAL)))
            .willReturn(JiraStub.json(201, Map.of("id", "777"))));
        CLOUD.stubFor(get(urlPathEqualTo("/rest/api/3/search/jql"))
            .withQueryParam("jql", equalTo("project = " + project))
            .willReturn(JiraStub.json(200, Map.of("isLast", true, "issues", List.of(JiraCloudStub.issue("1",
                    project + "-1", "Totals wrong", null, null, "acc-bob", List.of(), "To Do", "Bug",
                    "2026-10-01T10:00:00.000+0200", "2026-10-02T10:00:00.000+0200", null, List.of()))))));

        ToolOutcome commented = call(setup, token(CLOUD_CREDENTIAL), "comment", "{\"key\":\"" + project
                + "-1\",\"body\":\"Totals are **fixed**.\"}", "Analyst (Tinfirm agent):");
        assertThat(done(commented).written().externalId()).isEqualTo("777");
        CLOUD.verify(postRequestedFor(urlEqualTo("/rest/api/3/issue/" + project + "-1/comment"))
            .withRequestBody(matchingJsonPath("$.body.type", equalTo("doc")))
            .withRequestBody(matchingJsonPath("$.body.content[0].content[0].text",
                    equalTo("Analyst (Tinfirm agent):")))
            .withRequestBody(matchingJsonPath("$.body.content[0].content[0].marks[0].type", equalTo("strong")))
            .withRequestBody(matchingJsonPath("$.body.content[0].content[3].text", equalTo("fixed"))));

        ToolOutcome found = call(setup, token(CLOUD_CREDENTIAL), "search", "{\"query\":\"project = " + project
                + "\"}");
        assertThat(text(found)).isEqualTo("1 result\n\n" + project + "-1 Totals wrong (To Do; assignees: none)");
        CLOUD.verify(getRequestedFor(urlPathEqualTo("/rest/api/3/search/jql"))
            .withQueryParam("maxResults", equalTo("20")));
    }

    @Test
    void aJiraCloudCredentialGoesOnlyWhereItIsValid() {
        Setup setup = setup("JIRA_CLOUD", "http://localhost:" + CLOUD.port());

        // A stored credential that is not email:token, and an OAuth grant whose gateway is not Atlassian's: the
        // account must be connected again, and nothing is sent.
        assertThat(call(setup, token("no-colon-token"), "get", "{\"key\":\"ABC-1\"}"))
            .isInstanceOf(ToolOutcome.Reconnect.class);
        ToolOutcome elsewhere = call(setup, new CallCredential("test-oauth-token", true, "http://localhost:"
                + CLOUD.port() + "/ex/jira/abc", null), "get", "{\"key\":\"ABC-1\"}");
        assertThat(elsewhere).isEqualTo(new ToolOutcome.Reconnect("The account's Atlassian grant names no gateway "
                + "for the site; it must be connected again"));
        assertThat(CLOUD.findAll(getRequestedFor(urlPathEqualTo("/rest/api/3/issue/ABC-1")))).isEmpty();
        assertThat(CLOUD.findAll(getRequestedFor(urlPathEqualTo("/ex/jira/abc/rest/api/3/issue/ABC-1")))).isEmpty();
    }
}
