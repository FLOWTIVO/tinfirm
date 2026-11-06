package com.flowtivo.tinfirm.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.flowtivo.tinfirm.company.CompanyProvisioning;
import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.shared.security.SystemContext;

import tools.jackson.databind.JsonNode;

/**
 * Base class of the HTTP integration tests: the real server on a random port, backed by {@link TestDatabase} as
 * {@code tinfirm_app}. Tests share one database, so every test creates its own users and companies with unique
 * names instead of cleaning up (the audit log cannot be deleted anyway).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ TestOutbound.class, TestLicences.class })
public abstract class IntegrationTest {

    /** Bootstrap admin from application-test.yaml (test fixture). */
    public static final String ADMIN_EMAIL = "admin@tinfirm.test";

    public static final String ADMIN_PASSWORD = "test-admin-password-1";

    /** Password of every user the tests create (test fixture). */
    public static final String PASSWORD = "test-password-123";

    @LocalServerPort
    protected int port;

    @Autowired
    private CompanyProvisioning companyProvisioning;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> TestDatabase.APP);
        registry.add("spring.datasource.password", () -> TestDatabase.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> TestDatabase.OWNER);
        registry.add("spring.flyway.password", () -> TestDatabase.OWNER_PASSWORD);
        TestIdp.register(registry);
        // Started here, before any context builds the outbound HTTP client, so that the client trusts the stub's TLS
        // certificate (an https system, GitLab's API at the system's origin).
        GitLabStub.server();
        // The deployment's bundled LiteLLM (the package 2 contract, §6): the fake, shared by every test.
        registry.add("tinfirm.model.litellm-admin-url", () -> FakeLiteLlm.bundled().url());
        // Slack and the Bot Framework (the package 5 contract): their fixed endpoints are the stubs'.
        registry.add("tinfirm.chat.endpoints.slack-api", FakeSlack::apiUrl);
        registry.add("tinfirm.chat.endpoints.slack-files", FakeSlack::filesUrl);
        registry.add("tinfirm.chat.endpoints.slack-avatars", FakeSlack::filesUrl);
        registry.add("tinfirm.chat.endpoints.slack-socket-hosts", () -> "localhost");
        registry.add("tinfirm.chat.endpoints.teams-service", FakeBotFramework::url);
        registry.add("tinfirm.chat.endpoints.teams-login", FakeBotFramework::url);
        registry.add("tinfirm.chat.endpoints.teams-open-id", FakeBotFramework::openIdUrl);
        // Google for the Gmail add-on (ADR-0083): its keys and the Gmail API are the stub's, and its project the test's.
        registry.add("tinfirm.gmail.endpoints.google-keys", FakeGoogle::keysUrl);
        registry.add("tinfirm.gmail.endpoints.gmail-api", FakeGoogle::url);
        registry.add("tinfirm.gmail.service-account", () -> FakeGoogle.SERVICE_ACCOUNT);
        registry.add("tinfirm.gmail.client-id", () -> FakeGoogle.CLIENT_ID);
        // The vendor's error tracker (ADR-0070): the stub's DSN, so that an instance admin may turn delivery on.
        registry.add("tinfirm.error-reports.dsn", SentryStub::dsn);
    }

    /** The shared stub servers forget the requests of this class's tests, which verified them ({@link StubServers}). */
    @AfterAll
    static void forgetStubRequests() {
        StubServers.forgetRequests();
    }

    protected ApiClient client() {
        return new ApiClient(port);
    }

    protected ApiClient admin() {
        return loginAs(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    protected ApiClient loginAs(String email) {
        return loginAs(email, PASSWORD);
    }

    protected ApiClient loginAs(String email, String password) {
        ApiClient client = client();
        ApiResponse response = client.login(email, password);
        assertThat(response.status()).as("login of %s: %s", email, response).isEqualTo(200);
        return client;
    }

    protected static String unique(String name) {
        return name + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xffffffffffL);
    }

    protected static String uniqueEmail(String name) {
        return unique(name) + "@example.test";
    }

    protected UUID createUser(ApiClient admin, String email, boolean instanceAdmin) {
        ApiResponse response = admin.post("/api/admin/users",
                Map.of("email", email, "displayName", "User " + email, "password", PASSWORD, "instanceAdmin",
                        instanceAdmin));
        assertThat(response.status()).as("create user %s: %s", email, response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    /**
     * A new company with the caller as its OWNER. No endpoint creates companies, which the deployment declares
     * (ADR-0075): the tests create theirs through the company module, as SYSTEM.
     */
    protected UUID createCompany(ApiClient admin, String name) {
        UUID owner = UUID.fromString(admin.get("/api/me").json().get("id").asString());
        return SystemContext.call(() -> companyProvisioning.createCompany(name, owner));
    }

    protected void addMember(ApiClient actor, UUID companyId, String email, String role) {
        ApiResponse response = actor.post("/api/companies/" + companyId + "/members",
                Map.of("email", email, "role", role));
        assertThat(response.status()).as("add member %s: %s", email, response).isEqualTo(201);
    }

    protected UUID createSecret(ApiClient actor, UUID companyId, String name, String value) {
        ApiResponse response = actor.post("/api/companies/" + companyId + "/secrets",
                Map.of("name", name, "value", value, "description", "test secret"));
        assertThat(response.status()).as("create secret: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    /**
     * A secret with a name only Tinfirm gives (a managed one, {@code SYSTEM_CREDENTIAL…}), which the API refuses to
     * create by hand (the security review of 2026-10-27, I1): created under another name, then renamed in the
     * company's context, as a secret from before the refusal would be. The name is not part of the value's AAD.
     */
    protected UUID createManagedNamedSecret(ApiClient actor, UUID companyId, String name, String value) {
        UUID id = createSecret(actor, companyId, unique("HAND_MADE").toUpperCase(java.util.Locale.ROOT)
            .replaceAll("[^A-Z0-9_]", "_"), value);
        try (TenantSql sql = TenantSql.in(companyId)) {
            assertThat(sql.update("UPDATE secret SET name = ? WHERE id = ?", name, id)).isEqualTo(1);
            sql.commit();
        }
        catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return id;
    }

    /**
     * A company with one member per role (OWNER, ADMIN, MEMBER). The bootstrap admin creates it and then leaves, so the
     * test owner is the only OWNER.
     */
    protected TestCompany companyWithRoles() {
        ApiClient admin = admin();
        UUID companyId = createCompany(admin, unique("Company"));
        Map<String, String> emails = new HashMap<>();
        Map<String, UUID> ids = new HashMap<>();
        for (String role : List.of("OWNER", "ADMIN", "MEMBER")) {
            String email = uniqueEmail(role.toLowerCase());
            ids.put(role, createUser(admin, email, false));
            emails.put(role, email);
            addMember(admin, companyId, email, role);
        }
        JsonNode me = admin.get("/api/me").json();
        ApiResponse leave = admin.delete("/api/companies/" + companyId + "/members/" + me.get("id").asString());
        assertThat(leave.status()).as("admin leaves: %s", leave).isEqualTo(204);
        // The bootstrap admin's own departure needs no checklist (package 6): closed, so no DEPARTURE item waits.
        try (TenantSql sql = TenantSql.in(companyId)) {
            Object principal = sql.column("SELECT id FROM principal WHERE user_id = ? AND status = 'LEFT'",
                    UUID.fromString(me.get("id").asString())).getFirst();
            // By the company's OWNER, so that no break-glass of the instance admin is recorded.
            ApiResponse closed = loginAs(emails.get("OWNER")).post("/api/companies/" + companyId + "/principals/"
                    + principal + "/departure/close", Map.of("force", true));
            assertThat(closed.status()).as("admin's departure closed: %s", closed).isEqualTo(200);
        }
        catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return new TestCompany(companyId, emails, ids);
    }

    /** The company's audit events, newest first (at most 200), as an OWNER or ADMIN reads them. */
    protected List<JsonNode> auditEvents(ApiClient reader, TestCompany company) {
        ApiResponse response = reader.get(company.path("/audit-events?limit=200"));
        assertThat(response.status()).as("audit events: %s", response).isEqualTo(200);
        List<JsonNode> events = new ArrayList<>();
        response.json().get("items").forEach(events::add);
        return events;
    }

    /** The company's audit events of one action, newest first. */
    protected List<JsonNode> auditEvents(ApiClient reader, TestCompany company, String action) {
        return auditEvents(reader, company).stream()
            .filter(event -> event.get("action").asString().equals(action))
            .toList();
    }

    /** Registers a system (the package 1 contract, §3.3) and answers its id. */
    protected UUID createSystem(ApiClient actor, TestCompany company, Map<String, Object> body) {
        ApiResponse response = actor.post(company.path("/systems"), body);
        assertThat(response.status()).as("create system: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    /** Registers a system (the package 1 contract, §3.3) and answers its id. */
    protected UUID createSystem(ApiClient actor, TestCompany company, String name, String kind,
            @org.jspecify.annotations.Nullable String baseUrl, boolean personal) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("kind", kind);
        if (baseUrl != null) {
            body.put("baseUrl", baseUrl);
        }
        body.put("personal", personal);
        return createSystem(actor, company, body);
    }

    /** Creates a service identity (the package 1 contract, §3.1) and answers its principal's id. */
    protected UUID createServiceIdentity(ApiClient admin, TestCompany company, String name) {
        ApiResponse response = admin.post(company.path("/service-identities"), Map.of("name", name));
        assertThat(response.status()).as("service identity: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    /** Creates a group of the members and answers its id. */
    protected UUID createGroup(ApiClient admin, TestCompany company, String name, List<UUID> members) {
        ApiResponse response = admin.post(company.path("/groups"), Map.of("name", name, "memberIds",
                members.stream().map(UUID::toString).toList()));
        assertThat(response.status()).as("group: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    /**
     * Stores a token as an account (the package 1 contract, §6) that Jira's myself accepts as {@code name}, and
     * answers the account's id.
     *
     * @param accountPath {@code /api/companies/{id}/me/accounts/{systemId}} or a service identity's account
     */
    protected UUID connectToken(ApiClient actor, String accountPath, JiraStub.Instance jira, String token,
            String name) {
        jira.myself(token, name, "UTC");
        ApiResponse response = actor.put(accountPath + "/token", Map.of("token", token));
        assertThat(response.status()).as("token: %s", response).isEqualTo(200);
        return UUID.fromString(response.json().get("id").asString());
    }

    /**
     * Stores a token that Jira's myself accepts as {@code name} as a credential of the system, lent to the service
     * account (ADR-0072: what was the service identity's own account), and answers the credential's id.
     */
    protected UUID connectServiceToken(ApiClient admin, TestCompany company, UUID serviceIdentityId, UUID systemId,
            JiraStub.Instance jira, String token, String name) {
        jira.myself(token, name, "UTC");
        return TestCredentials.lendToken(admin, company.path(""), serviceIdentityId, systemId, token);
    }

    /** Lends the credential to the group and answers the grant's id. */
    protected UUID createGrant(ApiClient admin, TestCompany company, UUID accountId, UUID groupId, String access) {
        ApiResponse response = admin.post(company.path("/grants"), Map.of("credentialId", accountId.toString(),
                "groupId", groupId.toString(), "access", access));
        assertThat(response.status()).as("grant: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    /** The member's principal that has not left. */
    protected static UUID memberPrincipal(TestCompany company, UUID userId) {
        try (TenantSql sql = TenantSql.in(company.id())) {
            List<Object> ids = sql.column("SELECT id FROM principal WHERE user_id = ? AND status <> 'LEFT'", userId);
            assertThat(ids).as("principal of %s", userId).hasSize(1);
            return (UUID) ids.getFirst();
        }
        catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }
    /** Registers the actor's own personal runner host; the token is returned once. */
    protected RunnerHostRef registerRunnerHost(ApiClient actor, UUID companyId, String name) {
        return registerRunnerHost(actor, companyId, name, "PERSONAL");
    }

    /**
     * Registers a runner host of the scope (the package 2 contract, §2): {@code COMPANY} (OWNER and ADMIN) or the
     * actor's own {@code PERSONAL} host; the token is returned once.
     */
    protected RunnerHostRef registerRunnerHost(ApiClient actor, UUID companyId, String name, String scope) {
        ApiResponse response = actor.post("/api/companies/" + companyId + "/runner-hosts",
                Map.of("name", name, "scope", scope));
        assertThat(response.status()).as("register runner host: %s", response).isEqualTo(201);
        JsonNode body = response.json();
        return new RunnerHostRef(UUID.fromString(body.get("runnerHost").get("id").asString()),
                body.get("token").asString());
    }

    /**
     * Connects a fake runner host and completes hello/welcome; a personal host's credentials are stored, a company
     * host's refused.
     */
    protected FakeRunner connectRunner(RunnerHostRef host, List<Map<String, Object>> credentials) {
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.helloAndWelcome(credentials);
        return runner;
    }

    /** A registered runner host and its one-time token (test fixture). */
    public record RunnerHostRef(UUID id, String token) {
    }

    /** A company and its members by role. */
    public record TestCompany(UUID id, Map<String, String> emails, Map<String, UUID> userIds) {

        public String path(String suffix) {
            return "/api/companies/" + id + suffix;
        }

        public String email(String role) {
            return emails.get(role);
        }

        public UUID userId(String role) {
            return userIds.get(role);
        }
    }
}
