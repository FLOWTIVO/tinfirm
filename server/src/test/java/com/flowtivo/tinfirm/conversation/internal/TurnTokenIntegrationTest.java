package com.flowtivo.tinfirm.conversation.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.run.TurnSource;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.GitHubStub;
import com.flowtivo.tinfirm.support.OAuthStubs;
import com.flowtivo.tinfirm.support.TestConversations;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

/**
 * A turn's workspace token that cannot be got right now (the package 3 contract, §5.3, §5.7): the person's GitHub
 * account, connected with OAuth, holds an expired grant, and GitHub's token endpoint is down when it must be
 * refreshed. The binding holds, so the turn is not one that waits for an account: its run goes back to the queue for
 * a passing failure, and is tried again after the back-off. TEST FIXTURE ONLY: tokens are test values.
 */
class TurnTokenIntegrationTest extends ChangeRequestTestBench {

    private static final String TOKEN_PATH = "/login/oauth/access_token";

    @Autowired
    TurnSourceImpl turnSource;

    @Autowired
    Settings settings;

    @Autowired
    ConversationWorkspace workspace;

    @Test
    void aWorkspaceTokenThatCannotBeGotNowSendsTheTurnBackForLaterNotForAnAccount() {
        GitHubStub.Issues github = GitHubStub.newIssues();
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient person = loginAs(company.email("MEMBER"));
        String systemName = unique("GitHub");
        UUID systemId = createSystem(admin, company, Map.of("name", systemName, "kind", "GITHUB", "baseUrl",
                github.baseUrl()));
        UUID shopId = repository(admin, company, systemId, "https://localhost/acme/shop");
        String agentName = unique("Builder");
        UUID agentId = agent(person, company, agentName, List.of(Map.of("systemId", systemId.toString(), "tools",
                List.of(Map.of("name", "get", "mode", "ALLOW")))));
        UUID personId = memberPrincipal(company, company.userId("MEMBER"));
        Bench bench = new Bench(company, owner, person, personId, agentId, agentName);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        connectWithOAuth(admin, person, company, systemId, github, "test-gh-access-" + suffix,
                "test-gh-refresh-" + suffix);
        UUID accountId = (UUID) column(bench, "SELECT id FROM account WHERE system_id = ?", systemId).getFirst();
        Conversation conversation = conversation(bench);
        transactions.runInCompany(bench.companyId(), () -> workspace.addRequested(bench.companyId(),
                conversation.id(), shopId, RepositoryAccess.WRITE));
        TestConversations.bind(bench.companyId(), conversation.id(), systemId, accountId, personId, null);
        RunnerHostRef runner = host(bench);
        UUID runId = running(bench, conversation, runner, 1, 1);

        // GitHub's token endpoint is down when the expired grant must be refreshed.
        StubMapping down = GitHubStub.server().stubFor(post(urlEqualTo(TOKEN_PATH)).atPriority(1)
            .withRequestBody(containing("refresh_token=test-gh-refresh-" + suffix))
            .willReturn(aResponse().withStatus(503)));
        try {
            EffectiveSettings effective = inCompany(bench, () -> settings.effective(bench.companyId(), agentId,
                    new SettingsOwner.Person(personId)));
            TurnSource.TurnRef turn = new TurnSource.TurnRef(bench.companyId(), runId, conversation.id(),
                    conversation.key(), agentId, RuntimeKind.CLAUDE_CODE, TurnReason.FIRST, 1, personId, personId,
                    ModelAccessChoice.BUDGET);
            TurnSource.TurnContent content = SystemContext.callInCompany(bench.companyId(), () -> turnSource.content(
                    turn, new TurnSource.Placement(runner.id(), HostScope.COMPANY, 1, null), effective));

            // Not an account to connect: the binding holds, and the run is tried again after the back-off.
            assertThat(content).as("%s", content).isInstanceOfSatisfying(TurnSource.TurnContent.Later.class,
                    later -> assertThat(later.message()).isEqualTo("Tinfirm could not get a token for " + systemName
                            + " right now; the turn is tried again shortly."));
            assertThat(column(bench, "SELECT state FROM conversation_binding WHERE conversation_id = ?",
                    conversation.id())).containsExactly("BOUND");
        }
        finally {
            GitHubStub.server().removeStub(down);
        }
    }

    /**
     * The person connects their GitHub account with OAuth through the system's app: the code is exchanged at GitHub's
     * token endpoint for a grant that has expired already.
     */
    private void connectWithOAuth(ApiClient admin, ApiClient person, TestCompany company, UUID systemId,
            GitHubStub.Issues github, String accessToken, String refreshToken) {
        ApiResponse app = admin.put(company.path("/systems/" + systemId + "/app"), Map.of("clientId",
                "test-github-oauth-client", "clientSecret", "test-github-oauth-client-secret"));
        assertThat(app.status()).as("app: %s", app).isEqualTo(200);
        String accountPath = company.path("/me/accounts/" + systemId);
        String state = OAuthStubs.startSignIn(person, accountPath, "/accounts");
        String code = "test-github-code-" + UUID.randomUUID();
        GitHubStub.server().stubFor(post(urlEqualTo(TOKEN_PATH)).withRequestBody(containing("code=" + code))
            .willReturn(GitHubStub.json(200, Map.of("access_token", accessToken, "refresh_token", refreshToken,
                    "token_type", "bearer", "expires_in", 0, "scope", "repo"))));
        github.user(accessToken, "the-person");
        ApiResponse callback = person.get("/api/oauth/callback?code=" + code + "&state=" + state);
        assertThat(callback.status()).as("callback: %s", callback).isEqualTo(303);
        assertThat(callback.header("Location")).startsWith("/accounts?account=connected");
    }
}
