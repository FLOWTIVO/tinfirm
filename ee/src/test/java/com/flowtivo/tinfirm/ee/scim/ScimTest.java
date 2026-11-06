package com.flowtivo.tinfirm.ee.scim;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.extension.ExtendWith;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;

/**
 * TEST FIXTURE ONLY: the SCIM tests' base. The test profile ({@code application-test.yaml}) declares three companies
 * whose SCIM the deployment sets (ADR-0087, decision 2): {@code scim} allowed, the role the IdP's ({@code IDP});
 * {@code scimapp} allowed, the role the app's after creation ({@code APP}), new members {@code VIEWER};
 * {@code scimoff} not allowed. They are every test's, so the SCIM tests share the integration tests' context. A test
 * takes one of these keys for a company of its own ({@link #scimCompany}), so that each test starts from an empty
 * company with an OWNER, an ADMIN and a MEMBER. Under an Enterprise key, without a seat count.
 */
@ExtendWith(EnterpriseLicence.class)
abstract class ScimTest extends IntegrationTest {

    static final String CORE = "urn:ietf:params:scim:schemas:core:2.0:User";

    static final String EXTENSION = "urn:ietf:params:scim:schemas:extension:tinfirm:2.0:User";

    static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    static final String SCIM_JSON = "application/scim+json";

    /** A company of the test's own that holds the declaration's key, so its SCIM is the declaration's. */
    TestCompany scimCompany(String key) {
        TestCompany company = companyWithRoles();
        try (Connection connection = TestDatabase.connectAsOwner()) {
            // The company table's row-level security holds for its owner too: as the system's context.
            try (PreparedStatement admin = connection.prepareStatement(
                    "SELECT set_config('app.system', 'true', false)")) {
                admin.execute();
            }
            try (PreparedStatement release = connection.prepareStatement("UPDATE company SET key = NULL WHERE key = ?")) {
                release.setString(1, key);
                release.executeUpdate();
            }
            try (PreparedStatement take = connection.prepareStatement("UPDATE company SET key = ? WHERE id = ?")) {
                take.setString(1, key);
                take.setObject(2, company.id());
                assertThat(take.executeUpdate()).as("the company takes the key %s", key).isEqualTo(1);
            }
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return company;
    }

    TestCompany scimCompany() {
        return scimCompany("scim");
    }

    /** A token the company's OWNER issues, its secret. */
    String issueToken(TestCompany company) {
        ApiResponse issued = loginAs(company.email("OWNER")).post(company.path("/scim/tokens"),
                Map.of("name", "Entra ID"));
        assertThat(issued.status()).as("%s", issued).isEqualTo(201);
        return issued.json().get("secret").asString();
    }

    Scim scim(String token) {
        return new Scim(client(), token);
    }

    Scim scim(TestCompany company) {
        return scim(issueToken(company));
    }

    static Map<String, Object> user(String userName, String externalId) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("schemas", List.of(CORE));
        user.put("userName", userName);
        user.put("externalId", externalId);
        user.put("name", Map.of("givenName", "Ada", "familyName", "Lovelace"));
        user.put("emails", List.of(Map.of("value", userName, "type", "work", "primary", true)));
        user.put("active", true);
        return user;
    }

    static Map<String, Object> user(String userName, String externalId, String role) {
        Map<String, Object> user = user(userName, externalId);
        user.put(EXTENSION, Map.of("role", role));
        return user;
    }

    static Map<String, Object> patch(Map<String, Object>... operations) {
        return Map.of("schemas", List.of(PATCH_OP), "Operations", List.of(operations));
    }

    static Map<String, Object> op(String op, String path, Object value) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", op);
        if (path != null) {
            operation.put("path", path);
        }
        if (value != null) {
            operation.put("value", value);
        }
        return operation;
    }

    /** A SCIM client: the token as Bearer, SCIM's media type, no session. */
    record Scim(ApiClient client, String token) {

        ApiResponse get(String path) {
            return send("GET", path, null);
        }

        ApiResponse post(String path, Object body) {
            return send("POST", path, body);
        }

        ApiResponse put(String path, Object body) {
            return send("PUT", path, body);
        }

        ApiResponse patch(String path, Object body) {
            return send("PATCH", path, body);
        }

        ApiResponse delete(String path) {
            return send("DELETE", path, null);
        }

        ApiResponse send(String method, String path, Object body) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + token);
            headers.put("Accept", SCIM_JSON);
            if (body != null) {
                headers.put("Content-Type", SCIM_JSON);
            }
            return client.send(method, "/scim/v2" + path, body, headers);
        }

        /** Creates a user and answers its id. */
        String create(Map<String, Object> user) {
            ApiResponse created = post("/Users", user);
            assertThat(created.status()).as("%s", created).isEqualTo(201);
            return created.json().get("id").asString();
        }
    }

    static UUID id(ApiResponse response) {
        return UUID.fromString(response.json().get("id").asString());
    }
}
