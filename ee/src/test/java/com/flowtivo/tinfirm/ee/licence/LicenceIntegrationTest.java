package com.flowtivo.tinfirm.ee.licence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import java.sql.Connection;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;

import tools.jackson.databind.JsonNode;

/**
 * ADR-0077, decisions 5 and 6: the licence every user reads, the instance admins' key in force and next one, and the
 * seat check where a role is given. The tests share one database, whose seats in use other tests' users take: each
 * key here is sized from the count it reads, and every key is removed after each test.
 */
class LicenceIntegrationTest extends IntegrationTest {

    @Autowired
    private ApplicationContext context;

    private <T> T licence(Class<T> type) {
        return context.getBean(type);
    }

    @AfterEach
    void community() {
        TestLicences.clear();
    }

    private int seatsUsed() {
        return admin().get("/api/licence").json().get("seats").get("used").asInt();
    }

    private ApiResponse apply(ApiClient admin, String key) {
        return admin.put("/api/admin/licence", Map.of("key", key));
    }

    @Test
    void withoutAKeyTheInstanceIsCommunity() {
        TestCompany company = companyWithRoles();

        JsonNode licence = loginAs(company.email("MEMBER")).get("/api/licence").json();

        assertThat(licence.get("edition").asString()).isEqualTo("COMMUNITY");
        assertThat(licence.get("state").asString()).isEqualTo("NONE");
        assertThat(licence.get("seats").path("licensed").isMissingNode()).isTrue();
        assertThat(licence.get("seats").get("used").asInt()).isPositive();
        assertThat(licence.get("features")).isEmpty();
        assertThat(licence.get("companiesLicensed").asInt()).isEqualTo(1);
        JsonNode keys = admin().get("/api/admin/licence").json();
        assertThat(keys.path("current").isMissingNode()).isTrue();
        assertThat(keys.path("next").isMissingNode()).isTrue();
    }

    @Test
    void anInstanceAdminAppliesAKeyAndEveryoneReadsItsLicence() {
        ApiClient admin = admin();
        TestCompany company = companyWithRoles();
        int used = seatsUsed();

        ApiResponse applied = apply(admin, "\n" + TestLicences.pro(used + 5) + "  ");

        assertThat(applied.status()).as("%s", applied).isEqualTo(200);
        assertThat(applied.body()).doesNotContain("TINFIRM1.");
        JsonNode current = applied.json().get("current");
        assertThat(current.get("edition").asString()).isEqualTo("PRO");
        assertThat(current.get("rev").asInt()).isEqualTo(1);
        assertThat(current.get("seats").asInt()).isEqualTo(used + 5);
        assertThat(current.get("appliedByName").asString()).isNotBlank();
        assertThat(applied.json().path("next").isMissingNode()).isTrue();
        JsonNode licence = loginAs(company.email("ADMIN")).get("/api/licence").json();
        assertThat(licence.get("edition").asString()).isEqualTo("PRO");
        assertThat(licence.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(licence.get("customer").asString()).isEqualTo("Test Customer");
        assertThat(licence.get("seats").get("licensed").asInt()).isEqualTo(used + 5);
        assertThat(licence.get("endsAt").asString()).isEqualTo(TestLicences.today().plusYears(1).toString());
        assertThat(licence.get("features")).extracting(JsonNode::asString)
            .containsExactlyInAnyOrder("OUTLOOK_ADDIN", "GMAIL_ADDON", "AUDIT_TOOLS", "AUDIT_FULL_HISTORY",
                    "AUDIT_ANCHOR", "RETENTION_SETTINGS", "ELASTIC_RUNNERS");
        assertThat(admin.get("/api/admin/licence").body()).doesNotContain("TINFIRM1.");
        JsonNode audited = admin.get("/api/admin/audit-events?limit=50").json().get("items");
        assertThat(audited).filteredOn(event -> event.get("action").asString().equals("licence.key.applied"))
            .anySatisfy(event -> {
                assertThat(event.get("targetId").asString()).isEqualTo(current.get("licenceId").asString());
                assertThat(event.get("details").get("edition").asString()).isEqualTo("PRO");
                assertThat(event.get("details").get("next").asBoolean()).isFalse();
                assertThat(event.toString()).doesNotContain("TINFIRM1.");
            });
    }

    @Test
    void onlyAnInstanceAdminSeesAndAppliesTheKeys() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));

        assertThat(owner.get("/api/admin/licence").status()).isEqualTo(403);
        assertThat(apply(owner, TestLicences.pro(1000)).status()).isEqualTo(403);
        assertThat(new ApiClient(port).get("/api/licence").status()).isEqualTo(401);
    }

    @Test
    void refusesKeysThatAreInvalidEndedOrSuperseded() {
        ApiClient admin = admin();
        int seats = seatsUsed() + 10;
        LocalDate today = TestLicences.today();

        assertThat(apply(admin, "not a key").problemCode()).isEqualTo("LICENCE_KEY_INVALID");
        String forged = TestLicences.key(TestLicences.payload("forged", 1, "X", "PRO", 10, null, today, today),
                TestLicences.pair().getPrivate());
        ApiResponse refused = apply(admin, forged);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.problemCode()).isEqualTo("LICENCE_KEY_INVALID");
        ApiResponse ended = apply(admin, TestLicences.key("PRO", seats, null, today.minusYears(1),
                today.minusDays(1)));
        assertThat(ended.problemCode()).isEqualTo("LICENCE_KEY_EXPIRED");

        String id = "test-" + UUID.randomUUID();
        assertThat(apply(admin, TestLicences.key(id, 2, "PRO", seats, null, today, today.plusMonths(6))).status())
            .isEqualTo(200);
        for (int rev : new int[] {1, 2}) {
            ApiResponse superseded = apply(admin, TestLicences.key(id, rev, "PRO", seats + 50, null, today,
                    today.plusMonths(6)));
            assertThat(superseded.status()).as("rev %s", rev).isEqualTo(409);
            assertThat(superseded.problemCode()).isEqualTo("LICENCE_KEY_SUPERSEDED");
        }
    }

    @Test
    void seatsBoughtDuringTheYearComeAsTheNextRevision() {
        ApiClient admin = admin();
        int seats = seatsUsed() + 2;
        LocalDate today = TestLicences.today();
        String id = "test-" + UUID.randomUUID();
        assertThat(apply(admin, TestLicences.key(id, 1, "PRO", seats, null, today.minusMonths(8),
                today.plusMonths(4))).status()).isEqualTo(200);

        ApiResponse more = apply(admin, TestLicences.key(id, 2, "PRO", seats + 10, null, today.minusMonths(8),
                today.plusMonths(4)));

        assertThat(more.status()).as("%s", more).isEqualTo(200);
        assertThat(more.json().get("current").get("rev").asInt()).isEqualTo(2);
        assertThat(admin.get("/api/licence").json().get("seats").get("licensed").asInt()).isEqualTo(seats + 10);
        assertThat(admin.get("/api/admin/audit-events?limit=20").json().get("items"))
            .filteredOn(event -> event.get("action").asString().equals("licence.key.applied"))
            .first()
            .satisfies(event -> {
                assertThat(event.get("details").get("rev").asInt()).isEqualTo(2);
                assertThat(event.get("details").get("replaced").get("rev").asInt()).isEqualTo(1);
            });
    }

    @Test
    void aRenewalIsHeldAsTheNextKeyAndTakesOverOnItsStart() throws Exception {
        ApiClient admin = admin();
        int seats = seatsUsed() + 2;
        LocalDate today = TestLicences.today();
        assertThat(apply(admin, TestLicences.key("PRO", seats, null, today.minusMonths(11), today.plusDays(20)))
            .status()).isEqualTo(200);
        assertThat(admin.get("/api/licence").json().get("state").asString()).isEqualTo("EXPIRING");

        ApiResponse renewal = apply(admin, TestLicences.key("ENTERPRISE", null, null, today.plusDays(21),
                today.plusDays(21).plusYears(1)));

        assertThat(renewal.status()).as("%s", renewal).isEqualTo(200);
        assertThat(renewal.json().get("current").get("edition").asString()).isEqualTo("PRO");
        assertThat(renewal.json().get("next").get("edition").asString()).isEqualTo("ENTERPRISE");
        JsonNode licence = admin.get("/api/licence").json();
        assertThat(licence.get("edition").asString()).isEqualTo("PRO");
        assertThat(licence.get("state").asString()).as("a renewal that follows").isEqualTo("ACTIVE");

        // Its start date come: the next key held from today, as the hourly pass then finds it.
        try (Connection owner = TestDatabase.connectAsOwner(); Statement sql = owner.createStatement()) {
            sql.executeUpdate("UPDATE licence_key SET starts_at = CURRENT_DATE, key_text = '"
                    + TestLicences.key("ENTERPRISE", null, null, today, today.plusYears(1))
                    + "' WHERE slot = 'NEXT'");
        }
        assertThat(admin.get("/api/licence").json().get("edition").asString())
            .as("a mismatched row puts nothing in force: the key in force rules").isEqualTo("PRO");
        TestLicences.clear();
        assertThat(apply(admin, TestLicences.key("PRO", seats, null, today.minusMonths(11), today.plusDays(20)))
            .status()).isEqualTo(200);
        assertThat(apply(admin, TestLicences.key("ENTERPRISE", null, null, today.plusDays(1), today.plusYears(1)))
            .status()).isEqualTo(200);
        try (Connection owner = TestDatabase.connectAsOwner(); Statement sql = owner.createStatement()) {
            String id = "activated-" + UUID.randomUUID();
            String now = TestLicences.key(id, 1, "ENTERPRISE", null, null, today, today.plusYears(1));
            sql.executeUpdate("UPDATE licence_key SET licence_id = '" + id + "', starts_at = CURRENT_DATE,"
                    + " key_text = '" + now + "' WHERE slot = 'NEXT'");
        }
        licence(LicenceService.class).verifyAll();

        JsonNode keys = admin.get("/api/admin/licence").json();
        assertThat(keys.get("current").get("edition").asString()).isEqualTo("ENTERPRISE");
        assertThat(keys.path("next").isMissingNode()).isTrue();
        assertThat(admin.get("/api/licence").json().get("edition").asString()).isEqualTo("ENTERPRISE");
        assertThat(admin.get("/api/admin/audit-events?limit=20").json().get("items"))
            .anySatisfy(event -> {
                assertThat(event.get("action").asString()).isEqualTo("licence.key.activated");
                assertThat(event.get("actor").get("type").asString()).isEqualTo("SYSTEM");
            });
    }

    /** A renewal held for later bounds the grants of today: it takes over with no more seats than it holds. */
    @Test
    void aRoleIsGivenWithinTheSeatsOfTheRenewalHeld() {
        ApiClient admin = admin();
        TestCompany company = companyWithRoles();
        String newcomer = uniqueEmail("newcomer");
        createUser(admin, newcomer, false);
        int used = seatsUsed();
        LocalDate today = TestLicences.today();
        assertThat(apply(admin, TestLicences.key("PRO", used + 10, null, today.minusMonths(11), today.plusDays(20)))
            .status()).isEqualTo(200);
        assertThat(apply(admin, TestLicences.key("PRO", used, null, today.plusDays(21), today.plusYears(1)))
            .status()).isEqualTo(200);

        ApiResponse refused = loginAs(company.email("OWNER")).post(company.path("/members"),
                Map.of("email", newcomer, "role", "MEMBER"));

        assertThat(refused.problemCode()).isEqualTo("SEATS_EXHAUSTED");
        assertThat(refused.json().get("details").get("licensed").asInt()).isEqualTo(used);
    }

    @Test
    void aKeyWithFewerSeatsThanAreInUseIsNotApplied() {
        ApiClient admin = admin();
        int used = seatsUsed();

        ApiResponse refused = apply(admin, TestLicences.pro(used - 1));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("LICENCE_SEATS_TOO_FEW");
        assertThat(refused.json().get("details").get("required").asInt()).isEqualTo(used);
        assertThat(refused.json().get("details").get("licensed").asInt()).isEqualTo(used - 1);
        LocalDate today = TestLicences.today();
        ApiResponse smallRenewal = apply(admin, TestLicences.key("PRO", used - 1, null, today.plusDays(10),
                today.plusYears(1)));
        assertThat(smallRenewal.problemCode()).isEqualTo("LICENCE_SEATS_TOO_FEW");
    }

    @Test
    void aSeatRoleIsRefusedWhenEverySeatIsTaken() {
        ApiClient admin = admin();
        TestCompany company = companyWithRoles();
        String newcomer = uniqueEmail("newcomer");
        UUID newcomerId = createUser(admin, newcomer, false);
        assertThat(apply(admin, TestLicences.pro(seatsUsed())).status()).isEqualTo(200);
        ApiClient owner = loginAs(company.email("OWNER"));

        ApiResponse refused = owner.post(company.path("/members"), Map.of("email", newcomer, "role", "MEMBER"));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("SEATS_EXHAUSTED");
        assertThat(refused.json().get("details").get("licensed").asInt())
            .isEqualTo(refused.json().get("details").get("used").asInt());

        // A Viewer holds no seat; making them a Member is refused the same way.
        addMember(owner, company.id(), newcomer, "VIEWER");
        ApiResponse promoted = owner.patch(company.path("/members/" + newcomerId), Map.of("role", "MEMBER"));
        assertThat(promoted.problemCode()).isEqualTo("SEATS_EXHAUSTED");

        // Who holds a seat keeps it, in another role and in another company.
        assertThat(owner.patch(company.path("/members/" + company.userId("MEMBER")), Map.of("role", "ADMIN"))
            .status()).isEqualTo(200);
        UUID other = createCompany(admin, unique("Other"));
        addMember(admin, other, company.email("MEMBER"), "MEMBER");

        // A seat freed is a seat to give.
        assertThat(owner.patch(company.path("/members/" + company.userId("ADMIN")), Map.of("role", "VIEWER"))
            .status()).isEqualTo(200);
        assertThat(owner.patch(company.path("/members/" + newcomerId), Map.of("role", "MEMBER")).status())
            .isEqualTo(200);
    }

    @Test
    void aDeactivatedUserIsNotActivatedAgainWithoutASeat() {
        ApiClient admin = admin();
        TestCompany company = companyWithRoles();
        UUID member = company.userId("MEMBER");
        assertThat(admin.patch("/api/admin/users/" + member, Map.of("active", false)).status()).isEqualTo(200);
        assertThat(apply(admin, TestLicences.pro(seatsUsed())).status()).isEqualTo(200);

        ApiResponse refused = admin.patch("/api/admin/users/" + member, Map.of("active", true));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("SEATS_EXHAUSTED");
        TestLicences.clear();
        assertThat(admin.patch("/api/admin/users/" + member, Map.of("active", true)).status()).isEqualTo(200);
    }

    @Test
    void anEnterpriseKeyWithoutASeatCountCountsNothing() {
        ApiClient admin = admin();
        LocalDate today = TestLicences.today();
        assertThat(apply(admin, TestLicences.key("ENTERPRISE", null, 3, today, today.plusYears(1))).status())
            .isEqualTo(200);

        JsonNode licence = admin.get("/api/licence").json();

        assertThat(licence.get("edition").asString()).isEqualTo("ENTERPRISE");
        assertThat(licence.get("seats").path("licensed").isMissingNode()).isTrue();
        assertThat(licence.get("companiesLicensed").asInt()).isEqualTo(3);
        assertThat(licence.get("features")).extracting(JsonNode::asString)
            .contains("LEGAL_HOLDS", "SAML", "MULTI_COMPANY");
        assertThat(licence.get("state").asString()).isEqualTo("ACTIVE");
    }
}
