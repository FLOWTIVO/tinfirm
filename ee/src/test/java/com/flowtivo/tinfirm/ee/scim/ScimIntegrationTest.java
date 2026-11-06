package com.flowtivo.tinfirm.ee.scim;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestDatabase;

import tools.jackson.databind.JsonNode;

/**
 * SCIM 2.0 provisioning (ADR-0087): the discovery documents, the company's token from its issue to its revocation,
 * each operation and filter, each refusal, deactivation ending access at once and keeping the content, delete as a
 * member who leaves, the OWNER's rules and the seat fallback.
 */
class ScimIntegrationTest extends ScimTest {

    // ---- discovery ------------------------------------------------------------------------------------------------

    @Test
    void servesItsDiscoveryDocuments() {
        Scim scim = scim(scimCompany());

        JsonNode config = scim.get("/ServiceProviderConfig").json();
        assertThat(config.get("schemas").get(0).asString())
            .isEqualTo("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig");
        assertThat(config.get("patch").get("supported").asBoolean()).isTrue();
        assertThat(config.get("filter").get("supported").asBoolean()).isTrue();
        assertThat(config.get("filter").get("maxResults").asInt()).isEqualTo(100);
        for (String unsupported : List.of("bulk", "changePassword", "sort", "etag")) {
            assertThat(config.get(unsupported).get("supported").asBoolean()).as(unsupported).isFalse();
        }
        assertThat(config.get("authenticationSchemes").get(0).get("type").asString()).isEqualTo("oauthbearertoken");

        JsonNode types = scim.get("/ResourceTypes").json();
        assertThat(types.get("totalResults").asInt()).isEqualTo(1);
        JsonNode user = types.get("Resources").get(0);
        assertThat(user.get("endpoint").asString()).isEqualTo("/Users");
        assertThat(user.get("schema").asString()).isEqualTo(CORE);
        assertThat(user.get("schemaExtensions").get(0).get("schema").asString()).isEqualTo(EXTENSION);

        JsonNode schemas = scim.get("/Schemas").json();
        assertThat(schemas.get("Resources")).extracting(schema -> schema.get("id").asString())
            .containsExactly(CORE, EXTENSION);
        // RFC 7643 §7: every attribute names its type, plurality, requiredness, case, mutability, return and uniqueness.
        for (JsonNode schema : schemas.get("Resources")) {
            for (JsonNode attribute : schema.get("attributes")) {
                for (String field : List.of("name", "type", "multiValued", "required", "mutability", "returned",
                        "uniqueness")) {
                    assertThat(attribute.has(field)).as("%s.%s", attribute.get("name"), field).isTrue();
                }
            }
        }

        // RFC 7644 §4: each resource type and schema is served at the location it names.
        List<JsonNode> located = new ArrayList<>();
        types.get("Resources").forEach(located::add);
        schemas.get("Resources").forEach(located::add);
        for (JsonNode resource : located) {
            String location = resource.get("meta").get("location").asString();
            String path = location.substring(location.indexOf("/scim/v2") + "/scim/v2".length());
            JsonNode served = scim.get(path).json();
            assertThat(served.get("id").asString()).as(location).isEqualTo(resource.get("id").asString());
        }
        assertThat(scim.get("/ResourceTypes/Group").status()).isEqualTo(404);
        assertThat(scim.get("/Schemas/urn:ietf:params:scim:schemas:core:2.0:Group").status()).isEqualTo(404);
    }

    @Test
    void neitherGroupsNorBulkNorMeAreServed() {
        Scim scim = scim(scimCompany());

        for (String path : List.of("/Groups", "/Bulk", "/Me", "/Groups/1")) {
            ApiResponse response = scim.get(path);
            assertThat(response.status()).as("%s: %s", path, response).isEqualTo(404);
            assertThat(response.json().get("schemas").get(0).asString())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
        }
    }

    // ---- the token ------------------------------------------------------------------------------------------------

    @Test
    void theOwnerIssuesATokenShownOnceAndStoredHashed() throws Exception {
        TestCompany company = scimCompany();
        ApiClient owner = loginAs(company.email("OWNER"));

        ApiResponse issued = owner.post(company.path("/scim/tokens"), Map.of("name", "Entra ID"));

        assertThat(issued.status()).as("%s", issued).isEqualTo(201);
        String secret = issued.json().get("secret").asString();
        assertThat(secret).matches("^tfs_[A-Za-z0-9_-]{43}$");
        UUID tokenId = UUID.fromString(issued.json().get("token").get("id").asString());
        JsonNode listed = owner.get(company.path("/scim/tokens")).json().get("items");
        assertThat(listed).hasSize(1);
        assertThat(listed.toString()).doesNotContain(secret);
        assertThat(listed.get(0).get("createdByName").asString()).startsWith("User ");
        try (TenantSql sql = TenantSql.in(company.id())) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.US_ASCII)));
            assertThat(sql.column("SELECT token_hash FROM scim_token WHERE id = ?", tokenId)).containsExactly(hash);
        }
        assertThat(auditEvents(owner, company, "scim.token.created")).singleElement().satisfies(event -> {
            assertThat(event.get("details").get("tokenId").asString()).isEqualTo(tokenId.toString());
            assertThat(event.toString()).doesNotContain(secret);
        });
        // The ADMIN reads them and issues none; a MEMBER reads none.
        ApiClient admin = loginAs(company.email("ADMIN"));
        assertThat(admin.get(company.path("/scim/tokens")).status()).isEqualTo(200);
        assertThat(admin.post(company.path("/scim/tokens"), Map.of("name", "x")).status()).isEqualTo(403);
        assertThat(admin.delete(company.path("/scim/tokens/" + tokenId)).status()).isEqualTo(403);
        assertThat(loginAs(company.email("MEMBER")).get(company.path("/scim/tokens")).status()).isEqualTo(403);
        JsonNode status = admin.get(company.path("/scim")).json();
        assertThat(status.get("enabled").asBoolean()).isTrue();
        assertThat(status.get("licensed").asBoolean()).isTrue();
        assertThat(status.get("baseUrl").asString()).endsWith("/scim/v2");
        assertThat(status.get("defaultRole").asString()).isEqualTo("MEMBER");
        assertThat(status.get("roleSource").asString()).isEqualTo("IDP");
        // The token works.
        assertThat(scim(secret).get("/Users").status()).isEqualTo(200);
    }

    @Test
    void aCompanyHoldsTwoActiveTokensAndARevokedOrExpiredOneIsRefused() throws Exception {
        TestCompany company = scimCompany();
        ApiClient owner = loginAs(company.email("OWNER"));
        String first = issueToken(company);
        String second = issueToken(company);

        ApiResponse third = owner.post(company.path("/scim/tokens"), Map.of("name", "third"));
        assertThat(third.status()).isEqualTo(409);
        assertThat(third.problemCode()).isEqualTo("SCIM_TOKEN_LIMIT");

        UUID firstId = tokenId(owner, company, 1);
        assertThat(owner.delete(company.path("/scim/tokens/" + firstId)).status()).isEqualTo(204);
        assertThat(owner.delete(company.path("/scim/tokens/" + firstId)).status()).as("idempotent").isEqualTo(204);
        assertThat(auditEvents(owner, company, "scim.token.revoked")).hasSize(1);
        refused(scim(first).get("/Users"));
        assertThat(scim(second).get("/Users").status()).isEqualTo(200);

        // An expired one, as its time passed.
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update("UPDATE scim_token SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day'"
                    + " WHERE revoked_at IS NULL AND company_id = ?", company.id());
            sql.commit();
        }
        refused(scim(second).get("/Users"));
        assertThat(owner.post(company.path("/scim/tokens"), Map.of("name", "next")).status())
            .as("an expired token holds no place").isEqualTo(201);
        assertThat(owner.post(company.path("/scim/tokens"), Map.of("name", "past", "expiresAt",
                "2020-01-01T00:00:00Z")).problemCode()).isEqualTo("VALIDATION_FAILED");
        // Another company's token id is 404.
        assertThat(owner.delete(company.path("/scim/tokens/" + UUID.randomUUID())).status()).isEqualTo(404);
    }

    @Test
    void aCompanyWhoseScimTheDeploymentDoesNotAllowGetsNoToken() {
        TestCompany off = scimCompany("scimoff");

        ApiResponse issued = loginAs(off.email("OWNER")).post(off.path("/scim/tokens"), Map.of("name", "Okta"));

        assertThat(issued.status()).isEqualTo(409);
        assertThat(issued.problemCode()).isEqualTo("SCIM_NOT_ENABLED");
        assertThat(loginAs(off.email("ADMIN")).get(off.path("/scim")).json().get("enabled").asBoolean()).isFalse();
    }

    @Test
    void aTokenOfACompanyWhoseScimIsNoLongerAllowedIsRefused() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        assertThat(scim.get("/Users").status()).isEqualTo(200);

        scimCompany("scimoff");
        retakeKey(company, "scimoff");

        refused(scim.get("/Users"));
    }

    @Test
    void refusesWithoutAValidTokenAndFromABrowser() {
        TestCompany company = scimCompany();
        ApiClient anonymous = client().withClientIp("203.0.113." + (1 + (int) (Math.random() * 200)));

        refused(anonymous.send("GET", "/scim/v2/Users", null, Map.of()));
        refused(anonymous.send("GET", "/scim/v2/Users", null, Map.of("Authorization", "Bearer tfs_short")));
        refused(anonymous.send("GET", "/scim/v2/Users", null,
                Map.of("Authorization", "Bearer tfs_" + "A".repeat(43))));
        refused(anonymous.send("GET", "/scim/v2/Users", null, Map.of("Authorization", "Basic dXNlcjpwYXNz")));
        // A session is ignored: the chain reads no cookie.
        ApiClient owner = loginAs(company.email("OWNER"));
        refused(owner.send("GET", "/scim/v2/Users", null, Map.of()));

        String token = issueToken(company);
        ApiResponse browser = client().send("GET", "/scim/v2/Users", null,
                Map.of("Authorization", "Bearer " + token, "Origin", "https://evil.example"));
        assertThat(browser.status()).isEqualTo(403);

        assertThat(auditEvents(admin(), "/api/admin/audit-events?limit=200")).anySatisfy(event -> {
            assertThat(event.get("action").asString()).isEqualTo("scim.refused");
            assertThat(event.get("details").get("reason").asString()).isIn("missing", "malformed", "unknown");
        });
    }

    @Test
    void refusedTokensFromOneAddressAreLimited() {
        ApiClient attacker = client().withClientIp("198.51.100." + (1 + (int) (Math.random() * 200)));
        ApiResponse last = null;
        for (int i = 0; i < 21; i++) {
            last = attacker.send("GET", "/scim/v2/Users", null,
                    Map.of("Authorization", "Bearer tfs_" + "B".repeat(43)));
        }
        assertThat(last.status()).isEqualTo(429);
        assertThat(last.header("Retry-After")).isNotNull();
    }

    // ---- create, read, list ---------------------------------------------------------------------------------------

    @Test
    void createsAMemberWithoutASignInAndReadsThemBack() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String email = uniqueEmail("ada");
        Map<String, Object> body = user(email.toUpperCase(), "ext-" + UUID.randomUUID());
        body.put("password", "never-kept-1");
        body.put("title", "Engineer");

        ApiResponse created = scim.post("/Users", body);

        assertThat(created.status()).as("%s", created).isEqualTo(201);
        JsonNode user = created.json();
        assertThat(user.get("userName").asString()).isEqualTo(email);
        assertThat(user.get("displayName").asString()).isEqualTo("Ada Lovelace");
        assertThat(user.get("active").asBoolean()).isTrue();
        assertThat(user.get(EXTENSION).get("role").asString()).isEqualTo("member");
        assertThat(user.get("meta").get("resourceType").asString()).isEqualTo("User");
        assertThat(user.get("meta").get("location").asString()).endsWith("/scim/v2/Users/" + user.get("id").asString());
        assertThat(user.toString()).doesNotContain("never-kept-1").doesNotContain("Engineer");
        assertThat(scim.get("/Users/" + user.get("id").asString()).json().get("userName").asString())
            .isEqualTo(email);

        ApiClient owner = loginAs(company.email("OWNER"));
        JsonNode added = auditEvents(owner, company, "member.added").getFirst();
        assertThat(added.get("details").get("provider").asString()).isEqualTo("scim");
        assertThat(added.get("actor").get("type").asString()).isEqualTo("SCIM");
        assertThat(added.get("actor").get("display").asString()).isEqualTo("SCIM token Entra ID");
        assertThat(auditEvents(owner, company, "scim.user.created")).singleElement()
            .satisfies(event -> assertThat(event.toString()).doesNotContain(email));
        // A user with no sign-in yet, created by the token, in the instance chain.
        assertThat(auditEvents(admin(), "/api/admin/audit-events?limit=200")).anySatisfy(event -> {
            assertThat(event.get("action").asString()).isEqualTo("user.created");
            assertThat(event.get("targetId").asString()).isEqualTo(user.get("id").asString());
            assertThat(event.get("details").get("authSource").asString()).isEqualTo("SCIM");
            assertThat(event.get("actor").get("type").asString()).isEqualTo("SCIM");
        });
    }

    @Test
    void refusesWhatItCannotCreate() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String email = uniqueEmail("dup");
        String externalId = "ext-" + UUID.randomUUID();
        scim.create(user(email, externalId));

        scimError(scim.post("/Users", user(email, "other-" + UUID.randomUUID())), 409, "uniqueness");
        scimError(scim.post("/Users", user(uniqueEmail("other"), externalId)), 409, "uniqueness");
        Map<String, Object> noExternalId = user(uniqueEmail("noext"), "x");
        noExternalId.remove("externalId");
        scimError(scim.post("/Users", noExternalId), 400, "invalidValue");
        scimError(scim.post("/Users", user(uniqueEmail("owner"), "o-" + UUID.randomUUID(), "owner")), 400,
                "invalidValue");
        scimError(scim.post("/Users", user("not-an-email", "n-" + UUID.randomUUID())), 400, "invalidValue");
        assertThat(scim.send("POST", "/Users", "{not json").status()).isEqualTo(400);
        // A member of the company already, whom an admin added: their userName is taken in the company.
        scimError(scim.post("/Users", user(company.email("MEMBER"), "m-" + UUID.randomUUID())), 409, "uniqueness");
    }

    @Test
    void anExistingUserBecomesAMemberAndKeepsTheirName() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String email = uniqueEmail("local");
        UUID local = createUser(admin(), email, false);

        ApiResponse created = scim.post("/Users", user(email, "ext-" + UUID.randomUUID(), "admin"));

        assertThat(created.status()).isEqualTo(201);
        assertThat(id(created)).isEqualTo(local);
        assertThat(created.json().get("displayName").asString()).isEqualTo("User " + email);
        assertThat(created.json().get(EXTENSION).get("role").asString()).isEqualTo("admin");
        // The name is not this company's IdP's to change: the user is not one it created.
        ApiResponse renamed = scim.patch("/Users/" + local, patch(op("replace", "displayName", "Renamed")));
        assertThat(renamed.json().get("displayName").asString()).isEqualTo("User " + email);
    }

    @Test
    void filtersByUserNameExternalIdAndIdAndPagesByIndex() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String email = uniqueEmail("filter");
        String externalId = "ext-" + UUID.randomUUID();
        String id = scim.create(user(email, externalId));

        JsonNode byName = scim.get("/Users?filter=" + encode("userName eq \"" + email.toUpperCase() + "\"")).json();
        assertThat(byName.get("totalResults").asInt()).isEqualTo(1);
        assertThat(byName.get("Resources").get(0).get("id").asString()).isEqualTo(id);
        assertThat(scim.get("/Users?filter=" + encode("externalId eq \"" + externalId + "\"")).json()
            .get("Resources").get(0).get("id").asString()).isEqualTo(id);
        assertThat(scim.get("/Users?filter=" + encode("id eq \"" + id + "\"")).json().get("totalResults").asInt())
            .isEqualTo(1);
        // Entra ID's connection test: a random userName answers an empty list.
        JsonNode none = scim.get("/Users?filter=" + encode("userName eq \"" + UUID.randomUUID() + "\"")).json();
        assertThat(none.get("totalResults").asInt()).isZero();
        assertThat(none.get("Resources")).isEmpty();
        scimError(scim.get("/Users?filter=" + encode("displayName co \"a\"")), 400, "invalidFilter");
        scimError(scim.get("/Users?filter=" + encode("userName eq \"a\" or userName eq \"b\"")), 400,
                "invalidFilter");

        // The company's members, every role: the OWNER, ADMIN and MEMBER, and the one SCIM created.
        JsonNode all = scim.get("/Users").json();
        assertThat(all.get("totalResults").asInt()).isEqualTo(4);
        JsonNode first = scim.get("/Users?startIndex=1&count=2").json();
        assertThat(first.get("itemsPerPage").asInt()).isEqualTo(2);
        assertThat(first.get("startIndex").asInt()).isEqualTo(1);
        JsonNode rest = scim.get("/Users?startIndex=3&count=2").json();
        assertThat(rest.get("Resources")).hasSize(2);
        assertThat(List.of(first.get("Resources").get(0), first.get("Resources").get(1), rest.get("Resources").get(0),
                rest.get("Resources").get(1)).stream().map(user -> user.get("id").asString()).distinct()).hasSize(4);
        assertThat(scim.get("/Users?count=0").json().get("Resources")).isEmpty();
        assertThat(scim.get("/Users?count=1000").json().get("itemsPerPage").asInt()).isEqualTo(4);
        scimError(scim.get("/Users?startIndex=10001"), 400, "invalidValue");
        assertThat(scim.get("/Users/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(scim.get("/Users/not-a-uuid").status()).isEqualTo(404);
    }

    // ---- update ---------------------------------------------------------------------------------------------------

    @Test
    void putReplacesTheUserAndLeavesTheRoleWithoutTheExtension() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String email = uniqueEmail("put");
        String externalId = "ext-" + UUID.randomUUID();
        String id = scim.create(user(email, externalId, "admin"));

        Map<String, Object> replaced = user(email, externalId);
        replaced.remove("name");
        ApiResponse put = scim.put("/Users/" + id, replaced);

        assertThat(put.status()).as("%s", put).isEqualTo(200);
        assertThat(put.json().get("displayName").asString()).as("the name falls back to the email's local part")
            .isEqualTo(email.substring(0, email.indexOf('@')));
        assertThat(put.json().get(EXTENSION).get("role").asString()).isEqualTo("admin");
        assertThat(scim.put("/Users/" + id, user(email, externalId, "viewer")).json().get(EXTENSION).get("role")
            .asString()).isEqualTo("viewer");
        Map<String, Object> noUserName = user(email, externalId);
        noUserName.remove("userName");
        scimError(scim.put("/Users/" + id, noUserName), 400, "invalidValue");
        String newEmail = uniqueEmail("moved");
        assertThat(scim.put("/Users/" + id, user(newEmail, externalId)).json().get("userName").asString())
            .isEqualTo(newEmail);
        scimError(scim.put("/Users/" + id, user(company.email("ADMIN"), externalId)), 409, "uniqueness");
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, "scim.user.changed")).anySatisfy(event -> {
            assertThat(event.get("details").get("attributes").toString()).contains("userName");
            assertThat(event.toString()).doesNotContain(newEmail);
        });
    }

    @Test
    void patchTakesEntraIdsAndOktasForms() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String email = uniqueEmail("patch");
        String id = scim.create(user(email, "ext-" + UUID.randomUUID()));

        ApiResponse patched = scim.patch("/Users/" + id, patch(
                op("Replace", "displayName", "Ada King"),
                op("Add", "name.givenName", "Augusta"),
                op("replace", EXTENSION + ":role", "Admin"),
                op("replace", "emails[type eq \"work\"].value", "ignored@example.test"),
                op("replace", "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department", "R&D")));

        assertThat(patched.status()).as("%s", patched).isEqualTo(200);
        assertThat(patched.json().get("displayName").asString()).isEqualTo("Ada King");
        assertThat(patched.json().get(EXTENSION).get("role").asString()).isEqualTo("admin");
        assertThat(patched.json().get("userName").asString()).isEqualTo(email);
        // An operation without a path, its value an object of attributes, as Entra ID sends it.
        ApiResponse noPath = scim.patch("/Users/" + id, patch(op("replace", null,
                Map.of("displayName", "Countess", EXTENSION, Map.of("role", "member")))));
        assertThat(noPath.json().get("displayName").asString()).isEqualTo("Countess");
        assertThat(noPath.json().get(EXTENSION).get("role").asString()).isEqualTo("member");

        scimError(scim.patch("/Users/" + id, patch(op("remove", "userName", null))), 400, "invalidValue");
        scimError(scim.patch("/Users/" + id, patch(op("remove", "externalId", null))), 400, "invalidValue");
        scimError(scim.patch("/Users/" + id, patch(op("replace", "externalId", ""))), 400, "invalidValue");
        scimError(scim.patch("/Users/" + id, patch(op("replace", EXTENSION + ":role", "owner"))), 400,
                "invalidValue");
        scimError(scim.patch("/Users/" + id, patch(op("move", "displayName", "x"))), 400, "invalidSyntax");
        scimError(scim.patch("/Users/" + id, Map.of("schemas", List.of(PATCH_OP))), 400, "invalidSyntax");
    }

    @Test
    void anExternalIdChangeOfAUserWhoHasNotSignedInIsAuditedWithHashes() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String id = scim.create(user(uniqueEmail("ext"), "old-id"+ UUID.randomUUID()));
        String next = "new-" + UUID.randomUUID();

        assertThat(scim.patch("/Users/" + id, patch(op("replace", "externalId", next))).json().get("externalId")
            .asString()).isEqualTo(next);

        assertThat(auditEvents(loginAs(company.email("OWNER")), company, "scim.user.changed")).anySatisfy(event -> {
            assertThat(event.get("details").get("externalIdSha256").asString()).hasSize(64);
            assertThat(event.get("details").get("previousExternalIdSha256").asString()).hasSize(64);
            assertThat(event.toString()).doesNotContain(next);
        });
    }

    // ---- deactivate and delete ------------------------------------------------------------------------------------

    @Test
    void deactivationEndsAccessAtOnceAndKeepsTheData() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String email = uniqueEmail("leaver");
        UUID userId = createUser(admin(), email, false);
        scim.create(user(email, "ext-" + UUID.randomUUID()));
        ApiClient session = loginAs(email);
        assertThat(session.get(company.path("/agents")).status()).isEqualTo(200);
        String personal = session.post("/api/me/tokens", Map.of("currentPassword", PASSWORD,
                "name", "cli", "scope", "READ")).json().get("token")
            .asString();

        ApiResponse deactivated = scim.patch("/Users/" + userId, patch(op("Replace", "active", "False")));

        assertThat(deactivated.status()).as("%s", deactivated).isEqualTo(200);
        assertThat(deactivated.json().get("active").asBoolean()).isFalse();
        // Their access to the company ends at once, by the session and by a token.
        assertThat(session.get(company.path("/agents")).status()).isEqualTo(403);
        ApiResponse mcp = client().send("POST", "/api/companies/" + company.id() + "/mcp", Map.of("jsonrpc", "2.0",
                "id", 1, "method", "tools/list"), Map.of("Authorization", "Bearer " + personal));
        assertThat(mcp.status()).isNotEqualTo(200);
        // The company adopted the user by email: their account is not its IdP's to deactivate (L5 of the security
        // review of 2026-10-27).
        assertThat(session.get("/api/me").status()).isEqualTo(200);
        assertThat(userActive(userId)).isTrue();
        ApiClient owner = loginAs(company.email("OWNER"));
        JsonNode member = member(owner, company, userId);
        assertThat(member.get("suspended").asBoolean()).isTrue();
        assertThat(auditEvents(owner, company, "principal.suspended")).anySatisfy(
                event -> assertThat(event.get("details").get("cause").asString()).isEqualTo("SCIM"));
        assertThat(auditEvents(owner, company, "scim.user.deactivated")).hasSize(1);

        ApiResponse reactivated = scim.patch("/Users/" + userId, patch(op("replace", "active", true)));
        assertThat(reactivated.json().get("active").asBoolean()).isTrue();
        assertThat(member(owner, company, userId).get("suspended").asBoolean()).isFalse();
        assertThat(loginAs(email).get(company.path("/agents")).status()).isEqualTo(200);
    }

    /** A user this company's SCIM created, a member of no other company, is deactivated on the instance. */
    @Test
    void deactivationOfAUserTheCompanysScimCreatedDeactivatesTheUser() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        UUID userId = UUID.fromString(scim.create(user(uniqueEmail("created"), "ext-" + UUID.randomUUID())));

        assertThat(scim.patch("/Users/" + userId, patch(op("replace", "active", false))).status()).isEqualTo(200);
        assertThat(userActive(userId)).isFalse();

        assertThat(scim.patch("/Users/" + userId, patch(op("replace", "active", true))).json().get("active")
            .asBoolean()).isTrue();
        assertThat(userActive(userId)).isTrue();
    }

    @Test
    void scimNeverLiftsAnAdminsBlock() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        UUID userId = company.userId("MEMBER");
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiResponse suspended = admin.post(company.path("/members/" + userId + "/suspend"), Map.of("reason",
                "Investigation"));
        assertThat(suspended.status()).as("%s", suspended).isEqualTo(200);

        scim.patch("/Users/" + userId, patch(op("replace", "active", false)));
        ApiResponse reactivated = scim.patch("/Users/" + userId, patch(op("replace", "active", true)));

        assertThat(reactivated.json().get("active").asBoolean()).as("the admin's suspension stays").isFalse();
        assertThat(member(admin, company, userId).get("suspended").asBoolean()).isTrue();

        // The other way round: SCIM's block, then an admin's, which SCIM's reactivation keeps.
        UUID other = company.userId("ADMIN");
        ApiClient owner = loginAs(company.email("OWNER"));
        scim.patch("/Users/" + other, patch(op("replace", "active", false)));
        ApiResponse resume = owner.post(company.path("/members/" + other + "/resume"), Map.of());
        assertThat(resume.problemCode()).as("an admin resumes no SCIM suspension").isEqualTo("PRINCIPAL_SUSPENDED");
        assertThat(owner.post(company.path("/members/" + other + "/suspend"), Map.of("reason", "Audit")).status())
            .isEqualTo(200);
        assertThat(scim.patch("/Users/" + other, patch(op("replace", "active", true))).json().get("active")
            .asBoolean()).isFalse();
        assertThat(owner.post(company.path("/members/" + other + "/resume"), Map.of()).status()).isEqualTo(200);
        assertThat(member(owner, company, other).get("suspended").asBoolean()).isFalse();
    }

    @Test
    void deleteIsAMemberWhoLeavesNeverAnErasure() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String id = scim.create(user(uniqueEmail("gone"), "ext-" + UUID.randomUUID()));

        assertThat(scim.delete("/Users/" + id).status()).isEqualTo(204);

        assertThat(scim.get("/Users/" + id).status()).isEqualTo(404);
        assertThat(scim.delete("/Users/" + id).status()).isEqualTo(404);
        ApiClient owner = loginAs(company.email("OWNER"));
        assertThat(auditEvents(owner, company, "member.removed")).anySatisfy(
                event -> assertThat(event.get("details").get("userId").asString()).isEqualTo(id));
        assertThat(auditEvents(owner, company, "principal.left")).isNotEmpty();
        assertThat(auditEvents(owner, company, "scim.user.deleted")).hasSize(1);
        assertThat(auditEvents(owner, company, "principal.erased")).isEmpty();
    }

    @Test
    void aPersonDeletedAndCreatedAgainIsActiveAgain() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String email = uniqueEmail("returning");
        String externalId = "ext-" + UUID.randomUUID();
        UUID userId = UUID.fromString(scim.create(user(email, externalId)));
        assertThat(scim.delete("/Users/" + userId).status()).isEqualTo(204);
        // A user the company's SCIM created, a member of no company now: deactivated, they cannot sign in.
        assertThat(userActive(userId)).isFalse();

        ApiResponse again = scim.post("/Users", user(email, externalId));

        assertThat(again.status()).as("%s", again).isEqualTo(201);
        assertThat(id(again)).isEqualTo(userId);
        assertThat(again.json().get("active").asBoolean()).isTrue();
        assertThat(userActive(userId)).isTrue();
    }

    /** A user the company adopted by email leaves the company at a delete and keeps their account. */
    @Test
    void aDeletedAdoptedUserKeepsTheirAccount() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String email = uniqueEmail("adopted");
        UUID userId = createUser(admin(), email, false);
        scim.create(user(email, "ext-" + UUID.randomUUID()));

        assertThat(scim.delete("/Users/" + userId).status()).isEqualTo(204);

        assertThat(userActive(userId)).isTrue();
        assertThat(client().login(email, PASSWORD).status()).isEqualTo(200);
    }

    // ---- the OWNER ------------------------------------------------------------------------------------------------

    @Test
    void anOwnerIsDeactivatedButNeverTheLastActiveOneNorDemotedNorRebound() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        UUID owner = company.userId("OWNER");

        ApiResponse last = scim.patch("/Users/" + owner, patch(op("replace", "active", false)));
        scimError(last, 400, "mutability");
        assertThat(last.json().get("detail").asString()).isEqualTo("LAST_OWNER");
        scimError(scim.delete("/Users/" + owner), 400, "mutability");
        ApiClient ownerSession = loginAs(company.email("OWNER"));
        assertThat(ownerSession.get(company.path("/inbox")).json().get("items")).anySatisfy(
                item -> assertThat(item.get("kind").asString()).isEqualTo("SCIM_LAST_OWNER"));

        // Its role and externalId never change; the role is answered as owner.
        ApiResponse patched = scim.patch("/Users/" + owner, patch(op("replace", EXTENSION + ":role", "viewer"),
                op("replace", "externalId", "attacker-subject")));
        assertThat(patched.status()).as("%s", patched).isEqualTo(200);
        assertThat(patched.json().get(EXTENSION).get("role").asString()).isEqualTo("owner");
        assertThat(patched.json().has("externalId")).isFalse();

        // With a second owner, one is deactivated.
        String second = uniqueEmail("owner2");
        createUser(admin(), second, false);
        addMember(ownerSession, company.id(), second, "OWNER");
        assertThat(scim.patch("/Users/" + owner, patch(op("replace", "active", false))).status()).isEqualTo(200);
    }

    // ---- seats and the role ---------------------------------------------------------------------------------------

    @Test
    void aSeatsRoleFallsBackToViewerWhenNoSeatIsFree() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        LocalDate today = TestLicences.today();
        int used = admin().get("/api/licence").json().get("seats").get("used").asInt();
        TestLicences.hold("ENTERPRISE", used, today.minusMonths(1), today.plusYears(1));
        try {
            ApiResponse created = scim.post("/Users", user(uniqueEmail("seatless"), "ext-" + UUID.randomUUID(),
                    "admin"));

            assertThat(created.status()).as("never SEATS_EXHAUSTED: %s", created).isEqualTo(201);
            assertThat(created.json().get(EXTENSION).get("role").asString()).isEqualTo("viewer");
            String id = created.json().get("id").asString();
            ApiClient owner = loginAs(company.email("OWNER"));
            JsonNode added = auditEvents(owner, company, "member.added").getFirst();
            assertThat(added.get("details").get("seatFallback").asBoolean()).isTrue();
            assertThat(added.get("details").get("providerRole").asString()).isEqualTo("ADMIN");
            assertThat(owner.get(company.path("/inbox")).json().get("items")).anySatisfy(item -> {
                assertThat(item.get("kind").asString()).isEqualTo("SEAT_FALLBACK");
                assertThat(item.get("seatFallback").get("userId").asString()).isEqualTo(id);
                assertThat(item.get("seatFallback").get("provider").asString()).isEqualTo("scim");
            });
            // A role change asks again, and falls back again.
            ApiResponse changed = scim.patch("/Users/" + id, patch(op("replace", EXTENSION + ":role", "member")));
            assertThat(changed.status()).isEqualTo(200);
            assertThat(changed.json().get(EXTENSION).get("role").asString()).isEqualTo("viewer");
        }
        finally {
            TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.plusYears(1));
        }
    }

    @Test
    void underTheAppsRoleSourceScimGivesTheRoleOnceAtCreation() {
        TestCompany company = scimCompany("scimapp");
        Scim scim = scim(company);

        ApiResponse created = scim.post("/Users", user(uniqueEmail("app"), "ext-" + UUID.randomUUID()));
        assertThat(created.json().get(EXTENSION).get("role").asString()).as("_SCIM_ROLE").isEqualTo("viewer");
        String id = created.json().get("id").asString();

        ApiResponse changed = scim.patch("/Users/" + id, patch(op("replace", EXTENSION + ":role", "admin")));

        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.json().get(EXTENSION).get("role").asString()).as("answered as stored").isEqualTo("viewer");
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Whether the user is active on the instance, as {@code app_user} holds it. */
    static boolean userActive(UUID userId) {
        try (java.sql.Connection connection = TestDatabase.connectAsOwner();
                java.sql.PreparedStatement system = connection.prepareStatement(
                        "SELECT set_config('app.system', 'true', false)");
                java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT active FROM app_user WHERE id = ?")) {
            system.execute();
            statement.setObject(1, userId);
            try (java.sql.ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("the user %s exists", userId).isTrue();
                return row.getBoolean(1);
            }
        }
        catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void refused(ApiResponse response) {
        assertThat(response.status()).as("%s", response).isEqualTo(401);
        assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(response.json().get("schemas").get(0).asString())
            .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
    }

    static void scimError(ApiResponse response, int status, String scimType) {
        assertThat(response.status()).as("%s", response).isEqualTo(status);
        assertThat(response.json().get("status").asString()).isEqualTo(Integer.toString(status));
        assertThat(response.json().get("scimType").asString()).isEqualTo(scimType);
    }

    private void retakeKey(TestCompany company, String key) {
        try (java.sql.Connection connection = com.flowtivo.tinfirm.support.TestDatabase.connectAsOwner();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.system', 'true', false)");
            statement.executeUpdate("UPDATE company SET key = NULL WHERE key = '" + key + "'");
            statement.executeUpdate("UPDATE company SET key = '" + key + "' WHERE id = '" + company.id() + "'");
        }
        catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static UUID tokenId(ApiClient reader, TestCompany company, int oldest) {
        JsonNode items = reader.get(company.path("/scim/tokens")).json().get("items");
        return UUID.fromString(items.get(items.size() - oldest).get("id").asString());
    }

    private static JsonNode member(ApiClient reader, TestCompany company, UUID userId) {
        for (JsonNode member : reader.get(company.path("/members?limit=100")).json().get("items")) {
            if (member.get("userId").asString().equals(userId.toString())) {
                return member;
            }
        }
        throw new AssertionError("Not a member: " + userId);
    }

    private static List<JsonNode> auditEvents(ApiClient reader, String path) {
        List<JsonNode> events = new java.util.ArrayList<>();
        reader.get(path).json().get("items").forEach(events::add);
        return events;
    }

    static String encode(String filter) {
        return java.net.URLEncoder.encode(filter, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
