package com.flowtivo.tinfirm.company.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.company.DeclaredRunnerHosts;
import com.flowtivo.tinfirm.company.MemberPrincipals;
import com.flowtivo.tinfirm.company.internal.DeclaredCompaniesProperties.Declaration;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserEvents;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestDatabase;

/**
 * The deployment's declared companies (ADR-0075): each test applies declarations of its own, with keys of its own, to
 * the shared database, through a {@link DeclaredCompaniesService} built from the context's beans.
 */
class DeclaredCompaniesIntegrationTest extends IntegrationTest {

    @Autowired
    CompanyService companies;

    @Autowired
    CompanyQueries queries;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    UserDirectory users;

    @Autowired
    MemberPrincipals principals;

    @Autowired
    TenantScope tenantScope;

    @Autowired
    AuditLog auditLog;

    @Autowired
    TimeSource time;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    DeclaredRunnerHosts runnerHosts;

    @Test
    void createsADeclaredCompanyWithItsKeyAndMakesItsOwnerItsOwner() throws SQLException {
        String key = key();
        String owner = uniqueEmail("owner");
        UUID ownerId = createUser(admin(), owner, false);
        DeclaredCompaniesService declared = declaring(Map.of(key, new Declaration("Declared Co", owner, null)));

        declared.apply();

        UUID companyId = companyOf(key);
        try (TenantSql sql = TenantSql.in(companyId)) {
            assertThat(sql.column("SELECT name FROM company WHERE id = ?", companyId)).containsExactly("Declared Co");
            assertThat(sql.column("SELECT role FROM company_membership WHERE user_id = ?", ownerId))
                .containsExactly("OWNER");
            assertThat(actions(sql)).containsExactly("company.created", "member.added", "principal.created");
            assertThat(sql.column("SELECT actor_type FROM audit_event WHERE action = 'company.created'"))
                .containsExactly("SYSTEM");
            assertThat(sql.column("SELECT details->>'key' FROM audit_event WHERE action = 'company.created'"))
                .containsExactly(key);
            assertThat(sql.column("SELECT details->>'declaredOwner' FROM audit_event WHERE action = 'member.added'"))
                .containsExactly("true");
        }

        // At every start again: nothing changes.
        declared.apply();
        assertThat(companiesWith(key)).isOne();
        assertThat(companyOf(key)).isEqualTo(companyId);
        try (TenantSql sql = TenantSql.in(companyId)) {
            assertThat(actions(sql)).hasSize(3);
        }
    }

    /**
     * ADR-0077, decision 4: a declared company beyond the first waits for an Enterprise key, which applies it; one that
     * exists keeps working under any licence.
     */
    @Test
    void aDeclaredCompanyBeyondTheFirstWaitsForEnterprise() {
        String first = "a" + key();
        String second = "b" + key();
        Map<String, Declaration> declarations = new java.util.TreeMap<>(Map.of(first,
                new Declaration("First Co", null, null), second, new Declaration("Second Co", null, null)));
        com.flowtivo.tinfirm.shared.licence.Licensing community = TestLicences
            .licensing(com.flowtivo.tinfirm.shared.licence.LicenceStatus.COMMUNITY);

        declaring(declarations, null, community).apply();

        assertThat(companiesWith(first)).isOne();
        assertThat(companiesWith(second)).isZero();
        declaring(declarations, null).apply();
        assertThat(companiesWith(second)).isOne();
        declaring(declarations, null, community).apply();
        assertThat(companiesWith(first) + companiesWith(second)).as("both keep working").isEqualTo(2);
    }

    /**
     * A key applied in a transaction applies the waiting declarations after its commit, while the finished transaction
     * is still bound to the thread (LicenceChanged).
     */
    @Test
    void aKeyAppliedLaterAppliesTheDeclarationsThatWaitedAfterItsCommit() {
        String first = "a" + key();
        String second = "b" + key();
        Map<String, Declaration> declarations = new java.util.TreeMap<>(Map.of(first,
                new Declaration("First Co", null, null), second, new Declaration("Second Co", null, null)));
        declaring(declarations, null, TestLicences
            .licensing(com.flowtivo.tinfirm.shared.licence.LicenceStatus.COMMUNITY)).apply();
        assertThat(companiesWith(second)).isZero();

        DeclaredCompaniesService enterprise = declaring(declarations, null);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> org.springframework.transaction
            .support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            enterprise.licenceChanged(new com.flowtivo.tinfirm.shared.licence.LicenceChanged());
                        }
                    }));

        assertThat(companiesWith(second)).isOne();
    }

    /** The declared companies that exist count first: a new key that sorts before them waits under Community. */
    @Test
    void aNewDeclarationNeverTakesTheFirstPlaceOfOneThatExists() {
        String existing = "b" + key();
        String added = "a" + key();
        com.flowtivo.tinfirm.shared.licence.Licensing community = TestLicences
            .licensing(com.flowtivo.tinfirm.shared.licence.LicenceStatus.COMMUNITY);
        declaring(Map.of(existing, new Declaration("Existing Co", null, null)), null, community).apply();

        declaring(new java.util.TreeMap<>(Map.of(added, new Declaration("Added Co", null, null), existing,
                new Declaration("Existing Co", null, null))), null, community).apply();

        assertThat(companiesWith(existing)).isOne();
        assertThat(companiesWith(added)).isZero();
    }

    /** Adopting a company from before keys adds no company: it is never held back by the cap. */
    @Test
    void aCompanyFromBeforeKeysIsAdoptedWhateverTheLicence() {
        String first = "a" + key();
        String adopting = "b" + key();
        UUID before = createCompany(admin(), unique("Before Co"));
        com.flowtivo.tinfirm.shared.licence.Licensing community = TestLicences
            .licensing(com.flowtivo.tinfirm.shared.licence.LicenceStatus.COMMUNITY);
        declaring(Map.of(first, new Declaration("First Co", null, null)), null, community).apply();

        declaring(new java.util.TreeMap<>(Map.of(first, new Declaration("First Co", null, null), adopting,
                new Declaration("Adopted Co", null, before))), null, community).apply();

        assertThat(companyOf(adopting)).isEqualTo(before);
        // A second declaration of the same id is refused at the start, not held back by the cap.
        assertThatIllegalStateException().isThrownBy(() -> declaring(new java.util.TreeMap<>(Map.of(first,
                new Declaration("First Co", null, null), adopting, new Declaration("Adopted Co", null, before),
                "c" + key(), new Declaration("Again Co", null, before))), null, community).apply());
        // So is a name that several companies without a key have.
        String twice = unique("Twice Co");
        createCompany(admin(), twice);
        createCompany(admin(), twice);
        assertThatIllegalStateException().isThrownBy(() -> declaring(new java.util.TreeMap<>(Map.of(first,
                new Declaration("First Co", null, null), "c" + key(), new Declaration(twice, null, null))), null,
                community).apply());
    }

    @Test
    void anOwnerWithoutAnAccountBecomesTheOwnerAtTheirFirstSignIn() throws SQLException {
        String key = key();
        String owner = uniqueEmail("later");
        DeclaredCompaniesService declared = declaring(Map.of(key, new Declaration(unique("Later Co"), owner, null)));

        declared.apply();
        UUID companyId = companyOf(key);
        try (TenantSql sql = TenantSql.in(companyId)) {
            assertThat(sql.count("SELECT count(*) FROM company_membership")).isZero();
        }

        UUID ownerId = createUser(admin(), owner, false);
        declared.signedIn(new UserEvents.UserSignedIn(ownerId, true, java.util.Set.of()));
        declared.signedIn(new UserEvents.UserSignedIn(ownerId, true, java.util.Set.of()));

        try (TenantSql sql = TenantSql.in(companyId)) {
            assertThat(sql.column("SELECT role FROM company_membership WHERE user_id = ?", ownerId))
                .containsExactly("OWNER");
            assertThat(sql.count("SELECT count(*) FROM audit_event WHERE action = 'member.added'")).isOne();
        }
        assertThat(loginAs(owner).get("/api/companies/" + companyId).json().get("myRole").asString())
            .isEqualTo("OWNER");
    }

    @Test
    void aMemberWhoIsTheDeclaredOwnerIsPromotedWhileTheCompanyHasNoOwner() throws SQLException {
        String key = key();
        String owner = uniqueEmail("promoted");
        String other = uniqueEmail("other");
        DeclaredCompaniesService declared = declaring(Map.of(key, new Declaration(unique("Promote Co"), owner, null)));
        declared.apply();
        UUID companyId = companyOf(key);
        ApiClient admin = admin();
        UUID ownerId = createUser(admin, owner, false);
        UUID otherId = createUser(admin, other, false);
        // The instance admin's audited access with ADMIN's view adds members to a company without an owner.
        addMember(admin, companyId, owner, "MEMBER");
        addMember(admin, companyId, other, "MEMBER");

        // Another user's sign-in changes nothing.
        declared.signedIn(new UserEvents.UserSignedIn(otherId, true, java.util.Set.of()));
        declared.signedIn(new UserEvents.UserSignedIn(ownerId, true, java.util.Set.of()));

        try (TenantSql sql = TenantSql.in(companyId)) {
            assertThat(sql.column("SELECT role FROM company_membership WHERE user_id = ?", ownerId))
                .containsExactly("OWNER");
            assertThat(sql.column("SELECT role FROM company_membership WHERE user_id = ?", otherId))
                .containsExactly("MEMBER");
            assertThat(sql.column("SELECT details->>'previousRole' FROM audit_event"
                    + " WHERE action = 'member.role.changed' AND details->>'declaredOwner' = 'true'"))
                .containsExactly("MEMBER");
        }
    }

    /**
     * The declared owner's email asserted by another company's provider claims nothing: only a local sign-in, or one
     * through the company's own provider, does. At the start only a local user is made the owner.
     */
    @Test
    void anotherCompanysProviderNeverClaimsTheOwnership() throws SQLException {
        String key = key();
        String owner = uniqueEmail("asserted");
        UUID ownerId = insertOidcUser(owner);
        DeclaredCompaniesService declared = declaring(Map.of(key, new Declaration(unique("Claimed Co"), owner, null)));

        // At the start: an OIDC user's email is what some provider asserted.
        declared.apply();
        UUID companyId = companyOf(key);
        UUID otherCompany = createCompany(admin(), unique("Other Co"));
        // Through a provider of another company, or of none.
        declared.signedIn(new UserEvents.UserSignedIn(ownerId, false, java.util.Set.of(otherCompany)));
        declared.signedIn(new UserEvents.UserSignedIn(ownerId, false, java.util.Set.of()));
        try (TenantSql sql = TenantSql.in(companyId)) {
            assertThat(sql.count("SELECT count(*) FROM company_membership")).isZero();
        }

        // Through the company's own provider.
        declared.signedIn(new UserEvents.UserSignedIn(ownerId, false, java.util.Set.of(companyId)));
        try (TenantSql sql = TenantSql.in(companyId)) {
            assertThat(sql.column("SELECT role FROM company_membership WHERE user_id = ?", ownerId))
                .containsExactly("OWNER");
        }
    }

    @Test
    void adoptsACompanyFromBeforeKeysByItsIdAndKeepsItsOwner() throws SQLException {
        String key = key();
        ApiClient admin = admin();
        UUID companyId = createCompany(admin, unique("Before Co"));
        String declaredOwner = uniqueEmail("declared");
        UUID declaredOwnerId = createUser(admin, declaredOwner, false);

        declaring(Map.of(key, new Declaration("Another Name", declaredOwner, companyId))).apply();

        assertThat(companyOf(key)).isEqualTo(companyId);
        try (TenantSql sql = TenantSql.in(companyId)) {
            assertThat(sql.column("SELECT name FROM company WHERE id = ?", companyId)).doesNotContain("Another Name");
            assertThat(sql.column("SELECT details->>'adoptedBy' FROM audit_event WHERE action = 'company.updated'"))
                .containsExactly("id");
            // It has an owner (the admin who created it): the declared owner is not added.
            assertThat(sql.count("SELECT count(*) FROM company_membership WHERE user_id = ?", declaredOwnerId))
                .isZero();
        }
    }

    @Test
    void adoptsTheOnlyCompanyWithoutAKeyThatHasTheDeclaredName() {
        String key = key();
        String name = unique("Named Co");
        UUID companyId = createCompany(admin(), name);

        declaring(Map.of(key, new Declaration(name, null, null))).apply();

        assertThat(companyOf(key)).isEqualTo(companyId);
    }

    @Test
    void refusesADeclarationItCannotApply() {
        ApiClient admin = admin();
        String key = key();
        assertThatIllegalStateException()
            .isThrownBy(() -> declaring(Map.of(key, new Declaration("X", null, UUID.randomUUID()))).apply())
            .withMessageContaining("TINFIRM_COMPANIES_" + key.toUpperCase(Locale.ROOT) + "_ID names no company");

        String taken = key();
        declaring(Map.of(taken, new Declaration(unique("Taken Co"), null, null))).apply();
        UUID takenId = companyOf(taken);
        assertThatIllegalStateException()
            .isThrownBy(() -> declaring(Map.of(key, new Declaration("X", null, takenId))).apply())
            .withMessageContaining("already declared by the key " + taken);

        String twice = unique("Twice Co");
        createCompany(admin, twice);
        createCompany(admin, twice);
        assertThatIllegalStateException()
            .isThrownBy(() -> declaring(Map.of(key, new Declaration(twice, null, null))).apply())
            .withMessageContaining("2 companies without a key are named");
        assertThat(companiesWith(key)).isZero();
    }

    @Test
    void aKeyIsLowerCaseLettersAndDigits() {
        for (String key : List.of("Acme", "1acme", "acme-co", "a".repeat(41))) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DeclaredCompaniesProperties(Map.of(key, new Declaration("X", null, null))))
                .as(key)
                .withMessageContaining("lower-case letters and digits");
        }
        assertThat(new DeclaredCompaniesProperties(Map.of("acme2", new Declaration("X", null, null))).onlyKey())
            .contains("acme2");
    }

    /**
     * ADR-0101, decision 8: the declared runner host is registered with the declared token, which connects; a new
     * token replaces it, and the connection the earlier one opened ends.
     */
    @Test
    void registersTheDeclaredRunnerHostAndReplacesItsTokenWhenTheDeclarationsChanges() throws SQLException {
        String key = key();
        String first = token();
        String name = unique("Runner Co");
        declaring(Map.of(key, withRunnerHost(name, "chart-runner", first))).apply();

        UUID companyId = companyOf(key);
        UUID hostId;
        try (TenantSql sql = TenantSql.in(companyId)) {
            hostId = (UUID) sql.column("SELECT id FROM runner_host WHERE name = 'chart-runner' AND declared")
                .getFirst();
            assertThat(sql.column("SELECT scope FROM runner_host WHERE id = ?", hostId)).containsExactly("COMPANY");
            assertThat(sql.column("""
                    SELECT details->>'declared' FROM audit_event
                     WHERE action = 'runner_host.registered' AND target_id = ?""", hostId.toString()))
                .containsExactly("true");
        }
        try (FakeRunner runner = FakeRunner.connect(port, first)) {
            runner.helloAndWelcome(List.of());

            // Applied again with the same token: nothing changes, the connection stays.
            declaring(Map.of(key, withRunnerHost(name, "chart-runner", first))).apply();
            assertThat(runner.isClosed()).isFalse();

            String second = token();
            declaring(Map.of(key, withRunnerHost(name, "chart-runner", second))).apply();
            assertThat(runner.awaitClose()).isEqualTo(4401);
            try (FakeRunner again = FakeRunner.connect(port, second)) {
                assertThat(again.helloAndWelcome(List.of()).get("runnerHostId").asString())
                    .isEqualTo(hostId.toString());
            }
        }
        try (FakeRunner stale = FakeRunner.connect(port, first)) {
            assertThat(stale.awaitClose()).isEqualTo(4401);
        }
        try (TenantSql sql = TenantSql.in(companyId)) {
            assertThat(sql.count("SELECT count(*) FROM runner_host WHERE declared")).isOne();
            assertThat(sql.column("""
                    SELECT details->>'changed' FROM audit_event
                     WHERE action = 'runner_host.updated' AND target_id = ?""", hostId.toString()))
                .containsExactly("token");
        }
    }

    /** A host registered in the app is never taken over, and a refused token is never in the message. */
    @Test
    void refusesARunnerHostDeclarationItCannotApply() {
        String key = key();
        String variable = "TINFIRM_COMPANIES_" + key.toUpperCase(Locale.ROOT) + "_RUNNER_HOST";
        String name = unique("Hosts Co");
        declaring(Map.of(key, new Declaration(name, null, null))).apply();
        UUID companyId = companyOf(key);
        registerRunnerHost(admin(), companyId, "registered", "COMPANY");

        String token = token();
        assertThatIllegalStateException()
            .isThrownBy(() -> declaring(Map.of(key, withRunnerHost(name, "registered", token))).apply())
            .withMessageContaining(variable + "_NAME names the runner host 'registered', which was registered in "
                    + "the app");
        String malformed = "tfr_short-" + UUID.randomUUID();
        assertThatIllegalStateException()
            .isThrownBy(() -> declaring(Map.of(key, withRunnerHost(name, "chart", malformed))).apply())
            .withMessageContaining(variable + "_TOKEN is not a runner host token")
            .withMessageNotContaining(malformed);
        assertThatIllegalStateException()
            .isThrownBy(() -> declaring(Map.of(key, withRunnerHost(name, "-chart", token))).apply())
            .withMessageContaining(variable + "_NAME is not a runner host's name");
        assertThatIllegalStateException()
            .isThrownBy(() -> declaring(Map.of(key, withRunnerHost(name, "", token))).apply())
            .withMessageContaining(variable + "_NAME and " + variable + "_TOKEN go together");

        // Another company's declared host has the token.
        String other = key();
        declaring(Map.of(other, withRunnerHost(unique("Other Co"), "chart", token))).apply();
        assertThatIllegalStateException()
            .isThrownBy(() -> declaring(Map.of(key, withRunnerHost(name, "chart", token))).apply())
            .withMessageContaining(variable + "_TOKEN is another runner host's token")
            .withMessageNotContaining(token);
        // Blank values, as Compose passes unset ones, declare no host.
        declaring(Map.of(key, new Declaration(name, null, null, DeclaredCompaniesProperties.Scim.OFF,
                new DeclaredCompaniesProperties.RunnerHost("", "")))).apply();
    }

    private static Declaration withRunnerHost(String name, String hostName, String token) {
        return new Declaration(name, null, null, DeclaredCompaniesProperties.Scim.OFF,
                new DeclaredCompaniesProperties.RunnerHost(hostName, token));
    }

    /** A runner host token, as an operator makes one: {@code tfr_} and 43 base64url characters. */
    private static String token() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return "tfr_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A user as an OIDC provider creates one, without its login. */
    private static UUID insertOidcUser(String email) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection app = TestDatabase.connectAsApp();
                PreparedStatement insert = app.prepareStatement("""
                        INSERT INTO app_user (id, email, display_name, auth_source, oidc_issuer, oidc_subject,
                                              created_at, updated_at)
                        VALUES (?, ?, 'Asserted', 'OIDC', 'https://idp.example.test', ?, now(), now())""")) {
            insert.setObject(1, id);
            insert.setString(2, email);
            insert.setString(3, "sub-" + id);
            insert.executeUpdate();
        }
        return id;
    }

    private DeclaredCompaniesService declaring(Map<String, Declaration> declarations) {
        return declaring(declarations, null);
    }

    private DeclaredCompaniesService declaring(Map<String, Declaration> declarations, @Nullable String defaultOwner) {
        return declaring(declarations, defaultOwner, TestLicences.licensing(TestLicences.ENTERPRISE));
    }

    private DeclaredCompaniesService declaring(Map<String, Declaration> declarations, @Nullable String defaultOwner,
            com.flowtivo.tinfirm.shared.licence.Licensing licensing) {
        return new DeclaredCompaniesService(new DeclaredCompaniesProperties(declarations), defaultOwner, companies,
                queries, memberships, users, principals, tenantScope, auditLog, time, jdbc, transactionManager,
                licensing, runnerHosts);
    }

    /** The company the key declares, seen with the instance's rights. */
    private UUID companyOf(String key) {
        return instanceWide(() -> queries.companyIdByKey(key).orElseThrow());
    }

    private long companiesWith(String key) {
        return instanceWide(
                () -> jdbc.sql("SELECT count(*) FROM company WHERE key = ?").param(key).query(Long.class).single());
    }

    private <T> T instanceWide(Supplier<T> work) {
        return SystemContext.call(() -> new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    private static List<Object> actions(TenantSql sql) throws SQLException {
        return sql.column("SELECT action FROM audit_event ORDER BY seq");
    }

    /** A key of this test's own: the database is shared. */
    private static String key() {
        return "k" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xffffffffffL);
    }
}
