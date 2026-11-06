package com.flowtivo.tinfirm.trigger.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.GitHubStub;
import com.flowtivo.tinfirm.support.GitLabStub;

/**
 * Polling GitHub and GitLab for triggers (the package 4 contract, §5.4 to §5.6, §11 point 8): GitHub's conditional
 * listing ({@code If-None-Match}, a {@code 304} reads nothing); a scope that answers {@code 404} waits
 * {@code SCOPE_UNREADABLE} while another scope of the same poll fires; a pull request's new head commit raises
 * {@code CHANGE_REQUEST_UPDATED} unless it is a push Tinfirm recorded (T9); the chain of 3 through change requests that
 * triggered conversations opened, the fourth {@code SKIPPED_CHAIN} with a word in the conversation that created it
 * (row 10); GitLab's path read as a group when no project has it, a merge request's head commit, and a mention of the
 * poll's account. TEST FIXTURE ONLY: tokens are test values.
 */
class CodeHostPollingIntegrationTest extends PollingTestSupport {

    @Autowired
    SystemTransactions transactions;

    @Autowired
    SystemWriteRecorder writes;

    record GitHub(GitHubStub.Issues stub, UUID systemId, UUID aliceAccount, String token) {
    }

    GitHub github(Bench bench) {
        GitHubStub.Issues stub = GitHubStub.newIssues();
        UUID system = createSystem(bench.owner(), bench.company(), unique("GitHub"), "GITHUB", stub.baseUrl(), false);
        String token = unique("gh-alice");
        stub.user(token, "alice");
        ApiResponse connected = bench.alice().put(bench.path("/me/accounts/" + system + "/token"), Map.of("token",
                token));
        assertThat(connected.status()).as("%s", connected).isEqualTo(200);
        return new GitHub(stub, system, UUID.fromString(connected.json().get("id").asString()), token);
    }

    static Map<String, Object> pull(long id, int number, String repository, Duration createdAgo, Duration updatedAgo) {
        Map<String, Object> pull = new LinkedHashMap<>(GitHubStub.issue(id, number, repository, "Change " + number,
                "Body", List.of(), List.of(), isoAgo(createdAgo), isoAgo(updatedAgo), 0));
        pull.put("html_url", "https://github.com/" + repository + "/pull/" + number);
        pull.put("pull_request", Map.of("url", "https://api.github.com/repos/" + repository + "/pulls/" + number));
        pull.put("user", Map.of("login", "dana", "id", 77));
        return pull;
    }

    static void head(GitHub github, String repository, int number, String sha) {
        GitHubStub.server().stubFor(get(urlEqualTo(github.stub().url("/repos/" + repository + "/pulls/" + number)))
            .willReturn(GitHubStub.json(200, Map.of("number", number, "head", Map.of("sha", sha)))));
    }

    @Test
    void gitHubListsConditionallyAndAScopeItCannotReadWaits() throws Exception {
        Bench bench = bench();
        GitHub github = github(bench);
        UUID shop = create(bench.alice(), bench, tracker(bench, unique("Shop"), github.systemId(),
                github.aliceAccount(), bench.alicePrincipal(), List.of(event("LABEL_ADDED", "ai")), "ANY",
                "acme/shop", null));
        UUID secret = create(bench.alice(), bench, tracker(bench, unique("Secret"), github.systemId(),
                github.aliceAccount(), bench.alicePrincipal(), List.of(event("LABEL_ADDED", "ai")), "ANY",
                "acme/secret", null));
        UUID poll = pollOf(bench, shop);
        assertThat(pollOf(bench, secret)).isEqualTo(poll);
        armedAgo(bench, shop, Duration.ofHours(1));
        armedAgo(bench, secret, Duration.ofHours(1));
        String listing = github.stub().path() + "/repos/acme/shop/issues";
        GitHubStub.server().stubFor(get(urlPathEqualTo(listing))
            .withHeader("Authorization", equalTo("Bearer " + github.token()))
            .willReturn(GitHubStub.json(200, List.of(GitHubStub.issue(501, 5, "acme/shop", "Totals", "Wrong",
                    List.of(), List.of("ai"), isoAgo(Duration.ofMinutes(20)), isoAgo(Duration.ofMinutes(20)), 0)))
                .withHeader("ETag", "W/\"shop-1\"")));
        GitHubStub.server().stubFor(get(urlPathEqualTo(github.stub().path() + "/repos/acme/secret/issues"))
            .willReturn(GitHubStub.json(404, Map.of("message", "Not Found"))));

        TriggerPolls.Outcome first = pass(bench, poll);
        assertThat(first.handled()).isEqualTo(1);
        assertThat(firings(bench, shop)).containsExactly("LABEL_ADDED:STARTED:acme/shop#5");
        assertThat(firings(bench, secret)).isEmpty();
        assertThat(triggerRow(bench, secret).get("waiting_reason")).isEqualTo("SCOPE_UNREADABLE");
        assertThat(triggerRow(bench, shop).get("waiting_reason")).isNull();

        // Unchanged: GitHub answers 304 to the stored ETag, which costs no rate limit; nothing is handled.
        GitHubStub.server().stubFor(get(urlPathEqualTo(listing))
            .withHeader("If-None-Match", equalTo("W/\"shop-1\""))
            .willReturn(aResponse().withStatus(304)));
        TriggerPolls.Outcome second = pass(bench, poll);
        assertThat(second.handled()).isZero();
        assertThat(second.subjects()).isZero();
        GitHubStub.server().verify(getRequestedFor(urlPathEqualTo(listing))
            .withHeader("If-None-Match", equalTo("W/\"shop-1\"")));
        assertThat(firings(bench, shop)).hasSize(1);
    }

    @Test
    void aNewHeadCommitUpdatesAPullRequestUnlessTinfirmPushedIt() throws Exception {
        Bench bench = bench();
        GitHub github = github(bench);
        UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Pushes"), github.systemId(),
                github.aliceAccount(), bench.alicePrincipal(), List.of(event("CHANGE_REQUEST_UPDATED")),
                "CHANGE_REQUEST", "acme/shop", null));
        UUID poll = pollOf(bench, trigger);
        armedAgo(bench, trigger, Duration.ofHours(1));
        github.stub().issues("acme/shop", List.of(pull(900, 20, "acme/shop", Duration.ofHours(3),
                Duration.ofMinutes(30))));
        head(github, "acme/shop", 20, "aaa111");
        pass(bench, poll);
        assertThat(firings(bench, trigger)).isEmpty();

        // Tinfirm pushed bbb222 (recorded at the turn's end): no edge.
        transactions.runInCompany(bench.id(), () -> writes.record(bench.id(), github.systemId(),
                "acme/shop@agent/x-1", "PUSH", "bbb222", null, List.of(), List.of(), null, null));
        github.stub().issues("acme/shop", List.of(pull(900, 20, "acme/shop", Duration.ofHours(3),
                Duration.ofMinutes(20))));
        head(github, "acme/shop", 20, "bbb222");
        pass(bench, poll);
        assertThat(firings(bench, trigger)).isEmpty();

        // A person pushed ccc333: CHANGE_REQUEST_UPDATED.
        github.stub().issues("acme/shop", List.of(pull(900, 20, "acme/shop", Duration.ofHours(3),
                Duration.ofMinutes(10))));
        head(github, "acme/shop", 20, "ccc333");
        pass(bench, poll);
        assertThat(firings(bench, trigger)).containsExactly("CHANGE_REQUEST_UPDATED:STARTED:acme/shop#20");
    }

    @Test
    void theChainOfThreeEndsAtTheFourthChangeRequestATriggeredConversationOpened() throws Exception {
        Bench bench = bench();
        GitHub github = github(bench);
        UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Chain"), github.systemId(),
                github.aliceAccount(), bench.alicePrincipal(), List.of(event("CHANGE_REQUEST_OPENED")),
                "CHANGE_REQUEST", "acme/shop", null));
        UUID poll = pollOf(bench, trigger);
        armedAgo(bench, trigger, Duration.ofHours(1));
        List<Map<String, Object>> listed = new java.util.ArrayList<>();
        listed.add(pull(1010, 10, "acme/shop", Duration.ofMinutes(40), Duration.ofMinutes(40)));
        github.stub().issues("acme/shop", listed);
        head(github, "acme/shop", 10, "s10");
        pass(bench, poll);
        assertThat(firings(bench, trigger)).containsExactly("CHANGE_REQUEST_OPENED:STARTED:acme/shop#10");

        // Each triggered conversation opens the next change request; each fires one level deeper.
        for (int number = 11; number <= 14; number++) {
            List<Map<String, Object>> conversations = conversations(bench, trigger);
            Map<String, Object> creator = conversations.getLast();
            UUID creatorId = (UUID) creator.get("id");
            int depth = ((Number) creator.get("chain_depth")).intValue();
            String key = "acme/shop#" + number;
            transactions.runInCompany(bench.id(), () -> writes.record(bench.id(), github.systemId(), key,
                    "CHANGE_REQUEST", Integer.toString(10), null, List.of(), List.of(), creatorId, depth));
            listed.add(pull(1000 + number, number, "acme/shop", Duration.ofMinutes(50 - number),
                    Duration.ofMinutes(50 - number)));
            github.stub().issues("acme/shop", listed);
            head(github, "acme/shop", number, "s" + number);
            pass(bench, poll);
        }
        assertThat(firings(bench, trigger)).containsExactly("CHANGE_REQUEST_OPENED:STARTED:acme/shop#10",
                "CHANGE_REQUEST_OPENED:STARTED:acme/shop#11", "CHANGE_REQUEST_OPENED:STARTED:acme/shop#12",
                "CHANGE_REQUEST_OPENED:STARTED:acme/shop#13", "CHANGE_REQUEST_OPENED:SKIPPED_CHAIN:acme/shop#14");
        List<Map<String, Object>> conversations = conversations(bench, trigger);
        assertThat(conversations).extracting(row -> ((Number) row.get("chain_depth")).intValue())
            .containsExactly(0, 1, 2, 3);
        assertThat(messages(bench, (UUID) conversations.getLast().get("id")))
            .anySatisfy(message -> assertThat(message).startsWith("SYSTEM:acme/shop#14 was created by this "
                    + "conversation at the end of a chain of 3"));
        assertThat(((Number) firingRows(bench, trigger).getLast().get("chain_depth")).intValue()).isEqualTo(4);
    }

    @Test
    void gitLabReadsAGroupWhenNoProjectHasThePathAndAMentionOfTheAccountFires() throws Exception {
        Bench bench = bench();
        String base = GitLabStub.origin() + "/gl-" + UUID.randomUUID().toString().substring(0, 8);
        String api = base.substring(GitLabStub.origin().length()) + "/api/v4";
        String token = unique("gl-alice");
        GitLabStub.server().stubFor(get(urlEqualTo(api + "/user"))
            .withHeader("PRIVATE-TOKEN", equalTo(token))
            .willReturn(GitHubStub.json(200, Map.of("id", 4242, "username", "alice", "name", "Alice A"))));
        UUID system = createSystem(bench.owner(), bench.company(), unique("GitLab"), "GITLAB", base, false);
        ApiResponse connected = bench.alice().put(bench.path("/me/accounts/" + system + "/token"), Map.of("token",
                token));
        assertThat(connected.status()).as("%s", connected).isEqualTo(200);
        UUID account = UUID.fromString(connected.json().get("id").asString());
        UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Group"), system, account,
                bench.alicePrincipal(), List.of(event("MENTIONED"), event("CHANGE_REQUEST_OPENED")), "ANY",
                "acme/team", null));
        UUID poll = pollOf(bench, trigger);
        armedAgo(bench, trigger, Duration.ofHours(1));
        for (String what : List.of("issues", "merge_requests")) {
            GitLabStub.server().stubFor(get(urlPathEqualTo(api + "/projects/acme%2Fteam/" + what))
                .willReturn(GitHubStub.json(404, Map.of("message", "404 Project Not Found"))));
        }
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("id", 7001);
        issue.put("iid", 3);
        issue.put("title", "Totals");
        issue.put("description", "Wrong totals");
        issue.put("state", "opened");
        issue.put("labels", List.of());
        issue.put("assignees", List.of());
        issue.put("author", Map.of("id", 9, "username", "carol", "name", "Carol"));
        issue.put("references", Map.of("full", "acme/team/web#3"));
        issue.put("created_at", isoAgo(Duration.ofHours(5)));
        issue.put("updated_at", isoAgo(Duration.ofMinutes(10)));
        issue.put("user_notes_count", 1);
        GitLabStub.server().stubFor(get(urlPathEqualTo(api + "/groups/acme%2Fteam/issues"))
            .withHeader("PRIVATE-TOKEN", equalTo(token))
            .willReturn(GitHubStub.json(200, List.of(issue))));
        GitLabStub.server().stubFor(get(urlPathEqualTo(api + "/projects/acme%2Fteam%2Fweb/issues/3/notes"))
            .willReturn(GitHubStub.json(200, List.of(Map.of("id", 88, "body", "@alice can you look?", "system",
                    false, "created_at", isoAgo(Duration.ofMinutes(10)), "author", Map.of("id", 9, "username",
                            "carol", "name", "Carol"))))));
        Map<String, Object> mergeRequest = new LinkedHashMap<>(issue);
        mergeRequest.put("id", 8001);
        mergeRequest.put("iid", 4);
        mergeRequest.put("references", Map.of("full", "acme/team/web!4"));
        mergeRequest.put("created_at", isoAgo(Duration.ofMinutes(12)));
        mergeRequest.put("updated_at", isoAgo(Duration.ofMinutes(12)));
        mergeRequest.put("user_notes_count", 0);
        mergeRequest.put("sha", "d00d");
        GitLabStub.server().stubFor(get(urlPathEqualTo(api + "/groups/acme%2Fteam/merge_requests"))
            .willReturn(GitHubStub.json(200, List.of(mergeRequest))));

        TriggerPolls.Outcome outcome = pass(bench, poll);
        assertThat(outcome.error()).isNull();
        assertThat(firings(bench, trigger)).containsExactlyInAnyOrder("MENTIONED:STARTED:acme/team/web#3",
                "CHANGE_REQUEST_OPENED:STARTED:acme/team/web!4");
        try (com.flowtivo.tinfirm.support.TenantSql sql = com.flowtivo.tinfirm.support.TenantSql.in(bench.id())) {
            assertThat(sql.column("SELECT head_sha FROM trigger_subject WHERE poll_id = ? AND subject_id = 'mr:8001'",
                    poll)).containsExactly("d00d");
        }
        assertThat(firingRows(bench, trigger)).anySatisfy(row -> assertThat((String) row.get("request"))
            .contains("Carol mentioned alice", "> @alice can you look?"));
    }
}
