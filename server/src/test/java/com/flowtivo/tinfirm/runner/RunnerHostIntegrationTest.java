package com.flowtivo.tinfirm.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestDatabase;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * Runner hosts over HTTP and their live state (docs/archive/v1/CONVENTIONS.md §14.1, §14.2, §15.1), company and
 * personal (the package 2 contract, §2, §8, M4, M5): who registers, sees, changes and removes which host; a personal
 * host's credentials are stored as its owner's, a company host's refused; a leaving member's personal hosts go. A
 * run the server does not know (the run module's reconciliation on {@code hello}) is cancelled and its frames are
 * answered with {@code UNKNOWN_RUN}. A personal host is a machine present in every company of its owner (ADR-0092):
 * it follows the owner's memberships, serves each company's runs over one connection, and goes everywhere at once.
 */
class RunnerHostIntegrationTest extends IntegrationTest {

    @Test
    void registrationReturnsTheTokenOnceAndStoresOnlyItsHash() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        String name = unique("my-laptop");

        // Without a scope a member registers their own personal host.
        ApiResponse registered = member.post(company.path("/runner-hosts"), Map.of("name", name));
        assertThat(registered.status()).isEqualTo(201);
        JsonNode body = registered.json();
        String token = body.get("token").asString();
        assertThat(token).matches("^tfr_[A-Za-z0-9_-]{43}$");
        assertThat(body.get("connectUrl").asString()).isEqualTo("ws://localhost:8080/api/runner/v1/connect");
        JsonNode host = body.get("runnerHost");
        UUID hostId = UUID.fromString(host.get("id").asString());
        UUID memberPrincipal = memberPrincipal(company, company.userId("MEMBER"));
        assertThat(host.get("name").asString()).isEqualTo(name);
        assertThat(host.get("scope").asString()).isEqualTo("PERSONAL");
        assertThat(host.get("ownerPrincipalId").asString()).isEqualTo(memberPrincipal.toString());
        assertThat(host.get("ownerDisplay").asString()).startsWith("User ");
        assertThat(host.get("online").asBoolean()).isFalse();
        assertThat(host.has("gatewayRuns")).isFalse();
        assertThat(host.get("activeRuns").asInt()).isZero();
        assertThat(host.get("credentials")).isEmpty();

        ApiResponse listed = member.get(company.path("/runner-hosts"));
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body()).doesNotContain(token);
        assertThat(listed.json().get("items")).hasSize(1);

        ApiResponse duplicate = member.post(company.path("/runner-hosts"), Map.of("name", name));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.problemCode()).isEqualTo("DUPLICATE_RUNNER_HOST_NAME");
        assertThat(member.post(company.path("/runner-hosts"), Map.of("name", "no spaces allowed")).status())
            .isEqualTo(400);

        try (Connection owner = TestDatabase.connectAsOwner();
                PreparedStatement query = owner.prepareStatement("""
                        SELECT m.token_hash FROM runner_host h JOIN personal_runner_host m ON m.id = h.personal_host_id
                         WHERE h.id = ?""")) {
            query.setObject(1, hostId);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo(HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))));
            }
        }
        JsonNode audit = auditEvents(loginAs(company.email("OWNER")), company, "runner_host.registered").getFirst();
        assertThat(audit.get("targetId").asString()).isEqualTo(hostId.toString());
        assertThat(audit.get("details").get("scope").asString()).isEqualTo("PERSONAL");
        assertThat(audit.get("details").get("ownerPrincipalId").asString()).isEqualTo(memberPrincipal.toString());
    }

    @Test
    void adminsSeeTheCompanyHostsAndEveryoneOnlyTheirOwnPersonalHosts() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));

        ApiResponse refused = member.post(company.path("/runner-hosts"),
                Map.of("name", unique("company"), "scope", "COMPANY"));
        assertThat(refused.status()).isEqualTo(403);
        RunnerHostRef companyHost = registerRunnerHost(admin, company.id(), unique("company"), "COMPANY");
        RunnerHostRef adminsHost = registerRunnerHost(admin, company.id(), unique("admin-laptop"));
        RunnerHostRef membersHost = registerRunnerHost(member, company.id(), unique("member-laptop"));

        assertThat(ids(owner, company)).containsExactlyInAnyOrder(companyHost.id());
        assertThat(ids(admin, company)).containsExactlyInAnyOrder(companyHost.id(), adminsHost.id());
        assertThat(ids(member, company)).containsExactlyInAnyOrder(membersHost.id());
        JsonNode listed = owner.get(company.path("/runner-hosts")).json().get("items").get(0);
        assertThat(listed.get("scope").asString()).isEqualTo("COMPANY");
        assertThat(listed.has("ownerPrincipalId")).isFalse();
        assertThat(listed.has("ownerDisplay")).isFalse();
        JsonNode audit = auditEvents(owner, company, "runner_host.registered").stream()
            .filter(event -> event.get("targetId").asString().equals(companyHost.id().toString()))
            .findFirst()
            .orElseThrow();
        assertThat(audit.get("details").get("scope").asString()).isEqualTo("COMPANY");
        assertThat(audit.get("details").has("ownerPrincipalId")).isFalse();
    }

    @Test
    void aPersonalHostsCredentialsAreItsOwnersAndAreStored() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("host"));

        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            JsonNode welcome = runner.helloAndWelcome(List.of(FakeRunner.claudeCredential("claude-max"),
                    FakeRunner.credential("codex-plus", "CODEX_CHATGPT_AUTH", List.of("CODEX"), 1)));
            // A personal host's welcome names the machine, not its presence in one company (ADR-0092).
            assertThat(welcome.get("runnerHostId").asString()).isNotEqualTo(host.id().toString());
            assertThat(welcome.get("companyId").asString()).isEqualTo(company.id().toString());
            assertThat(welcome.get("heartbeatIntervalSeconds").asInt()).isEqualTo(10);
            assertThat(welcome.get("leaseTtlSeconds").asInt()).isEqualTo(30);

            JsonNode listed = owner.get(company.path("/runner-hosts")).json().get("items").get(0);
            assertThat(listed.get("online").asBoolean()).isTrue();
            assertThat(listed.get("version").asString()).isEqualTo("0.1.0-test");
            assertThat(listed.get("hostname").asString()).isEqualTo("test-host");
            assertThat(listed.get("os").asString()).isEqualTo("linux");
            assertThat(listed.get("arch").asString()).isEqualTo("x86_64");
            assertThat(listed.has("lastSeenAt")).isTrue();
            assertThat(listed.get("credentials")).extracting(credential -> credential.get("label").asString())
                .containsExactly("claude-max", "codex-plus");
            JsonNode claude = listed.get("credentials").get(0);
            assertThat(claude.get("kind").asString()).isEqualTo("CLAUDE_LOGIN");
            assertThat(claude.get("runtimes").get(0).asString()).isEqualTo("CLAUDE_CODE");
            assertThat(claude.get("status").asString()).isEqualTo("AVAILABLE");
            assertThat(claude.get("reported").asBoolean()).isTrue();

            // A credential.status of a label the hello reported is stored; another is an error.
            runner.send(Map.of("type", "credential.status", "label", "claude-max", "status", "INVALID", "detail",
                    "token expired"));
            runner.expectNone("error", Duration.ofMillis(500));
            assertThat(credential(owner, company, "claude-max").get("status").asString()).isEqualTo("INVALID");
            runner.send(Map.of("type", "credential.status", "label", "unknown-label", "status", "AVAILABLE"));
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("UNKNOWN_CREDENTIAL");
            runner.assertFramesValid();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode offline = owner.get(company.path("/runner-hosts")).json().get("items").get(0);
            assertThat(offline.get("online").asBoolean()).isFalse();
            assertThat(offline.get("credentials").get(0).get("status").asString()).isEqualTo("OFFLINE");
        });

        // A hello that no longer lists a credential leaves it unreported; one it lists again starts AVAILABLE.
        try (FakeRunner again = FakeRunner.connect(port, host.token())) {
            again.helloAndWelcome(List.of(FakeRunner.claudeCredential("claude-max")));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(credential(owner, company, "codex-plus").get("reported").asBoolean()).isFalse();
                assertThat(credential(owner, company, "claude-max").get("status").asString()).isEqualTo("AVAILABLE");
            });
            again.send(Map.of("type", "credential.status", "label", "codex-plus", "status", "AVAILABLE"));
            assertThat(again.expect("error").get("code").asString()).isEqualTo("UNKNOWN_CREDENTIAL");
            again.assertFramesValid();
        }
    }

    @Test
    void aCredentialOfAKindTheServerDoesNotKnowIsIgnoredAndTheHostTold() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("host"));

        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            // A host before ADR-0085 still holds a `setup-token` token: the login beside it serves, the token is named.
            Map<String, Object> hello = FakeRunner.hello(List.of(
                    FakeRunner.credential("claude-old", "CLAUDE_OAUTH_TOKEN", List.of("CLAUDE_CODE"), 3),
                    FakeRunner.claudeCredential("claude-max")), List.of());
            runner.send(hello);
            runner.expect("welcome");
            runner.expect("host.config");
            JsonNode error = runner.expect("error");
            assertThat(error.get("code").asString()).isEqualTo("UNKNOWN_CREDENTIAL_KIND");
            assertThat(error.get("message").asString()).contains("claude-old").contains("Upgrade the runner host");

            JsonNode listed = owner.get(company.path("/runner-hosts")).json().get("items").get(0);
            assertThat(listed.get("online").asBoolean()).isTrue();
            assertThat(listed.get("credentials")).extracting(credential -> credential.get("label").asString())
                .containsExactly("claude-max");
            // The old kind is not in the schema: the hello of this test is invalid by design, so no frame check here.
        }
    }

    @Test
    void aCompanyHostHoldsNoCredentialsAndReportsItsVersion() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        RunnerHostRef host = registerRunnerHost(admin, company.id(), unique("company"), "COMPANY");

        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            Map<String, Object> hello = FakeRunner.hello(List.of(FakeRunner.claudeCredential("claude-max")), List.of());
            hello.put("runnerVersion", "9.9.9");
            runner.send(hello);
            runner.expect("welcome");
            runner.expect("host.config");
            JsonNode error = runner.expect("error");
            assertThat(error.get("code").asString()).isEqualTo("PERSONAL_HOST_CREDENTIAL_ONLY");
            runner.send(Map.of("type", "credential.status", "label", "claude-max", "status", "INVALID"));
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("UNKNOWN_CREDENTIAL");

            JsonNode listed = admin.get(company.path("/runner-hosts")).json().get("items").get(0);
            assertThat(listed.get("online").asBoolean()).isTrue();
            assertThat(listed.get("credentials")).isEmpty();
            // The version is stored and shown as the host said it, and decides nothing (ADR-0109).
            assertThat(listed.get("version").asString()).isEqualTo("9.9.9");
            runner.assertFramesValid();
        }
    }

    /** ADR-0100: a host that lists ready sandboxes without the bridge is refused as a bridged one is. */
    @Test
    void aCommunityServerRefusesAHostWithASandboxPool() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        RunnerHostRef host = registerRunnerHost(admin, company.id(), unique("pooled"), "COMPANY");

        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            Map<String, Object> hello = FakeRunner.helloWithPool(2, 3);
            hello.put("features", List.of("run.steer", "session.bundle", "sandbox.pool"));
            runner.send(hello);
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("EDITION_REQUIRED");
            assertThat(runner.awaitClose()).isEqualTo(1008);
            assertThat(runner.received()).noneMatch(frame -> frame.get("type").asString().equals("welcome"));
            runner.assertFramesValid();
        }
    }

    /** ADR-0100: without elastic runners in the licence, a host with the sandbox bridge stops at its hello. */
    @Test
    void aCommunityServerRefusesAHostWithTheSandboxBridge() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        RunnerHostRef host = registerRunnerHost(admin, company.id(), unique("bridged"), "COMPANY");

        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            Map<String, Object> hello = FakeRunner.helloV2(List.of(), List.of());
            hello.put("features", List.of("run.steer", "session.bundle", "sandbox.bridge"));
            runner.send(hello);
            JsonNode error = runner.expect("error");
            assertThat(error.get("code").asString()).isEqualTo("EDITION_REQUIRED");
            assertThat(error.get("message").asString()).contains("ELASTIC_RUNNERS", "--sandbox-bridge");
            assertThat(runner.awaitClose()).isEqualTo(1008);
            assertThat(runner.received()).noneMatch(frame -> frame.get("type").asString().equals("welcome"));
            runner.assertFramesValid();
        }
        assertThat(admin.get(company.path("/runner-hosts")).json().get("items").get(0).get("online").asBoolean())
            .isFalse();

        // The same host with Docker alone, as Community runs it.
        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            Map<String, Object> hello = FakeRunner.helloV2(List.of(), List.of());
            hello.put("features", List.of("run.steer", "session.bundle"));
            runner.send(hello);
            runner.expect("welcome");
            runner.expect("host.config");
            runner.assertFramesValid();
        }
    }

    @Test
    void aRunTheServerDoesNotKnowIsCancelled() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("host"));
        UUID stale = UUID.randomUUID();

        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            // A run the host still reports but the server does not know is cancelled; its frames get UNKNOWN_RUN.
            runner.helloAndWelcome(List.of(), List.of(Map.of("runId", stale.toString(), "epoch", 3, "lastSeq", 0)));
            JsonNode cancel = runner.expect("run.cancel");
            assertThat(cancel.get("runId").asString()).isEqualTo(stale.toString());
            assertThat(cancel.get("epoch").asInt()).isEqualTo(3);
            runner.accepted(stale, 3);
            JsonNode error = runner.expect("error");
            assertThat(error.get("code").asString()).isEqualTo("UNKNOWN_RUN");
            assertThat(error.get("runId").asString()).isEqualTo(stale.toString());
            runner.assertFramesValid();
        }
    }

    @Test
    void adminsRemoveCompanyHostsAndOwnersTheirPersonalOnes() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        RunnerHostRef companyHost = registerRunnerHost(owner, company.id(), unique("company-host"), "COMPANY");
        RunnerHostRef membersHost = registerRunnerHost(member, company.id(), unique("member-host"));

        assertThat(member.delete(company.path("/runner-hosts/" + companyHost.id())).status()).isEqualTo(403);
        // Another member's personal host is not there for an admin, as an unknown id.
        assertThat(admin.delete(company.path("/runner-hosts/" + membersHost.id())).status()).isEqualTo(404);
        assertThat(member.delete(company.path("/runner-hosts/" + membersHost.id())).status()).isEqualTo(204);
        assertThat(member.delete(company.path("/runner-hosts/" + membersHost.id())).status()).isEqualTo(404);
        assertThat(admin.delete(company.path("/runner-hosts/" + companyHost.id())).status()).isEqualTo(204);
        assertThat(owner.get(company.path("/runner-hosts")).json().get("items")).isEmpty();
        List<JsonNode> removed = auditEvents(owner, company, "runner_host.removed");
        assertThat(removed).extracting(event -> event.get("details").get("scope").asString())
            .containsExactly("COMPANY", "PERSONAL");
    }

    @Test
    void aMemberWhoLeavesTheirOnlyCompanyTakesTheirPersonalHostsAlong() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        RunnerHostRef membersHost = registerRunnerHost(member, company.id(), unique("member-host"));
        RunnerHostRef companyHost = registerRunnerHost(owner, company.id(), unique("company-host"), "COMPANY");
        FakeRunner runner = connectRunner(membersHost, List.of(FakeRunner.claudeCredential("claude-max")));

        ApiResponse leave = owner.delete(company.path("/members/" + company.userId("MEMBER")));
        assertThat(leave.status()).as("%s", leave).isEqualTo(204);
        // The host's presence in the company is gone, and the company sees the machine no more.
        assertThat(ids(owner, company)).containsExactly(companyHost.id());
        JsonNode removed = auditEvents(owner, company, "runner_host.removed").getFirst();
        assertThat(removed.get("targetId").asString()).isEqualTo(membersHost.id().toString());
        assertThat(removed.get("details").get("cause").asString()).isEqualTo("principal.left");
        assertThat(removed.get("details").has("personalHostId")).isTrue();
        // The machine stays the person's, with its token, in no company: its connection goes on (ADR-0092).
        runner.send(Map.of("type", "credential.status", "label", "claude-max", "status", "INVALID"));
        runner.expectNone("error", Duration.ofMillis(500));
        assertThat(runner.isClosed()).isFalse();
        runner.close();
        FakeRunner again = FakeRunner.connect(port, membersHost.token());
        JsonNode welcome = again.helloAndWelcome(List.of(FakeRunner.claudeCredential("claude-max")));
        assertThat(welcome.get("companyId").asString()).isEqualTo(new UUID(0, 0).toString());
        again.assertFramesValid();
        again.close();
    }

    @Test
    void aPersonalHostIsPresentInEveryCompanyOfItsOwnerAndFollowsTheirMemberships() {
        TestCompany a = companyWithRoles();
        TestCompany b = companyWithRoles();
        TestCompany c = companyWithRoles();
        ApiClient ownerOfB = loginAs(b.email("OWNER"));
        ApiClient ownerOfC = loginAs(c.email("OWNER"));
        String email = a.email("MEMBER");
        addMember(ownerOfB, b.id(), email, "MEMBER");
        ApiClient member = loginAs(email);
        String name = unique("laptop");

        // Registered in A, the host is present in B too: another row, the same name, machine and facts.
        RunnerHostRef host = registerRunnerHost(member, a.id(), name);
        JsonNode inA = member.get(a.path("/runner-hosts")).json().get("items").get(0);
        JsonNode inB = member.get(b.path("/runner-hosts")).json().get("items").get(0);
        UUID presenceInB = UUID.fromString(inB.get("id").asString());
        assertThat(inA.get("id").asString()).isEqualTo(host.id().toString());
        assertThat(presenceInB).isNotEqualTo(host.id());
        assertThat(inB.get("name").asString()).isEqualTo(name);
        assertThat(inB.get("scope").asString()).isEqualTo("PERSONAL");
        assertThat(inB.get("ownerPrincipalId").asString())
            .isEqualTo(memberPrincipal(b, a.userId("MEMBER")).toString());
        // Each company's chain has its own registration, naming the machine.
        JsonNode registeredInB = auditEvents(ownerOfB, b, "runner_host.registered").getFirst();
        assertThat(registeredInB.get("targetId").asString()).isEqualTo(presenceInB.toString());
        assertThat(registeredInB.get("details").get("personalHostId").asString())
            .isEqualTo(auditEvents(loginAs(a.email("OWNER")), a, "runner_host.registered").getFirst()
                .get("details").get("personalHostId").asString());
        // The same name again, in B this time, is the same person's machine: a duplicate.
        assertThat(member.post(b.path("/runner-hosts"), Map.of("name", name)).status()).isEqualTo(409);
        // Another member's host of that name is theirs.
        registerRunnerHost(loginAs(b.email("ADMIN")), b.id(), name);

        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            JsonNode welcome = runner.helloAndWelcome(List.of(FakeRunner.claudeCredential("claude-max")));
            assertThat(runner.expect("host.config").get("version").asLong()).isZero();
            // One connection serves every company: online, with the credentials and the slots, in each.
            assertThat(welcome.get("companyId").asString()).isIn(a.id().toString(), b.id().toString());
            for (TestCompany company : List.of(a, b)) {
                JsonNode listed = member.get(company.path("/runner-hosts")).json().get("items").get(0);
                assertThat(listed.get("online").asBoolean()).as("online in %s", company.id()).isTrue();
                assertThat(listed.get("hostname").asString()).isEqualTo("test-host");
                assertThat(listed.get("credentials")).extracting(credential -> credential.get("label").asString())
                    .containsExactly("claude-max");
            }
            // A capacity change in B is the machine's: A sees it, and the host gets one host.config.
            ApiResponse updated = member.patch(b.path("/runner-hosts/" + presenceInB),
                    Map.of("desiredMaxConcurrentRuns", 2));
            assertThat(updated.status()).as("%s", updated).isEqualTo(200);
            assertThat(runner.expect("host.config").get("desiredMaxConcurrentRuns").asInt()).isEqualTo(2);
            runner.expectNone("host.config", Duration.ofMillis(500));
            JsonNode inANow = member.get(a.path("/runner-hosts")).json().get("items").get(0);
            assertThat(inANow.get("desiredMaxConcurrentRuns").asInt()).isEqualTo(2);

            // A run of B on its presence there is the host's, over the same connection, as a run of A is.
            UUID runOfA = UUID.randomUUID();
            UUID runOfB = UUID.randomUUID();
            TestRuns.running(a.id(), runOfA, 1, host.id(), memberPrincipal(a, a.userId("MEMBER")));
            TestRuns.running(b.id(), runOfB, 1, presenceInB, memberPrincipal(b, a.userId("MEMBER")));
            runner.accepted(runOfB, 1);
            runner.accepted(runOfA, 1);
            runner.send(Map.of("type", "run.heartbeat", "runs", List.of(
                    Map.of("runId", runOfA.toString(), "epoch", 1), Map.of("runId", runOfB.toString(), "epoch", 1))));
            runner.expectNone("error", Duration.ofMillis(500));
            runner.expectNone("run.cancel", Duration.ofMillis(200));
            // Removing it while B's run runs is refused, from A as well.
            assertThat(member.delete(a.path("/runner-hosts/" + host.id())).status()).isEqualTo(409);
            TestRuns.ended(b.id(), runOfB);
            TestRuns.ended(a.id(), runOfA);

            // Joining C brings the machine along; leaving B takes only the presence there, the connection stays.
            addMember(ownerOfC, c.id(), email, "MEMBER");
            JsonNode inC = member.get(c.path("/runner-hosts")).json().get("items").get(0);
            assertThat(inC.get("name").asString()).isEqualTo(name);
            assertThat(inC.get("online").asBoolean()).isTrue();
            assertThat(auditEvents(ownerOfC, c, "runner_host.registered").getFirst().get("details").get("cause")
                .asString()).isEqualTo("principal.joined");
            assertThat(ownerOfB.delete(b.path("/members/" + a.userId("MEMBER"))).status()).isEqualTo(204);
            assertThat(member.get(b.path("/runner-hosts")).status()).isEqualTo(404);
            assertThat(ids(loginAs(b.email("ADMIN")), b)).doesNotContain(presenceInB);
            assertThat(runner.isClosed()).isFalse();
            assertThat(member.get(a.path("/runner-hosts")).json().get("items").get(0).get("online").asBoolean())
                .isTrue();
            // A run of B on the old presence is unknown to the host now; joined again, the host serves B's new
            // presence over the same connection, without a reconnect.
            UUID staleRun = UUID.randomUUID();
            runner.accepted(staleRun, 1);
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("UNKNOWN_RUN");
            addMember(ownerOfB, b.id(), email, "MEMBER");
            UUID presenceInBAgain = UUID.fromString(member.get(b.path("/runner-hosts")).json().get("items").get(0)
                .get("id").asString());
            assertThat(presenceInBAgain).isNotEqualTo(presenceInB);
            UUID runOfBAgain = UUID.randomUUID();
            TestRuns.running(b.id(), runOfBAgain, 1, presenceInBAgain, memberPrincipal(b, a.userId("MEMBER")));
            runner.accepted(runOfBAgain, 1);
            runner.expectNone("error", Duration.ofMillis(500));
            TestRuns.ended(b.id(), runOfBAgain);

            // Removed in C, the machine is gone from A too, with its token, and the connection is closed.
            UUID presenceInC = UUID.fromString(inC.get("id").asString());
            assertThat(member.delete(c.path("/runner-hosts/" + presenceInC)).status()).isEqualTo(204);
            assertThat(runner.awaitClose()).isEqualTo(4401);
            assertThat(ids(member, a)).isEmpty();
            assertThat(auditEvents(loginAs(a.email("OWNER")), a, "runner_host.removed").getFirst().get("targetId")
                .asString()).isEqualTo(host.id().toString());
            FakeRunner again = FakeRunner.connect(port, host.token());
            assertThat(again.awaitClose()).isEqualTo(4401);
        }
    }

    @Test
    void theDatabaseKeepsCredentialsOnMachinesAndScopesFixed() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        RunnerHostRef companyHost = registerRunnerHost(owner, company.id(), unique("company-host"), "COMPANY");
        RunnerHostRef personalHost = registerRunnerHost(owner, company.id(), unique("personal-host"));
        try (TenantSql sql = TenantSql.in(company.id())) {
            UUID machine = (UUID) sql.column("SELECT personal_host_id FROM runner_host WHERE id = ?",
                    personalHost.id()).getFirst();
            // M5: a credential is a machine's (ADR-0092); a company host, which has no machine, holds none.
            sql.accepted("""
                    INSERT INTO personal_runner_credential (id, personal_host_id, label, kind, runtimes, pool_size,
                                                            created_at, updated_at)
                    VALUES (?, ?, 'claude', 'CLAUDE_LOGIN', ARRAY['CLAUDE_CODE']::varchar(16)[], 1, now(), now())""",
                    UUID.randomUUID(), machine);
            assertThat(sql.refused("UPDATE runner_host SET personal_host_id = ? WHERE id = ?", machine,
                    companyHost.id())).isEqualTo("runner_host_personal_ck");
            // A machine's presence carries no token of its own; a company host does.
            assertThat(sql.refused("UPDATE runner_host SET token_hash = repeat('c', 64) WHERE id = ?",
                    personalHost.id())).isEqualTo("runner_host_token_ck");
            assertThat(sql.refused("UPDATE runner_host SET token_hash = NULL WHERE id = ?", companyHost.id()))
                .isEqualTo("runner_host_token_ck");
            // One presence per machine and company.
            assertThat(sql.refused("""
                    INSERT INTO runner_host (id, company_id, name, scope, owner_principal_id, personal_host_id,
                                             created_at)
                    SELECT ?, company_id, 'twice', scope, owner_principal_id, personal_host_id, now()
                      FROM runner_host WHERE id = ?""", UUID.randomUUID(), personalHost.id()))
                .isEqualTo("runner_host_presence_uq");
            // M4: the scope and owner of a host never change; a company host has no owner, a personal one has.
            assertThat(sql.refused("UPDATE runner_host SET scope = 'PERSONAL' WHERE id = ?", companyHost.id()))
                .isEqualTo("runner_host_scope_fixed_ck");
            assertThat(sql.refused("UPDATE runner_host SET owner_principal_id = NULL WHERE id = ?", personalHost.id()))
                .isEqualTo("runner_host_scope_fixed_ck");
            assertThat(sql.refused("""
                    INSERT INTO runner_host (id, company_id, name, scope, personal_host_id, created_at)
                    VALUES (?, ?, 'ownerless', 'PERSONAL', ?, now())""", UUID.randomUUID(), company.id(), machine))
                .isEqualTo("runner_host_owner_ck");
            UUID service = createServiceIdentity(owner, company, unique("bot"));
            UUID other = UUID.randomUUID();
            sql.accepted("""
                    INSERT INTO personal_runner_host (id, owner_user_id, name, token_hash, created_at)
                    VALUES (?, ?, 'other', repeat('d', 64), now())""", other, company.userId("OWNER"));
            assertThat(sql.refused("""
                    INSERT INTO runner_host (id, company_id, name, scope, owner_principal_id, personal_host_id,
                                             created_at)
                    VALUES (?, ?, 'bots', 'PERSONAL', ?, ?, now())""", UUID.randomUUID(), company.id(), service,
                    other))
                .isEqualTo("runner_host_owner_fk");
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static List<UUID> ids(ApiClient actor, TestCompany company) {
        List<UUID> ids = new ArrayList<>();
        actor.get(company.path("/runner-hosts")).json().get("items")
            .forEach(host -> ids.add(UUID.fromString(host.get("id").asString())));
        return ids;
    }

    private static JsonNode credential(ApiClient actor, TestCompany company, String label) {
        for (JsonNode credential : actor.get(company.path("/runner-hosts")).json().get("items").get(0)
            .get("credentials")) {
            if (credential.get("label").asString().equals(label)) {
                return credential;
            }
        }
        throw new AssertionError("No credential " + label);
    }
}
