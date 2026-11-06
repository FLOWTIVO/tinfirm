package com.flowtivo.tinfirm.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;

import tools.jackson.databind.JsonNode;

/**
 * A system's handle is never chosen (CONVENTIONS §15.2): the registration makes it from the name, with {@code _2},
 * {@code _3}, ... while the company has it, for every kind alike, and it never changes. A handle in a request is
 * refused, never ignored; registrations at the same moment never take one handle twice.
 */
class SystemHandleTest extends IntegrationTest {

    @Test
    void theRegistrationMakesTheHandleFromTheNameWithASuffixWhileTaken() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        String jira = JiraStub.newInstance().baseUrl();

        assertThat(handle(admin, company, Map.of("name", "Ops Alerts", "kind", "JIRA_DC", "baseUrl", jira)))
            .isEqualTo("ops_alerts");
        assertThat(handle(admin, company, Map.of("name", "Ops-Alerts", "kind", "JIRA_DC", "baseUrl", jira)))
            .isEqualTo("ops_alerts_2");
        assertThat(handle(admin, company, Map.of("name", "Ops·Alérts", "kind", "GIT"))).isEqualTo("ops_alerts_3");
        // A long name is cut before its suffix, so that the handle keeps its form.
        assertThat(handle(admin, company, Map.of("name", "Escalations of customer support", "kind", "GIT")))
            .isEqualTo("escalations_of_cu");
        assertThat(handle(admin, company, Map.of("name", "Escalations of customer success", "kind", "GIT")))
            .isEqualTo("escalations_of_cu_2");
        assertThat(handle(admin, company, Map.of("name", "42 Desk", "kind", "GIT"))).isEqualTo("s_42_desk");

        // The chat and webhook kinds alike: a second system whose name makes the same handle gets the next suffix.
        Map<String, Object> slack = Map.of("transport", "EVENTS_API");
        assertThat(handle(admin, company, Map.of("name", "Slack", "kind", "SLACK", "slack", slack)))
            .isEqualTo("slack");
        assertThat(handle(admin, company, Map.of("name", "Slack!", "kind", "SLACK", "slack", slack)))
            .isEqualTo("slack_2");
        Map<String, Object> webhook = Map.of("scheme", "SECRET_HEADER", "mapping", Map.of());
        assertThat(handle(admin, company, Map.of("name", "Webhook", "kind", "WEBHOOK", "webhook", webhook)))
            .isEqualTo("webhook");
        assertThat(handle(admin, company, Map.of("name", "Webhook.", "kind", "WEBHOOK", "webhook", webhook)))
            .isEqualTo("webhook_2");
    }

    @Test
    void aHandleInARequestIsRefusedAndARenameKeepsTheHandle() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        String jira = JiraStub.newInstance().baseUrl();

        // A registration that names a handle is refused, naming the field; nothing is registered.
        ApiResponse named = admin.post(company.path("/systems"), Map.of("name", "Support", "handle", "support",
                "kind", "JIRA_DC", "baseUrl", jira));
        assertThat(named.status()).as("%s", named).isEqualTo(400);
        assertThat(named.problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(named.json().get("errors").get(0).get("field").asString()).isEqualTo("handle");
        assertThat(admin.get(company.path("/systems")).json().get("items")).isEmpty();

        JsonNode system = admin.post(company.path("/systems"), Map.of("name", "Support", "kind", "JIRA_DC",
                "baseUrl", jira)).json();
        String path = company.path("/systems/" + system.get("id").asString());
        assertThat(system.get("handle").asString()).isEqualTo("support");

        // A change that names a handle is refused, its own handle included; nothing changes.
        for (String handle : List.of("helpdesk", "support")) {
            ApiResponse renamed = admin.patch(path, Map.of("name", "Helpdesk", "handle", handle));
            assertThat(renamed.status()).as("%s", renamed).isEqualTo(400);
            assertThat(renamed.problemCode()).isEqualTo("VALIDATION_FAILED");
            assertThat(renamed.json().get("errors").get(0).get("field").asString()).isEqualTo("handle");
        }
        assertThat(admin.get(path).json().get("name").asString()).isEqualTo("Support");

        // A new name keeps the handle: the tools of open conversations keep their names. Audited without handle in
        // what changed.
        ApiResponse renamed = admin.patch(path, Map.of("name", "Helpdesk"));
        assertThat(renamed.status()).as("%s", renamed).isEqualTo(200);
        assertThat(renamed.json().get("handle").asString()).isEqualTo("support");
        assertThat(auditEvents(owner, company, "system.changed")).singleElement().satisfies(event -> {
            assertThat(event.get("details").get("changed")).extracting(JsonNode::asString).containsExactly("name");
            assertThat(event.get("details").get("handle").asString()).isEqualTo("support");
        });
        assertThat(auditEvents(owner, company, "system.created")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("handle").asString()).isEqualTo("support"));

        // The handle of the renamed system stays taken: a new "Support" gets the next one.
        assertThat(handle(admin, company, Map.of("name", "Support", "kind", "GIT"))).isEqualTo("support_2");
    }

    @Test
    void registrationsAtTheSameMomentNeverTakeOneHandle() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        List<String> names = List.of("Ops Alerts", "Ops-Alerts", "Ops_Alerts", "Ops.Alerts", "Ops/Alerts",
                "Ops+Alerts", "Ops:Alerts", "Ops,Alerts");
        ExecutorService threads = Executors.newFixedThreadPool(names.size());
        try {
            List<Callable<ApiResponse>> registrations = new ArrayList<>();
            for (String name : names) {
                registrations.add(() -> admin.post(company.path("/systems"), Map.of("name", name, "kind", "GIT")));
            }
            List<String> handles = new ArrayList<>();
            for (Future<ApiResponse> answer : threads.invokeAll(registrations, 120, TimeUnit.SECONDS)) {
                ApiResponse response = answer.get();
                assertThat(response.status()).as("%s", response).isEqualTo(201);
                handles.add(response.json().get("handle").asString());
            }
            assertThat(handles).containsExactlyInAnyOrder("ops_alerts", "ops_alerts_2", "ops_alerts_3",
                    "ops_alerts_4", "ops_alerts_5", "ops_alerts_6", "ops_alerts_7", "ops_alerts_8");
        }
        finally {
            threads.shutdownNow();
        }
    }

    private String handle(ApiClient actor, TestCompany company, Map<String, Object> body) {
        ApiResponse response = actor.post(company.path("/systems"), body);
        assertThat(response.status()).as("%s", response).isEqualTo(201);
        return response.json().get("handle").asString();
    }
}
