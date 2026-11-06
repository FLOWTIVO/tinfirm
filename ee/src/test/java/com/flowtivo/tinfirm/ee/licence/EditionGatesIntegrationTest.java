package com.flowtivo.tinfirm.ee.licence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeGoogle;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * ADR-0077, decisions 4, 7 and 8: each paid feature refused under Community ({@code 403 EDITION_REQUIRED}, after the
 * access check), served under Enterprise; after a licence expired, the audit's export for 90 days, the retention
 * periods frozen, no new hold; Community's retention keeping everything; the admins' {@code LICENCE} notice.
 */
class EditionGatesIntegrationTest extends IntegrationTest {

    private static final Map<String, Object> PERIODS = Map.of("runEventsDays", 90, "chatContentDays", 30,
            "contentDays", 365, "filesDays", 365);

    @AfterEach
    void community() {
        TestLicences.clear();
    }

    private static void editionRequired(ApiResponse response, String feature) {
        assertThat(response.status()).as("%s", response).isEqualTo(403);
        assertThat(response.problemCode()).isEqualTo("EDITION_REQUIRED");
        assertThat(response.json().get("details").get("feature").asString()).isEqualTo(feature);
    }

    @Test
    void communityRefusesEachPaidFeatureAfterTheAccessCheck() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));

        assertThat(owner.get(company.path("/audit-events?limit=5")).status()).isEqualTo(200);
        editionRequired(owner.get(company.path("/audit-events?action=member.added")), "AUDIT_TOOLS");
        editionRequired(owner.get(company.path("/audit-events/verification")), "AUDIT_TOOLS");
        editionRequired(owner.get(company.path("/audit-events/export")), "AUDIT_TOOLS");
        editionRequired(owner.get(company.path("/audit-events/checks")), "AUDIT_TOOLS");
        editionRequired(owner.put(company.path("/retention"), PERIODS), "RETENTION_SETTINGS");
        editionRequired(owner.post(company.path("/retention/preview"), PERIODS), "RETENTION_SETTINGS");
        editionRequired(owner.post(company.path("/holds"), Map.of("principalId", UUID.randomUUID().toString(),
                "reason", "Litigation")), "LEGAL_HOLDS");
        editionRequired(owner.get(company.path("/holds/conversations/any-key-1")), "LEGAL_HOLDS");
        editionRequired(owner.get(company.path("/outlook/manifest")), "OUTLOOK_ADDIN");
        editionRequired(owner.get(company.path("/gmail/deployment")), "GMAIL_ADDON");
        editionRequired(owner.post(company.path("/gmail/links/read"), Map.of("token", "any-token")), "GMAIL_ADDON");
        editionRequired(owner.post(company.path("/gmail/links/confirm"), Map.of("token", "any-token")), "GMAIL_ADDON");
        editionRequired(admin().get("/api/admin/audit-events/verification"), "AUDIT_TOOLS");
        // SCIM (ADR-0087): no token is issued; the tokens are listed and revoked in every edition.
        editionRequired(owner.post(company.path("/scim/tokens"), Map.of("name", "Entra ID")), "SCIM");
        assertThat(owner.get(company.path("/scim/tokens")).status()).isEqualTo(200);
        assertThat(owner.delete(company.path("/scim/tokens/" + UUID.randomUUID())).status()).isEqualTo(404);
        assertThat(owner.get(company.path("/scim")).json().get("licensed").asBoolean()).isFalse();
        // A person's links are theirs to see and end in every edition, as the holds that exist are.
        assertThat(owner.get("/api/me/gmail/links").status()).isEqualTo(200);
        // Elastic runners (ADR-0100): a pool's settings, set or cleared; the host's slots stay the core's.
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("vm-host"), "COMPANY");
        String hostPath = company.path("/runner-hosts/" + host.id());
        editionRequired(owner.patch(hostPath, Map.of("pool", Map.of("readyDefault", 1, "windows", List.of()))),
                "ELASTIC_RUNNERS");
        editionRequired(owner.patch(hostPath, Map.of("clearPool", true)), "ELASTIC_RUNNERS");
        ApiResponse slots = owner.patch(hostPath, Map.of("desiredMaxConcurrentRuns", 2));
        assertThat(slots.status()).as("%s", slots).isEqualTo(200);
        assertThat(slots.json().has("pool")).isFalse();

        // Another company's paths stay 404: nothing is learned from the edition.
        TestCompany other = companyWithRoles();
        assertThat(owner.get(other.path("/audit-events/verification")).status()).isEqualTo(404);
        assertThat(owner.post(other.path("/gmail/links/read"), Map.of("token", "any-token")).status()).isEqualTo(404);
    }

    /**
     * The Outlook add-in's MCP calls, which carry its task pane's Origin, are refused after the access check too: the
     * member's are EDITION_REQUIRED, a token of theirs sent to another company gets the endpoint's usual answer.
     */
    @Test
    void theOutlookAddInsCallsAreRefusedAfterTheAccessCheck() {
        TestCompany company = companyWithRoles();
        ApiResponse created = loginAs(company.email("MEMBER")).post("/api/me/tokens",
                Map.of("currentPassword", PASSWORD, "name", "outlook", "scope", "READ"));
        String token = created.json().get("token").asString();
        Map<String, Object> call = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params",
                Map.of("name", "list_agents", "arguments", Map.of()));
        Map<String, String> pane = Map.of("Authorization", "Bearer " + token, "Origin", "http://localhost:8080");

        ApiResponse member = client().send("POST", "/api/companies/" + company.id() + "/mcp", call, pane);
        assertThat(member.status()).as("%s", member).isEqualTo(403);
        assertThat(member.json().get("error").get("data").get("code").asString()).isEqualTo("EDITION_REQUIRED");

        ApiResponse elsewhere = client().send("POST", "/api/companies/" + companyWithRoles().id() + "/mcp", call,
                pane);
        assertThat(elsewhere.status()).as("%s", elsewhere).isEqualTo(404);
        assertThat(elsewhere.json().toString()).as("%s", elsewhere).doesNotContain("EDITION_REQUIRED");
    }

    @Test
    void communityRetentionKeepsEverything() {
        TestCompany company = companyWithRoles();

        JsonNode retention = loginAs(company.email("OWNER")).get(company.path("/retention")).json();

        assertThat(retention.get("mode").asString()).isEqualTo("KEEP_EVERYTHING");
        assertThat(retention.path("runEventsDays").isMissingNode()).isTrue();
        assertThat(retention.path("chatContentDays").isMissingNode()).isTrue();
        assertThat(retention.path("contentDays").isMissingNode()).isTrue();
        assertThat(retention.path("filesDays").isMissingNode()).isTrue();
        assertThat(retention.get("nextSweep").get("conversations").asLong()).isZero();
    }

    @Test
    void enterpriseServesThePaidFeaturesWithTodaysPeriodsAsTheStart() {
        LocalDate today = TestLicences.today();
        TestLicences.hold("ENTERPRISE", null, today.minusMonths(1), today.plusYears(1));
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));

        assertThat(owner.get(company.path("/audit-events?action=member.added")).status()).isEqualTo(200);
        assertThat(owner.get(company.path("/audit-events/verification")).status()).isEqualTo(200);
        assertThat(owner.get(company.path("/outlook/manifest")).status()).isEqualTo(200);
        JsonNode retention = owner.get(company.path("/retention")).json();
        assertThat(retention.get("mode").asString()).isEqualTo("PERIODS");
        assertThat(retention.get("runEventsDays").asInt()).isEqualTo(90);
        assertThat(retention.get("contentDays").asInt()).isEqualTo(365);
        ApiResponse keep = owner.put(company.path("/retention"), Map.of("runEventsDays", 90, "chatContentDays", 30,
                "contentDays", 365, "filesDays", 365, "memoryDays", 730));
        assertThat(keep.status()).as("%s", keep).isEqualTo(200);
        java.util.Map<String, Object> keepAll = new java.util.HashMap<>();
        keepAll.put("runEventsDays", null);
        keepAll.put("chatContentDays", null);
        keepAll.put("contentDays", null);
        keepAll.put("filesDays", null);
        ApiResponse kept = owner.put(company.path("/retention"), keepAll);
        assertThat(kept.status()).as("a keep of every kind: %s", kept).isEqualTo(200);
        assertThat(kept.json().path("runEventsDays").isMissingNode()).isTrue();
        java.util.Map<String, Object> chatsLonger = new java.util.HashMap<>(keepAll);
        chatsLonger.put("contentDays", 365);
        assertThat(owner.put(company.path("/retention"), chatsLonger).problemCode()).isEqualTo("RETENTION_INVALID");
    }

    @Test
    void anExpiredLicenceKeepsTheExportAndTheHoldsAndFreezesTheRetention() {
        LocalDate today = TestLicences.today();
        TestLicences.hold("ENTERPRISE", null, today.minusMonths(1), today.plusYears(1));
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        assertThat(owner.put(company.path("/retention"), Map.of("runEventsDays", 60, "chatContentDays", 30,
                "contentDays", 365, "filesDays", 365)).status()).isEqualTo(200);

        TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.minusDays(40));

        JsonNode licence = owner.get("/api/licence").json();
        assertThat(licence.get("state").asString()).isEqualTo("EXPIRED");
        assertThat(licence.get("edition").asString()).isEqualTo("COMMUNITY");
        assertThat(licence.get("features")).extracting(JsonNode::asString).containsExactly("SAML");
        assertThat(owner.get(company.path("/audit-events/export")).status()).as("the export for 90 days more")
            .isEqualTo(200);
        editionRequired(owner.get(company.path("/audit-events?action=member.added")), "AUDIT_TOOLS");
        JsonNode retention = owner.get(company.path("/retention")).json();
        assertThat(retention.get("mode").asString()).isEqualTo("FROZEN");
        assertThat(retention.get("runEventsDays").asInt()).isEqualTo(60);
        editionRequired(owner.put(company.path("/retention"), PERIODS), "RETENTION_SETTINGS");
        editionRequired(owner.post(company.path("/holds"), Map.of("principalId", UUID.randomUUID().toString(),
                "reason", "Litigation")), "LEGAL_HOLDS");
        assertThat(owner.get(company.path("/holds")).status()).as("open holds are listed").isEqualTo(200);

        TestLicences.hold("ENTERPRISE", null, today.minusYears(2), today.minusDays(200));
        editionRequired(owner.get(company.path("/audit-events/export")), "AUDIT_TOOLS");
    }

    /** The Outlook add-in's uploads of an email's attachments, which carry its Origin, are refused as its calls are. */
    @Test
    void theOutlookAddInsUploadsAreRefused() {
        TestCompany company = companyWithRoles();
        String token = loginAs(company.email("MEMBER"))
            .post("/api/me/tokens", Map.of("currentPassword", PASSWORD, "name", "outlook", "scope", "WRITE"))
            .json()
            .get("token")
            .asString();
        ApiClient.Multipart file = new ApiClient.Multipart("a.txt", "text/plain", "a".getBytes());
        String path = "/api/companies/" + company.id() + "/mcp/files";

        editionRequired(client().send("POST", path, file, Map.of("Authorization", "Bearer " + token, "Origin",
                "http://localhost:8080")), "OUTLOOK_ADDIN");
        assertThat(client().send("POST", path, file, Map.of("Authorization", "Bearer " + token)).status())
            .as("another client's upload, without the add-in's Origin").isEqualTo(201);
    }

    /**
     * Google's requests to the Gmail add-on (ADR-0083) are verified as ever, and answered with a card that says the
     * add-on needs Pro: no sign-in link is offered.
     */
    @Test
    void theGmailAddOnSaysItNeedsPro() {
        TestCompany company = companyWithRoles();
        String endpoint = "http://localhost:8080/api/gmail/" + company.id() + "/home";
        FakeGoogle.Account account = FakeGoogle.Account.create();
        Map<String, Object> event = Map.of("commonEventObject", Map.of("hostApp", "GMAIL"),
                "authorizationEventObject", Map.of("userIdToken", FakeGoogle.userToken(account)));

        ApiResponse refused = client().send("POST", "/api/gmail/" + company.id() + "/home", event, Map.of());
        assertThat(refused.status()).isEqualTo(401);
        ApiResponse card = client().send("POST", "/api/gmail/" + company.id() + "/home", event,
                Map.of("Authorization", "Bearer " + FakeGoogle.systemToken(endpoint)));
        assertThat(card.status()).as("%s", card).isEqualTo(200);
        assertThat(card.body()).contains("needs Tinfirm's Pro edition").doesNotContain("/gmail/link/");
    }

    @Test
    void theAdminsSeeTheLicenceNearItsEnd() {
        LocalDate today = TestLicences.today();
        TestLicences.hold("PRO", 100_000, today.minusYears(1), today.plusDays(10));
        TestCompany company = companyWithRoles();

        for (String role : List.of("OWNER", "ADMIN")) {
            JsonNode items = loginAs(company.email(role)).get(company.path("/inbox")).json().get("items");
            assertThat(items).as(role).anySatisfy(item -> {
                assertThat(item.get("kind").asString()).isEqualTo("LICENCE");
                assertThat(item.get("licence").get("state").asString()).isEqualTo("EXPIRING");
                assertThat(item.get("licence").get("endsAt").asString()).isEqualTo(today.plusDays(10).toString());
                assertThat(item.get("dismissible").asBoolean()).isTrue();
            });
        }
        assertThat(loginAs(company.email("MEMBER")).get(company.path("/inbox")).json().get("items"))
            .noneSatisfy(item -> assertThat(item.get("kind").asString()).isEqualTo("LICENCE"));
    }
}
