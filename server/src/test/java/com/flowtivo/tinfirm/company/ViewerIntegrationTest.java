package com.flowtivo.tinfirm.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;

/**
 * ADR-0077, decision 5: VIEWER, the role without a seat, reads what the company shows its members and their own
 * inbox, and starts, changes or decides nothing ({@code 403 VIEWER_READ_ONLY}).
 */
class ViewerIntegrationTest extends IntegrationTest {

    private ApiClient viewer(TestCompany company) {
        String email = uniqueEmail("viewer");
        createUser(admin(), email, false);
        addMember(loginAs(company.email("OWNER")), company.id(), email, "VIEWER");
        return loginAs(email);
    }

    @Test
    void aViewerReads() {
        TestCompany company = companyWithRoles();
        ApiClient viewer = viewer(company);

        for (String path : List.of("", "/members", "/groups", "/agents", "/skills", "/conversations", "/decisions",
                "/inbox", "/systems", "/triggers", "/runner-hosts", "/service-identities")) {
            ApiResponse read = viewer.get(company.path(path));
            assertThat(read.status()).as("GET %s: %s", path, read).isEqualTo(200);
        }
        assertThat(viewer.get(company.path("")).json().get("myRole").asString()).isEqualTo("VIEWER");
        // A Viewer decides nothing: their decisions are none, never an error.
        ApiResponse mine = viewer.get(company.path("/decisions?mine=true"));
        assertThat(mine.status()).as("%s", mine).isEqualTo(200);
        assertThat(mine.json().get("items")).isEmpty();
        ApiResponse secrets = viewer.get(company.path("/secrets"));
        assertThat(secrets.status()).isEqualTo(403);
        assertThat(secrets.problemCode()).as("a read the role lacks is the plain 403").isNull();
    }

    @Test
    void aViewerStartsChangesAndDecidesNothing() {
        TestCompany company = companyWithRoles();
        ApiClient viewer = viewer(company);
        String agentId = loginAs(company.email("MEMBER")).post(company.path("/agents"),
                Map.of("name", unique("Agent"), "runtimes", List.of("CLAUDE_CODE"))).json().get("id").asString();

        List<ApiResponse> refused = List.of(
                viewer.post(company.path("/conversations"), Map.of("agentId", agentId, "message", "Hello")),
                viewer.post(company.path("/agents"), Map.of("name", unique("Mine"), "runtimes",
                        List.of("CLAUDE_CODE"))),
                viewer.patch(company.path("/agents/" + agentId), Map.of("description", "Mine now")),
                viewer.post(company.path("/runner-hosts"), Map.of("name", unique("host"))),
                viewer.put(company.path("/agents/" + agentId + "/settings/me"), Map.of()),
                viewer.post(company.path("/decisions/" + java.util.UUID.randomUUID() + "/decide"),
                        Map.of("answer", "yes")));
        for (ApiResponse response : refused) {
            assertThat(response.status()).as("%s", response).isEqualTo(403);
            assertThat(response.problemCode()).as("%s", response).isEqualTo("VIEWER_READ_ONLY");
        }
        // Their own inbox's dismissals are theirs: an unknown notice is 404, not a Viewer's refusal.
        ApiResponse dismissal = viewer.put(company.path("/inbox/dismissals/BUDGET:nothing"), Map.of());
        assertThat(dismissal.problemCode()).isNotEqualTo("VIEWER_READ_ONLY");
    }

    @Test
    void anOwnerMakesAMemberAViewerAndBack() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String path = company.path("/members/" + company.userId("MEMBER"));

        assertThat(owner.patch(path, Map.of("role", "VIEWER")).json().get("role").asString()).isEqualTo("VIEWER");
        assertThat(loginAs(company.email("MEMBER")).post(company.path("/agents"), Map.of("name", unique("A"),
                "runtimes", List.of("CLAUDE_CODE"))).problemCode()).isEqualTo("VIEWER_READ_ONLY");
        assertThat(owner.patch(path, Map.of("role", "MEMBER")).json().get("role").asString()).isEqualTo("MEMBER");
    }
}
