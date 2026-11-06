package com.flowtivo.tinfirm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * The two rules package 3 asks before it dispatches, as tables (the package 2 contract, §5, §11): who pays for a run
 * ({@link ModelAccess#forRun}: M2, M3, the redesign's V7, nobody else pays) and where it may run
 * ({@link Hosts#eligible}: M4, M5, V8, a personal host runs only its owner's work; the minimum runner version of a
 * gateway's runs). Also which models a key's list allows ({@link RunModelAccess#allows}), the one matcher of the
 * relay, of settings and of turns.
 */
class ModelAccessRulesTest extends IntegrationTest {

    private static final RuntimeKind CLAUDE = RuntimeKind.CLAUDE_CODE;

    @Autowired
    ModelAccess modelAccess;

    @Autowired
    Hosts hosts;

    @Test
    void aKeysModelsAllowAModelAsLiteLlmReadsThem() {
        // Empty, LiteLLM's two names for every model, and a lone *: every model.
        for (List<String> every : List.of(List.<String>of(), List.of("all-proxy-models"), List.of("all-team-models"),
                List.of("*"))) {
            assertThat(RunModelAccess.allows(every, "gpt-5.4")).as("%s", every).isTrue();
        }
        // A name allows itself only; a pattern ending in * the names it prefixes.
        assertThat(RunModelAccess.allows(List.of("claude-sonnet-4-5"), "claude-sonnet-4-5")).isTrue();
        assertThat(RunModelAccess.allows(List.of("claude-sonnet-4-5"), "claude-sonnet-4-5-20250929")).isFalse();
        assertThat(RunModelAccess.allows(List.of("gpt-5.4", "claude-*"), "claude-opus-4-1")).isTrue();
        assertThat(RunModelAccess.allows(List.of("claude-*"), "gpt-5.4")).isFalse();
        assertThat(RunModelAccess.allows(List.of("claude-*"), "anthropic/claude-opus-4-1")).isFalse();
        assertThat(new RunModelAccess.Budget(UUID.randomUUID(), List.of("claude-*")).allows("claude-opus-4-1"))
            .isTrue();
    }

    @Test
    void whoPaysForARun() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        ModelGateways.configureBundled(owner, company);
        UUID p = memberPrincipal(company, company.userId("MEMBER"));
        UUID q = memberPrincipal(company, company.userId("ADMIN"));
        UUID ok = createServiceIdentity(owner, company, unique("ok"));
        UUID overridden = createServiceIdentity(owner, company, unique("override"));
        UUID spent = createServiceIdentity(owner, company, unique("spent"));
        UUID resetPassed = createServiceIdentity(owner, company, unique("reset"));
        UUID refused = createServiceIdentity(owner, company, unique("refused"));
        UUID missing = createServiceIdentity(owner, company, unique("missing"));
        UUID suspended = createServiceIdentity(owner, company, unique("suspended"));
        for (UUID principal : List.of(p, q, ok, overridden, spent, resetPassed, refused, missing, suspended)) {
            ModelGateways.awaitStatus(owner, company, principal, "OK");
        }
        assertThat(owner.patch(company.path("/principals/" + overridden + "/model-access"),
                Map.of("models", List.of("gpt-5.4"))).status()).isEqualTo(200);
        assertThat(owner.post(company.path("/service-identities/" + suspended + "/suspend"), null).status())
            .isEqualTo(200);
        ModelGateways.awaitStatus(owner, company, suspended, "BLOCKED");
        Instant future = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update("UPDATE principal_model_key SET status = 'BUDGET_SPENT', budget_resets_at = ? "
                    + "WHERE principal_id = ?", future.atOffset(ZoneOffset.UTC), spent);
            sql.update("UPDATE principal_model_key SET status = 'BUDGET_SPENT', budget_resets_at = ? "
                    + "WHERE principal_id = ?", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), resetPassed);
            sql.update("UPDATE principal_model_key SET status = 'ERROR', last_error = 'LiteLLM refused the key (401)'"
                    + " WHERE principal_id = ?", refused);
            sql.update("DELETE FROM principal_model_key WHERE principal_id = ?", missing);
            sql.commit();
        }

        // Personal hosts: the member's with a subscription, connected; another member's; a company host.
        RunnerHostRef personal = registerRunnerHost(member, company.id(), unique("member-laptop"));
        RunnerHostRef othersHost = registerRunnerHost(loginAs(company.email("ADMIN")), company.id(),
                unique("admin-laptop"));
        RunnerHostRef companyHost = registerRunnerHost(owner, company.id(), unique("company"), "COMPANY");
        FakeRunner online = connectRunner(personal, List.of(FakeRunner.claudeCredential("claude-max"),
                FakeRunner.claudeCredential("claude-team")));
        connectRunner(othersHost, List.of(FakeRunner.claudeCredential("claude-max"))).close();

        record Row(String name, UUID payer, ModelAccessChoice access, String expected) {
        }
        List<Row> rows = List.of(
                new Row("budget, key OK", ok, ModelAccessChoice.BUDGET, "Budget"),
                new Row("budget, models overridden", overridden, ModelAccessChoice.BUDGET, "Budget"),
                new Row("budget spent until its reset", spent, ModelAccessChoice.BUDGET, "Wait BUDGET_SPENT"),
                new Row("budget spent, reset passed", resetPassed, ModelAccessChoice.BUDGET, "Budget"),
                new Row("key refused", refused, ModelAccessChoice.BUDGET, "Wait KEY_ERROR"),
                new Row("key not created", missing, ModelAccessChoice.BUDGET, "Wait KEY_ERROR"),
                new Row("suspended principal", suspended, ModelAccessChoice.BUDGET, "Refuse PRINCIPAL_INACTIVE"),
                new Row("subscription on the payer's host", p,
                        new ModelAccessChoice.Subscription(personal.id(), "claude-max"), "Subscription"),
                new Row("unknown label", p, new ModelAccessChoice.Subscription(personal.id(), "no-such-label"),
                        "Refuse SUBSCRIPTION_GONE"),
                new Row("another member's host", p, new ModelAccessChoice.Subscription(othersHost.id(), "claude-max"),
                        "Refuse SUBSCRIPTION_GONE"),
                new Row("a company host", p, new ModelAccessChoice.Subscription(companyHost.id(), "claude-max"),
                        "Refuse PERSONAL_HOST_CREDENTIAL_ONLY"),
                new Row("unknown host", p, new ModelAccessChoice.Subscription(UUID.randomUUID(), "claude-max"),
                        "Refuse SUBSCRIPTION_GONE"),
                new Row("suspended subscriber", suspended,
                        new ModelAccessChoice.Subscription(personal.id(), "claude-max"), "Refuse PRINCIPAL_INACTIVE"));
        for (Row row : rows) {
            RunModelAccess answer = forRun(company, row.payer(), row.access());
            assertThat(describe(answer)).as(row.name()).isEqualTo(row.expected());
            // V7: whoever pays is the principal the run named, never another.
            if (answer instanceof RunModelAccess.Budget budget) {
                assertThat(budget.principalId()).as(row.name()).isEqualTo(row.payer());
            }
        }
        assertThat(((RunModelAccess.Budget) forRun(company, ok, ModelAccessChoice.BUDGET)).models())
            .containsExactlyElementsOf(ModelGateways.DEFAULT_MODELS);
        assertThat(((RunModelAccess.Budget) forRun(company, overridden, ModelAccessChoice.BUDGET)).models())
            .containsExactly("gpt-5.4");
        RunModelAccess.Wait wait = (RunModelAccess.Wait) forRun(company, spent, ModelAccessChoice.BUDGET);
        assertThat(wait.until()).isEqualTo(future);
        assertThat(((RunModelAccess.Wait) forRun(company, refused, ModelAccessChoice.BUDGET)).detail())
            .contains("401");
        assertThat(((RunModelAccess.Refuse) forRun(company, p,
                new ModelAccessChoice.Subscription(companyHost.id(), "claude-max"))).problem().status().value())
            .isEqualTo(400);

        // The subscription's host goes offline; then reports the subscription no longer.
        online.close();
        await().atMost(ModelGateways.WAIT).until(() -> describe(forRun(company, p,
                new ModelAccessChoice.Subscription(personal.id(), "claude-max"))).equals("Wait HOST_OFFLINE"));
        connectRunner(personal, List.of(FakeRunner.claudeCredential("claude-team"))).close();
        assertThat(describe(forRun(company, p, new ModelAccessChoice.Subscription(personal.id(), "claude-max"))))
            .isEqualTo("Refuse SUBSCRIPTION_GONE");
        // The missing key is being created.
        ModelGateways.awaitStatus(owner, company, missing, "OK");
        assertThat(describe(forRun(company, missing, ModelAccessChoice.BUDGET))).isEqualTo("Budget");

        // A member who left pays for nothing.
        assertThat(owner.delete(company.path("/members/" + company.userId("MEMBER"))).status()).isEqualTo(204);
        assertThat(describe(forRun(company, p, ModelAccessChoice.BUDGET))).isEqualTo("Refuse PRINCIPAL_INACTIVE");
    }

    @Test
    void withoutATestedGatewayNoBudgetPays() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID p = memberPrincipal(company, company.userId("MEMBER"));
        assertThat(describe(forRun(company, p, ModelAccessChoice.BUDGET))).isEqualTo("Refuse GATEWAY_NOT_CONFIGURED");
        assertThat(owner.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 5))
            .status()).isEqualTo(200);
        RunModelAccess.Refuse refuse = (RunModelAccess.Refuse) forRun(company, p, ModelAccessChoice.BUDGET);
        assertThat(refuse.reason()).isEqualTo(RunModelAccess.RefuseReason.GATEWAY_NOT_CONFIGURED);
        assertThat(refuse.problem().code().name()).isEqualTo("GATEWAY_NOT_CONFIGURED");
    }

    @Test
    void whereARunMayExecute() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID p = memberPrincipal(company, company.userId("MEMBER"));
        UUID q = memberPrincipal(company, company.userId("ADMIN"));
        UUID service = createServiceIdentity(owner, company, unique("bot"));
        RunnerHostRef company1 = registerRunnerHost(owner, company.id(), unique("company-new"), "COMPANY");
        RunnerHostRef companyOld = registerRunnerHost(owner, company.id(), unique("company-old"), "COMPANY");
        RunnerHostRef companyCodex = registerRunnerHost(owner, company.id(), unique("company-codex"), "COMPANY");
        RunnerHostRef pHost = registerRunnerHost(member, company.id(), unique("p-laptop"));
        RunnerHostRef qHost = registerRunnerHost(admin, company.id(), unique("q-laptop"));
        // Before any host said hello, none has a version with the always-on model proxy: none takes a budget's run.
        Hosts.Eligibility.Eligible before = (Hosts.Eligibility.Eligible) eligible(company, p,
                ModelAccessChoice.BUDGET, CLAUDE, Set.of());
        assertThat(before.hosts()).isEmpty();
        assertThat(before.whyNone()).contains("0.2.0");
        hello(company1, "0.2.0", List.of("CLAUDE_CODE", "CODEX"), List.of());
        hello(companyOld, "0.1.9", List.of("CLAUDE_CODE", "CODEX"), List.of());
        hello(companyCodex, "0.3.0", List.of("CODEX"), List.of());
        hello(pHost, "0.2.1", List.of("CLAUDE_CODE"), List.of(FakeRunner.claudeCredential("claude-max")));
        hello(qHost, "0.2.0", List.of("CLAUDE_CODE"), List.of(FakeRunner.claudeCredential("claude-max")));

        record Row(String name, UUID payer, ModelAccessChoice access, RuntimeKind runtime, Set<UUID> holders,
                Set<UUID> expected) {
        }
        ModelAccessChoice pSubscription = new ModelAccessChoice.Subscription(pHost.id(), "claude-max");
        List<Row> rows = List.of(
                new Row("budget, no workspace: company hosts and the payer's own", p, ModelAccessChoice.BUDGET,
                        CLAUDE, Set.of(), Set.of(company1.id(), pHost.id())),
                new Row("budget, every account the payer's", p, ModelAccessChoice.BUDGET, CLAUDE, Set.of(p),
                        Set.of(company1.id(), pHost.id())),
                new Row("budget, a service identity's account", p, ModelAccessChoice.BUDGET, CLAUDE, Set.of(service),
                        Set.of(company1.id())),
                new Row("budget, another member's account (a grant)", p, ModelAccessChoice.BUDGET, CLAUDE,
                        Set.of(p, q), Set.of(company1.id())),
                new Row("budget, another runtime", p, ModelAccessChoice.BUDGET, RuntimeKind.CODEX, Set.of(),
                        Set.of(company1.id(), companyCodex.id())),
                new Row("a service identity's budget", service, ModelAccessChoice.BUDGET, CLAUDE, Set.of(service),
                        Set.of(company1.id())),
                new Row("subscription, every account the owner's", p, pSubscription, CLAUDE, Set.of(p),
                        Set.of(pHost.id())),
                new Row("subscription, no workspace", p, pSubscription, CLAUDE, Set.of(), Set.of(pHost.id())));
        for (Row row : rows) {
            Hosts.Eligibility eligibility = eligible(company, row.payer(), row.access(), row.runtime(), row.holders());
            assertThat(eligibility).as(row.name()).isInstanceOf(Hosts.Eligibility.Eligible.class);
            assertThat(((Hosts.Eligibility.Eligible) eligibility).hosts()).as(row.name())
                .extracting(RunnerHostInfo::id)
                .containsExactlyInAnyOrderElementsOf(row.expected());
        }

        // A subscription with an account that is not its owner's, or on another member's host, runs nowhere.
        for (Hosts.Eligibility nowhere : List.of(eligible(company, p, pSubscription, CLAUDE, Set.of(service)),
                eligible(company, p, pSubscription, CLAUDE, Set.of(p, q)),
                eligible(company, q, pSubscription, CLAUDE, Set.of(q)))) {
            assertThat(nowhere).isInstanceOf(Hosts.Eligibility.Nowhere.class);
            assertThat(((Hosts.Eligibility.Nowhere) nowhere).refusal().reason())
                .isEqualTo(RunModelAccess.RefuseReason.CANNOT_RUN_ANYWHERE);
            assertThat(((Hosts.Eligibility.Nowhere) nowhere).refusal().problem().code().name())
                .isEqualTo("CANNOT_RUN_ANYWHERE");
        }
        // A subscription's host that cannot run the runtime takes nothing, and says why.
        Hosts.Eligibility.Eligible codex = (Hosts.Eligibility.Eligible) eligible(company, p, pSubscription,
                RuntimeKind.CODEX, Set.of(p));
        assertThat(codex.hosts()).isEmpty();
        assertThat(codex.whyNone()).contains("CODEX");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private RunModelAccess forRun(TestCompany company, UUID payer, ModelAccessChoice access) {
        return SystemContext.callInCompany(company.id(),
                () -> modelAccess.forRun(new RunModelContext(company.id(), payer, access, CLAUDE, Set.of())));
    }

    private Hosts.Eligibility eligible(TestCompany company, UUID payer, ModelAccessChoice access, RuntimeKind runtime,
            Set<UUID> holders) {
        return SystemContext.callInCompany(company.id(),
                () -> hosts.eligible(new RunModelContext(company.id(), payer, access, runtime, holders)));
    }

    private static String describe(RunModelAccess answer) {
        return switch (answer) {
            case RunModelAccess.Budget budget -> "Budget";
            case RunModelAccess.Subscription subscription -> "Subscription";
            case RunModelAccess.Wait wait -> "Wait " + wait.reason();
            case RunModelAccess.Refuse refuse -> "Refuse " + refuse.reason();
        };
    }

    /** Connects the host once with this version, runtimes and credentials, so that it records them. */
    private void hello(RunnerHostRef host, String version, List<String> runtimes,
            List<Map<String, Object>> credentials) {
        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            Map<String, Object> hello = FakeRunner.hello(credentials, List.of(), 4, runtimes);
            hello.put("runnerVersion", version);
            runner.send(hello);
            runner.expect("welcome");
        }
    }
}
