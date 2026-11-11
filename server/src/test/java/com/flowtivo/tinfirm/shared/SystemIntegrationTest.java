package com.flowtivo.tinfirm.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.core.io.ClassPathResource;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.BuildVersion;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;

class SystemIntegrationTest extends IntegrationTest {

    @LocalManagementPort
    int managementPort;

    @Test
    void systemInfoIsPublicAndReportsNoTelemetry() {
        ApiResponse info = client().get("/api/system/info");

        assertThat(info.status()).isEqualTo(200);
        assertThat(info.json().get("version").asString()).isEqualTo(BuildVersion.expected());
        assertThat(info.json().get("telemetryEnabled").asBoolean()).isFalse();
    }

    @Test
    void theSpaIsServedForClientRoutesButNotForApiOrInfrastructurePaths() {
        ApiClient browser = client();
        for (String route : new String[] { "/", "/login", "/c/0192/members", "/admin/users" }) {
            ApiResponse page = browser.send("GET", route, null,
                    Map.of("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
            assertThat(page.status()).as(route).isEqualTo(200);
            assertThat(page.contentType()).as(route).startsWith("text/html");
            assertThat(page.body()).as(route).contains("<app-root>");
        }
        assertThat(browser.get("/api/unknown").status()).isEqualTo(401);
        ApiResponse unknownApi = admin().get("/api/unknown");
        assertThat(unknownApi.status()).isEqualTo(404);
        assertThat(unknownApi.contentType()).startsWith("application/problem+json");
        assertThat(browser.get("/actuator/health").status()).isEqualTo(404);
        assertThat(browser.get("/login/oauth2/nothing").status()).isEqualTo(404);
    }

    @Test
    void hashedBuildFilesAreCachedForAYearAndEverythingElseIsNot() {
        ApiClient browser = client();
        for (String file : new String[] { "/chunk-TEST0001.js", "/styles-TEST0001.css", "/media/font-TEST0001.woff2" }) {
            ApiResponse hashed = browser.get(file);
            assertThat(hashed.status()).as(file).isEqualTo(200);
            assertThat(hashed.header("Cache-Control")).as(file).isEqualTo("max-age=31536000, public, immutable");
        }
        assertThat(browser.get("/chunk-TEST0001.js").contentType()).contains("javascript");
        // A chunk of an older build is gone: 404, never index.html served as JavaScript.
        assertThat(browser.get("/chunk-GONE0001.js").status()).isEqualTo(404);
        assertThat(browser.get("/media/gone-GONE0001.woff2").status()).isEqualTo(404);

        for (String path : new String[] { "/", "/index.html", "/c/0192/inbox" }) {
            ApiResponse page = browser.send("GET", path, null, Map.of("Accept", "text/html"));
            assertThat(page.status()).as(path).isEqualTo(200);
            assertThat(page.header("Cache-Control")).as(path).contains("no-store");
        }
    }

    @Test
    void theBuildFilesNeitherReadNorTouchTheSession() throws SQLException {
        ApiClient browser = admin();
        String sessionId = new String(
                Base64.getDecoder().decode(browser.cookie("TINFIRM_SESSION").orElseThrow().getValue()),
                StandardCharsets.UTF_8);
        long longAgo = System.currentTimeMillis() - 60_000;
        setLastAccess(sessionId, longAgo);

        // A page's first load fetches every one of them, and each read of the session is a trip to the database.
        for (String file : new String[] { "/chunk-TEST0001.js", "/styles-TEST0001.css", "/media/font-TEST0001.woff2" }) {
            assertThat(browser.get(file).status()).as(file).isEqualTo(200);
        }
        assertThat(lastAccess(sessionId)).isEqualTo(longAgo);

        // The API reads it, and so marks it accessed.
        assertThat(browser.get("/api/me").status()).isEqualTo(200);
        assertThat(lastAccess(sessionId)).isGreaterThan(longAgo);
    }

    private static void setLastAccess(String sessionId, long millis) throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner();
                PreparedStatement update = owner
                    .prepareStatement("UPDATE spring_session SET last_access_time = ? WHERE session_id = ?")) {
            update.setLong(1, millis);
            update.setString(2, sessionId);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private static long lastAccess(String sessionId) throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner();
                PreparedStatement select = owner
                    .prepareStatement("SELECT last_access_time FROM spring_session WHERE session_id = ?")) {
            select.setString(1, sessionId);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    @Test
    void aBrowserThatAcceptsGzipGetsTheBuildsGzipCopyOfAHashedFile() throws IOException {
        ApiClient browser = client();
        Map<String, String> gzip = Map.of("Accept-Encoding", "gzip, deflate, br, zstd");

        ApiResponse gzipped = browser.send("GET", "/chunk-TEST0001.js", null, gzip);
        assertThat(gzipped.status()).isEqualTo(200);
        assertThat(gzipped.header("Content-Encoding")).isEqualTo("gzip");
        assertThat(gzipped.header("Vary")).contains("Accept-Encoding");
        assertThat(gzipped.header("Content-Length"))
            .isEqualTo(String.valueOf(new ClassPathResource("static/chunk-TEST0001.js.gz").contentLength()));
        assertThat(gzipped.contentType()).contains("javascript");
        assertThat(gzipped.header("Cache-Control")).isEqualTo("max-age=31536000, public, immutable");

        // Without gzip, or for a file the build left uncompressed, the file itself.
        assertThat(browser.get("/chunk-TEST0001.js").header("Content-Encoding")).isNull();
        assertThat(browser.send("GET", "/styles-TEST0001.css", null, gzip).header("Content-Encoding")).isNull();
        // Nothing else is compressed: not the page, not the API (BREACH).
        ApiResponse page = browser.send("GET", "/", null,
                Map.of("Accept", "text/html", "Accept-Encoding", "gzip, deflate, br, zstd"));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.header("Content-Encoding")).isNull();
        ApiResponse info = browser.send("GET", "/api/system/info", null, gzip);
        assertThat(info.status()).isEqualTo(200);
        assertThat(info.header("Content-Encoding")).isNull();
    }

    @Test
    void probesAndMetricsAreOnTheManagementPort() throws Exception {
        assertThat(managementGet("/actuator/health/liveness")).contains("UP");
        assertThat(managementGet("/actuator/health/readiness")).contains("UP");
        assertThat(managementGet("/actuator/prometheus")).contains("jvm_memory_used_bytes");
    }

    private String managementGet(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient()
            .send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return response.body();
    }
}
