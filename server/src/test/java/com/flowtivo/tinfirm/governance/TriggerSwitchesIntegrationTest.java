package com.flowtivo.tinfirm.governance;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import com.flowtivo.tinfirm.support.ModelGateways;

import tools.jackson.databind.JsonNode;

/**
 * Package 6's kill switches on package 4's triggers, with real triggers (the package 6 contract, §5.4, §5.5, open
 * points 6, 7 and 21): the company's stop makes every active trigger wait {@code COMPANY_STOPPED} and its lift arms
 * them again without a resume (the stated exception to package 4's decision 9); a system's pause pauses its triggers
 * {@code SYSTEM_PAUSED}, listed on the switches' page, and its resume resumes them through the gate, or leaves them
 * paused; a leaver's checklist lists the triggers paused {@code AUTHOR_LEFT}.
 */
class TriggerSwitchesIntegrationTest extends IntegrationTest {

    final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    /** A company whose MEMBER (the author) uses an agent the OWNER shared, with the bundled gateway and their key. */
    record Bench(TestCompany company, ApiClient owner, ApiClient author, UUID authorPrincipal, UUID agentId,
            int agentVersion) {

        String path(String suffix) {
            return company.path(suffix);
        }
    }

    Bench bench() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID author = memberPrincipal(company, company.userId("MEMBER"));
        ModelGateways.configureBundled(owner, company);
        ModelGateways.awaitKey(litellm, company, author);
        ModelGateways.awaitStatus(owner, company, author, "OK");
        UUID users = createGroup(owner, company, unique("Builders"), List.of(company.userId("MEMBER")));
        ApiResponse agent = owner.post(company.path("/agents"), Map.of("name", unique("Builder"), "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "Build what is asked.", "shareGroupIds",
                List.of(users.toString())));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        return new Bench(company, owner, loginAs(company.email("MEMBER")), author,
                UUID.fromString(agent.json().get("id").asString()), agent.json().get("version").asInt());
    }

    Map<String, Object> body(Bench bench, String source) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Trigger"));
        body.put("source", source);
        body.put("agentId", bench.agentId().toString());
        body.put("agentVersion", bench.agentVersion());
        body.put("instruction", "Do the work.");
        body.put("accounts", List.of());
        body.put("payerPrincipalId", bench.authorPrincipal().toString());
        return body;
    }

    String schedule(Bench bench) {
        Map<String, Object> body = body(bench, "SCHEDULE");
        body.put("schedule", Map.of("cron", "0 9 1 * *", "zone", "Europe/Prague"));
        return create(bench, body);
    }

    String create(Bench bench, Map<String, Object> body) {
        ApiResponse created = bench.author().post(bench.path("/triggers"), withModel(body));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        return created.json().get("id").asString();
    }

    JsonNode trigger(Bench bench, String id) {
        return bench.owner().get(bench.path("/triggers/" + id)).json();
    }

    static boolean absent(JsonNode node, String field) {
        return node.path(field).isMissingNode() || node.path(field).isNull();
    }

    @Test
    void theCompanysStopMakesTriggersWaitAndItsLiftArmsThemWithoutAResume() {
        Bench bench = bench();
        String id = schedule(bench);
        Instant armedBefore = Instant.parse(trigger(bench, id).get("armedAt").asString());

        ApiResponse stopped = bench.owner().post(bench.path("/runs-stop"), Map.of("reason", "Incident"));
        assertThat(stopped.status()).as("%s", stopped).isEqualTo(200);
        JsonNode waiting = trigger(bench, id);
        assertThat(waiting.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(waiting.get("waitingReason").asString()).isEqualTo("COMPANY_STOPPED");
        JsonNode audit = auditEvents(bench.owner(), bench.company(), "trigger.waiting").getFirst();
        assertThat(audit.get("details").get("reason").asString()).isEqualTo("COMPANY_STOPPED");
        // The gate answers the same: it fires nothing while the stop lasts.
        assertThat(bench.author().post(bench.path("/triggers/" + id + "/run"), Map.of()).problemCode())
            .isEqualTo("TRIGGER_PAUSED");
        // A person's pause and resume while stopped: resumed, it waits again.
        assertThat(bench.author().post(bench.path("/triggers/" + id + "/pause"), Map.of()).status()).isEqualTo(200);
        ApiResponse resumed = bench.author().post(bench.path("/triggers/" + id + "/resume"), Map.of());
        assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);
        assertThat(resumed.json().get("waitingReason").asString()).isEqualTo("COMPANY_STOPPED");

        ApiResponse lifted = bench.owner().delete(bench.path("/runs-stop"));
        assertThat(lifted.status()).as("%s", lifted).isEqualTo(200);
        assertThat(lifted.json().get("triggersArmed").asInt()).isEqualTo(1);
        assertThat(lifted.json().get("heldRowsReleased").asInt()).isZero();
        JsonNode armed = trigger(bench, id);
        assertThat(armed.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(absent(armed, "waitingReason")).isTrue();
        assertThat(Instant.parse(armed.get("armedAt").asString())).isAfter(armedBefore);
        assertThat(auditEvents(bench.owner(), bench.company(), "company.runs_resumed").getFirst().get("details")
            .get("triggersArmed").asInt()).isEqualTo(1);
        ApiResponse run = bench.author().post(bench.path("/triggers/" + id + "/run"), Map.of());
        assertThat(run.status()).as("%s", run).isEqualTo(200);
        assertThat(run.json().get("outcome").asString()).isEqualTo("STARTED");
    }

    @Test
    void aSystemsPausePausesItsTriggersAndItsResumeResumesThemThroughTheGate() {
        Bench bench = bench();
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        UUID account = connectToken(bench.author(), bench.path("/me/accounts/" + system), jira, unique("alice"),
                "alice");
        Map<String, Object> body = body(bench, "TRACKER");
        body.put("systemId", system.toString());
        body.put("events", List.of(Map.of("event", "ASSIGNED")));
        body.put("subject", "ISSUE");
        body.put("scope", "SHOP");
        body.put("accounts", List.of(Map.of("systemId", system.toString(), "accountId", account.toString())));
        String tracker = create(bench, body);
        // A schedule on no system is not paused.
        String other = schedule(bench);

        ApiResponse paused = bench.owner().post(bench.path("/systems/" + system + "/pause"),
                Map.of("reason", "The token leaked"));
        assertThat(paused.status()).as("%s", paused).isEqualTo(200);
        assertThat(paused.json().get("triggersPaused").asInt()).isEqualTo(1);
        JsonNode trigger = trigger(bench, tracker);
        assertThat(trigger.get("status").asString()).isEqualTo("PAUSED");
        assertThat(trigger.get("pausedReason").asString()).isEqualTo("SYSTEM_PAUSED");
        assertThat(trigger(bench, other).get("status").asString()).isEqualTo("ACTIVE");
        JsonNode audit = auditEvents(bench.owner(), bench.company(), "trigger.paused").getFirst();
        assertThat(audit.get("details").get("reason").asString()).isEqualTo("SYSTEM_PAUSED");
        assertThat(audit.get("details").get("cause").asString()).isEqualTo("system.paused");

        // The switches' page lists it under the system, and as a paused trigger.
        JsonNode page = bench.owner().get(bench.path("/kill-switches")).json();
        JsonNode systemSwitch = switchOf(page, "SYSTEM_PAUSED");
        assertThat(systemSwitch.get("pausedTriggers")).hasSize(1);
        assertThat(systemSwitch.get("pausedTriggers").get(0).get("id").asString()).isEqualTo(tracker);
        assertThat(switchOf(page, "TRIGGER_PAUSED").get("targetId").asString()).isEqualTo(tracker);

        // Its author cannot resume it while the system is paused: the gate stops it.
        assertThat(bench.author().post(bench.path("/triggers/" + tracker + "/resume"), Map.of()).problemCode())
            .isEqualTo("TRIGGER_PAUSED");

        // Resumed without its triggers: they stay paused.
        ApiResponse alone = bench.owner().post(bench.path("/systems/" + system + "/resume"),
                Map.of("resumeTriggers", false));
        assertThat(alone.status()).as("%s", alone).isEqualTo(200);
        assertThat(alone.json().get("triggersResumed").asInt()).isZero();
        assertThat(alone.json().get("triggersStillPaused").asInt()).isEqualTo(1);
        assertThat(trigger(bench, tracker).get("pausedReason").asString()).isEqualTo("SYSTEM_PAUSED");

        // Paused again (the trigger is paused already: nothing more), then resumed with its triggers.
        assertThat(bench.owner().post(bench.path("/systems/" + system + "/pause"), Map.of("reason", "Again"))
            .json().get("triggersPaused").asInt()).isZero();
        Instant armedBefore = Instant.parse(trigger(bench, tracker).get("armedAt").asString());
        ApiResponse resumed = bench.owner().post(bench.path("/systems/" + system + "/resume"), Map.of());
        assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);
        assertThat(resumed.json().get("triggersResumed").asInt()).isEqualTo(1);
        assertThat(resumed.json().get("triggersStillPaused").asInt()).isZero();
        JsonNode active = trigger(bench, tracker);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(Instant.parse(active.get("armedAt").asString())).isAfter(armedBefore);
        assertThat(auditEvents(bench.owner(), bench.company(), "trigger.resumed").getFirst().get("details")
            .get("cause").asString()).isEqualTo("system.resumed");
        assertThat(kindsOf(bench.owner().get(bench.path("/kill-switches")).json())).doesNotContain("TRIGGER_PAUSED",
                "SYSTEM_PAUSED");
    }

    @Test
    void aLeaversChecklistListsTheTriggersPausedAuthorLeft() {
        Bench bench = bench();
        String id = schedule(bench);
        UUID leaver = bench.company().userId("MEMBER");
        assertThat(bench.owner().get(bench.path("/members/" + leaver + "/departure")).json().get("triggersPausing")
            .asInt()).isEqualTo(1);

        assertThat(bench.owner().delete(bench.path("/members/" + leaver)).status()).isEqualTo(204);
        assertThat(trigger(bench, id).get("pausedReason").asString()).isEqualTo("AUTHOR_LEFT");
        String path = bench.path("/principals/" + bench.authorPrincipal() + "/departure");
        JsonNode checklist = bench.owner().get(path).json();
        assertThat(checklist.get("triggers")).hasSize(1);
        assertThat(checklist.get("triggers").get(0).get("id").asString()).isEqualTo(id);
        ApiResponse refused = bench.owner().post(path + "/close", Map.of());
        assertThat(refused.problemCode()).as("%s", refused).isEqualTo("DEPARTURE_OPEN");
        assertThat(bench.owner().post(path + "/close", Map.of("force", true)).status()).isEqualTo(200);
    }

    private static List<String> kindsOf(JsonNode page) {
        List<String> kinds = new java.util.ArrayList<>();
        page.get("switches").forEach(item -> kinds.add(item.get("kind").asString()));
        return kinds;
    }

    private static JsonNode switchOf(JsonNode page, String kind) {
        for (JsonNode item : page.get("switches")) {
            if (item.get("kind").asString().equals(kind)) {
                return item;
            }
        }
        throw new AssertionError("no " + kind + " in " + page);
    }
}
