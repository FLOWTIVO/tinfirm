package com.flowtivo.tinfirm.trigger;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TestCredentials;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * The trigger API end to end (the package 4 contract, §5.1, §5.2, §5.11, §6, with §11): who sees and acts on a trigger
 * and its conversations (T15, rights per role, an admin's edit on the author's behalf), the editor's refusals for the
 * author's entitlement (T1), a schedule's run now, pause, resume and delete, the company's firing limits, an author
 * who leaves and the reassignment's rebinding of the waiting conversation (V9 as §11 decided it), a service identity
 * that a live trigger names, and an MCP server that needs no account (ADR-0071). The security review of 2026-10-27:
 * reassignment only after the author's loss, accepted by the new author (H2), an admin's edit that names the author's
 * own account refused, the choices of another member only for the trigger named, a member made Viewer whose triggers
 * pause (M7), an open trigger that writes with a lent credential acknowledged by its author and its lender told (D2),
 * and an acknowledgement a later change made unnecessary cleared whole.
 */
class TriggerIntegrationTest extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(20);

    final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    /**
     * A company: OWNER, ADMIN, Alice (MEMBER, the author), Bob (a member of the team and of the agent's group), Carol
     * (a member of neither); the bundled gateway with every member's key; an agent the OWNER made and shared with
     * Alice's and Bob's group.
     */
    record Bench(TestCompany company, ApiClient owner, ApiClient admin, ApiClient alice, ApiClient bob, ApiClient carol,
            UUID adminPrincipal, UUID alicePrincipal, UUID bobPrincipal, UUID carolPrincipal, UUID team, UUID agentId,
            int agentVersion, UUID carolUser) {

        String path(String suffix) {
            return company.path(suffix);
        }
    }

    Bench bench() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String bobEmail = uniqueEmail("bob");
        String carolEmail = uniqueEmail("carol");
        UUID bobUser = createUser(admin(), bobEmail, false);
        UUID carolUser = createUser(admin(), carolEmail, false);
        addMember(owner, company.id(), bobEmail, "MEMBER");
        addMember(owner, company.id(), carolEmail, "MEMBER");
        UUID alicePrincipal = memberPrincipal(company, company.userId("MEMBER"));
        UUID bobPrincipal = memberPrincipal(company, bobUser);
        UUID carolPrincipal = memberPrincipal(company, carolUser);
        UUID adminPrincipal = memberPrincipal(company, company.userId("ADMIN"));
        ModelGateways.configureBundled(owner, company);
        for (UUID principal : List.of(alicePrincipal, bobPrincipal, carolPrincipal, adminPrincipal)) {
            ModelGateways.awaitKey(litellm, company, principal);
            ModelGateways.awaitStatus(owner, company, principal, "OK");
        }
        UUID users = createGroup(owner, company, unique("Builders"), List.of(company.userId("MEMBER"), bobUser));
        UUID team = createGroup(owner, company, unique("Team"), List.of(bobUser));
        ApiResponse agent = owner.post(company.path("/agents"), Map.of("name", unique("Builder"), "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "Build what is asked.", "shareGroupIds",
                List.of(users.toString())));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        return new Bench(company, owner, loginAs(company.email("ADMIN")), loginAs(company.email("MEMBER")),
                loginAs(bobEmail), loginAs(carolEmail), adminPrincipal, alicePrincipal, bobPrincipal, carolPrincipal,
                team, UUID.fromString(agent.json().get("id").asString()), agent.json().get("version").asInt(),
                carolUser);
    }

    Map<String, Object> schedule(Bench bench, String name, UUID payer, @org.jspecify.annotations.Nullable UUID team) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("source", "SCHEDULE");
        body.put("schedule", Map.of("cron", "0 9 1 * *", "zone", "Europe/Prague"));
        body.put("agentId", bench.agentId().toString());
        body.put("agentVersion", bench.agentVersion());
        body.put("instruction", "Write the monthly report.");
        body.put("accounts", List.of());
        body.put("payerPrincipalId", payer.toString());
        if (team != null) {
            body.put("teamGroupId", team.toString());
        }
        return body;
    }

    JsonNode create(ApiClient client, Bench bench, Map<String, Object> body) {
        ApiResponse response = client.post(bench.path("/triggers"), withModel(body));
        assertThat(response.status()).as("%s", response).isEqualTo(201);
        return response.json();
    }

    static boolean absent(JsonNode node, String field) {
        return node.path(field).isMissingNode() || node.path(field).isNull();
    }

    /** The reasons of the caller's {@code TRIGGER} items in their inbox, for the trigger when one is named. */
    static List<String> triggerReasons(ApiClient reader, Bench bench, @org.jspecify.annotations.Nullable String id) {
        List<String> reasons = new ArrayList<>();
        reader.get(bench.path("/inbox")).json().get("items").forEach(item -> {
            if ("TRIGGER".equals(item.get("kind").asString())
                    && (id == null || id.equals(item.get("trigger").get("triggerId").asString()))) {
                reasons.add(item.get("trigger").get("reason").asString());
            }
        });
        return reasons;
    }

    static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    @Test
    void aScheduleIsSeenRunPausedAndDeletedByItsAudienceOnly() {
        Bench bench = bench();
        JsonNode trigger = create(bench.alice(), bench, schedule(bench, unique("Monthly"), bench.alicePrincipal(),
                bench.team()));
        String id = trigger.get("id").asString();
        assertThat(trigger.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(trigger.get("rights").get("edits").asBoolean()).isTrue();
        assertThat(trigger.get("schedule").get("nextFirings")).hasSize(3);
        assertThat(trigger.get("sentence").asString()).contains("0 9 1 * *");

        // T15: its author, its team and admins see it; anyone else gets 404, and lists leave it out.
        assertThat(bench.bob().get(bench.path("/triggers/" + id)).json().get("rights").get("edits").asBoolean())
            .isFalse();
        assertThat(bench.admin().get(bench.path("/triggers/" + id)).status()).isEqualTo(200);
        assertThat(bench.carol().get(bench.path("/triggers/" + id)).status()).isEqualTo(404);
        assertThat(ids(bench.carol().get(bench.path("/triggers")).json())).doesNotContain(id);
        assertThat(ids(bench.bob().get(bench.path("/triggers")).json())).contains(id);
        assertThat(ids(bench.owner().get(bench.path("/triggers")).json())).contains(id);

        // §5.1: the team does not edit, resume or delete it; it runs and pauses it.
        assertThat(bench.bob().patch(bench.path("/triggers/" + id), Map.of("name", "Renamed")).problemCode())
            .isEqualTo("TRIGGER_NOT_EDITABLE");
        assertThat(bench.carol().patch(bench.path("/triggers/" + id), Map.of("name", "Renamed")).status())
            .isEqualTo(404);
        ApiResponse run = bench.bob().post(bench.path("/triggers/" + id + "/run"), Map.of());
        assertThat(run.status()).as("%s", run).isEqualTo(200);
        assertThat(run.json().get("outcome").asString()).isEqualTo("STARTED");
        assertThat(run.json().get("event").asString()).isEqualTo("SCHEDULE");
        String conversationId = run.json().get("conversationId").asString();

        // Its conversation: no person, its trigger and author; seen by the audience, 404 to anyone else.
        JsonNode conversation = bench.alice().get(bench.path("/conversations/" + conversationId)).json();
        assertThat(absent(conversation, "personPrincipalId")).isTrue();
        assertThat(conversation.get("trigger").get("triggerId").asString()).isEqualTo(id);
        assertThat(conversation.get("trigger").get("authorPrincipalId").asString())
            .isEqualTo(bench.alicePrincipal().toString());
        assertThat(conversation.get("place").asString()).isEqualTo("SCHEDULE");
        assertThat(conversation.get("origin").asString()).isEqualTo("TRIGGER");
        assertThat(conversation.get("payer").get("principalId").asString())
            .isEqualTo(bench.alicePrincipal().toString());
        assertThat(conversation.get("turnCount").asInt()).isEqualTo(1);
        assertThat(bench.bob().get(bench.path("/conversations/" + conversationId)).status()).isEqualTo(200);
        assertThat(bench.admin().get(bench.path("/conversations/" + conversationId)).status()).isEqualTo(200);
        assertThat(bench.carol().get(bench.path("/conversations/" + conversationId)).status()).isEqualTo(404);
        assertThat(ids(bench.carol().get(bench.path("/conversations")).json())).doesNotContain(conversationId);
        assertThat(ids(bench.bob().get(bench.path("/conversations?triggerId=" + id)).json()))
            .containsExactly(conversationId);
        assertThat(bench.carol().post(bench.path("/conversations/" + conversationId + "/messages"),
                Map.of("body", "Hello")).status()).isEqualTo(404);
        assertThat(bench.bob().get(bench.path("/triggers/" + id + "/firings")).json().get("items")).hasSize(1);

        // Pause (the team's kill switch), resume (not the team's), delete (the author's).
        ApiResponse paused = bench.bob().post(bench.path("/triggers/" + id + "/pause"), Map.of("stopRunning", true));
        assertThat(paused.status()).as("%s", paused).isEqualTo(200);
        assertThat(paused.json().get("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.json().get("pausedReason").asString()).isEqualTo("USER");
        assertThat(bench.bob().post(bench.path("/triggers/" + id + "/run"), Map.of()).problemCode())
            .isEqualTo("TRIGGER_PAUSED");
        assertThat(bench.bob().post(bench.path("/triggers/" + id + "/resume"), Map.of()).problemCode())
            .isEqualTo("TRIGGER_NOT_EDITABLE");
        ApiResponse resumed = bench.alice().post(bench.path("/triggers/" + id + "/resume"), Map.of());
        assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);
        assertThat(resumed.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(bench.bob().delete(bench.path("/triggers/" + id)).problemCode()).isEqualTo("TRIGGER_NOT_EDITABLE");
        assertThat(bench.alice().delete(bench.path("/triggers/" + id)).status()).isEqualTo(204);
        assertThat(bench.alice().get(bench.path("/triggers/" + id)).status()).isEqualTo(404);
        JsonNode ended = bench.alice().get(bench.path("/conversations/" + conversationId)).json();
        assertThat(ended.get("state").asString()).isEqualTo("ENDED");
        assertThat(ended.get("endedReason").asString()).isEqualTo("TRIGGER_DELETED");

        for (String action : List.of("trigger.created", "trigger.accounts_changed", "trigger.settings_changed",
                "trigger.run", "trigger.paused", "trigger.resumed", "trigger.deleted")) {
            assertThat(auditEvents(bench.owner(), bench.company(), action)).as(action).isNotEmpty();
        }
        assertThat(auditEvents(bench.owner(), bench.company(), "trigger.created").getFirst().get("details")
            .has("instruction")).isFalse();
    }

    @Test
    void theEditorChecksEveryChoiceForTheAuthor() {
        Bench bench = bench();
        String name = unique("Weekly");
        // T1: a payer who is another member; a payer the author may not use.
        assertThat(bench.alice().post(bench.path("/triggers"),
                withModel(schedule(bench, name, bench.bobPrincipal(), null))).problemCode())
            .isEqualTo("MODEL_ACCESS_NOT_ENTITLED");
        UUID bot = createServiceIdentity(bench.owner(), bench.company(), unique("Bot"));
        assertThat(bench.alice().post(bench.path("/triggers"), withModel(schedule(bench, name, bot, null)))
            .problemCode()).isEqualTo("MODEL_ACCESS_NOT_ENTITLED");
        // Who may not use the agent (nor see it) does not make a trigger of it.
        assertThat(bench.carol().post(bench.path("/triggers"),
                withModel(schedule(bench, name, bench.carolPrincipal(), null))).status()).isEqualTo(404);
        // ADR-0043's checks: a cron more often than every 15 minutes, a tracker without a system.
        Map<String, Object> often = schedule(bench, name, bench.alicePrincipal(), null);
        often.put("schedule", Map.of("cron", "* * * * *", "zone", "UTC"));
        assertThat(bench.alice().post(bench.path("/triggers"), withModel(often)).problemCode())
            .isEqualTo("INVALID_TRIGGER");
        Map<String, Object> tracker = schedule(bench, name, bench.alicePrincipal(), null);
        tracker.put("source", "TRACKER");
        tracker.remove("schedule");
        tracker.put("events", List.of(Map.of("event", "ASSIGNED")));
        assertThat(bench.alice().post(bench.path("/triggers"), withModel(tracker)).status()).isEqualTo(400);
        // §11, open point 15: the company's maximum.
        Map<String, Object> fast = schedule(bench, name, bench.alicePrincipal(), null);
        fast.put("maxFiringsPerHour", 1000);
        assertThat(bench.alice().post(bench.path("/triggers"), withModel(fast)).problemCode())
            .isEqualTo("TRIGGER_LIMIT_TOO_HIGH");

        // A trigger's settings add no instructions: its own instruction is in every turn.
        Map<String, Object> added = schedule(bench, name, bench.alicePrincipal(), null);
        added.put("settings", Map.of("instructions", "Answer in German."));
        assertThat(bench.alice().post(bench.path("/triggers"), withModel(added)).problemCode())
            .isEqualTo("VALIDATION_FAILED");

        JsonNode trigger = create(bench.alice(), bench, schedule(bench, name, bench.alicePrincipal(), null));
        assertThat(trigger.get("maxFiringsPerHour").asInt()).isEqualTo(30);
        assertThat(bench.alice().put(bench.path("/triggers/" + trigger.get("id").asString() + "/settings"),
                Map.of("model", TestSettings.MODEL, "instructions", "Answer in German.")).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        // A trigger pays with its payer's budget, never a subscription, its author's own included, until the company
        // allows one (ADR-0085, decision 7).
        assertThat(bench.alice().put(bench.path("/triggers/" + trigger.get("id").asString() + "/settings"),
                Map.of("model", TestSettings.MODEL, "modelAccess", "SUBSCRIPTION", "subscription",
                        Map.of("runnerHostId", UUID.randomUUID().toString(), "label", "claude-max"))).problemCode())
            .isEqualTo("SUBSCRIPTION_NOT_SERVING");
        assertThat(bench.alice().post(bench.path("/triggers"), withModel(schedule(bench, name.toUpperCase(),
                bench.alicePrincipal(), null))).problemCode()).isEqualTo("DUPLICATE_TRIGGER_NAME");

        // §11, open point 7: an admin edits on the author's behalf, checked for the author.
        String id = trigger.get("id").asString();
        ApiResponse renamed = bench.admin().patch(bench.path("/triggers/" + id), Map.of("name", name + " v2"));
        assertThat(renamed.status()).as("%s", renamed).isEqualTo(200);
        JsonNode changed = auditEvents(bench.owner(), bench.company(), "trigger.changed").getFirst();
        assertThat(changed.get("details").get("editorPrincipalId").asString())
            .isEqualTo(bench.adminPrincipal().toString());
        assertThat(bench.admin().put(bench.path("/triggers/" + id + "/accounts"), Map.of("accounts", List.of(),
                "payerPrincipalId", bench.adminPrincipal().toString())).problemCode())
            .isEqualTo("MODEL_ACCESS_NOT_ENTITLED");
        JsonNode inbox = bench.alice().get(bench.path("/inbox")).json();
        List<String> reasons = new ArrayList<>();
        inbox.get("items").forEach(item -> {
            if ("TRIGGER".equals(item.get("kind").asString())) {
                reasons.add(item.get("trigger").get("reason").asString());
            }
        });
        assertThat(reasons).contains("CHANGED_BY_ADMIN");
        // The preview stores nothing and says who acts and pays.
        Map<String, Object> draft = schedule(bench, unique("Draft"), bench.alicePrincipal(), null);
        ApiResponse preview = bench.alice().post(bench.path("/triggers/preview"), Map.of("draft", withModel(draft)));
        assertThat(preview.status()).as("%s", preview).isEqualTo(200);
        assertThat(preview.json().get("payer").get("principalId").asString())
            .isEqualTo(bench.alicePrincipal().toString());
        assertThat(preview.json().get("nextFirings")).hasSize(3);
    }

    @Test
    void theCompanysFiringLimitsAreItsAdmins() {
        Bench bench = bench();
        JsonNode settings = bench.alice().get(bench.path("/settings")).json();
        assertThat(settings.get("triggerFiringsPerHour").get("default").asInt()).isEqualTo(30);
        assertThat(settings.get("triggerFiringsPerHour").get("max").asInt()).isEqualTo(300);
        // Whether a trigger may pay with its author's subscription: off until the company turns it on (ADR-0085).
        assertThat(settings.get("triggerSubscriptions").asBoolean()).isFalse();
        // Nothing saved yet: version 0. Each save names the version it read and answers with the next one.
        int version = settings.get("version").asInt();
        assertThat(version).isEqualTo(0);
        ApiResponse allowed = bench.owner().put(bench.path("/settings"), Map.of("triggerFiringsPerHour",
                Map.of("default", 30, "max", 300), "triggerSubscriptions", true, "version", version));
        assertThat(allowed.status()).as("%s", allowed).isEqualTo(200);
        assertThat(allowed.json().get("triggerSubscriptions").asBoolean()).isTrue();
        assertThat(allowed.json().get("version").asInt()).isEqualTo(1);
        assertThat(bench.alice().get(bench.path("/settings")).json().get("triggerSubscriptions").asBoolean()).isTrue();
        // A save on the version already replaced is refused, and changes nothing; one without a version too.
        ApiResponse stale = bench.owner().put(bench.path("/settings"), Map.of("triggerFiringsPerHour",
                Map.of("default", 30, "max", 300), "triggerSubscriptions", false, "version", 0));
        assertThat(stale.status()).as("%s", stale).isEqualTo(409);
        assertThat(stale.problemCode()).isEqualTo("SETTINGS_CHANGED");
        assertThat(bench.owner().put(bench.path("/settings"), Map.of("triggerFiringsPerHour",
                Map.of("default", 30, "max", 300), "triggerSubscriptions", false)).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        assertThat(bench.alice().get(bench.path("/settings")).json().get("triggerSubscriptions").asBoolean()).isTrue();
        ApiResponse off = bench.owner().put(bench.path("/settings"), Map.of("triggerFiringsPerHour",
                Map.of("default", 30, "max", 300), "triggerSubscriptions", false, "version", 1));
        assertThat(off.json().get("triggerSubscriptions").asBoolean()).isFalse();
        assertThat(off.json().get("version").asInt()).isEqualTo(2);
        assertThat(auditEvents(bench.owner(), bench.company(), "company_settings.changed")).hasSize(2);
        Map<String, Object> fast = schedule(bench, unique("Busy"), bench.alicePrincipal(), null);
        fast.put("maxFiringsPerHour", 200);
        String id = create(bench.alice(), bench, fast).get("id").asString();
        Map<String, Object> lower = Map.of("triggerFiringsPerHour", Map.of("default", 10, "max", 50), "version", 2);
        assertThat(bench.alice().put(bench.path("/settings"), lower).status()).isEqualTo(403);
        assertThat(bench.owner().put(bench.path("/settings"), Map.of("triggerFiringsPerHour",
                Map.of("default", 60, "max", 50), "version", 2)).problemCode()).isEqualTo("VALIDATION_FAILED");
        ApiResponse saved = bench.owner().put(bench.path("/settings"), lower);
        assertThat(saved.status()).as("%s", saved).isEqualTo(200);
        // Lowering the maximum lowers the triggers above it; a new trigger takes the company's default.
        assertThat(bench.alice().get(bench.path("/triggers/" + id)).json().get("maxFiringsPerHour").asInt())
            .isEqualTo(50);
        assertThat(create(bench.alice(), bench, schedule(bench, unique("Calm"), bench.alicePrincipal(), null))
            .get("maxFiringsPerHour").asInt()).isEqualTo(10);
        assertThat(auditEvents(bench.owner(), bench.company(), "company_settings.changed")).hasSize(3);
    }

    @Test
    void anAuthorWhoLeavesPausesTheTriggerAndItsConversationWaitsForTheReassignment() {
        Bench bench = bench();
        JsonNode trigger = create(bench.alice(), bench, schedule(bench, unique("Report"), bench.alicePrincipal(),
                null));
        String id = trigger.get("id").asString();
        String conversationId = bench.alice().post(bench.path("/triggers/" + id + "/run"), Map.of()).json()
            .get("conversationId").asString();
        // H2: a live trigger is not reassigned; only one whose author left or lost a right is.
        ApiResponse live = bench.admin().post(bench.path("/triggers/" + id + "/reassign"),
                Map.of("authorPrincipalId", bench.bobPrincipal().toString()));
        assertThat(live.status()).as("%s", live).isEqualTo(409);
        assertThat(live.problemCode()).isEqualTo("TRIGGER_PAUSED");

        // V9 as §11 decided it: Alice leaves; her trigger pauses AUTHOR_LEFT in the leaving transaction; its open
        // conversation waits, its turn cancelled.
        assertThat(bench.owner().delete(bench.path("/members/" + bench.company().userId("MEMBER"))).status())
            .isEqualTo(204);
        JsonNode paused = bench.owner().get(bench.path("/triggers/" + id)).json();
        assertThat(paused.get("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.get("pausedReason").asString()).isEqualTo("AUTHOR_LEFT");
        JsonNode waiting = bench.owner().get(bench.path("/conversations/" + conversationId)).json();
        assertThat(waiting.get("state").asString()).isEqualTo("OPEN");
        await().atMost(WAIT).until(() -> absent(bench.owner().get(bench.path("/conversations/" + conversationId))
            .json(), "activeRunId"));
        List<String> reasons = new ArrayList<>();
        JsonNode inbox = bench.admin().get(bench.path("/inbox")).json();
        inbox.get("items").forEach(item -> {
            if ("TRIGGER".equals(item.get("kind").asString())) {
                reasons.add(item.get("trigger").get("reason").asString());
            }
        });
        assertThat(reasons).contains("TO_REASSIGN");
        // The trigger module's items come through the inbox's generic port, counted as its notices.
        assertThat(inbox.get("counts").get("notices").asInt()).isGreaterThanOrEqualTo(reasons.size());
        // Nobody resumes it before a reassignment.
        assertThat(bench.owner().post(bench.path("/triggers/" + id + "/resume"), Map.of()).problemCode())
            .isEqualTo("TRIGGER_PAUSED");

        // The choices of a new author, for the trigger an admin may reassign: who may not use the agent has none; a
        // Viewer is no author.
        String choices = "/triggers/choices?agentId=" + bench.agentId() + "&triggerId=" + id + "&forPrincipalId=";
        assertThat(bench.admin().get(bench.path(choices + bench.carolPrincipal())).problemCode())
            .isEqualTo("AGENT_NOT_USABLE");
        ApiResponse forBob = bench.admin().get(bench.path(choices + bench.bobPrincipal()));
        assertThat(forBob.status()).as("%s", forBob).isEqualTo(200);
        assertThat(bench.owner().patch(bench.path("/members/" + bench.carolUser()), Map.of("role", "VIEWER"))
            .status()).isEqualTo(200);
        assertThat(bench.admin().get(bench.path(choices + bench.carolPrincipal())).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        assertThat(bench.admin().post(bench.path("/triggers/" + id + "/reassign"), Map.of("authorPrincipalId",
                bench.carolPrincipal().toString())).problemCode()).isEqualTo("VALIDATION_FAILED");

        // Reassigned to Bob, whose own budget pays: paused TO_ACCEPT until Bob accepts it, and Bob's inbox says so.
        assertThat(bench.bob().post(bench.path("/triggers/" + id + "/reassign"), Map.of("authorPrincipalId",
                bench.bobPrincipal().toString())).status()).isEqualTo(404);
        ApiResponse reassigned = bench.admin().post(bench.path("/triggers/" + id + "/reassign"),
                Map.of("authorPrincipalId", bench.bobPrincipal().toString(), "payerPrincipalId",
                        bench.bobPrincipal().toString()));
        assertThat(reassigned.status()).as("%s", reassigned).isEqualTo(200);
        assertThat(reassigned.json().get("status").asString()).isEqualTo("PAUSED");
        assertThat(reassigned.json().get("pausedReason").asString()).isEqualTo("TO_ACCEPT");
        assertThat(reassigned.json().get("authorPrincipalId").asString()).isEqualTo(bench.bobPrincipal().toString());
        assertThat(triggerReasons(bench.bob(), bench, id)).containsExactly("TRIGGER_REASSIGNED_TO_YOU");
        // Nobody resumes it; an admin does not accept it on Bob's behalf.
        for (ApiClient someone : List.of(bench.owner(), bench.bob())) {
            ApiResponse refused = someone.post(bench.path("/triggers/" + id + "/resume"), Map.of());
            assertThat(refused.problemCode()).as("%s", refused).isEqualTo("TRIGGER_PAUSED");
        }
        ApiResponse onHisBehalf = bench.admin().post(bench.path("/triggers/" + id + "/accept"),
                Map.of("version", bench.agentVersion()));
        assertThat(onHisBehalf.status()).as("%s", onHisBehalf).isEqualTo(403);
        assertThat(onHisBehalf.problemCode()).isEqualTo("TRIGGER_NOT_EDITABLE");

        // Bob accepts it: it resumes, and the waiting conversation takes the new author's choices
        // (conversation_rebind), audited and said.
        ApiResponse resumed = bench.bob().post(bench.path("/triggers/" + id + "/accept"),
                Map.of("version", bench.agentVersion()));
        assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);
        assertThat(resumed.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(triggerReasons(bench.bob(), bench, id)).doesNotContain("TRIGGER_REASSIGNED_TO_YOU");
        JsonNode rebound = bench.bob().get(bench.path("/conversations/" + conversationId)).json();
        assertThat(rebound.get("trigger").get("authorPrincipalId").asString())
            .isEqualTo(bench.bobPrincipal().toString());
        assertThat(rebound.get("payer").get("principalId").asString()).isEqualTo(bench.bobPrincipal().toString());
        List<String> notes = new ArrayList<>();
        rebound.get("messages").forEach(message -> notes.add(message.get("body").asString()));
        assertThat(notes).anyMatch(note -> note.startsWith("From now on,"));
        assertThat(auditEvents(bench.owner(), bench.company(), "conversation.rebound")).hasSize(1);
        assertThat(auditEvents(bench.owner(), bench.company(), "trigger.reassigned")).hasSize(1);
    }

    @Test
    void aServiceIdentityALiveTriggerNamesIsNotDeleted() {
        Bench bench = bench();
        UUID bot = createServiceIdentity(bench.owner(), bench.company(), unique("Bot"));
        ModelGateways.awaitKey(litellm, bench.company(), bot);
        ApiResponse users = bench.owner().put(bench.path("/service-identities/" + bot + "/users"),
                Map.of("groupIds", List.of(bench.team().toString())));
        assertThat(users.status()).as("%s", users).isEqualTo(200);
        ModelGateways.awaitKey(litellm, bench.company(), bot);
        ModelGateways.awaitStatus(bench.owner(), bench.company(), bot, "OK");
        String id = create(bench.bob(), bench, schedule(bench, unique("Bot report"), bot, null)).get("id")
            .asString();
        ApiResponse refused = bench.owner().delete(bench.path("/service-identities/" + bot));
        assertThat(refused.problemCode()).as("%s", refused).isEqualTo("SERVICE_IDENTITY_IN_USE");
        assertThat(bench.bob().delete(bench.path("/triggers/" + id)).status()).isEqualTo(204);
        assertThat(bench.owner().delete(bench.path("/service-identities/" + bot)).status()).isEqualTo(204);
    }

    @Test
    void aTrackerTriggerTakesOnlyAccountsItsAuthorIsEntitledTo() {
        Bench bench = bench();
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        UUID aliceAccount = connectToken(bench.alice(), bench.path("/me/accounts/" + system), jira, unique("alice"),
                "alice");
        UUID bobAccount = connectToken(bench.bob(), bench.path("/me/accounts/" + system), jira, unique("bob"), "bob");
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Assigned"));
        body.put("source", "TRACKER");
        body.put("systemId", system.toString());
        body.put("events", List.of(Map.of("event", "ASSIGNED")));
        body.put("subject", "ISSUE");
        body.put("scope", "SHOP");
        body.put("agentId", bench.agentId().toString());
        body.put("agentVersion", bench.agentVersion());
        body.put("instruction", "Prepare the issue.");
        body.put("payerPrincipalId", bench.alicePrincipal().toString());
        // T2: a tracker trigger needs its source's account; T1: never another member's.
        body.put("accounts", List.of());
        assertThat(bench.alice().post(bench.path("/triggers"), withModel(body)).problemCode())
            .isEqualTo("SOURCE_ACCOUNT_REQUIRED");
        body.put("accounts", List.of(Map.of("systemId", system.toString(), "accountId", bobAccount.toString())));
        ApiResponse refused = bench.alice().post(bench.path("/triggers"), withModel(body));
        assertThat(refused.problemCode()).as("%s", refused).isEqualTo("ACCOUNT_NOT_ENTITLED");
        body.put("accounts", List.of(Map.of("systemId", system.toString(), "accountId", aliceAccount.toString())));
        JsonNode trigger = create(bench.alice(), bench, body);
        assertThat(trigger.get("accounts")).hasSize(1);
        assertThat(trigger.get("accounts").get(0).get("status").asString()).isEqualTo("OK");
        assertThat(trigger.get("sentence").asString()).contains("SHOP");
        String id = trigger.get("id").asString();
        // Run now on a subject its account cannot read (part b reads it with the source account).
        assertThat(bench.alice().post(bench.path("/triggers/" + id + "/run"), Map.of("subjectKey", "SHOP-1"))
            .problemCode()).isEqualTo("SUBJECT_NOT_VISIBLE");

        // Its account goes: the trigger pauses ACCOUNT_REMOVED in that transaction.
        assertThat(bench.alice().delete(bench.path("/me/accounts/" + system)).status()).isIn(200, 204);
        JsonNode paused = bench.alice().get(bench.path("/triggers/" + id)).json();
        assertThat(paused.get("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.get("pausedReason").asString()).isEqualTo("ACCOUNT_REMOVED");
        assertThat(paused.get("accounts").get(0).get("status").asString()).isEqualTo("ACCOUNT_REMOVED");

        // The system goes: the triggers on it are deleted.
        assertThat(bench.owner().delete(bench.path("/systems/" + system)).status()).isIn(200, 204);
        assertThat(bench.alice().get(bench.path("/triggers/" + id)).status()).isEqualTo(404);
        assertThat(auditEvents(bench.owner(), bench.company(), "trigger.deleted").getFirst().get("details")
            .get("cause").asString()).isEqualTo("system.deleted");
    }

    @Test
    void aServiceAccountsRowNamesTheCredentialItBorrowsAndFollowsItWhenTheLendingIsReplaced() throws SQLException {
        // ADR-0072: the row acts through the service account with the credential it borrows on the system; lent
        // another there, the trigger follows it, its open conversation too, and its author is told; withdrawn, it
        // pauses.
        Bench bench = bench();
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        UUID bot = createServiceIdentity(bench.owner(), bench.company(), unique("Bot"));
        assertThat(bench.owner().put(bench.path("/service-identities/" + bot + "/users"),
                Map.of("groupIds", List.of(bench.team().toString()))).status()).isEqualTo(200);
        jira.myself("test-first-credential", "first-bot", "UTC");
        UUID first = TestCredentials.add(bench.owner(), bench.path(""), system, "First", "test-first-credential");
        TestCredentials.lend(bench.owner(), bench.path(""), system, first, bot, "WRITE");
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Assigned"));
        body.put("source", "TRACKER");
        body.put("systemId", system.toString());
        body.put("events", List.of(Map.of("event", "ASSIGNED")));
        body.put("subject", "ISSUE");
        body.put("scope", "SHOP");
        body.put("agentId", bench.agentId().toString());
        body.put("agentVersion", bench.agentVersion());
        body.put("instruction", "Prepare the issue.");
        body.put("payerPrincipalId", bench.bobPrincipal().toString());
        body.put("accounts", List.of(Map.of("systemId", system.toString(), "accountId", first.toString())));
        // Alice may not use the service account: its credential is no row of hers.
        ApiResponse notHers = bench.alice().post(bench.path("/triggers"), withModel(new HashMap<>(body)));
        assertThat(notHers.problemCode()).as("%s", notHers).isEqualTo("ACCOUNT_NOT_ENTITLED");
        JsonNode trigger = create(bench.bob(), bench, body);
        JsonNode row = trigger.get("accounts").get(0);
        assertThat(row.get("accountId").asString()).isEqualTo(first.toString());
        assertThat(row.get("holderPrincipalId").asString()).isEqualTo(bot.toString());
        assertThat(row.get("holderKind").asString()).isEqualTo("SERVICE");
        assertThat(row.get("credentialName").asString()).isEqualTo("First");
        assertThat(row.get("status").asString()).isEqualTo("OK");
        assertThat(row.get("access").asString()).isEqualTo("WRITE");
        String id = trigger.get("id").asString();
        // The credential's page counts the trigger under the lending; its poll is the credential's, no service
        // account's.
        JsonNode lending = bench.owner().get(bench.path("/systems/" + system + "/credentials")).json().get("items")
            .get(0).get("serviceAccounts").get(0);
        assertThat(lending.get("triggers").asInt()).isEqualTo(1);
        assertThat(lending.get("createdAt").asString()).isNotBlank();
        String polls = bench.path("/systems/" + system + "/polls");
        await().atMost(WAIT).until(() -> bench.owner().get(polls).json().get("items").size() == 1);
        JsonNode poll = bench.owner().get(polls).json().get("items").get(0);
        assertThat(poll.get("credentialName").asString()).isEqualTo("First");
        assertThat(poll.path("holderPrincipalId").isMissingNode() || poll.get("holderPrincipalId").isNull()).isTrue();
        // Lent read-only now: the row only reads, and the trigger goes on with the same credential.
        TestCredentials.lend(bench.owner(), bench.path(""), system, first, bot, "READ");
        JsonNode narrowed = bench.bob().get(bench.path("/triggers/" + id)).json();
        assertThat(narrowed.get("accounts").get(0).get("access").asString()).isEqualTo("READ");
        assertThat(narrowed.get("status").asString()).isEqualTo("ACTIVE");

        // Its conversation on an issue the credential reads, bound through the service account.
        jira.issueFor("test-first-credential", JiraStub.issue("10001", "SHOP-1", "Checkout", "Broken", "first-bot",
                List.of(), "To Do", "2026-10-01T10:00:00.000+0000", List.of()));
        ApiResponse run = bench.bob().post(bench.path("/triggers/" + id + "/run"), Map.of("subjectKey", "SHOP-1"));
        assertThat(run.status()).as("%s", run).isEqualTo(200);
        String conversation = bench.path("/conversations/" + run.json().get("conversationId").asString());
        assertThat(bench.bob().get(conversation).json().get("bindings").get(0).get("credentialName").asString())
            .isEqualTo("First");

        // Another credential lent to the service account on the system: the trigger follows it in that transaction,
        // and its author is told, as of an admin's change.
        jira.myself("test-second-credential", "second-bot", "UTC");
        UUID second = TestCredentials.add(bench.owner(), bench.path(""), system, "Second", "test-second-credential");
        TestCredentials.lend(bench.owner(), bench.path(""), system, second, bot, "WRITE");
        assertThat(auditEvents(bench.owner(), bench.company(), "credential.lent").getFirst().get("details")
            .get("replaced").asString()).isEqualTo(first.toString());
        JsonNode followed = bench.bob().get(bench.path("/triggers/" + id)).json();
        assertThat(followed.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(followed.get("accounts").get(0).get("accountId").asString()).isEqualTo(second.toString());
        assertThat(followed.get("accounts").get(0).get("holderPrincipalId").asString()).isEqualTo(bot.toString());
        assertThat(followed.get("accounts").get(0).get("credentialName").asString()).isEqualTo("Second");
        assertThat(followed.get("accounts").get(0).get("access").asString()).isEqualTo("WRITE");
        assertThat(followed.get("accounts").get(0).get("status").asString()).isEqualTo("OK");
        assertThat(auditEvents(bench.owner(), bench.company(), "trigger.accounts_changed").getFirst().get("details")
            .get("why").asString()).isEqualTo("lending.replaced");
        JsonNode notice = null;
        for (JsonNode item : bench.bob().get(bench.path("/inbox")).json().get("items")) {
            if ("TRIGGER".equals(item.get("kind").asString())
                    && "CHANGED_BY_ADMIN".equals(item.get("trigger").get("reason").asString())) {
                notice = item;
            }
        }
        assertThat(notice).as("the author's notice").isNotNull();
        assertThat(notice.get("trigger").get("detail").asString())
            .contains("changed your trigger", "now borrows the credential Second on", "in place of First");
        JsonNode lendings = bench.owner().get(bench.path("/systems/" + system + "/credentials")).json().get("items");
        for (JsonNode credential : lendings) {
            int triggers = credential.get("serviceAccounts").isEmpty() ? 0
                    : credential.get("serviceAccounts").get(0).get("triggers").asInt();
            assertThat(triggers).as(credential.get("name").asString())
                .isEqualTo(credential.get("id").asString().equals(second.toString()) ? 1 : 0);
        }
        // Its open conversation binds the new credential, and says so; its poll is the new credential's.
        await().atMost(WAIT).until(() -> "Second".equals(bench.bob().get(conversation).json().get("bindings").get(0)
            .get("credentialName").asString()));
        assertThat(bench.bob().get(conversation).json().get("bindings").get(0).get("state").asString())
            .isEqualTo("BOUND");
        try (TenantSql sql = TenantSql.in(bench.company().id())) {
            assertThat(sql.column("SELECT body FROM message WHERE conversation_id = ?",
                    UUID.fromString(run.json().get("conversationId").asString())))
                .anySatisfy(said -> assertThat(String.valueOf(said)).contains("Second now, in place of First: an "
                        + "admin lent it to"));
        }
        await().atMost(WAIT).until(() -> {
            for (JsonNode item : bench.owner().get(polls).json().get("items")) {
                if ("Second".equals(item.get("credentialName").asString())) {
                    return true;
                }
            }
            return false;
        });

        // Withdrawn: nothing to follow, and the trigger pauses with its row as it was.
        assertThat(bench.owner().delete(bench.path("/systems/" + system + "/credentials/" + second
                + "/service-accounts/" + bot)).status()).isIn(200, 204);
        await().atMost(WAIT).until(() -> "PAUSED".equals(bench.bob().get(bench.path("/triggers/" + id)).json()
            .get("status").asString()));
        JsonNode paused = bench.bob().get(bench.path("/triggers/" + id)).json();
        assertThat(paused.get("pausedReason").asString()).isEqualTo("NOT_ENTITLED");
        assertThat(paused.get("accounts").get(0).get("accountId").asString()).isEqualTo(second.toString());
        assertThat(bench.bob().post(bench.path("/triggers/" + id + "/resume"), Map.of()).problemCode())
            .isEqualTo("TRIGGER_PAUSED");
    }

    @Test
    void theEditorAnswersForTheAuthorAndAHoldersTriggersAreListed() {
        Bench bench = bench();
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        UUID aliceAccount = connectToken(bench.alice(), bench.path("/me/accounts/" + system), jira, unique("alice"),
                "alice");
        // The agent comments on Jira without asking: open events would write with Alice's own account.
        JsonNode agent = bench.owner().put(bench.path("/agents/" + bench.agentId() + "/systems"), Map.of("systems",
                List.of(Map.of("systemId", system.toString(), "tools", List.of(Map.of("name", "comment", "mode",
                        "ALLOW")))))).json();
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Assigned"));
        body.put("source", "TRACKER");
        body.put("systemId", system.toString());
        body.put("events", List.of(Map.of("event", "ASSIGNED")));
        body.put("subject", "ISSUE");
        body.put("scope", "SHOP");
        body.put("agentId", bench.agentId().toString());
        body.put("agentVersion", agent.get("version").asInt());
        body.put("instruction", "Prepare the issue.");
        body.put("accounts", List.of(Map.of("systemId", system.toString(), "accountId", aliceAccount.toString())));
        body.put("payerPrincipalId", bench.alicePrincipal().toString());

        // The preview shows what the acknowledgement means instead of refusing; the save still asks for it.
        ApiResponse preview = bench.alice().post(bench.path("/triggers/preview"), Map.of("draft", withModel(body)));
        assertThat(preview.status()).as("%s", preview).isEqualTo(200);
        JsonNode open = preview.json().get("openToAnyone");
        assertThat(open.get("acknowledgementNeeded").asBoolean()).isTrue();
        assertThat(open.get("acknowledgementMissing").asBoolean()).isTrue();
        assertThat(open.get("writes").get(0).get("tool").asString()).isEqualTo("comment");
        assertThat(preview.json().get("warnings").toString()).contains("acknowledgeOpenEvents");
        assertThat(bench.alice().post(bench.path("/triggers"), withModel(body)).problemCode())
            .isEqualTo("OPEN_EVENTS_NOT_ACKNOWLEDGED");
        body.put("acknowledgeOpenEvents", true);
        JsonNode acknowledged = bench.alice().post(bench.path("/triggers/preview"), Map.of("draft", withModel(body)))
            .json();
        assertThat(acknowledged.get("openToAnyone").get("acknowledgementMissing").asBoolean()).isFalse();
        assertThat(acknowledged.path("warnings").toString()).doesNotContain("acknowledgeOpenEvents");
        String tracker = create(bench.alice(), bench, body).get("id").asString();
        String schedule = create(bench.alice(), bench, schedule(bench, unique("Monthly"), bench.alicePrincipal(),
                null)).get("id").asString();

        // The choices of the author, for an admin who edits her trigger on her behalf, and only for that trigger (H2);
        // each payer with its key's models.
        String choices = "/triggers/choices?agentId=" + bench.agentId() + "&systemId=" + system;
        assertThat(bench.admin().get(bench.path(choices + "&forPrincipalId=" + bench.alicePrincipal())).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        ApiResponse forAlice = bench.admin().get(bench.path(choices + "&forPrincipalId=" + bench.alicePrincipal()
                + "&triggerId=" + tracker));
        assertThat(forAlice.status()).as("%s", forAlice).isEqualTo(200);
        assertThat(forAlice.json().get("items")).extracting(item -> item.get("principalId").asString())
            .containsExactly(bench.alicePrincipal().toString());
        JsonNode alices = forAlice.json().get("items").get(0);
        assertThat(alices.get("systems").get(0).get("accountId").asString()).isEqualTo(aliceAccount.toString());
        assertThat(alices.get("budget").get("models").isArray()).isTrue();
        // On the schedule, whose table holds no account of hers, her own account is not offered to the admin.
        JsonNode onSchedule = bench.admin().get(bench.path(choices + "&forPrincipalId=" + bench.alicePrincipal()
                + "&triggerId=" + schedule)).json().get("items").get(0);
        assertThat(onSchedule.get("systems").get(0).get("status").asString()).isEqualTo("NONE");
        assertThat(absent(onSchedule.get("systems").get(0), "accountId")).isTrue();
        // Alice asks for her own; a member never for another's; a member who may not use the agent has none.
        assertThat(bench.alice().get(bench.path(choices + "&forPrincipalId=" + bench.alicePrincipal())).json()
            .get("items").get(0).get("principalId").asString()).isEqualTo(bench.alicePrincipal().toString());
        assertThat(bench.bob().get(bench.path(choices + "&forPrincipalId=" + bench.alicePrincipal()
                + "&triggerId=" + tracker)).status()).isEqualTo(403);
        // Another member than the author of a live trigger: not the admin's to see.
        for (UUID other : List.of(bench.bobPrincipal(), bench.carolPrincipal(), UUID.randomUUID())) {
            assertThat(bench.admin().get(bench.path(choices + "&forPrincipalId=" + other + "&triggerId=" + tracker))
                .status()).as("%s", other).isEqualTo(403);
        }


        // The triggers holding an account of a principal, among those the caller sees.
        String held = "/triggers?accountHolderPrincipalId=";
        assertThat(ids(bench.owner().get(bench.path(held + bench.alicePrincipal())).json())).containsExactly(tracker);
        assertThat(ids(bench.owner().get(bench.path("/triggers")).json())).contains(tracker, schedule);
        assertThat(ids(bench.alice().get(bench.path(held + bench.alicePrincipal())).json())).containsExactly(tracker);
        assertThat(ids(bench.owner().get(bench.path(held + bench.bobPrincipal())).json())).isEmpty();
        assertThat(ids(bench.carol().get(bench.path(held + bench.alicePrincipal())).json())).isEmpty();

        // H2: an admin's change on Alice's behalf names no account of hers that the trigger did not hold; she does.
        Map<String, Object> ownRow = Map.of("accounts", List.of(Map.of("systemId", system.toString(), "accountId",
                aliceAccount.toString())), "payerPrincipalId", bench.alicePrincipal().toString());
        ApiResponse added = bench.admin().put(bench.path("/triggers/" + schedule + "/accounts"), ownRow);
        assertThat(added.status()).as("%s", added).isEqualTo(403);
        assertThat(added.problemCode()).isEqualTo("AUTHOR_ACCOUNT_NOT_ADMINS");
        ApiResponse hers = bench.alice().put(bench.path("/triggers/" + schedule + "/accounts"), ownRow);
        assertThat(hers.status()).as("%s", hers).isEqualTo(200);
        // The row the trigger holds stays the admin's to keep.
        ApiResponse kept = bench.admin().put(bench.path("/triggers/" + schedule + "/accounts"), ownRow);
        assertThat(kept.status()).as("%s", kept).isEqualTo(200);
    }

    @Test
    void aChangeThatMakesTheAcknowledgementUnnecessaryClearsItWhole() throws SQLException {
        // An acknowledged open trigger whose writes then ask: the next save clears the acknowledgement's time and its
        // author together (trigger_acknowledged_ck refused the author kept alone).
        Bench bench = bench();
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        UUID aliceAccount = connectToken(bench.alice(), bench.path("/me/accounts/" + system), jira, unique("alice"),
                "alice");
        JsonNode agent = bench.owner().put(bench.path("/agents/" + bench.agentId() + "/systems"), Map.of("systems",
                List.of(Map.of("systemId", system.toString(), "tools", List.of(Map.of("name", "comment", "mode",
                        "ALLOW")))))).json();
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Assigned"));
        body.put("source", "TRACKER");
        body.put("systemId", system.toString());
        body.put("events", List.of(Map.of("event", "ASSIGNED")));
        body.put("subject", "ISSUE");
        body.put("scope", "SHOP");
        body.put("agentId", bench.agentId().toString());
        body.put("agentVersion", agent.get("version").asInt());
        body.put("instruction", "Prepare the issue.");
        body.put("accounts", List.of(Map.of("systemId", system.toString(), "accountId", aliceAccount.toString())));
        body.put("payerPrincipalId", bench.alicePrincipal().toString());
        body.put("acknowledgeOpenEvents", true);
        JsonNode trigger = create(bench.alice(), bench, body);
        String id = trigger.get("id").asString();
        assertThat(absent(trigger, "openEventsAcknowledgedAt")).isFalse();

        // The comment asks the team now: nothing writes without approval.
        ApiResponse asks = bench.alice().put(bench.path("/triggers/" + id + "/settings"), Map.of("model",
                TestSettings.MODEL, "toolModes", List.of(Map.of("systemId", system.toString(), "tool", "comment",
                        "mode", "ASK"))));
        assertThat(asks.status()).as("%s", asks).isEqualTo(200);
        ApiResponse renamed = bench.alice().patch(bench.path("/triggers/" + id), Map.of("name", unique("Renamed")));
        assertThat(renamed.status()).as("%s", renamed).isEqualTo(200);
        assertThat(absent(renamed.json(), "openEventsAcknowledgedAt")).isTrue();
        try (TenantSql sql = TenantSql.in(bench.company().id())) {
            assertThat(sql.column("SELECT open_events_acknowledged_by FROM trigger WHERE id = ?", UUID.fromString(id)))
                .containsOnlyNulls();
        }
    }

    @Test
    void aNewVersionThatWidensNothingWaitsForNoAcceptance() {
        // A version that only changes the description is newer than the one the author accepted but adds nothing: the
        // trigger's page shows no widening and its author's inbox no WIDENING item.
        Bench bench = bench();
        String id = create(bench.alice(), bench, schedule(bench, unique("Digest"), bench.alicePrincipal(), null))
            .get("id").asString();
        ApiResponse described = bench.owner().patch(bench.path("/agents/" + bench.agentId()),
                Map.of("description", "Builds what is asked, now described."));
        assertThat(described.status()).as("%s", described).isEqualTo(200);
        JsonNode trigger = bench.alice().get(bench.path("/triggers/" + id)).json();
        assertThat(trigger.get("currentVersion").asInt()).isGreaterThan(trigger.get("acceptedVersion").asInt());
        assertThat(trigger.get("wideningPending").asBoolean()).isFalse();
        assertThat(triggerReasons(bench.alice(), bench, id)).doesNotContain("WIDENING");
    }

    @Test
    void aMemberMadeViewerPausesTheirTriggers() {
        // M7: a VIEWER authors no trigger; demoted by an admin, the member's triggers pause NOT_ENTITLED (the gate runs
        // on MemberRoleChanged, as it does for the identity provider's claim and SCIM, which publish the same event).
        Bench bench = bench();
        String id = create(bench.alice(), bench, schedule(bench, unique("Digest"), bench.alicePrincipal(), null))
            .get("id").asString();
        ApiResponse demoted = bench.owner().patch(bench.path("/members/" + bench.company().userId("MEMBER")),
                Map.of("role", "VIEWER"));
        assertThat(demoted.status()).as("%s", demoted).isEqualTo(200);
        await().atMost(WAIT).until(() -> "PAUSED".equals(bench.owner().get(bench.path("/triggers/" + id)).json()
            .get("status").asString()));
        assertThat(bench.owner().get(bench.path("/triggers/" + id)).json().get("pausedReason").asString())
            .isEqualTo("NOT_ENTITLED");
        // Nobody resumes it while its author holds no seat; it may be reassigned, to a member who holds one.
        assertThat(bench.owner().post(bench.path("/triggers/" + id + "/resume"), Map.of()).problemCode())
            .isEqualTo("TRIGGER_PAUSED");
        assertThat(bench.admin().post(bench.path("/triggers/" + id + "/reassign"), Map.of("authorPrincipalId",
                bench.alicePrincipal().toString())).problemCode()).isEqualTo("VALIDATION_FAILED");
        ApiResponse reassigned = bench.admin().post(bench.path("/triggers/" + id + "/reassign"),
                Map.of("authorPrincipalId", bench.bobPrincipal().toString(), "payerPrincipalId",
                        bench.bobPrincipal().toString()));
        assertThat(reassigned.status()).as("%s", reassigned).isEqualTo(200);
        assertThat(reassigned.json().get("pausedReason").asString()).isEqualTo("TO_ACCEPT");
    }

    @Test
    void anOpenTriggerThatWritesWithALentCredentialIsAcknowledgedAndItsLenderTold() {
        // D2, the owner's decision Q3: the author acknowledges every open write, own or lent; the admin who lent the
        // credential is told once, and the credential's and the service account's pages list the open trigger.
        Bench bench = bench();
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        UUID bot = createServiceIdentity(bench.owner(), bench.company(), unique("Bot"));
        assertThat(bench.owner().put(bench.path("/service-identities/" + bot + "/users"),
                Map.of("groupIds", List.of(bench.team().toString()))).status()).isEqualTo(200);
        jira.myself("test-lent-credential", "lent-bot", "UTC");
        UUID credential = TestCredentials.add(bench.admin(), bench.path(""), system, "Lent", "test-lent-credential");
        TestCredentials.lend(bench.admin(), bench.path(""), system, credential, bot, "WRITE");
        // The agent comments on Jira without asking.
        JsonNode agent = bench.owner().put(bench.path("/agents/" + bench.agentId() + "/systems"), Map.of("systems",
                List.of(Map.of("systemId", system.toString(), "tools", List.of(Map.of("name", "comment", "mode",
                        "ALLOW")))))).json();
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Mentioned"));
        body.put("source", "TRACKER");
        body.put("systemId", system.toString());
        body.put("events", List.of(Map.of("event", "ASSIGNED")));
        body.put("subject", "ISSUE");
        body.put("scope", "SHOP");
        body.put("agentId", bench.agentId().toString());
        body.put("agentVersion", agent.get("version").asInt());
        body.put("instruction", "Prepare the issue.");
        body.put("payerPrincipalId", bench.bobPrincipal().toString());
        body.put("accounts", List.of(Map.of("systemId", system.toString(), "accountId", credential.toString())));

        JsonNode preview = bench.bob().post(bench.path("/triggers/preview"), Map.of("draft", withModel(body))).json();
        assertThat(preview.get("openToAnyone").get("acknowledgementNeeded").asBoolean()).isTrue();
        assertThat(preview.get("openToAnyone").get("writes").get(0).get("own").asBoolean()).isFalse();
        assertThat(bench.bob().post(bench.path("/triggers"), withModel(body)).problemCode())
            .isEqualTo("OPEN_EVENTS_NOT_ACKNOWLEDGED");
        assertThat(triggerReasons(bench.admin(), bench, null)).doesNotContain("CREDENTIAL_EXPOSED_TO_OPEN_SOURCE");
        body.put("acknowledgeOpenEvents", true);
        String id = create(bench.bob(), bench, body).get("id").asString();

        // The credential's page and the service account's list it.
        JsonNode open = bench.admin().get(bench.path("/systems/" + system + "/credentials")).json().get("items")
            .get(0).get("openTriggers");
        assertThat(open).hasSize(1);
        assertThat(open.get(0).get("id").asString()).isEqualTo(id);
        assertThat(open.get(0).get("source").asString()).isEqualTo("TRACKER");
        assertThat(open.get(0).get("serviceIdentityId").asString()).isEqualTo(bot.toString());
        JsonNode borrowed = bench.admin().get(bench.path("/service-identities/" + bot + "/credentials")).json()
            .get("items").get(0).get("openTriggers");
        assertThat(borrowed).extracting(trigger -> trigger.get("id").asString()).containsExactly(id);

        // The admin who lent it is told, once; the owner, who did not, is not.
        assertThat(triggerReasons(bench.admin(), bench, id)).containsExactly("CREDENTIAL_EXPOSED_TO_OPEN_SOURCE");
        assertThat(triggerReasons(bench.owner(), bench, id)).doesNotContain("CREDENTIAL_EXPOSED_TO_OPEN_SOURCE");
        ApiResponse again = bench.bob().put(bench.path("/triggers/" + id + "/accounts"), Map.of("accounts",
                List.of(Map.of("systemId", system.toString(), "accountId", credential.toString())),
                "payerPrincipalId", bench.bobPrincipal().toString()));
        assertThat(again.status()).as("%s", again).isEqualTo(200);
        assertThat(triggerReasons(bench.admin(), bench, id)).containsExactly("CREDENTIAL_EXPOSED_TO_OPEN_SOURCE");
    }

    @Test
    void anMcpServerThatNeedsNoAccountTakesNoRowAndIsNeverUnavailable() {
        Bench bench = bench();
        McpStub.Instance logs = McpStub.newInstance().serves(List.of(McpStub.tool("query", "Queries the logs")));
        UUID mcp = createSystem(bench.owner(), bench.company(), unique("Logs"), "MCP", logs.url(), false);
        assertThat(bench.owner().post(bench.path("/systems/" + mcp + "/tools/refresh"), Map.of()).status())
            .isEqualTo(200);
        assertThat(bench.owner().patch(bench.path("/systems/" + mcp + "/tools/query"), Map.of("effect", "READ"))
            .status()).isEqualTo(200);
        JsonNode agent = bench.owner().put(bench.path("/agents/" + bench.agentId() + "/systems"), Map.of("systems",
                List.of(Map.of("systemId", mcp.toString())))).json();
        Map<String, Object> body = schedule(bench, unique("Nightly"), bench.alicePrincipal(), null);
        body.put("agentVersion", agent.get("version").asInt());

        // Needing accounts, it is unavailable without a row, and nobody holds one.
        String choices = "/triggers/choices?agentId=" + bench.agentId();
        assertThat(bench.alice().get(bench.path(choices)).json().get("items").get(0).get("systems").get(0)
            .get("status").asString()).isEqualTo("NONE");
        JsonNode before = bench.alice().post(bench.path("/triggers/preview"), Map.of("draft", withModel(body)))
            .json();
        assertThat(before.get("unavailableSystems")).extracting(system -> system.get("systemId").asString())
            .containsExactly(mcp.toString());

        // ADR-0071: needing none, it takes no row, and the trigger's runs call it without one.
        assertThat(bench.owner().patch(bench.path("/systems/" + mcp), Map.of("accountsRequired", false)).status())
            .isEqualTo(200);
        assertThat(bench.alice().get(bench.path(choices)).json().get("items")).allSatisfy(choice -> assertThat(
                choice.get("systems").get(0).get("status").asString()).isEqualTo("NOT_NEEDED"));
        JsonNode preview = bench.alice().post(bench.path("/triggers/preview"), Map.of("draft", withModel(body)))
            .json();
        assertThat(preview.get("unavailableSystems")).isEmpty();
        assertThat(preview.get("noAccountSystems")).extracting(system -> system.get("systemId").asString())
            .containsExactly(mcp.toString());
        JsonNode trigger = create(bench.alice(), bench, body);
        assertThat(trigger.get("unavailableSystems")).isEmpty();
        assertThat(trigger.get("noAccountSystems")).extracting(system -> system.get("systemId").asString())
            .containsExactly(mcp.toString());
    }

    @Test
    void anotherCompanysTriggerIsNotFound() {
        Bench a = bench();
        Bench b = bench();
        String id = create(b.alice(), b, schedule(b, unique("Theirs"), b.alicePrincipal(), null)).get("id")
            .asString();
        for (String suffix : List.of("", "/settings", "/firings", "/subjects")) {
            assertThat(a.owner().get(a.path("/triggers/" + id + suffix)).status()).as(suffix).isEqualTo(404);
        }
        assertThat(a.owner().post(a.path("/triggers/" + id + "/pause"), Map.of()).status()).isEqualTo(404);
        assertThat(a.owner().post(a.path("/triggers/" + id + "/run"), Map.of()).status()).isEqualTo(404);
        assertThat(a.owner().delete(a.path("/triggers/" + id)).status()).isEqualTo(404);
        assertThat(ids(a.owner().get(a.path("/triggers")).json())).doesNotContain(id);
        // Another company's path is not the caller's at all.
        assertThat(a.owner().get(b.path("/triggers/" + id)).status()).isIn(403, 404);
    }
}
