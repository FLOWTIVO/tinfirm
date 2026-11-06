package com.flowtivo.tinfirm.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.McpAuthorizationServer;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TestCredentials;

import tools.jackson.databind.JsonNode;

/**
 * Systems over HTTP (the package 1 contract, §3.3, §3.4, §6, §7, §8 and §10): admins register company systems and
 * every member sees them; a member's personal MCP server is theirs alone, never reaches a private address, and is
 * refused while the company switched them off; the kind and the base URL are checked; the app's secret is never
 * shown; every change is audited and announced.
 */
class SystemIntegrationTest extends IntegrationTest {

    @Test
    void adminsRegisterCompanySystemsAndEveryMemberSeesThem() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();

        ApiResponse created = admin.post(company.path("/systems"), Map.of("name", "Support Jira", "kind", "JIRA_DC",
                "baseUrl", jira.baseUrl() + "/"));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        JsonNode system = created.json();
        assertThat(system.get("handle").asString()).isEqualTo("support_jira");
        assertThat(system.get("baseUrl").asString()).isEqualTo(jira.baseUrl());
        assertThat(system.get("personal").asBoolean()).isFalse();
        assertThat(system.get("status").asString()).isEqualTo("NEW");
        assertThat(system.get("oauthAvailable").asBoolean()).isFalse();
        // Agents may call its tools, so it receives what they pass (D3); its host is not one of the internal hosts.
        assertThat(system.get("receivesData").asBoolean()).isTrue();
        assertThat(system.get("internalHost").asBoolean()).isFalse();
        String path = company.path("/systems/" + system.get("id").asString());

        // Unique names ignoring case; a handle is never chosen (SystemHandleTest); the kinds and base URLs are checked.
        assertThat(owner.post(company.path("/systems"), Map.of("name", "SUPPORT JIRA", "kind", "JIRA_DC", "baseUrl",
                jira.baseUrl())).problemCode()).isEqualTo("DUPLICATE_SYSTEM_NAME");
        assertThat(owner.post(company.path("/systems"), Map.of("name", "Other", "handle", "support_jira", "kind",
                "JIRA_DC", "baseUrl", jira.baseUrl())).problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(owner.post(company.path("/systems"), Map.of("name", "Slack", "kind", "SLACK", "baseUrl",
                "https://slack.example.test")).problemCode()).isEqualTo("SYSTEM_KIND_INVALID");
        assertThat(owner.post(company.path("/systems"), Map.of("name", "Git", "kind", "GIT", "baseUrl",
                "https://git.example.test")).problemCode()).isEqualTo("SYSTEM_KIND_INVALID");
        assertThat(owner.post(company.path("/systems"), Map.of("name", "No URL", "kind", "MCP")).problemCode())
            .isEqualTo("INVALID_BASE_URL");
        assertThat(owner.post(company.path("/systems"), Map.of("name", "Ftp", "kind", "MCP", "baseUrl",
                "ftp://mcp.example.test")).problemCode()).isEqualTo("INVALID_BASE_URL");
        assertThat(owner.post(company.path("/systems"), Map.of("name", "Creds", "kind", "MCP", "baseUrl",
                "https://user:pw@mcp.example.test/mcp")).problemCode()).isEqualTo("INVALID_BASE_URL");
        assertThat(createSystem(owner, company, "Code", "GIT", null, false)).isNotNull();

        // A MEMBER registers no company system and changes none; every member sees them.
        assertThat(member.post(company.path("/systems"), Map.of("name", "Mine", "kind", "JIRA_DC", "baseUrl",
                jira.baseUrl())).status()).isEqualTo(403);
        assertThat(member.patch(path, Map.of("name", "Renamed")).status()).isEqualTo(403);
        assertThat(member.delete(path).status()).isEqualTo(403);
        assertThat(member.get(company.path("/systems")).json().get("items")).extracting(item -> item.get("name")
            .asString()).containsExactly("Code", "Support Jira");

        // A rename, audited; the kind, the base URL and the handle never change.
        ApiResponse renamed = admin.patch(path, Map.of("name", "Helpdesk"));
        assertThat(renamed.status()).as("%s", renamed).isEqualTo(200);
        assertThat(renamed.json().get("handle").asString()).isEqualTo("support_jira");
        assertThat(renamed.json().get("baseUrl").asString()).isEqualTo(jira.baseUrl());
        assertThat(auditEvents(owner, company, "system.changed")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("changed")).extracting(JsonNode::asString)
                .containsExactly("name"));
        assertThat(auditEvents(owner, company, "system.created")).hasSize(2);

        // The fixed tools of a tracker, with their effects.
        assertThat(member.get(path + "/tools").json().get("items")).extracting(item -> item.get("name").asString()
                + ":" + item.get("effect").asString() + ":" + item.get("fixed").asBoolean())
            .containsExactly("comment:WRITE:true", "get:READ:true", "search:READ:true", "transition:WRITE:true");

        // Removed with an audit and an event; then gone.
        UUID systemId = UUID.fromString(system.get("id").asString());
        assertThat(admin.delete(path).status()).isEqualTo(204);
        assertThat(member.get(path).status()).isEqualTo(404);
        assertThat(auditEvents(owner, company, "system.deleted")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("name").asString()).isEqualTo("Helpdesk"));
        assertThat(RecordedEvents.of(company.id(), SystemEvents.SystemDeleted.class))
            .containsExactly(new SystemEvents.SystemDeleted(company.id(), systemId));
    }

    @Test
    void aPersonalMcpServerIsItsOwnersAloneAndNeverReachesAPrivateAddress() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        McpStub.Instance mcp = McpStub.newInstance().serves(List.of(McpStub.tool("search", "Searches")));

        UUID mine = createSystem(member, company, "My notes", "MCP", mcp.url(), true);
        String path = company.path("/systems/" + mine);
        JsonNode own = member.get(path).json();
        assertThat(own.get("personal").asBoolean()).isTrue();
        assertThat(own.get("receivesData").asBoolean()).isTrue();
        assertThat(own.get("internalHost").asBoolean()).isFalse();

        // Nobody else sees it, admins included, and nobody else changes it.
        assertThat(admin.get(path).status()).isEqualTo(404);
        assertThat(owner.delete(path).status()).isEqualTo(404);
        assertThat(owner.get(company.path("/systems")).json().get("items")).isEmpty();
        assertThat(member.get(company.path("/systems")).json().get("items")).hasSize(1);

        // I6: a personal system is an MCP server.
        assertThat(member.post(company.path("/systems"), Map.of("name", "My Jira", "kind", "JIRA_DC", "baseUrl",
                JiraStub.newInstance().baseUrl(), "personal", true)).problemCode()).isEqualTo("PERSONAL_SYSTEM");

        // A personal server never reaches a private address; a company system may where the operator allows it.
        String literal = mcp.url().replace("localhost", "127.0.0.1");
        ApiResponse privateAddress = member.post(company.path("/systems"), Map.of("name", "Loopback", "kind", "MCP",
                "baseUrl", literal, "personal", true));
        assertThat(privateAddress.problemCode()).isEqualTo("INVALID_BASE_URL");
        assertThat(createSystem(admin, company, "Loopback", "MCP", literal, false)).isNotNull();

        // The owner reads its catalogue and confirms an effect.
        ApiResponse refreshed = member.post(path + "/tools/refresh", Map.of());
        assertThat(refreshed.json().get("added")).extracting(JsonNode::asString).containsExactly("search");
        assertThat(member.patch(path + "/tools/search", Map.of("effect", "READ")).json().get("effect").asString())
            .isEqualTo("READ");

        // Switched off: no new personal server; existing ones stay. Members may not switch.
        assertThat(member.put(company.path("/system-settings"), Map.of("personalMcpServers", false)).status())
            .isEqualTo(403);
        assertThat(admin.put(company.path("/system-settings"), Map.of("personalMcpServers", false)).json()
            .get("personalMcpServers").asBoolean()).isFalse();
        assertThat(member.get(company.path("/system-settings")).json().get("personalMcpServers").asBoolean())
            .isFalse();
        ApiResponse off = member.post(company.path("/systems"), Map.of("name", "Another", "kind", "MCP", "baseUrl",
                McpStub.newInstance().url(), "personal", true));
        assertThat(off.status()).isEqualTo(409);
        assertThat(off.problemCode()).isEqualTo("PERSONAL_SYSTEMS_OFF");
        assertThat(member.get(path).status()).isEqualTo(200);
        assertThat(admin.put(company.path("/system-settings"), Map.of("personalMcpServers", true)).status())
            .isEqualTo(200);
        assertThat(auditEvents(owner, company, "system.settings_changed")).hasSize(2);
        assertThat(RecordedEvents.of(company.id(), SystemEvents.PersonalSystemsSwitched.class))
            .containsExactly(new SystemEvents.PersonalSystemsSwitched(company.id(), false),
                    new SystemEvents.PersonalSystemsSwitched(company.id(), true));

        // The audit names the owner's principal, and the member's leaving takes the server (I8).
        JsonNode audit = auditEvents(owner, company, "system.created").getLast();
        assertThat(audit.get("details").has("ownerPrincipalId")).isTrue();
        assertThat(owner.delete(company.path("/members/" + company.userId("MEMBER"))).status()).isEqualTo(204);
        assertThat(auditEvents(owner, company, "system.deleted")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("cause").asString()).isEqualTo("principal.left"));
    }

    @Test
    void codeHostsHoldRepositoriesThatAdminsAddByUrl() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID github = createSystem(admin, company, "GitHub", "GITHUB", "https://api.github.com", false);
        String path = company.path("/systems/" + github + "/repositories");

        ApiResponse added = admin.post(path, Map.of("url", "https://github.com/acme/shop.git", "defaultBranch",
                "main"));
        assertThat(added.status()).as("%s", added).isEqualTo(201);
        assertThat(added.json().get("name").asString()).isEqualTo("acme/shop");
        assertThat(admin.post(path, Map.of("url", "https://github.com/acme/shop.git")).problemCode())
            .isEqualTo("DUPLICATE_REPOSITORY");
        assertThat(admin.post(path, Map.of("url", "https://gitlab.com/acme/shop")).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        assertThat(admin.post(path, Map.of("url", "http://github.com/acme/shop")).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        assertThat(admin.post(path, Map.of("url", "https://github.com/acme")).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        assertThat(member.post(path, Map.of("url", "https://github.com/acme/other")).status()).isEqualTo(403);
        assertThat(member.get(path).json().get("items")).singleElement()
            .satisfies(item -> assertThat(item.get("defaultBranch").asString()).isEqualTo("main"));

        UUID mcp = createSystem(admin, company, "Tools", "MCP", McpStub.newInstance().url(), false);
        assertThat(admin.post(company.path("/systems/" + mcp + "/repositories"), Map.of("url",
                "https://github.com/acme/shop")).problemCode()).isEqualTo("SYSTEM_KIND_INVALID");

        UUID repositoryId = UUID.fromString(added.json().get("id").asString());
        assertThat(member.delete(path + "/" + repositoryId).status()).isEqualTo(403);
        assertThat(admin.delete(path + "/" + repositoryId).status()).isEqualTo(204);
        assertThat(RecordedEvents.of(company.id(), SystemEvents.RepositoryRemoved.class))
            .containsExactly(new SystemEvents.RepositoryRemoved(company.id(), github, repositoryId));
        ApiClient owner = loginAs(company.email("OWNER"));
        assertThat(auditEvents(owner, company, "repository.added")).hasSize(1);
        assertThat(auditEvents(owner, company, "repository.removed")).hasSize(1);
    }

    @Test
    void anMcpCatalogueKeepsConfirmedEffectsUntilAToolChanges() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        Map<String, Object> search = annotated(McpStub.tool("search", "Searches the CRM"), true, null);
        Map<String, Object> delete = annotated(McpStub.tool("delete_contact", "Deletes a contact"), false, true);
        McpStub.Instance mcp = McpStub.newInstance().serves(List.of(search, delete, McpStub.tool("ping", null)));
        UUID systemId = createSystem(admin, company, "CRM", "MCP", mcp.url(), false);
        String path = company.path("/systems/" + systemId);

        ApiResponse first = admin.post(path + "/tools/refresh", Map.of());
        assertThat(first.json().get("ok").asBoolean()).as("%s", first).isTrue();
        assertThat(first.json().get("added")).extracting(JsonNode::asString)
            .containsExactlyInAnyOrder("search", "delete_contact", "ping");
        JsonNode tools = member.get(path + "/tools").json().get("items");
        assertThat(tools).extracting(item -> item.get("name").asString() + ":" + item.get("effect").asString() + ":"
                + item.get("suggestedEffect").asString())
            .containsExactly("delete_contact:UNKNOWN:WRITE", "ping:UNKNOWN:UNKNOWN", "search:UNKNOWN:READ");
        assertThat(admin.get(path).json().get("status").asString()).isEqualTo("OK");

        // Confirmed by an admin; a member may not; a fixed tool's effect is not confirmed.
        assertThat(member.patch(path + "/tools/search", Map.of("effect", "READ")).status()).isEqualTo(403);
        assertThat(admin.patch(path + "/tools/search", Map.of("effect", "READ")).json().get("effect").asString())
            .isEqualTo("READ");
        assertThat(admin.patch(path + "/tools/ping", Map.of("effect", "READ")).status()).isEqualTo(200);
        assertThat(admin.patch(path + "/tools/delete_contact", Map.of("enabled", false)).json().get("enabled")
            .asBoolean()).isFalse();
        assertThat(admin.patch(path + "/tools/unknown", Map.of("enabled", false)).status()).isEqualTo(404);

        // The description of search changed and ping is gone: search loses its confirmation (the redesign's security
        // review, row 13).
        mcp.serves(List.of(annotated(McpStub.tool("search", "Searches the CRM and the mail"), true, null), delete));
        JsonNode second = admin.post(path + "/tools/refresh", Map.of()).json();
        assertThat(second.get("removed")).extracting(JsonNode::asString).containsExactly("ping");
        assertThat(second.get("unconfirmed")).extracting(JsonNode::asString).containsExactly("search");
        assertThat(member.get(path + "/tools").json().get("items")).extracting(item -> item.get("name").asString()
                + ":" + item.get("effect").asString() + ":" + item.get("enabled").asBoolean())
            .containsExactly("delete_contact:UNKNOWN:false", "search:UNKNOWN:true");

        // An unchanged catalogue changes nothing.
        assertThat(admin.post(path + "/tools/refresh", Map.of()).json().get("added")).isEmpty();
        ApiClient owner = loginAs(company.email("OWNER"));
        assertThat(auditEvents(owner, company, "system.tools_refreshed")).hasSize(2);
        assertThat(auditEvents(owner, company, "system.tool_effect_confirmed")).hasSize(2);
        assertThat(auditEvents(owner, company, "system.tool_switched")).hasSize(1);
        assertThat(RecordedEvents.of(company.id(), SystemEvents.SystemToolsChanged.class)).isNotEmpty();

        // A server that cannot be read keeps the catalogue and says why.
        mcp.failsWith(503);
        JsonNode failed = admin.post(path + "/tools/refresh", Map.of()).json();
        assertThat(failed.get("ok").asBoolean()).isFalse();
        assertThat(failed.get("message").asString()).contains("503");
        assertThat(admin.get(path).json().get("status").asString()).isEqualTo("ERROR");
        assertThat(member.get(path + "/tools").json().get("items")).hasSize(2);

        // A tracker's tools are fixed.
        UUID jira = createSystem(admin, company, "Jira", "JIRA_DC", JiraStub.newInstance().baseUrl(), false);
        assertThat(admin.post(company.path("/systems/" + jira + "/tools/refresh"), Map.of()).problemCode())
            .isEqualTo("SYSTEM_KIND_INVALID");
        assertThat(admin.patch(company.path("/systems/" + jira + "/tools/get"), Map.of("enabled", false))
            .problemCode()).isEqualTo("SYSTEM_KIND_INVALID");
    }

    @Test
    void anAdminWithoutAnAccountReadsATokenSecuredCatalogueWithTheSystemsCredential() {
        // An admin who holds no account of their own adds an MCP server that asks for a token (ADR-0072): without a
        // credential the refresh says so and the system stays OK, as its test finds it; with one it reads.
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        McpStub.Instance mcp = McpStub.newInstance().requiring("Authorization", "Bearer test-crm-credential")
            .serves(List.of(McpStub.tool("search", "Searches the CRM")));
        UUID systemId = createSystem(admin, company, "CRM", "MCP", mcp.url(), false);
        String path = company.path("/systems/" + systemId);

        JsonNode asked = admin.post(path + "/tools/refresh", Map.of()).json();
        assertThat(asked.get("ok").asBoolean()).as("%s", asked).isFalse();
        assertThat(asked.get("withAccount").asBoolean()).isFalse();
        assertThat(asked.get("credentialRequired").asBoolean()).isTrue();
        assertThat(asked.get("message").asString()).startsWith("CRM asks for a token: add a credential");
        assertThat(admin.get(path).json().get("status").asString()).isEqualTo("OK");
        assertThat(admin.get(path + "/tools").json().get("items")).isEmpty();

        // The credential, added on the same page: the system's only one, the refresh reads with it unnamed.
        UUID credential = TestCredentials.add(admin, company.path(""), systemId, "CRM service",
                "test-crm-credential");
        JsonNode read = admin.post(path + "/tools/refresh", Map.of()).json();
        assertThat(read.get("ok").asBoolean()).as("%s", read).isTrue();
        assertThat(read.get("withAccount").asBoolean()).isTrue();
        assertThat(read.get("credentialId").asString()).isEqualTo(credential.toString());
        assertThat(read.get("added")).extracting(JsonNode::asString).containsExactly("search");
        assertThat(admin.get(path).json().get("status").asString()).isEqualTo("OK");

        // Named, it is used even beside another; one the system does not hold is 404, and a refused one says so.
        mcp.requiring("Authorization", "Bearer test-crm-other").serves(List.of(McpStub.tool("search",
                "Searches the CRM")));
        UUID other = TestCredentials.add(admin, company.path(""), systemId, "CRM other", "test-crm-other");
        JsonNode named = admin.post(path + "/tools/refresh?credentialId=" + other, Map.of()).json();
        assertThat(named.get("ok").asBoolean()).as("%s", named).isTrue();
        assertThat(named.get("credentialId").asString()).isEqualTo(other.toString());
        assertThat(admin.post(path + "/tools/refresh?credentialId=" + UUID.randomUUID(), Map.of()).status())
            .isEqualTo(404);
        mcp.failsWith(401);
        JsonNode refused = admin.post(path + "/tools/refresh?credentialId=" + credential, Map.of()).json();
        assertThat(refused.get("ok").asBoolean()).isFalse();
        assertThat(refused.get("withAccount").asBoolean()).isTrue();
        assertThat(refused.get("credentialRequired").asBoolean()).isTrue();
        assertThat(admin.get(path).json().get("status").asString()).isEqualTo("ERROR");
        // A disabled credential is not used: nothing is sent.
        assertThat(admin.patch(path + "/credentials/" + other, Map.of("enabled", false)).status()).isEqualTo(200);
        int sent = mcp.requests().size();
        JsonNode disabled = admin.post(path + "/tools/refresh?credentialId=" + other, Map.of()).json();
        assertThat(disabled.get("ok").asBoolean()).isFalse();
        assertThat(disabled.get("message").asString()).isEqualTo("The credential is disabled, or must be signed in "
                + "again");
        assertThat(mcp.requests()).hasSize(sent);
    }

    @Test
    void theAppKeepsItsSecretInTheVaultAndShowsItOnlyToWhoManagesTheSystem() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID jira = createSystem(admin, company, "Jira", "JIRA_DC", JiraStub.newInstance().baseUrl(), false);
        String path = company.path("/systems/" + jira);

        // The callback to register the app at the system with, before the app is set: to who manages the system.
        JsonNode before = admin.get(path).json();
        assertThat(before.has("app")).isFalse();
        String callbackUrl = before.get("oauthCallbackUrl").asString();
        assertThat(callbackUrl).endsWith("/api/oauth/callback");
        assertThat(member.get(path).json().has("oauthCallbackUrl")).isFalse();
        UUID plainGit = createSystem(admin, company, unique("Git"), "GIT", null, false);
        assertThat(admin.get(company.path("/systems/" + plainGit)).json().has("oauthCallbackUrl")).isFalse();

        ApiResponse set = admin.put(path + "/app", Map.of("clientId", "test-client", "clientSecret",
                "test-app-secret-value"));
        assertThat(set.status()).as("%s", set).isEqualTo(200);
        JsonNode app = set.json().get("app");
        assertThat(app.get("clientId").asString()).isEqualTo("test-client");
        assertThat(app.get("clientSecretSet").asBoolean()).isTrue();
        assertThat(app.get("callbackUrl").asString()).isEqualTo(callbackUrl);
        assertThat(set.json().get("oauthCallbackUrl").asString()).isEqualTo(callbackUrl);
        assertThat(set.body()).doesNotContain("test-app-secret-value");
        JsonNode seenByMember = member.get(path).json();
        assertThat(seenByMember.get("oauthAvailable").asBoolean()).isTrue();
        assertThat(seenByMember.has("app")).isFalse();
        assertThat(member.put(path + "/app", Map.of("clientId", "mine")).status()).isEqualTo(403);

        // The secret is a managed secret of the company; the audit names no secret.
        JsonNode secrets = owner.get(company.path("/secrets")).json().get("items");
        assertThat(secrets).anySatisfy(secret -> assertThat(secret.get("name").asString())
            .startsWith("SYSTEM_OAUTH_CLIENT_SECRET"));
        auditEvents(owner, company).forEach(event -> assertThat(event.toString()).doesNotContain("test-app-secret"));

        // The app keeps the secret: the Secrets page does not delete it; the app is removed on the system's page.
        String clientSecretId = java.util.stream.StreamSupport.stream(secrets.spliterator(), false)
            .filter(secret -> secret.get("name").asString().startsWith("SYSTEM_OAUTH_CLIENT_SECRET"))
            .findFirst()
            .orElseThrow()
            .get("id")
            .asString();
        ApiResponse kept = owner.delete(company.path("/secrets/" + clientSecretId));
        assertThat(kept.status()).as("%s", kept).isEqualTo(409);
        assertThat(kept.problemCode()).isEqualTo("SECRET_MANAGED");

        // Kept while the client id stays; none for a new client id.
        assertThat(admin.put(path + "/app", Map.of("clientId", "test-client", "scopes", "READ")).json().get("app")
            .get("clientSecretSet").asBoolean()).isTrue();
        assertThat(admin.put(path + "/app", Map.of("clientId", "another")).json().get("app").get("clientSecretSet")
            .asBoolean()).isFalse();

        // Removed: no OAuth any more, its secret gone with it, audited; a member may not, and twice is 404.
        assertThat(admin.put(path + "/app", Map.of("clientId", "test-client", "clientSecret",
                "test-app-secret-value-2")).status()).isEqualTo(200);
        assertThat(member.delete(path + "/app").status()).isEqualTo(403);
        assertThat(admin.delete(path + "/app").status()).isEqualTo(204);
        JsonNode removed = admin.get(path).json();
        assertThat(removed.has("app")).isFalse();
        assertThat(removed.get("oauthAvailable").asBoolean()).isFalse();
        assertThat(owner.get(company.path("/secrets")).json().get("items")).noneSatisfy(secret -> assertThat(
                secret.get("name").asString()).startsWith("SYSTEM_OAUTH_CLIENT_SECRET"));
        assertThat(admin.delete(path + "/app").status()).isEqualTo(404);
        JsonNode removal = auditEvents(owner, company, "system.app_changed").getFirst().get("details");
        assertThat(removal.get("removed").asBoolean()).isTrue();
        assertThat(removal.get("clientId").asString()).isEqualTo("test-client");
        auditEvents(owner, company).forEach(event -> assertThat(event.toString()).doesNotContain("test-app-secret"));

        // An MCP server's app: discovered, with Tinfirm's metadata document as its client id where it takes one.
        McpStub.Instance mcp = McpStub.newInstance();
        McpAuthorizationServer.of(mcp, true, false);
        UUID crm = createSystem(admin, company, "CRM", "MCP", mcp.url(), false);
        ApiResponse discovered = admin.put(company.path("/systems/" + crm + "/app"), Map.of());
        assertThat(discovered.status()).as("%s", discovered).isEqualTo(200);
        assertThat(discovered.json().get("app").get("clientId").asString()).endsWith("/oauth/client-metadata.json");

        // A server that takes no metadata document but registers clients: Tinfirm registers itself (RFC 7591).
        McpStub.Instance registering = McpStub.newInstance();
        McpAuthorizationServer.of(registering, false, true);
        UUID registered = createSystem(admin, company, "Registering", "MCP", registering.url(), false);
        ApiResponse self = admin.put(company.path("/systems/" + registered + "/app"), Map.of());
        assertThat(self.status()).as("%s", self).isEqualTo(200);
        assertThat(self.json().get("app").get("clientId").asString()).isEqualTo("test-dynamic-client");
        assertThat(auditEvents(owner, company, "system.app_changed").getFirst().get("details").get("registered")
            .asBoolean()).isTrue();

        // A member's personal server: its owner sets and removes its app, which nobody else sees.
        McpStub.Instance own = McpStub.newInstance();
        McpAuthorizationServer.of(own, true, false);
        UUID personal = createSystem(member, company, "Mine", "MCP", own.url(), true);
        String personalPath = company.path("/systems/" + personal + "/app");
        assertThat(member.put(personalPath, Map.of()).status()).isEqualTo(200);
        assertThat(admin.delete(personalPath).status()).isEqualTo(404);
        assertThat(member.delete(personalPath).status()).isEqualTo(204);

        // A server that needs no token offers no OAuth; a git system has no app.
        UUID open = createSystem(admin, company, "Open", "MCP", McpStub.newInstance().serves(List.of()).url(), false);
        assertThat(admin.put(company.path("/systems/" + open + "/app"), Map.of()).problemCode())
            .isEqualTo("OAUTH_NOT_AVAILABLE");
        UUID git = createSystem(admin, company, "Git", "GIT", null, false);
        assertThat(admin.put(company.path("/systems/" + git + "/app"), Map.of("clientId", "x")).problemCode())
            .isEqualTo("SYSTEM_KIND_INVALID");
    }

    @Test
    void aNewMcpServerSetsItsAppUpAsItsDiscoveryFindsIt() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));

        // A server that takes Tinfirm's client metadata document: its app is that document, nobody setting it.
        McpStub.Instance documented = McpStub.newInstance();
        McpAuthorizationServer.of(documented, true, false);
        JsonNode crm = created(admin, company, Map.of("name", "CRM", "kind", "MCP", "baseUrl", documented.url()));
        assertThat(crm.get("oauthAvailable").asBoolean()).isTrue();
        assertThat(crm.get("app").get("clientId").asString()).endsWith("/oauth/client-metadata.json");
        assertThat(crm.get("app").get("clientSecretSet").asBoolean()).isFalse();
        assertThat(crm.get("app").get("authorizationEndpoint").asString()).endsWith("-as/authorize");
        JsonNode automatic = auditEvents(owner, company, "system.app_changed").getFirst().get("details");
        assertThat(automatic.get("automatic").asBoolean()).isTrue();
        assertThat(automatic.has("registered")).isFalse();

        // A server that registers clients (RFC 7591): Tinfirm registers itself once, as the server is registered.
        McpStub.Instance registering = McpStub.newInstance();
        McpAuthorizationServer server = McpAuthorizationServer.of(registering, false, true);
        JsonNode registered = created(admin, company, Map.of("name", "Registering", "kind", "MCP", "baseUrl",
                registering.url()));
        assertThat(registered.get("app").get("clientId").asString()).isEqualTo("test-dynamic-client");
        assertThat(server.requests("/register")).hasSize(1);
        JsonNode registration = auditEvents(owner, company, "system.app_changed").getFirst().get("details");
        assertThat(registration.get("registered").asBoolean()).isTrue();
        assertThat(registration.get("automatic").asBoolean()).isTrue();

        // A registration whose client id or secret is longer than Tinfirm keeps: no app, the server registered all
        // the same, as Set up the app refuses it.
        McpStub.Instance longId = McpStub.newInstance();
        McpAuthorizationServer.of(longId, false, true).registers("i".repeat(501), "test-dynamic-secret");
        assertThat(created(admin, company, Map.of("name", "Long id", "kind", "MCP", "baseUrl", longId.url()))
            .has("app")).isFalse();
        McpStub.Instance longSecret = McpStub.newInstance();
        McpAuthorizationServer.of(longSecret, false, true).registers("test-dynamic-client", "s".repeat(2001));
        JsonNode tooLong = created(admin, company, Map.of("name", "Long secret", "kind", "MCP", "baseUrl",
                longSecret.url()));
        assertThat(tooLong.has("app")).isFalse();
        assertThat(admin.put(company.path("/systems/" + tooLong.get("id").asString() + "/app"), Map.of())
            .problemCode()).isEqualTo("OAUTH_NOT_AVAILABLE");

        // A member's personal server: set up for its owner alike.
        McpStub.Instance own = McpStub.newInstance();
        McpAuthorizationServer.of(own, true, false);
        JsonNode personal = created(member, company, Map.of("name", "Mine", "kind", "MCP", "baseUrl", own.url(),
                "personal", true));
        assertThat(personal.get("app").get("clientId").asString()).endsWith("/oauth/client-metadata.json");

        // A server that needs no token, or one that cannot be reached, has no app and is registered all the same.
        JsonNode open = created(admin, company, Map.of("name", "Open", "kind", "MCP", "baseUrl",
                McpStub.newInstance().serves(List.of()).url()));
        assertThat(open.has("app")).isFalse();
        assertThat(open.get("oauthAvailable").asBoolean()).isFalse();
        JsonNode down = created(admin, company, Map.of("name", "Down", "kind", "MCP", "baseUrl",
                "http://localhost:9/mcp"));
        assertThat(down.has("app")).isFalse();

        // A server whose authorization server takes neither: its admin registers a client by hand and sets the app.
        McpStub.Instance manual = McpStub.newInstance();
        McpAuthorizationServer.of(manual, false, false);
        JsonNode byHand = created(admin, company, Map.of("name", "By hand", "kind", "MCP", "baseUrl", manual.url()));
        assertThat(byHand.has("app")).isFalse();
        assertThat(byHand.get("oauthAvailable").asBoolean()).isFalse();
        String byHandApp = company.path("/systems/" + byHand.get("id").asString() + "/app");
        assertThat(admin.put(byHandApp, Map.of()).problemCode()).isEqualTo("VALIDATION_FAILED");
        ApiResponse set = admin.put(byHandApp, Map.of("clientId", "test-hand-registered"));
        assertThat(set.status()).as("%s", set).isEqualTo(200);
        assertThat(set.json().get("oauthAvailable").asBoolean()).isTrue();
        assertThat(auditEvents(owner, company, "system.app_changed").getFirst().get("details").has("automatic"))
            .isFalse();
    }

    @Test
    void anMcpServersNewEndpointTakesNoTokenThereAndKeepsItsTools() {
        // ADR-0079: an MCP server moves to a new endpoint, and every account and credential on it goes, with its app:
        // no token reaches the new address. Its name, handle and catalogue stay.
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        McpStub.Instance old = McpStub.newInstance().serves(List.of(McpStub.tool("search", "Searches the tickets")));
        McpAuthorizationServer.of(old, true, false);
        JsonNode registered = created(admin, company, Map.of("name", "Tickets", "kind", "MCP", "baseUrl",
                old.url()));
        assertThat(registered.get("app").get("clientId").asString()).endsWith("/oauth/client-metadata.json");
        UUID systemId = UUID.fromString(registered.get("id").asString());
        String path = company.path("/systems/" + systemId);
        UUID credential = TestCredentials.add(admin, company.path(""), systemId, "Tickets service", "test-old-token");
        createGrant(admin, company, credential, createGroup(admin, company, "Support", List.of()), "READ");
        ApiResponse own = member.put(company.path("/me/accounts/" + systemId + "/token"), Map.of("token",
                "test-own-token"));
        assertThat(own.status()).as("%s", own).isEqualTo(200);
        assertThat(admin.post(path + "/tools/refresh", Map.of()).json().get("ok").asBoolean()).isTrue();
        assertThat(admin.patch(path + "/tools/search", Map.of("effect", "READ")).status()).isEqualTo(200);
        assertThat(admin.post(path + "/test", Map.of()).status()).isEqualTo(200);

        // Who may move it, to where: only an MCP server, only to a usable endpoint; the same one changes nothing.
        McpStub.Instance moved = McpStub.newInstance().requiring("Authorization", "Bearer test-new-token")
            .serves(List.of(McpStub.tool("search", "Searches the tickets")));
        assertThat(member.patch(path, Map.of("baseUrl", moved.url())).status()).isEqualTo(403);
        assertThat(admin.patch(path, Map.of("baseUrl", "ftp://mcp.example.test")).problemCode())
            .isEqualTo("INVALID_BASE_URL");
        UUID jira = createSystem(admin, company, "Jira", "JIRA_DC", JiraStub.newInstance().baseUrl(), false);
        assertThat(admin.patch(company.path("/systems/" + jira), Map.of("baseUrl", JiraStub.newInstance().baseUrl()))
            .problemCode()).isEqualTo("SYSTEM_KIND_INVALID");
        assertThat(admin.patch(path, Map.of("baseUrl", old.url() + "/")).status()).isEqualTo(200);
        assertThat(auditEvents(owner, company, "system.changed")).isEmpty();

        // Moved: the same system, without its accounts, credentials, grants, app and last test.
        ApiResponse change = admin.patch(path, Map.of("baseUrl", moved.url()));
        assertThat(change.status()).as("%s", change).isEqualTo(200);
        JsonNode after = change.json();
        assertThat(after.get("id").asString()).isEqualTo(systemId.toString());
        assertThat(after.get("handle").asString()).isEqualTo(registered.get("handle").asString());
        assertThat(after.get("baseUrl").asString()).isEqualTo(moved.url());
        assertThat(after.get("status").asString()).isEqualTo("NEW");
        assertThat(after.has("lastTest") && !after.get("lastTest").isNull()).isFalse();
        assertThat(after.has("app")).isFalse();
        assertThat(after.get("oauthAvailable").asBoolean()).isFalse();
        assertThat(admin.get(path + "/credentials").json().get("items")).isEmpty();
        assertThat(admin.get(company.path("/grants")).json().get("items")).isEmpty();
        assertThat(owner.get(company.path("/secrets")).json().get("items")).noneSatisfy(secret -> assertThat(
                secret.get("name").asString()).startsWith("SYSTEM_CREDENTIAL"));
        JsonNode audited = auditEvents(owner, company, "system.changed").getFirst().get("details");
        assertThat(audited.get("changed")).extracting(JsonNode::asString).containsExactly("baseUrl");
        assertThat(audited.get("baseUrl").asString()).isEqualTo(moved.url());
        assertThat(audited.get("previousBaseUrl").asString()).isEqualTo(old.url());
        assertThat(audited.get("accountsRemoved").asInt()).isEqualTo(2);
        assertThat(audited.get("appRemoved").asBoolean()).isTrue();
        assertThat(auditEvents(owner, company, "account.disconnected")).hasSize(2)
            .allSatisfy(event -> assertThat(event.get("details").get("cause").asString()).isEqualTo("system.moved"));
        assertThat(auditEvents(owner, company, "grant.deleted")).hasSize(1);
        assertThat(RecordedEvents.of(company.id(), SystemEvents.SystemMoved.class))
            .containsExactly(new SystemEvents.SystemMoved(company.id(), systemId));
        auditEvents(owner, company).forEach(event -> assertThat(event.toString()).doesNotContain("test-old-token")
            .doesNotContain("test-own-token"));

        // The catalogue stays with its confirmed effect; a new credential reads the new address and nothing else.
        assertThat(member.get(path + "/tools").json().get("items")).extracting(item -> item.get("name").asString()
                + ":" + item.get("effect").asString())
            .containsExactly("search:READ");
        int oldRequests = old.requests().size();
        TestCredentials.add(admin, company.path(""), systemId, "Tickets service", "test-new-token");
        JsonNode refreshed = admin.post(path + "/tools/refresh", Map.of()).json();
        assertThat(refreshed.get("ok").asBoolean()).as("%s", refreshed).isTrue();
        assertThat(refreshed.get("unconfirmed")).isEmpty();
        assertThat(old.requests()).hasSize(oldRequests);
        // Its app's discovery asked without a token; the refresh with the new one; never with a token of before.
        assertThat(moved.requests()).extracting(request -> String.valueOf(request.getHeader("Authorization")))
            .contains("Bearer test-new-token")
            .allSatisfy(header -> assertThat(header).isIn("null", "Bearer test-new-token"));

        // A new endpoint with an authorization server: the app is set up there, as for a new server.
        McpStub.Instance documented = McpStub.newInstance();
        McpAuthorizationServer.of(documented, true, false);
        JsonNode withApp = admin.patch(path, Map.of("baseUrl", documented.url())).json();
        assertThat(withApp.get("app").get("authorizationEndpoint").asString()).endsWith("-as/authorize");
        assertThat(withApp.get("app").get("clientId").asString()).endsWith("/oauth/client-metadata.json");
    }

    @Test
    void anAppFoundAtAnEndpointThatIsNoLongerTheServersIsNotStored() throws Exception {
        // Two moves close together: the first one's discovery still runs when the second commits. The app it finds is
        // the old endpoint's authorization server's, whose tokens would reach the new one: it is not stored.
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID systemId = createSystem(admin, company, "Notes", "MCP",
                McpStub.newInstance().serves(List.of()).url(), false);
        String path = company.path("/systems/" + systemId);
        McpStub.Instance slow = McpStub.newInstance();
        McpAuthorizationServer.of(slow, true, false).slowDiscovery(1500);
        McpStub.Instance open = McpStub.newInstance().serves(List.of());

        java.util.concurrent.CompletableFuture<ApiResponse> first = java.util.concurrent.CompletableFuture
            .supplyAsync(() -> admin.patch(path, Map.of("baseUrl", slow.url())));
        // The first move commits at once, then waits on its discovery; the second moves it on meanwhile.
        long deadline = System.currentTimeMillis() + 5000;
        while (!owner.get(path).json().get("baseUrl").asString().equals(slow.url())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        ApiResponse second = owner.patch(path, Map.of("baseUrl", open.url()));
        assertThat(second.status()).as("%s", second).isEqualTo(200);
        assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(200);

        JsonNode after = owner.get(path).json();
        assertThat(after.get("baseUrl").asString()).isEqualTo(open.url());
        assertThat(after.has("app")).isFalse();
        assertThat(auditEvents(owner, company, "system.app_changed")).isEmpty();
    }

    @Test
    void aServerWithoutAccountsMovesOnlyWhereItAnswersWithoutACredential() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        McpStub.Instance open = McpStub.newInstance().serves(List.of(McpStub.tool("ping", null)));
        UUID systemId = createSystem(admin, company, "Open", "MCP", open.url(), false);
        String path = company.path("/systems/" + systemId);
        assertThat(admin.patch(path, Map.of("accountsRequired", false)).status()).isEqualTo(200);

        // A new endpoint that asks for a token: refused with its answer, and nothing changes.
        McpStub.Instance guarded = McpStub.newInstance().requiring("Authorization", "Bearer test-guarded")
            .serves(List.of(McpStub.tool("ping", null)));
        ApiResponse refused = admin.patch(path, Map.of("baseUrl", guarded.url()));
        assertThat(refused.problemCode()).as("%s", refused).isEqualTo("CREDENTIAL_REQUIRED");
        assertThat(admin.get(path).json().get("baseUrl").asString()).isEqualTo(open.url());

        // One that answers without one: moved, still without accounts. Paused, it is not called and not moved.
        McpStub.Instance next = McpStub.newInstance().serves(List.of(McpStub.tool("ping", null)));
        ApiResponse change = admin.patch(path, Map.of("baseUrl", next.url()));
        assertThat(change.status()).as("%s", change).isEqualTo(200);
        assertThat(change.json().get("baseUrl").asString()).isEqualTo(next.url());
        assertThat(change.json().get("accountsRequired").asBoolean()).isFalse();
        assertThat(next.requests()).isNotEmpty()
            .allSatisfy(request -> assertThat(request.getHeader("Authorization")).isNull());
        assertThat(admin.post(path + "/pause", Map.of("reason", "test")).status()).isEqualTo(200);
        McpStub.Instance third = McpStub.newInstance().serves(List.of(McpStub.tool("ping", null)));
        assertThat(admin.patch(path, Map.of("baseUrl", third.url())).problemCode()).isEqualTo("SYSTEM_PAUSED");
        assertThat(third.requests()).isEmpty();
    }

    /** Registers a system and answers it as the caller sees it. */
    private JsonNode created(ApiClient actor, TestCompany company, Map<String, Object> body) {
        ApiResponse response = actor.post(company.path("/systems"), body);
        assertThat(response.status()).as("create system: %s", response).isEqualTo(201);
        return response.json();
    }

    @Test
    void aTrackersPollIntervalIsItsAdminsToSet() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        // A tracker polls every 60 seconds unless its admins name another interval, 30 to 3600 seconds.
        UUID jira = createSystem(admin, company, "Jira", "JIRA_DC", JiraStub.newInstance().baseUrl(), false);
        String path = company.path("/systems/" + jira);
        assertThat(member.get(path).json().get("pollIntervalSeconds").asInt()).isEqualTo(60);
        ApiResponse github = admin.post(company.path("/systems"), Map.of("name", "GitHub", "kind", "GITHUB",
                "baseUrl", "https://api.github.com", "pollIntervalSeconds", 30));
        assertThat(github.status()).as("%s", github).isEqualTo(201);
        assertThat(github.json().get("pollIntervalSeconds").asInt()).isEqualTo(30);
        assertThat(auditEvents(owner, company, "system.created").getFirst().get("details").get("pollIntervalSeconds")
            .asInt()).isEqualTo(30);
        ApiResponse changed = admin.patch(path, Map.of("pollIntervalSeconds", 600));
        assertThat(changed.status()).as("%s", changed).isEqualTo(200);
        assertThat(changed.json().get("pollIntervalSeconds").asInt()).isEqualTo(600);
        assertThat(auditEvents(owner, company, "system.changed")).singleElement().satisfies(event -> {
            assertThat(event.get("details").get("changed")).extracting(JsonNode::asString)
                .containsExactly("pollIntervalSeconds");
            assertThat(event.get("details").get("pollIntervalSeconds").asInt()).isEqualTo(600);
        });
        assertThat(member.patch(path, Map.of("pollIntervalSeconds", 30)).status()).isEqualTo(403);

        // Out of range, or a kind that is not polled: refused, naming the field.
        for (int seconds : List.of(29, 3601)) {
            ApiResponse refused = admin.patch(path, Map.of("pollIntervalSeconds", seconds));
            assertThat(refused.problemCode()).as("%s", refused).isEqualTo("VALIDATION_FAILED");
        }
        ApiResponse mcp = admin.post(company.path("/systems"), Map.of("name", "Notes", "kind", "MCP", "baseUrl",
                McpStub.newInstance().url(), "pollIntervalSeconds", 60));
        assertThat(mcp.problemCode()).as("%s", mcp).isEqualTo("VALIDATION_FAILED");
        assertThat(mcp.json().get("errors").get(0).get("field").asString()).isEqualTo("pollIntervalSeconds");
        UUID notes = createSystem(admin, company, "Notes", "MCP", McpStub.newInstance().url(), false);
        assertThat(member.get(company.path("/systems/" + notes)).json().has("pollIntervalSeconds")).isFalse();
        assertThat(admin.get(path).json().get("pollIntervalSeconds").asInt()).isEqualTo(600);
    }

    @Test
    void aTestReachesTheSystemAndSetsItsStatus() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID reachable = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        String path = company.path("/systems/" + reachable);
        assertThat(admin.get(path).json().has("lastTest")).isFalse();
        JsonNode ok = admin.post(path + "/test", Map.of()).json();
        assertThat(ok.get("ok").asBoolean()).as("%s", ok).isTrue();
        assertThat(ok.get("withAccount").asBoolean()).isFalse();
        assertThat(admin.get(path).json().get("status").asString()).isEqualTo("OK");
        assertThat(member.post(path + "/test", Map.of()).status()).isEqualTo(403);

        // The test is kept: when, how it ended and who ran it, for whoever manages the system.
        UUID tester = memberPrincipal(company, company.userId("ADMIN"));
        JsonNode stored = admin.get(path).json().get("lastTest");
        assertThat(stored.get("ok").asBoolean()).isTrue();
        assertThat(stored.get("withAccount").asBoolean()).isFalse();
        assertThat(stored.has("error")).isFalse();
        assertThat(stored.get("at").asString()).isNotBlank();
        assertThat(stored.get("byPrincipalId").asString()).isEqualTo(tester.toString());
        assertThat(stored.get("byName").asString()).isEqualTo("User " + company.email("ADMIN"));
        assertThat(stored.has("credentialId")).isFalse();
        // The OWNER, who manages it too, reads who; a MEMBER reads when and how it ended, never who (§6).
        assertThat(owner.get(path).json().get("lastTest").get("byName").asString())
            .isEqualTo("User " + company.email("ADMIN"));
        JsonNode seenByMember = member.get(path).json().get("lastTest");
        assertThat(seenByMember.get("ok").asBoolean()).isTrue();
        assertThat(seenByMember.get("at").asString()).isEqualTo(stored.get("at").asString());
        assertThat(seenByMember.has("byPrincipalId")).isFalse();
        assertThat(seenByMember.has("byName")).isFalse();
        // The list carries it alike.
        assertThat(owner.get(company.path("/systems")).json().get("items").get(0).get("lastTest").get("byName")
            .asString()).isEqualTo("User " + company.email("ADMIN"));
        assertThat(member.get(company.path("/systems")).json().get("items").get(0).get("lastTest")
            .has("byName")).isFalse();

        // With the caller's own account: its "who am I", the token revealed for this test only; the last test says it
        // ran with an account and names none, the tester's own being theirs.
        connectToken(admin, company.path("/me/accounts/" + reachable), jira, "test-tester-token", "tess");
        JsonNode own = admin.post(path + "/test", Map.of()).json();
        assertThat(own.get("ok").asBoolean()).as("%s", own).isTrue();
        assertThat(own.get("withAccount").asBoolean()).isTrue();
        assertThat(own.get("credentialRequired").asBoolean()).isFalse();
        assertThat(own.get("login").asString()).isEqualTo("tess");
        JsonNode withAccount = admin.get(path).json().get("lastTest");
        assertThat(withAccount.get("withAccount").asBoolean()).isTrue();
        assertThat(withAccount.has("credentialId")).isFalse();
        // With one of the system's credentials, named: the last test names it, for whoever manages the system.
        jira.myself("test-credential-token", "svc", "UTC");
        UUID credential = TestCredentials.add(admin, company.path(""), reachable, "Service", "test-credential-token");
        JsonNode named = admin.post(path + "/test?credentialId=" + credential, Map.of()).json();
        assertThat(named.get("ok").asBoolean()).as("%s", named).isTrue();
        assertThat(named.get("credentialId").asString()).isEqualTo(credential.toString());
        assertThat(named.get("credentialName").asString()).isEqualTo("Service");
        JsonNode withCredential = admin.get(path).json().get("lastTest");
        assertThat(withCredential.get("credentialId").asString()).isEqualTo(credential.toString());
        assertThat(withCredential.get("credentialName").asString()).isEqualTo("Service");
        assertThat(member.get(path).json().get("lastTest").has("credentialId")).isFalse();
        // Removed, the credential goes from the test; that it ran with one stays.
        assertThat(admin.delete(path + "/credentials/" + credential).status()).isEqualTo(204);
        JsonNode removed = admin.get(path).json().get("lastTest");
        assertThat(removed.has("credentialId")).isFalse();
        assertThat(removed.get("withAccount").asBoolean()).isTrue();

        Map<String, Object> down = new HashMap<>(Map.of("name", "Down", "kind", "MCP", "baseUrl",
                "http://localhost:9/mcp"));
        UUID unreachable = createSystem(admin, company, down);
        JsonNode failed = admin.post(company.path("/systems/" + unreachable + "/test"), Map.of()).json();
        assertThat(failed.get("ok").asBoolean()).isFalse();
        JsonNode system = admin.get(company.path("/systems/" + unreachable)).json();
        assertThat(system.get("status").asString()).isEqualTo("ERROR");
        assertThat(system.get("lastError").asString()).isNotBlank();
        assertThat(system.get("lastTest").get("ok").asBoolean()).isFalse();
        assertThat(system.get("lastTest").get("error").asString()).isEqualTo(system.get("lastError").asString());
        assertThat(member.get(company.path("/systems/" + unreachable)).json().get("lastTest").get("error")
            .asString()).isNotBlank();

        // A personal server's test: its owner reads who ran it, themselves; nobody else sees the server.
        UUID mine = createSystem(member, company, "Mine", "MCP",
                McpStub.newInstance().serves(List.of(McpStub.tool("search", "Searches"))).url(), true);
        JsonNode personal = member.post(company.path("/systems/" + mine + "/test"), Map.of()).json();
        assertThat(personal.get("ok").asBoolean()).as("%s", personal).isTrue();
        assertThat(member.get(company.path("/systems/" + mine)).json().get("lastTest").get("byPrincipalId")
            .asString()).isEqualTo(memberPrincipal(company, company.userId("MEMBER")).toString());
        assertThat(admin.get(company.path("/systems/" + mine)).status()).isEqualTo(404);
    }

    private static Map<String, Object> annotated(Map<String, Object> tool, Boolean readOnly, Boolean destructive) {
        Map<String, Object> copy = new HashMap<>(tool);
        Map<String, Object> annotations = new HashMap<>();
        if (readOnly != null) {
            annotations.put("readOnlyHint", readOnly);
        }
        if (destructive != null) {
            annotations.put("destructiveHint", destructive);
        }
        copy.put("annotations", annotations);
        return copy;
    }
}
