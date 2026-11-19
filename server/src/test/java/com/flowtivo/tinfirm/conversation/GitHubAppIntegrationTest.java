package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.GitHubAppStub;
import com.flowtivo.tinfirm.support.GitHubStub;

import tools.jackson.databind.JsonNode;

/**
 * ADR-0116, decisions 6 to 10, through the API, a fake runner host and a fake GitHub ({@link GitHubAppStub}, a GitHub
 * Enterprise Server's API base): an admin sets a {@code GITHUB} system's App, which GitHub is asked for with a JWT its
 * key signed; lists its installations; adds one as a credential of kind {@code APP}; a run on it gets a token of its
 * own, minted for its repository with {@code contents} and {@code pull_requests} only, with its expiry and the
 * credential's mirror key, on a host that renews tokens; the host renews it with {@code GIT_TOKEN}; a credential
 * disabled refuses the renewal for good; a host that cannot renew never gets the run ({@code NO_HOST_FOR_APP_TOKENS});
 * a repository the installation does not cover ends the turn with a message that names it. TEST FIXTURE ONLY: keys
 * and tokens are test values.
 */
class GitHubAppIntegrationTest extends ConversationTestBench {

    private static final List<String> RENEWS = List.of("run.git.revoke", "run.git.refresh");

    private static final Map<String, String> INSTALLED = Map.of("contents", "write", "pull_requests", "write",
            "issues", "write", "metadata", "read", "workflows", "write");

    /** A GITHUB system at the App's fake GitHub, with the repository acme/shop: its id and the repository's. */
    private UUID[] gitHub(Bench bench, GitHubAppStub app, String repositoryUrl) {
        UUID system = createSystem(bench.owner(), bench.company(), Map.of("name", unique("GitHub"), "kind", "GITHUB",
                "baseUrl", app.baseUrl()));
        ApiResponse repository = bench.owner().post(bench.path("/systems/" + system + "/repositories"),
                Map.of("url", repositoryUrl));
        assertThat(repository.status()).as("%s", repository).isEqualTo(201);
        return new UUID[] { system, UUID.fromString(repository.json().get("id").asString()) };
    }

    private FakeRunner host(Bench bench, List<String> features) {
        RunnerHostRef ref = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        FakeRunner runner = FakeRunner.connect(port, ref.token());
        Map<String, Object> hello = FakeRunner.helloV2(List.of(), List.of(), RUNNER_VERSION, 4);
        hello.put("features", features);
        runner.send(hello);
        runner.expect("welcome");
        await().atMost(WAIT).until(() -> connections.isConfigured(ref.id()));
        return runner;
    }

    /** The App set on the system, an installation on acme added as a credential, lent to the member's group. */
    private UUID appCredential(Bench bench, GitHubAppStub app, UUID system, long installationId, String access) {
        ApiResponse set = bench.owner().put(bench.path("/systems/" + system + "/github-app"),
                Map.of("appId", app.appId(), "privateKey", app.privateKeyPkcs1Pem()));
        assertThat(set.status()).as("%s", set).isEqualTo(200);
        app.installation(installationId, "acme", INSTALLED);
        ApiResponse credential = bench.owner().post(bench.path("/systems/" + system + "/credentials/app"),
                Map.of("name", unique("Acme App"), "installationId", installationId));
        assertThat(credential.status()).as("%s", credential).isEqualTo(201);
        UUID credentialId = UUID.fromString(credential.json().get("id").asString());
        UUID group = createGroup(bench.owner(), bench.company(), unique("Builders"),
                List.of(bench.company().userId("MEMBER")));
        createGrant(bench.owner(), bench.company(), credentialId, group, access);
        return credentialId;
    }

    // ---- The App on the system ------------------------------------------------------------------------------------

    @Test
    void anAdminSetsTheAppListsItsInstallationsAndAddsOneAsACredential() {
        Bench bench = bench(false);
        GitHubAppStub app = GitHubAppStub.create(424242);
        UUID[] git = gitHub(bench, app, "https://localhost/acme/shop");
        String path = bench.path("/systems/" + git[0] + "/github-app");

        // Only an admin; a key of another App, or no key, is refused, and nothing is stored.
        assertThat(bench.member().put(path, Map.of("appId", app.appId(), "privateKey", app.privateKeyPem()))
            .status()).isEqualTo(403);
        assertThat(bench.owner().put(path, Map.of("appId", app.appId(), "privateKey", "not a key")).status())
            .isEqualTo(400);
        ApiResponse other = bench.owner().put(path, Map.of("appId", 7, "privateKey", app.privateKeyPem()));
        assertThat(other.status()).isEqualTo(400);
        assertThat(other.problemCode()).isEqualTo("GITHUB_APP_REFUSED");
        assertThat(bench.owner().get(path).status()).isEqualTo(404);
        assertThat(bench.owner().get(bench.path("/systems/" + git[0])).json().get("githubApp").asBoolean())
            .isFalse();

        // PKCS#8 and PKCS#1 (as GitHub downloads it) both sign a JWT GitHub takes as the App's.
        ApiResponse set = bench.owner().put(path, Map.of("appId", app.appId(), "privateKey", app.privateKeyPem()));
        assertThat(set.status()).as("%s", set).isEqualTo(200);
        assertThat(set.json().get("appId").asLong()).isEqualTo(app.appId());
        assertThat(set.json().get("slug").asString()).isEqualTo("tinfirm-test");
        assertThat(set.json().has("privateKey")).isFalse();
        assertThat(set.json().get("permissions")).anySatisfy(permission -> {
            assertThat(permission.get("name").asString()).isEqualTo("Workflows");
            assertThat(permission.get("access").asString()).isEqualTo("NEVER");
        });
        ApiResponse again = bench.owner().put(path, Map.of("appId", app.appId(), "privateKey",
                app.privateKeyPkcs1Pem()));
        assertThat(again.status()).as("%s", again).isEqualTo(200);
        // Every JWT is signed by the App's key; the two saves' are the same token (RS256 is deterministic).
        assertThat(app.jwts().stream().filter(app::signedByTheApp).count()).isGreaterThanOrEqualTo(2);
        assertThat(bench.owner().get(bench.path("/systems/" + git[0])).json().get("githubApp").asBoolean())
            .isTrue();
        assertThat(bench.member().get(bench.path("/systems/" + git[0])).json().has("githubApp")).isFalse();
        List<JsonNode> audits = auditEvents(bench.owner(), bench.company(), "system.github_app_set");
        assertThat(audits).hasSize(2);
        assertThat(audits.getFirst().get("details").get("keyReplaced").asBoolean()).isTrue();
        assertThat(audits.toString()).doesNotContain("PRIVATE KEY");

        // Its installations, then one as a credential of kind APP, once.
        app.installations(List.of(GitHubAppStub.installationBody(11, "acme", INSTALLED),
                GitHubAppStub.installationBody(12, "acme-labs", INSTALLED)));
        app.installation(11, "acme", INSTALLED);
        JsonNode listed = bench.owner().get(path + "/installations").json().get("items");
        assertThat(listed).extracting(item -> item.get("accountLogin").asString()).containsExactly("acme",
                "acme-labs");
        assertThat(listed.get(0).path("credentialId").isMissingNode() || listed.get(0).get("credentialId").isNull())
            .isTrue();
        ApiResponse credential = bench.owner().post(bench.path("/systems/" + git[0] + "/credentials/app"),
                Map.of("name", "Acme App", "installationId", 11));
        assertThat(credential.status()).as("%s", credential).isEqualTo(201);
        assertThat(credential.json().get("kind").asString()).isEqualTo("APP");
        assertThat(credential.json().get("login").asString()).isEqualTo("acme");
        ApiResponse twice = bench.owner().post(bench.path("/systems/" + git[0] + "/credentials/app"),
                Map.of("name", "Acme App 2", "installationId", 11));
        assertThat(twice.problemCode()).isEqualTo("DUPLICATE_INSTALLATION");
        assertThat(bench.owner().get(path + "/installations").json().get("items").get(0).get("credentialName")
            .asString()).isEqualTo("Acme App");
        // A token on it is refused: an installation mints its own.
        assertThat(bench.owner().put(bench.path("/systems/" + git[0] + "/credentials/"
                + credential.json().get("id").asString() + "/token"), Map.of("token", "test-x")).problemCode())
            .isEqualTo("SYSTEM_KIND_INVALID");

        // The App stays while an installation's credential does.
        ApiResponse inUse = bench.owner().delete(path);
        assertThat(inUse.status()).isEqualTo(409);
        assertThat(inUse.problemCode()).isEqualTo("GITHUB_APP_IN_USE");
        assertThat(bench.owner().delete(bench.path("/systems/" + git[0] + "/credentials/"
                + credential.json().get("id").asString())).status()).isEqualTo(204);
        assertThat(bench.owner().delete(path).status()).isEqualTo(204);
        assertThat(auditEvents(bench.owner(), bench.company(), "system.github_app_removed")).hasSize(1);
        assertThat(bench.owner().get(path).status()).isEqualTo(404);
    }

    @Test
    void anotherAppIsRefusedWhileAnInstallationsCredentialRemainsAndTheSameAppsKeyIsReplaced() {
        Bench bench = bench(false);
        GitHubAppStub app = GitHubAppStub.create(515151);
        UUID[] git = gitHub(bench, app, "https://localhost/acme/shop");
        String path = bench.path("/systems/" + git[0] + "/github-app");
        UUID credential = appCredential(bench, app, git[0], 21, "WRITE");

        // The same App's key again: its rotation.
        ApiResponse rotated = bench.owner().put(path, Map.of("appId", app.appId(), "privateKey", app.privateKeyPem()));
        assertThat(rotated.status()).as("%s", rotated).isEqualTo(200);

        // Another App on the same GitHub: its credential stands for an installation of the first.
        app.answersAs(616161);
        ApiResponse other = bench.owner().put(path, Map.of("appId", 616161, "privateKey", app.privateKeyPem()));
        assertThat(other.status()).as("%s", other).isEqualTo(409);
        assertThat(other.problemCode()).isEqualTo("GITHUB_APP_IN_USE");
        assertThat(bench.owner().get(path).json().get("appId").asLong()).isEqualTo(app.appId());

        // Once the credential is gone, another App may be set.
        assertThat(bench.owner().delete(bench.path("/systems/" + git[0] + "/credentials/" + credential)).status())
            .isEqualTo(204);
        ApiResponse set = bench.owner().put(path, Map.of("appId", 616161, "privateKey", app.privateKeyPem()));
        assertThat(set.status()).as("%s", set).isEqualTo(200);
        assertThat(set.json().get("appId").asLong()).isEqualTo(616161);
    }

    @Test
    void anInstallationVerifiedForAnAppReplacedMeanwhileIsNotStored() throws Exception {
        Bench bench = bench(false);
        GitHubAppStub app = GitHubAppStub.create(717171);
        UUID[] git = gitHub(bench, app, "https://localhost/acme/shop");
        String path = bench.path("/systems/" + git[0] + "/github-app");
        assertThat(bench.owner().put(path, Map.of("appId", app.appId(), "privateKey", app.privateKeyPem())).status())
            .isEqualTo(200);
        app.installationSlowly(31, "acme", INSTALLED, 2000);
        java.util.concurrent.CompletableFuture<ApiResponse> adding = java.util.concurrent.CompletableFuture
            .supplyAsync(() -> bench.owner().post(bench.path("/systems/" + git[0] + "/credentials/app"),
                    Map.of("name", unique("Acme App"), "installationId", 31)));
        // While GitHub answers for the first App, an admin sets another one on the system.
        await().atMost(WAIT).until(() -> app.installationAsked(31) > 0);
        app.answersAs(818181);
        ApiResponse replaced = bench.owner().put(path, Map.of("appId", 818181, "privateKey", app.privateKeyPem()));
        assertThat(replaced.status()).as("%s", replaced).isEqualTo(200);

        ApiResponse added = adding.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(added.status()).as("%s", added).isEqualTo(409);
        assertThat(added.problemCode()).isEqualTo("GITHUB_APP_CHANGED");
        // Nothing was stored: no credential of kind APP on the system.
        assertThat(bench.owner().get(bench.path("/systems/" + git[0] + "/credentials")).json().toString())
            .doesNotContain("\"APP\"");
    }

    // ---- A run's own tokens -----------------------------------------------------------------------------------------

    @Test
    void aRunGetsItsOwnTokenAndRenewsItUntilTheCredentialIsDisabled() {
        Bench bench = bench(true);
        GitHubAppStub app = GitHubAppStub.create(515151);
        UUID[] git = gitHub(bench, app, "https://localhost/acme/shop");
        UUID credential = appCredential(bench, app, git[0], 21, "WRITE");
        app.mints(21, "test-ghs-run-first");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(bench, RENEWS)) {
            start(bench.member(), bench, agentId, "Fix the cart");
            JsonNode assign = runner.expect("run.assign");
            JsonNode token = assign.get("workspaces").get(0).get("git");
            assertThat(token.get("username").asString()).isEqualTo("x-access-token");
            assertThat(token.get("token").asString()).isEqualTo("test-ghs-run-first");
            Instant expiresAt = Instant.parse(token.get("expiresAt").asString());
            assertThat(expiresAt).isBetween(Instant.now().plusSeconds(3000), Instant.now().plusSeconds(3700));
            assertThat(token.get("mirrorKey").asString()).matches("[0-9a-f]{64}");
            // Minted for the run's repository with contents and pull requests only: never workflows.
            JsonNode mint = app.mintBodies(21).getLast();
            assertThat(mint.get("repositories")).extracting(JsonNode::asString).containsExactly("shop");
            assertThat(mint.get("permissions").properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("contents", "pull_requests");
            assertThat(mint.get("permissions").get("contents").asString()).isEqualTo("write");
            assertThat(mint.toString()).doesNotContain("workflows");
            String runId = assign.get("runId").asString();
            long epoch = assign.get("epoch").asLong();
            runner.accepted(UUID.fromString(runId), epoch);
            await().atMost(WAIT).until(() -> "RUNNING".equals(bench.member().get(bench.path("/runs/" + runId))
                .json().get("status").asString()));

            // The host renews it: the binding is checked again, and a new token minted.
            app.mints(21, "test-ghs-run-second");
            String componentId = assign.get("workspaces").get(0).get("componentId").asString();
            JsonNode renewed = runner.requestAndAck(UUID.fromString(runId), epoch, "GIT_TOKEN",
                    Map.of("componentIds", List.of(componentId)));
            assertThat(renewed.get("status").asString()).as("%s", renewed).isEqualTo("DONE");
            assertThat(renewed.get("payload").get("git")).singleElement().satisfies(git2 -> {
                assertThat(git2.get("componentId").asString()).isEqualTo(componentId);
                assertThat(git2.get("token").asString()).isEqualTo("test-ghs-run-second");
                assertThat(git2.get("expiresAt").asString()).isNotBlank();
            });
            assertThat(app.mintBodies(21).getLast().toString()).doesNotContain("workflows");
            // A repository that is not the run's is refused for good.
            JsonNode foreign = runner.requestAndAck(UUID.fromString(runId), epoch, "GIT_TOKEN",
                    Map.of("componentIds", List.of(UUID.randomUUID().toString())));
            assertThat(foreign.get("status").asString()).isEqualTo("REJECTED");
            assertThat(foreign.get("code").asString()).isEqualTo("GIT_REVOKED");

            // The credential disabled: the run's token is revoked, and a renewal refused for good.
            assertThat(bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/" + credential),
                    Map.of("enabled", false)).status()).isEqualTo(200);
            JsonNode revoke = runner.expect("run.git.revoke");
            assertThat(revoke.get("componentIds").get(0).asString()).isEqualTo(componentId);
            JsonNode refused = runner.requestAndAck(UUID.fromString(runId), epoch, "GIT_TOKEN",
                    Map.of("componentIds", List.of(componentId)));
            assertThat(refused.get("status").asString()).isEqualTo("REJECTED");
            assertThat(refused.get("code").asString()).isEqualTo("GIT_REVOKED");
            assertThat(refused.get("message").asString()).isNotBlank();
            assertThat(auditEvents(bench.owner(), bench.company(), "secret.accessed").stream()
                .filter(event -> ("run:" + runId).equals(event.get("details").path("purpose").asString()))
                .count()).isGreaterThanOrEqualTo(4);
            runner.assertFramesValid();
        }
    }

    @Test
    void aRunOnAnInstallationWaitsForAHostThatRenewsTokens() {
        Bench bench = bench(true);
        GitHubAppStub app = GitHubAppStub.create(616161);
        UUID[] git = gitHub(bench, app, "https://localhost/acme/shop");
        appCredential(bench, app, git[0], 31, "WRITE");
        app.mints(31, "test-ghs-never-sent");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(bench, List.of("run.git.revoke"))) {
            String runId = lastTurn(start(bench.member(), bench, agentId, "Fix the cart")).get("id").asString();
            await().atMost(WAIT).untilAsserted(() -> assertThat(bench.member().get(bench.path("/runs/" + runId))
                .json().path("queueReason").path("code").asString()).isEqualTo("NO_HOST_FOR_APP_TOKENS"));
            runner.expectNone("run.assign", Duration.ofSeconds(1));
            assertThat(app.mintBodies(31)).isEmpty();
        }
        // The App's page counts the connected hosts that cannot renew.
        assertThat(bench.owner().get(bench.path("/systems/" + git[0] + "/github-app")).json()
            .get("hostsConnected").asInt()).isZero();
    }

    @Test
    void aRepositoryTheInstallationDoesNotCoverEndsTheTurnWithAMessageThatNamesIt() {
        Bench bench = bench(true);
        GitHubAppStub app = GitHubAppStub.create(717171);
        UUID[] git = gitHub(bench, app, "https://localhost/other-org/shop");
        appCredential(bench, app, git[0], 41, "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(bench, RENEWS)) {
            String runId = lastTurn(start(bench.member(), bench, agentId, "Fix the cart")).get("id").asString();
            await().atMost(WAIT).until(() -> "CANCELLED".equals(bench.member().get(bench.path("/runs/" + runId))
                .json().get("status").asString()));
            assertThat(bench.member().get(bench.path("/runs/" + runId)).json().get("failureMessage").asString())
                .contains("other-org/shop", "install the App");
            runner.expectNone("run.assign", Duration.ofMillis(200));
            assertThat(app.mintBodies(41)).isEmpty();
        }
        assertThat(GitHubStub.url()).isNotBlank();
    }
}
