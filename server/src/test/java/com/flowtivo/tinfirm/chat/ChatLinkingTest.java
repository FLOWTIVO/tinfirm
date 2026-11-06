package com.flowtivo.tinfirm.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeBotFramework;
import com.flowtivo.tinfirm.support.FakeSlack;

import tools.jackson.databind.JsonNode;

/**
 * Linking chat users to members (the package 5 contract, §5.4, security review rows 5 and 6, open point 16): the
 * sign-in link bound to one chat user, used once, for 10 minutes, its token never in a path; both sides told; Teams'
 * automatic link by the Entra tenant and object id of the member's own login; an admin's removal; a member who leaves.
 */
class ChatLinkingTest extends ChatTestBench {

    @Test
    void linksAChatUserThroughTheSignInLinkAndTellsBothSides() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String user = workspace.member("Mia Member");
        Map<String, Object> message = workspace.directMessage(user, "hello", null);
        workspace.deliver(port, slack.systemId(), message);
        String eventId = (String) message.get("event_id");
        awaitEvent(bench.id(), slack.systemId(), eventId, "AWAITING_LINK");
        // A second message waits for the same link: one link per 10 minutes.
        Map<String, Object> second = workspace.directMessage(user, "are you there?", null);
        workspace.deliver(port, slack.systemId(), second);
        awaitEvent(bench.id(), slack.systemId(), (String) second.get("event_id"), "AWAITING_LINK");
        List<JsonNode> posts = workspace.posts("D" + user);
        assertThat(posts).hasSize(1);
        String[] link = linkIn(posts);
        assertThat(link[0]).isEqualTo(bench.id().toString());
        assertThat(auditEvents(bench.owner(), bench.company(), "chat.link_offered")).hasSize(1);

        // What it links: the chat user's immutable id, the workspace, when they asked; nothing consumed.
        ApiResponse read = bench.member().post(bench.path("/chat-links/read"), Map.of("token", link[1]));
        assertThat(read.status()).as("%s", read).isEqualTo(200);
        assertThat(read.json().get("chatUserId").asString()).isEqualTo(user);
        assertThat(read.json().get("workspaceId").asString()).isEqualTo(workspace.teamId);
        assertThat(read.json().get("workspaceName").asString()).isEqualTo(workspace.teamName);
        assertThat(read.json().get("requestedAt").asString()).isNotBlank();
        assertThat(read.json().get("ownerNotice").asString()).contains("Owner");
        // Another company's member, or an unknown token: nothing to see.
        Bench other = bench(false);
        assertThat(other.member().post(bench.path("/chat-links/read"), Map.of("token", link[1])).status())
            .isEqualTo(404);
        assertThat(other.member().post(other.path("/chat-links/read"), Map.of("token", link[1])).status())
            .isEqualTo(404);
        assertThat(bench.member().post(bench.path("/chat-links/read"), Map.of("token", "unknown")).status())
            .isEqualTo(404);

        ApiResponse confirmed = bench.member().post(bench.path("/chat-links/confirm"), Map.of("token", link[1]));
        assertThat(confirmed.status()).as("%s", confirmed).isEqualTo(200);
        assertThat(confirmed.json().get("linked").asBoolean()).isTrue();
        UUID accountId = UUID.fromString(confirmed.json().get("accountId").asString());

        // Single use.
        ApiResponse again = bench.admin().post(bench.path("/chat-links/confirm"), Map.of("token", link[1]));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.json().get("code").asString()).isEqualTo("CHAT_LINK_USED");

        // The member's account: a LINK naming the chat user; audited with how.
        JsonNode accounts = bench.member().get(bench.path("/me/accounts")).json();
        assertThat(accounts.toString()).contains(accountId.toString()).contains("LINK");
        assertThat(auditEvents(bench.owner(), bench.company(), "account.connected"))
            .anySatisfy(event -> assertThat(event.toString()).contains("SIGN_IN_LINK").contains(user));
        // Both sides told: the direct message, and the member's inbox with its unlink.
        await().atMost(WAIT).until(() -> said(workspace.posts("D" + user)).contains("Linked to"));
        JsonNode inbox = bench.member().get(bench.path("/inbox")).json();
        assertThat(inbox.toString()).contains("CHAT_LINKED").contains(slack.systemId().toString());
        // The chat user by name and id, for the web to name them; the text names them too.
        JsonNode linked = linkedItem(inbox);
        assertThat(linked.get("chatUserId").asString()).isEqualTo(user);
        assertThat(linked.get("chatUserName").asString()).isEqualTo("Mia Member");
        assertThat(linked.get("text").asString()).contains("Slack user Mia Member (" + user + ") of ");
        assertThat(bench.admin().get(bench.path("/inbox")).json().toString()).doesNotContain("CHAT_LINKED");
        // The held messages are dropped, not sent as the member who confirmed (the security review of 2026-10-27, L3).
        awaitEvent(bench.id(), slack.systemId(), eventId, "IGNORED");
    }

    @Test
    void refusesAnExpiredLinkAndAChatUserLinkedToSomeoneElse() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String user = workspace.member("Eve Expired");
        Map<String, Object> message = workspace.directMessage(user, "hello", null);
        workspace.deliver(port, slack.systemId(), message);
        awaitEvent(bench.id(), slack.systemId(), (String) message.get("event_id"), "AWAITING_LINK");
        String[] link = linkIn(workspace.posts("D" + user));
        inCompany(bench.id(), "UPDATE chat_link_request SET expires_at = now() - interval '1 second', created_at = now() -"
                + " interval '11 minutes' WHERE system_id = ?", slack.systemId());
        inCompany(bench.id(), "UPDATE chat_event SET held_until = now() - interval '1 second' WHERE system_id = ?"
                + " AND status = 'AWAITING_LINK'", slack.systemId());
        ApiResponse expired = bench.member().post(bench.path("/chat-links/confirm"), Map.of("token", link[1]));
        assertThat(expired.status()).as("%s", expired).isEqualTo(410);
        assertThat(expired.json().get("code").asString()).isEqualTo("CHAT_LINK_EXPIRED");
        // The held message was dropped with its hold, its sender told.
        await().atMost(WAIT).until(() -> "LINK_EXPIRED".equals(event(bench.id(), slack.systemId(),
                (String) message.get("event_id")).orElseThrow().get("reason")));

        // The member links; the admin cannot take the same chat user, nor link a second one of their own twice.
        linkSlack(bench, slack, bench.member(), workspace.member("Nina"));
        String taken = workspace.member("Theo");
        linkSlack(bench, slack, bench.admin(), taken);
        String another = workspace.member("Ola");
        Map<String, Object> fromAnother = workspace.directMessage(another, "hello", null);
        workspace.deliver(port, slack.systemId(), fromAnother);
        awaitEvent(bench.id(), slack.systemId(), (String) fromAnother.get("event_id"), "AWAITING_LINK");
        String[] secondLink = linkIn(workspace.posts("D" + another));
        ApiResponse twice = bench.admin().post(bench.path("/chat-links/confirm"), Map.of("token", secondLink[1]));
        assertThat(twice.status()).as("%s", twice).isEqualTo(409);
        assertThat(twice.json().get("code").asString()).isEqualTo("ALREADY_LINKED");
        // Consumed whatever the outcome.
        assertThat(bench.owner().post(bench.path("/chat-links/confirm"), Map.of("token", secondLink[1])).status())
            .isEqualTo(409);
        assertThat(auditEvents(bench.owner(), bench.company(), "chat.link_refused")).hasSize(1);
    }

    @Test
    void anAdminRemovesAMembersChatAccountAndAMemberWhoLeavesLosesTheirLinks() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String user = workspace.member("Rita");
        linkSlack(bench, slack, bench.member(), user);
        String path = bench.path("/members/" + bench.company().userId("MEMBER") + "/accounts/" + slack.systemId());
        assertThat(bench.member().delete(path).status()).isEqualTo(403);
        UUID git = createSystem(bench.owner(), bench.company(), unique("git"), "GIT", null, false);
        ApiResponse notChat = bench.admin().delete(bench.path("/members/" + bench.company().userId("MEMBER")
                + "/accounts/" + git));
        assertThat(notChat.status()).isEqualTo(400);
        assertThat(notChat.json().get("code").asString()).isEqualTo("NOT_A_CHAT_ACCOUNT");
        assertThat(bench.admin().delete(path).status()).isEqualTo(204);
        assertThat(bench.admin().delete(path).status()).isEqualTo(404);
        assertThat(auditEvents(bench.owner(), bench.company(), "account.disconnected"))
            .anySatisfy(event -> assertThat(event.toString()).contains("admin"));
        // Their next message asks for a link again.
        Map<String, Object> message = workspace.directMessage(user, "back again", null);
        workspace.deliver(port, slack.systemId(), message);
        awaitEvent(bench.id(), slack.systemId(), (String) message.get("event_id"), "AWAITING_LINK");

        // A member who leaves loses their links.
        String admins = workspace.member("Ada");
        linkSlack(bench, slack, bench.admin(), admins);
        assertThat(rows(bench.id(), "SELECT count(*) AS n FROM account WHERE system_id = ? AND kind = 'LINK'",
                slack.systemId()).getFirst().get("n")).isEqualTo(1L);
        ApiResponse left = bench.owner().delete(bench.path("/members/" + bench.company().userId("ADMIN")));
        assertThat(left.status()).as("%s", left).isEqualTo(204);
        await().atMost(WAIT).until(() -> rows(bench.id(), "SELECT count(*) AS n FROM account WHERE system_id = ?"
                + " AND kind = 'LINK'", slack.systemId()).getFirst().get("n").equals(0L));
    }

    @Test
    void linksATeamsUserByTheEntraIdsOfTheMembersOwnLogin() {
        Bench bench = bench(false);
        Teams teams = teams(bench);
        FakeBotFramework.Tenant tenant = teams.tenant();
        FakeBotFramework.TeamsUser user = tenant.user("Tom Teams", "user");
        // The member's last login through the tenant's Entra ID named this object.
        entraLogin(bench.company().userId("MEMBER"), tenant.tenantId, user.aadObjectId());
        Map<String, Object> message = tenant.personalMessage(user, "hello");
        tenant.deliver(port, teams.systemId(), message);
        String eventId = "a:dm-" + user.id() + ":" + message.get("id");
        await().atMost(WAIT).until(() -> event(bench.id(), teams.systemId(), eventId)
            .map(row -> !"RECEIVED".equals(row.get("status"))).orElse(false));
        assertThat(rows(bench.id(), "SELECT external_id FROM account WHERE system_id = ? AND kind = 'LINK'",
                teams.systemId())).singleElement()
            .satisfies(row -> assertThat(row.get("external_id")).isEqualTo(user.aadObjectId()));
        assertThat(auditEvents(bench.owner(), bench.company(), "account.connected"))
            .anySatisfy(event -> assertThat(event.toString()).contains("AUTOMATIC"));
        await().atMost(WAIT).until(() -> said(tenant.posts("a:dm-" + user.id())).contains("Linked to"));
        assertThat(said(tenant.posts("a:dm-" + user.id()))).doesNotContain("/link/");
        JsonNode linked = linkedItem(bench.member().get(bench.path("/inbox")).json());
        assertThat(linked.get("chatUserId").asString()).isEqualTo(user.id());
        assertThat(linked.get("chatUserName").asString()).isEqualTo("Tom Teams");

        // Another tenant's object id never links: the sign-in link instead.
        FakeBotFramework.TeamsUser stranger = tenant.user("Sam Same", "user");
        entraLogin(bench.company().userId("ADMIN"), UUID.randomUUID().toString(), stranger.aadObjectId());
        Map<String, Object> fromStranger = tenant.personalMessage(stranger, "hello");
        tenant.deliver(port, teams.systemId(), fromStranger);
        awaitEvent(bench.id(), teams.systemId(), "a:dm-" + stranger.id() + ":" + fromStranger.get("id"),
                "AWAITING_LINK");
        assertThat(said(tenant.posts("a:dm-" + stranger.id()))).contains("/link/");
    }

    /** The user's last login was through the tenant's Entra ID, whose ID token named the object. */
    private static void entraLogin(UUID userId, String tenantId, String objectId) {
        asOwner("UPDATE app_user SET auth_source = 'OIDC', password_hash = NULL, oidc_issuer = ?, oidc_subject = ?,"
                + " oidc_tenant_id = ?, oidc_object_id = ? WHERE id = ?", "https://login.microsoftonline.com/"
                + tenantId + "/v2.0", "subject-" + objectId, tenantId, objectId, userId);
    }

    @Test
    void setsTheMembersDefaultAgentOnlyToOneTheyUse() {
        Bench bench = bench(false);
        String path = bench.path("/me/chat");
        assertThat(bench.member().get(path).json().has("defaultAgentId")).isFalse();
        String[] own = agent(bench.member(), bench, "Own", List.of());
        ApiResponse set = bench.member().put(path, Map.of("defaultAgentId", own[0]));
        assertThat(set.status()).as("%s", set).isEqualTo(200);
        assertThat(bench.member().get(path).json().get("defaultAgentId").asString()).isEqualTo(own[0]);
        String[] admins = agent(bench.admin(), bench, "Admins", List.of());
        assertThat(bench.member().put(path, Map.of("defaultAgentId", admins[0])).status()).isEqualTo(404);
        ApiResponse notUsed = bench.owner().put(path, Map.of("defaultAgentId", admins[0]));
        assertThat(notUsed.status()).isEqualTo(403);
        assertThat(notUsed.json().get("code").asString()).isEqualTo("AGENT_NOT_USABLE");
        assertThat(bench.member().put(path, Map.of("defaultAgentId", UUID.randomUUID().toString())).status())
            .isEqualTo(404);
        ApiClient member = bench.member();
        java.util.Map<String, Object> none = new java.util.HashMap<>();
        none.put("defaultAgentId", null);
        assertThat(member.put(path, none).status()).isEqualTo(200);
        assertThat(member.get(path).json().has("defaultAgentId")).isFalse();
    }

    /** The inbox's {@code CHAT_LINKED} item's {@code chat}. */
    static JsonNode linkedItem(JsonNode inbox) {
        for (JsonNode item : inbox.get("items")) {
            if ("CHAT_LINKED".equals(item.get("kind").asString())) {
                return item.get("chat");
            }
        }
        throw new AssertionError("No CHAT_LINKED item in " + inbox);
    }
}
