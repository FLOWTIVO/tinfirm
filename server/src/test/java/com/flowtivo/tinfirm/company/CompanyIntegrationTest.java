package com.flowtivo.tinfirm.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

class CompanyIntegrationTest extends IntegrationTest {

    @Test
    void aCompanyStartsItsAuditChainWithItsCreationAndItsOwner() {
        ApiClient admin = admin();

        UUID id = createCompany(admin, "Acme");

        assertThat(id.version()).isEqualTo(7);
        JsonNode company = admin.get("/api/companies/" + id).json();
        assertThat(company.get("name").asString()).isEqualTo("Acme");
        assertThat(company.get("myRole").asString()).isEqualTo("OWNER");
        assertThat(company.path("key").isNull() || company.path("key").isMissingNode())
            .as("a company no declaration names has no key: %s", company)
            .isTrue();
        JsonNode events = admin.get("/api/companies/" + id + "/audit-events").json().get("items");
        // The owner's principal comes with the membership (the package 1 contract, §3.1).
        assertThat(actions(events)).containsExactly("principal.created", "member.added", "company.created");
        assertThat(events.get(2).get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(events.get(2).get("seq").asLong()).isEqualTo(1);
        assertThat(events.get(2).get("prevHash").asString()).isEqualTo("0".repeat(64));
    }

    /** Companies come from the deployment's declarations (ADR-0075): no endpoint creates one, for anyone. */
    @Test
    void noEndpointCreatesACompany() {
        ApiResponse asAdmin = admin().post("/api/companies", Map.of("name", "Nope"));
        assertThat(asAdmin.status()).as("%s", asAdmin).isEqualTo(405);

        TestCompany company = companyWithRoles();
        ApiResponse asOwner = loginAs(company.email("OWNER")).post("/api/companies", Map.of("name", "Nope"));
        assertThat(asOwner.status()).as("%s", asOwner).isEqualTo(405);
    }

    @Test
    void theDeclaredCompanyShowsItsKey() {
        ApiClient admin = admin();
        // The test profile declares it, with the bootstrap admin as its owner.
        JsonNode membership = StreamSupport.stream(admin.get("/api/me").json().get("memberships").spliterator(), false)
            .filter(candidate -> candidate.get("companyName").asString().equals("Bootstrap Co"))
            .findFirst()
            .orElseThrow();
        assertThat(membership.get("role").asString()).isEqualTo("OWNER");
        JsonNode company = admin.get("/api/companies/" + membership.get("companyId").asString()).json();
        assertThat(company.get("key").asString()).isEqualTo("bootstrap");
    }

    @Test
    void membersListOnlyTheirCompaniesWithKeysetPagination() {
        ApiClient admin = admin();
        String email = uniqueEmail("multi");
        createUser(admin, email, false);
        List<UUID> mine = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID companyId = createCompany(admin, unique("Paged-" + i));
            addMember(admin, companyId, email, "MEMBER");
            mine.add(companyId);
        }
        createCompany(admin, unique("NotMine"));
        ApiClient user = loginAs(email);

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ApiResponse page = user.get("/api/companies?limit=2" + (cursor != null ? "&cursor=" + cursor : ""));
            assertThat(page.status()).isEqualTo(200);
            page.json().get("items").forEach(item -> {
                seen.add(UUID.fromString(item.get("id").asString()));
                assertThat(item.get("myRole").asString()).isEqualTo("MEMBER");
            });
            JsonNode next = page.json().get("nextCursor");
            cursor = next == null || next.isNull() ? null : next.asString();
            pages++;
        }
        while (cursor != null);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(mine);
        assertThat(pages).isEqualTo(2);
        assertThat(user.get("/api/companies?cursor=not!base64").status()).isEqualTo(400);
        assertThat(user.get("/api/companies?limit=201").status()).isEqualTo(400);
        assertThat(user.get("/api/companies?limit=0").problemCode()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void nonMembersGet404AndMembersSeeTheirRole() {
        TestCompany company = companyWithRoles();
        ApiClient outsider = loginAs(companyWithRoles().email("OWNER"));

        assertThat(outsider.get(company.path("")).status()).isEqualTo(404);
        ApiResponse asMember = loginAs(company.email("MEMBER")).get(company.path(""));
        assertThat(asMember.status()).isEqualTo(200);
        assertThat(asMember.json().get("myRole").asString()).isEqualTo("MEMBER");
        assertThat(loginAs(company.email("OWNER")).get("/api/companies/" + UUID.randomUUID()).status())
            .isEqualTo(404);
    }

    @Test
    void instanceAdminAccessWithoutMembershipIsAudited() {
        TestCompany company = companyWithRoles();
        ApiClient admin = admin();

        ApiResponse view = admin.get(company.path(""));

        assertThat(view.status()).isEqualTo(200);
        assertThat(view.json().has("myRole")).isFalse();
        JsonNode events = loginAs(company.email("OWNER")).get(company.path("/audit-events")).json().get("items");
        JsonNode access = StreamSupport.stream(events.spliterator(), false)
            .filter(event -> event.get("action").asString().equals("admin.company.accessed"))
            .findFirst()
            .orElseThrow();
        assertThat(access.get("actor").get("type").asString()).isEqualTo("USER");
        assertThat(access.get("details").get("permission").asString()).isEqualTo("VIEW_COMPANY");
    }

    @Test
    void ownersAndAdminsRenameOthersGet403() {
        TestCompany company = companyWithRoles();

        for (String role : List.of("MEMBER")) {
            assertThat(loginAs(company.email(role)).patch(company.path(""), Map.of("name", "Nope")).status())
                .as(role)
                .isEqualTo(403);
        }
        ApiResponse renamed = loginAs(company.email("ADMIN")).patch(company.path(""), Map.of("name", "Renamed Co"));
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.json().get("name").asString()).isEqualTo("Renamed Co");
        assertThat(renamed.json().get("myRole").asString()).isEqualTo("ADMIN");

        JsonNode latest = loginAs(company.email("OWNER")).get(company.path("/audit-events?limit=1")).json()
            .get("items").get(0);
        assertThat(latest.get("action").asString()).isEqualTo("company.updated");
        assertThat(latest.get("details").get("name").asString()).isEqualTo("Renamed Co");
    }

    static List<String> actions(JsonNode events) {
        List<String> actions = new ArrayList<>();
        events.forEach(event -> actions.add(event.get("action").asString()));
        return actions;
    }
}
