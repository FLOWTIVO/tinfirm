package com.flowtivo.tinfirm.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeBotFramework;
import com.flowtivo.tinfirm.support.FakeSlack;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chat systems and their bots (the package 5 contract, §3.1, §3.2, §5.1, §6, C9): registering, the bot set, replaced
 * and removed only through {@code …/bot}, a Slack system's own secrets, the manifests and the company's rates.
 */
class ChatSystemsIntegrationTest extends ChatTestBench {

    @Test
    void registersSlackAndTeamsWithTheirSettingsAndFixedBaseUrls() {
        Bench bench = bench(false);
        ApiResponse noSettings = bench.owner().post(bench.path("/systems"), Map.of("name", unique("Slack"), "kind",
                "SLACK"));
        assertThat(noSettings.status()).as("%s", noSettings).isEqualTo(400);
        ApiResponse withBase = bench.owner().post(bench.path("/systems"), Map.of("name", unique("Slack"), "kind",
                "SLACK", "baseUrl", "https://slack.example.test", "slack", Map.of("transport", "EVENTS_API")));
        assertThat(withBase.status()).as("%s", withBase).isEqualTo(400);
        assertThat(withBase.json().get("code").asString()).isEqualTo("SYSTEM_KIND_INVALID");
        ApiResponse byMember = bench.member().post(bench.path("/systems"), Map.of("name", unique("Slack"), "kind",
                "SLACK", "slack", Map.of("transport", "EVENTS_API")));
        assertThat(byMember.status()).isEqualTo(403);

        Slack slack = slack(bench);
        JsonNode seenByMember = bench.member().get(bench.path("/systems/" + slack.systemId())).json();
        assertThat(seenByMember.get("chat").get("botSet").asBoolean()).isTrue();
        assertThat(seenByMember.get("chat").has("eventsUrl")).isFalse();
        JsonNode system = bench.owner().get(bench.path("/systems/" + slack.systemId())).json();
        assertThat(system.get("baseUrl").asString()).isEqualTo(FakeSlack.apiUrl());
        JsonNode chat = system.get("chat");
        assertThat(chat.get("transport").asString()).isEqualTo("EVENTS_API");
        assertThat(chat.get("teamId").asString()).isEqualTo(slack.workspace().teamId);
        assertThat(chat.get("botUserId").asString()).isEqualTo(slack.workspace().botUserId);
        assertThat(chat.get("botSet").asBoolean()).isTrue();
        assertThat(chat.get("signingSecretSet").asBoolean()).isTrue();
        assertThat(chat.get("eventsUrl").asString()).endsWith("/api/chat/slack/" + slack.systemId() + "/events");
        assertThat(system.toString()).doesNotContain(slack.workspace().signingSecret)
            .doesNotContain(slack.workspace().botToken);

        Teams teams = teams(bench);
        JsonNode teamsSystem = bench.owner().get(bench.path("/systems/" + teams.systemId())).json();
        assertThat(teamsSystem.get("baseUrl").asString()).isEqualTo(FakeBotFramework.url());
        assertThat(teamsSystem.get("chat").get("tenantId").asString()).isEqualTo(teams.tenant().tenantId);
        assertThat(teamsSystem.get("chat").get("messagingUrl").asString())
            .endsWith("/api/chat/teams/" + teams.systemId() + "/messages");
        ApiResponse sameTenant = bench.owner().post(bench.path("/systems"), Map.of("name", unique("Teams"), "kind",
                "TEAMS", "teams", Map.of("tenantId", teams.tenant().tenantId, "appId", UUID.randomUUID().toString())));
        assertThat(sameTenant.status()).as("%s", sameTenant).isEqualTo(409);
        assertThat(sameTenant.json().get("code").asString()).isEqualTo("CHAT_WORKSPACE_TAKEN");

        assertThat(auditEvents(bench.owner(), bench.company(), "system.bot_set")).hasSize(2);

        // A chat system's test is kept as its last test: with the bot, never an account of the tester's own.
        ApiResponse tested = bench.owner().post(bench.path("/systems/" + slack.systemId() + "/test"), Map.of());
        assertThat(tested.status()).as("%s", tested).isEqualTo(200);
        JsonNode lastTest = bench.owner().get(bench.path("/systems/" + slack.systemId())).json().get("lastTest");
        assertThat(lastTest.get("ok").asBoolean()).isEqualTo(tested.json().get("ok").asBoolean());
        assertThat(lastTest.get("withAccount").asBoolean()).isEqualTo(tested.json().get("withAccount").asBoolean());
        assertThat(lastTest.has("credentialId")).isFalse();
        assertThat(lastTest.get("byName").asString()).isNotBlank();
        assertThat(bench.member().get(bench.path("/systems/" + slack.systemId())).json().get("lastTest")
            .has("byName")).isFalse();
    }

    @Test
    void setsTheBotOnlyWithATokenThePlatformAcceptsForTheSystemsWorkspace() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        String path = bench.path("/systems/" + slack.systemId() + "/bot");

        // Another workspace's token: the system's teamId is fixed once set.
        String other = "xoxb-test-other-" + UUID.randomUUID();
        slack.workspace().authTestOf(other, "TOTHER" + UUID.randomUUID().toString().substring(0, 4), "UOTHER");
        ApiResponse mismatch = bench.owner().put(path, Map.of("principalId", slack.botPrincipal().toString(),
                "token", other));
        assertThat(mismatch.status()).as("%s", mismatch).isEqualTo(400);
        assertThat(mismatch.json().get("code").asString()).isEqualTo("BOT_IDENTITY_MISMATCH");

        // A refused token.
        String refused = "xoxb-test-refused-" + UUID.randomUUID();
        slack.workspace().revoke(refused);
        ApiResponse notAccepted = bench.owner().put(path, Map.of("principalId", slack.botPrincipal().toString(),
                "token", refused));
        assertThat(notAccepted.status()).as("%s", notAccepted).isEqualTo(400);
        assertThat(notAccepted.json().get("code").asString()).isEqualTo("ACCOUNT_REFUSED");
        assertThat(notAccepted.toString()).doesNotContain(refused);

        // Not a bot token.
        ApiResponse userToken = bench.owner().put(path, Map.of("principalId", slack.botPrincipal().toString(),
                "token", "xoxp-test-user"));
        assertThat(userToken.status()).isEqualTo(400);

        // Another service identity holds the bot: removed first.
        UUID second = createServiceIdentity(bench.owner(), bench.company(), unique("Second bot"));
        ApiResponse inUse = bench.owner().put(path, Map.of("principalId", second.toString(), "token",
                slack.workspace().botToken));
        assertThat(inUse.status()).as("%s", inUse).isEqualTo(409);
        assertThat(inUse.json().get("code").asString()).isEqualTo("BOT_ACCOUNT_IN_USE");

        // The same service identity sets the token again (a rotation).
        ApiResponse again = bench.owner().put(path, Map.of("principalId", slack.botPrincipal().toString(), "token",
                slack.workspace().botToken));
        assertThat(again.status()).as("%s", again).isEqualTo(200);
        assertThat(again.json().get("identity").asString()).isEqualTo(slack.workspace().botUserId);

        // Members and admins of another kind may not; an unknown system is 404, a system of another kind 400.
        assertThat(bench.member().put(path, Map.of("principalId", second.toString(), "token", "xoxb-x")).status())
            .isEqualTo(403);
        assertThat(bench.owner().put(bench.path("/systems/" + UUID.randomUUID() + "/bot"), Map.of("principalId",
                second.toString(), "token", "xoxb-x")).status()).isEqualTo(404);
        UUID git = createSystem(bench.owner(), bench.company(), unique("git"), "GIT", null, false);
        ApiResponse wrongKind = bench.owner().put(bench.path("/systems/" + git + "/bot"), Map.of("principalId",
                second.toString(), "token", "xoxb-x"));
        assertThat(wrongKind.status()).isEqualTo(400);
        assertThat(wrongKind.json().get("code").asString()).isEqualTo("SYSTEM_KIND_INVALID");

        // Removed: the account goes, events are dropped until a bot is set again.
        assertThat(bench.owner().delete(path).status()).isEqualTo(204);
        assertThat(bench.owner().delete(path).status()).isEqualTo(404);
        JsonNode system = bench.owner().get(bench.path("/systems/" + slack.systemId())).json();
        assertThat(system.get("chat").get("botSet").asBoolean()).isFalse();
        assertThat(system.get("chat").get("teamId").asString()).isEqualTo(slack.workspace().teamId);
        assertThat(auditEvents(bench.owner(), bench.company(), "system.bot_removed")).hasSize(1);
        ApiResponse byOther = bench.owner().put(path, Map.of("principalId", second.toString(), "token",
                slack.workspace().botToken));
        assertThat(byOther.status()).as("%s", byOther).isEqualTo(200);
    }

    @Test
    void neverGrantsBindsOrRemovesTheBotsAccountOutsideItsRoutes() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        JsonNode system = bench.owner().get(bench.path("/systems/" + slack.systemId())).json();
        UUID botAccount = UUID.fromString(system.get("chat").get("botCredentialId").asString());
        assertThat(system.get("chat").get("botCredentialName").asString()).isNotBlank();
        // Whose account the bot is, for whoever manages the system; a member sees neither.
        assertThat(system.get("chat").get("botPrincipalId").asString()).isEqualTo(slack.botPrincipal().toString());
        assertThat(system.get("chat").get("botPrincipalName").asString()).isNotBlank();
        assertThat(bench.member().get(bench.path("/systems/" + slack.systemId())).json().get("chat")
            .has("botPrincipalId")).isFalse();
        UUID group = createGroup(bench.owner(), bench.company(), unique("group"), List.of(bench.company()
            .userId("MEMBER")));

        ApiResponse grant = bench.owner().post(bench.path("/grants"), Map.of("credentialId", botAccount.toString(),
                "groupId", group.toString(), "access", "READ"));
        assertThat(grant.status()).as("%s", grant).isEqualTo(400);
        assertThat(grant.json().get("code").asString()).isEqualTo("BOT_ACCOUNT");

        // The bot is a credential of the system that changes only with the bot: never removed, disabled, stored
        // again or lent elsewhere among the system's credentials.
        String credential = bench.path("/systems/" + slack.systemId() + "/credentials/" + botAccount);
        UUID other = createServiceIdentity(bench.owner(), bench.company(), unique("svc-other"));
        for (ApiResponse refused : List.of(bench.owner().delete(credential),
                bench.owner().patch(credential, Map.of("enabled", false)),
                bench.owner().patch(credential, Map.of("name", "Renamed bot")),
                bench.owner().put(credential + "/token", Map.of("token", "xoxb-test-other")),
                bench.owner().put(credential + "/service-accounts/" + other, Map.of("access", "READ")),
                bench.owner().delete(credential + "/service-accounts/" + slack.botPrincipal()))) {
            assertThat(refused.status()).as("%s", refused).isEqualTo(409);
            assertThat(refused.json().get("code").asString()).isEqualTo("BOT_ACCOUNT_IN_USE");
        }
        assertThat(bench.owner().get(bench.path("/systems/" + slack.systemId() + "/credentials")).json()
            .get("items")).singleElement().satisfies(item -> {
                assertThat(item.get("bot").asBoolean()).isTrue();
                assertThat(item.get("enabled").asBoolean()).isTrue();
            });

        ApiResponse deleted = bench.owner().delete(bench.path("/service-identities/" + slack.botPrincipal()));
        assertThat(deleted.status()).as("%s", deleted).isEqualTo(409);
        assertThat(deleted.json().get("code").asString()).isEqualTo("SERVICE_IDENTITY_IN_USE");

        // The database refuses a grant of a bot's account however it is written (C9).
        try (var sql = com.flowtivo.tinfirm.support.TenantSql.in(bench.id())) {
            String refusal = sql.refused("INSERT INTO account_grant (id, company_id, account_id, group_id, access,"
                    + " created_at, updated_at) VALUES (?, ?, ?, ?, 'READ', now(), now())", UUID.randomUUID(),
                    bench.id(), botAccount, group);
            assertThat(refusal).contains("bot");
        }
        catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void storesASlackSystemsOwnSecretsWithoutEverAnsweringThem() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        String path = bench.path("/systems/" + slack.systemId() + "/chat/secrets");
        assertThat(bench.owner().put(path, Map.of()).status()).isEqualTo(400);
        assertThat(bench.owner().put(path, Map.of("appToken", "xoxb-not-an-app-token")).status()).isEqualTo(400);
        assertThat(bench.member().put(path, Map.of("signingSecret", "x")).status()).isEqualTo(403);
        ApiResponse stored = bench.owner().put(path, Map.of("signingSecret", "test-new-signing-secret", "appToken",
                "xapp-test-new"));
        assertThat(stored.status()).as("%s", stored).isEqualTo(200);
        assertThat(stored.json().get("chat").get("appTokenSet").asBoolean()).isTrue();
        assertThat(stored.toString()).doesNotContain("test-new-signing-secret").doesNotContain("xapp-test-new");
        assertThat(auditEvents(bench.owner(), bench.company(), "system.changed")).isNotEmpty();

        // The managed secrets stay the system's: the Secrets API neither deletes nor replaces them.
        JsonNode secrets = bench.owner().get(bench.path("/secrets")).json().get("items");
        JsonNode signing = null;
        for (JsonNode secret : secrets) {
            if ("SLACK_SIGNING_SECRET".equals(secret.get("name").asString())) {
                signing = secret;
            }
        }
        assertThat(signing).as("%s", secrets).isNotNull();
        assertThat(bench.owner().delete(bench.path("/secrets/" + signing.get("id").asString())).status())
            .isEqualTo(409);

        Teams teams = teams(bench);
        ApiResponse onTeams = bench.owner().put(bench.path("/systems/" + teams.systemId() + "/chat/secrets"),
                Map.of("signingSecret", "x"));
        assertThat(onTeams.status()).isEqualTo(400);
        assertThat(onTeams.json().get("code").asString()).isEqualTo("SYSTEM_KIND_INVALID");
    }

    @Test
    void generatesTheManifestsAnAdminInstalls() throws IOException {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        ApiResponse manifest = bench.owner().get(bench.path("/systems/" + slack.systemId() + "/chat/manifest"));
        assertThat(manifest.status()).as("%s", manifest).isEqualTo(200);
        JsonNode app = manifest.json().get("manifest");
        List<String> scopes = strings(app.get("oauth_config").get("scopes").get("bot"));
        assertThat(scopes).contains("app_mentions:read", "im:history", "chat:write", "users:read")
            .doesNotContain("channels:history", "groups:history", "mpim:history", "mpim:read");
        List<String> events = strings(app.get("settings").get("event_subscriptions").get("bot_events"));
        assertThat(events).contains("app_mention", "message.im", "group_archive", "group_unarchive", "group_rename",
                "group_deleted", "group_left", "channel_shared", "channel_unshared", "app_uninstalled",
                "tokens_revoked")
            .doesNotContain("message.channels", "message.groups", "message.mpim");
        assertThat(app.get("settings").get("event_subscriptions").get("request_url").asString())
            .endsWith("/api/chat/slack/" + slack.systemId() + "/events");
        assertThat(app.get("settings").get("interactivity").get("request_url").asString())
            .endsWith("/api/chat/slack/" + slack.systemId() + "/interactions");
        assertThat(bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/manifest")).status())
            .isEqualTo(403);

        Teams teams = teams(bench);
        JsonNode teamsManifest = bench.owner().get(bench.path("/systems/" + teams.systemId() + "/chat/manifest"))
            .json();
        byte[] zip = Base64.getDecoder().decode(teamsManifest.get("appPackage").asString());
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        assertThat(entries).containsKeys("manifest.json", "color.png", "outline.png");
        JsonNode teamsApp = JsonMapper.builder().build().readTree(entries.get("manifest.json"));
        assertThat(teamsApp.get("id").asString()).isEqualTo(teams.tenant().appId);
        JsonNode bot = teamsApp.get("bots").get(0);
        assertThat(strings(bot.get("scopes"))).containsExactlyInAnyOrder("personal", "team");
        assertThat(bot.get("supportsFiles").asBoolean()).isFalse();
        assertThat(teamsApp.has("authorization")).isFalse();
    }

    @Test
    void keepsTheCompanysRatesWithinTheirRanges() {
        Bench bench = bench(false);
        String path = bench.path("/chat-settings");
        JsonNode defaults = bench.owner().get(path).json();
        assertThat(defaults.get("messagesPerUserPerMinute").asInt()).isEqualTo(20);
        assertThat(defaults.get("messagesPerUserPerDay").asInt()).isEqualTo(200);
        assertThat(defaults.get("eventsPerSystemPerMinute").asInt()).isEqualTo(600);
        // The defaults are not a row yet: version 0.
        assertThat(defaults.get("version").asInt()).isEqualTo(0);
        assertThat(bench.member().get(path).status()).isEqualTo(403);
        assertThat(bench.owner().put(path, Map.of("messagesPerUserPerMinute", 0, "messagesPerUserPerDay", 1,
                "eventsPerSystemPerMinute", 1, "version", 0)).status()).isEqualTo(400);
        assertThat(bench.owner().put(path, Map.of("messagesPerUserPerMinute", 5, "messagesPerUserPerDay", 50,
                "eventsPerSystemPerMinute", 6001, "version", 0)).status()).isEqualTo(400);
        // Without the version read, the save is refused: it could write over another admin's.
        assertThat(bench.owner().put(path, Map.of("messagesPerUserPerMinute", 5, "messagesPerUserPerDay", 50,
                "eventsPerSystemPerMinute", 100)).problemCode()).isEqualTo("VALIDATION_FAILED");
        ApiResponse set = bench.admin().put(path, Map.of("messagesPerUserPerMinute", 5, "messagesPerUserPerDay", 50,
                "eventsPerSystemPerMinute", 100, "version", 0));
        assertThat(set.status()).as("%s", set).isEqualTo(200);
        assertThat(set.json().get("version").asInt()).isEqualTo(1);
        assertThat(bench.owner().get(path).json().get("messagesPerUserPerMinute").asInt()).isEqualTo(5);
        // A second admin who read version 0 is refused: the rates are the first one's until read again.
        ApiResponse stale = bench.owner().put(path, Map.of("messagesPerUserPerMinute", 7, "messagesPerUserPerDay", 70,
                "eventsPerSystemPerMinute", 700, "version", 0));
        assertThat(stale.status()).as("%s", stale).isEqualTo(409);
        assertThat(stale.problemCode()).isEqualTo("SETTINGS_CHANGED");
        assertThat(bench.owner().get(path).json().get("messagesPerUserPerMinute").asInt()).isEqualTo(5);
        ApiResponse again = bench.owner().put(path, Map.of("messagesPerUserPerMinute", 7, "messagesPerUserPerDay", 70,
                "eventsPerSystemPerMinute", 700, "version", 1));
        assertThat(again.status()).as("%s", again).isEqualTo(200);
        assertThat(again.json().get("version").asInt()).isEqualTo(2);
        assertThat(auditEvents(bench.owner(), bench.company(), "chat.settings_changed")).hasSize(2);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }
}
