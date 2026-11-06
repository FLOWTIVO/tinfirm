package com.flowtivo.tinfirm.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.TestDatabase;
import com.flowtivo.tinfirm.support.TestIdp;

/**
 * Runs on its own fresh database, where the bootstrap admin is the only instance admin, so the global
 * "last active instance admin" rule can be tested without touching other tests' data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LastInstanceAdminIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@tinfirm.test";

    private static final String ADMIN_PASSWORD = "test-admin-password-1";

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void freshDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabase.createDatabase("last_admin");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> TestDatabase.APP);
        registry.add("spring.datasource.password", () -> TestDatabase.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> TestDatabase.OWNER);
        registry.add("spring.flyway.password", () -> TestDatabase.OWNER_PASSWORD);
        TestIdp.register(registry);
    }

    @Test
    void theLastActiveInstanceAdminCannotBeDemotedOrDeactivated() {
        ApiClient admin = new ApiClient(port);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);
        String adminId = admin.get("/api/me").json().get("id").asString();

        ApiResponse demote = admin.patch("/api/admin/users/" + adminId, Map.of("instanceAdmin", false));
        assertThat(demote.status()).isEqualTo(409);
        assertThat(demote.problemCode()).isEqualTo("LAST_INSTANCE_ADMIN");
        ApiResponse deactivate = admin.patch("/api/admin/users/" + adminId, Map.of("active", false));
        assertThat(deactivate.status()).isEqualTo(409);
        assertThat(deactivate.problemCode()).isEqualTo("LAST_INSTANCE_ADMIN");

        // A second admin makes the first one removable; then the second one is the last.
        ApiResponse second = admin.post("/api/admin/users", Map.of("email", "second@tinfirm.test", "displayName",
                "Second", "password", "second-admin-password", "instanceAdmin", true));
        assertThat(second.status()).isEqualTo(201);
        UUID secondId = UUID.fromString(second.json().get("id").asString());
        assertThat(admin.patch("/api/admin/users/" + adminId, Map.of("instanceAdmin", false)).status())
            .isEqualTo(200);
        // The demotion is effective on the next request.
        assertThat(admin.get("/api/admin/users").status()).isEqualTo(403);

        ApiClient secondAdmin = new ApiClient(port);
        assertThat(secondAdmin.login("second@tinfirm.test", "second-admin-password").status()).isEqualTo(200);
        ApiResponse lastOne = secondAdmin.patch("/api/admin/users/" + secondId, Map.of("active", false));
        assertThat(lastOne.status()).isEqualTo(409);
        assertThat(lastOne.problemCode()).isEqualTo("LAST_INSTANCE_ADMIN");
        // Deactivating a non-admin is fine.
        assertThat(secondAdmin.patch("/api/admin/users/" + adminId, Map.of("active", false)).status())
            .isEqualTo(200);
    }
}
