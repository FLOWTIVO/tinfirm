package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.GitHubStub;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;

/**
 * The bench of the change request tests (TEST FIXTURE ONLY): a GitHub system on the GitHub stub with two repositories,
 * the person's own account on it, the person's agent listing it with {@code open_change_request} in a mode, and turns
 * of a conversation bound to that account that pushed, ended as rows and handed to {@link OutputTurnEnd} as part 1's
 * {@code TurnEnded} would.
 */
abstract class ChangeRequestTestBench extends OutputTestBench {

    static final String TOKEN = "test-github-token-of-the-person";

    @Autowired
    OutputTurnEnd turnEnd;

    @Autowired
    ChangeRequestOpener opener;

    @Autowired
    OutputService outputs;

    /** A bench with a GitHub code host, its repositories and the person's own account there. */
    record Host(Bench bench, GitHubStub.Issues github, UUID systemId, UUID shopId, UUID webId, UUID accountId) {

        String pulls(String repository) {
            return github.path() + "/repos/acme/" + repository + "/pulls";
        }
    }

    /** The bench, its agent listing the code host with {@code open_change_request} in this mode (null: not at all). */
    Host codeHost(String mode) {
        GitHubStub.Issues github = GitHubStub.newIssues();
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient person = loginAs(company.email("MEMBER"));
        UUID systemId = createSystem(admin, company, Map.of("name", unique("GitHub"), "kind", "GITHUB", "baseUrl",
                github.baseUrl()));
        UUID shopId = repository(admin, company, systemId, "https://localhost/acme/shop");
        UUID webId = repository(admin, company, systemId, "https://localhost/acme/web");
        github.user(TOKEN, "the-person");
        ApiResponse account = person.put(company.path("/me/accounts/" + systemId + "/token"), Map.of("token", TOKEN));
        assertThat(account.status()).as("account: %s", account).isEqualTo(200);
        List<Map<String, Object>> tools = mode == null ? List.of(Map.of("name", "get", "mode", "ALLOW"))
                : List.of(Map.of("name", "get", "mode", "ALLOW"), Map.of("name", "open_change_request", "mode", mode));
        String agentName = unique("Builder");
        UUID agentId = agent(person, company, agentName, List.of(Map.of("systemId", systemId.toString(), "tools",
                tools)));
        Bench bench = new Bench(company, owner, person, memberPrincipal(company, company.userId("MEMBER")), agentId,
                agentName);
        return new Host(bench, github, systemId, shopId, webId, UUID.fromString(account.json().get("id").asString()));
    }

    UUID repository(ApiClient admin, TestCompany company, UUID systemId, String url) {
        Map<String, Object> body = new HashMap<>();
        body.put("url", url);
        body.put("defaultBranch", "main");
        ApiResponse response = admin.post(company.path("/systems/" + systemId + "/repositories"), body);
        assertThat(response.status()).as("repository: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    /** A conversation of the person bound to their own account on the code host: the conversation and its binding. */
    record Bound(Conversation conversation, UUID bindingId) {
    }

    Bound bound(Host host) {
        Conversation conversation = conversation(host.bench());
        UUID bindingId = TestConversations.bind(host.bench().companyId(), conversation.id(), host.systemId(),
                host.accountId(), host.bench().personPrincipalId(), null);
        return new Bound(conversation, bindingId);
    }

    /** {@code GET …/pulls/{number}} of the repository answers it in this state ({@code open}, {@code closed}, merged). */
    void reads(Host host, String repository, int number, String state, boolean merged, String headSha) {
        GitHubStub.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(host.pulls(repository) + "/" + number))
            .willReturn(GitHubStub.json(200, Map.of("number", number, "state", state, "merged", merged, "body", "",
                    "head", Map.of("sha", headSha)))));
    }

    /** {@code POST …/pulls} of the repository answers a new pull request of this number. */
    void opens(Host host, String repository, int number) {
        GitHubStub.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(host.pulls(repository)))
            .willReturn(GitHubStub.json(201, Map.of("number", number, "html_url",
                    "https://github.com/acme/" + repository + "/pull/" + number, "state", "open", "draft", false))));
    }

    /**
     * A turn of the conversation that ended SUCCEEDED after pushing {@code commits} new commits to the repository, as
     * part 1 hands it over: its run, ended.
     */
    UUID pushed(Host host, Bound bound, UUID repositoryId, String repositoryName, boolean readOnly, int commits) {
        return pushed(host, bound, repositoryId, repositoryName, readOnly, commits, RunStatus.SUCCEEDED,
                "Fixed the totals.");
    }

    /** A turn of the conversation that ended with this status and summary after pushing, as above. */
    UUID pushed(Host host, Bound bound, UUID repositoryId, String repositoryName, boolean readOnly, int commits,
            RunStatus status, String summary) {
        return pushed(host, bound, repositoryId, repositoryName, readOnly, commits, status, summary, "abc123");
    }

    /**
     * A turn of the conversation that ended SUCCEEDED after pushing the branch at this head commit, as above; its run's
     * repository row records the push, as part 1 does before the turn's end.
     */
    UUID pushedAt(Host host, Bound bound, UUID repositoryId, String repositoryName, String headSha) {
        return pushed(host, bound, repositoryId, repositoryName, false, 1, RunStatus.SUCCEEDED, "Fixed the totals.",
                headSha);
    }

    private UUID pushed(Host host, Bound bound, UUID repositoryId, String repositoryName, boolean readOnly,
            int commits, RunStatus status, String summary, String headSha) {
        RunnerHostRef runner = host(host.bench());
        int turn = column(host.bench(), "SELECT id FROM run WHERE conversation_id = ?", bound.conversation().id())
            .size() + 1;
        UUID runId = running(host.bench(), bound.conversation(), runner, 1, turn);
        TestRuns.ended(host.bench().companyId(), runId, status.name(), summary);
        update(host.bench(), """
                INSERT INTO run_repository (run_id, company_id, position, repository_id, system_id, binding_id, name,
                                            dir, read_only, read_only_cause, pushed, head_sha, commits)
                VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", runId, host.bench().companyId(), repositoryId,
                host.systemId(), bound.bindingId(), repositoryName, repositoryName, readOnly,
                readOnly ? "AGENT" : null, !readOnly && commits > 0, headSha, commits);
        TurnEnded.TurnEnd end = new TurnEnded.TurnEnd(host.bench().companyId(), runId, bound.conversation().id(),
                host.bench().agentId(), turn == 1 ? TurnReason.FIRST : TurnReason.MESSAGE, turn,
                host.bench().personPrincipalId(), host.bench().personPrincipalId(), ModelAccessChoice.BUDGET,
                status, null, null, summary, null, null,
                List.of(new TurnEnded.RepositoryResult(repositoryId, host.systemId(), bound.bindingId(),
                        repositoryName, "agent/" + bound.conversation().key(), readOnly, true, headSha, commits)),
                List.of(), null);
        transactions.runInCompany(host.bench().companyId(),
                () -> turnEnd.turnEnded(conversationInfo(host.bench(), bound.conversation()), end));
        return runId;
    }

    /** Waits until the conversation has this many change requests. */
    void awaitChangeRequests(Host host, Bound bound, int count) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(column(host.bench(),
                "SELECT output_id FROM run_change_request WHERE conversation_id = ?", bound.conversation().id()))
            .hasSize(count));
    }

    /** Waits until the conversation has a SYSTEM message, and answers the messages' bodies. */
    List<Object> awaitMessages(Host host, Bound bound) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(messages(host, bound)).isNotEmpty());
        return messages(host, bound);
    }

    List<Object> messages(Host host, Bound bound) {
        return column(host.bench(), "SELECT body FROM message WHERE conversation_id = ? AND kind = 'SYSTEM' "
                + "ORDER BY created_at", bound.conversation().id());
    }
}
