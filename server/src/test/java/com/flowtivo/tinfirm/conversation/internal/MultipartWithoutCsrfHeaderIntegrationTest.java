package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.ServletContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.RawHttp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A multipart request on a chain with CSRF protection carries its token in the {@code X-XSRF-TOKEN} header, or it is
 * refused before its body is read: the CSRF filter would otherwise look for the {@code _csrf} parameter, and Tomcat
 * would parse the whole multipart, its files written to the disk, to answer it. The session chain answers the CSRF
 * filter's own 403 under {@code /api/**} and 415 anywhere else; the authorization server's chain, none of whose
 * endpoints takes a multipart, answers 415.
 */
class MultipartWithoutCsrfHeaderIntegrationTest extends OutputTestBench {

    private static final long MIB = 1024 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    ServletContext servletContext;

    @Test
    void aMultipartWithoutTheHeaderIsRefusedUnreadOnTheSessionChain() {
        Bench bench = bench();
        String path = files(bench, conversation(bench));
        Set<String> before = uploads();
        // Anonymous: no session, no cookie, no header. 20 MiB is under the multipart limit.
        assertRefusedUnread(RawHttp.upload(port, path, Map.of(), "a.bin", 20 * MIB), 403, "anonymous");
        // The person's session and CSRF cookie, but no header.
        assertRefusedUnread(RawHttp.upload(port, path, Map.of("Cookie", cookies(bench.person())), "b.bin", 20 * MIB),
                403, "session without the header");
        // An empty header is no header.
        assertRefusedUnread(RawHttp.upload(port, path, Map.of("Cookie", cookies(bench.person()), "X-XSRF-TOKEN", ""),
                "c.bin", 20 * MIB), 403, "empty header");
        assertThat(uploads()).as("no part on the disk").isEqualTo(before);

        // The token in a _csrf form field only: refused too, the web app always sends the header.
        String boundary = "b" + UUID.randomUUID();
        String xsrf = bench.person().cookie("XSRF-TOKEN").orElseThrow().getValue();
        byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n" + xsrf
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f.txt\""
                + "\r\nContent-Type: text/plain\r\n\r\nhello\r\n--" + boundary + "--\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        RawHttp.Answer field = RawHttp.send(port, "POST", path, Map.of("Cookie", cookies(bench.person()),
                "Content-Type", "multipart/form-data; boundary=" + boundary, "Accept", "application/json"), body);
        assertThat(field.status()).as("%s", field).isEqualTo(403);
        assertThat(JSON.readTree(field.body()).get("detail").asString()).isEqualTo("Missing or invalid CSRF token");

        // With the header, as the web app sends it: stored.
        ApiResponse stored = bench.person().upload(path, new ApiClient.Multipart("ok.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)));
        assertThat(stored.status()).as("%s", stored).isEqualTo(201);
    }

    @Test
    void aMultipartToTheAuthorizationServerIsRefusedUnread() {
        assertRefusedUnread(RawHttp.upload(port, "/oauth2/token", Map.of(), "a.bin", 20 * MIB), 415, "token");
        // A header does not help: no endpoint of the authorization server takes a multipart.
        assertRefusedUnread(RawHttp.upload(port, "/oauth2/revoke", Map.of("X-XSRF-TOKEN", "anything"), "a.bin",
                20 * MIB), 415, "revoke with a header");
        assertRefusedUnread(RawHttp.upload(port, "/oauth2/authorize", Map.of(), "a.bin", 20 * MIB), 415,
                "consent");
    }

    /**
     * Outside {@code /api/**} the session chain serves the web app and the sign-in endpoints, whose filters read
     * their parameters before anyone is signed in, and none of which takes a multipart. A CSRF cookie and header
     * that the caller made up itself pass the CSRF check (they only have to be equal), so the header alone would
     * not keep such a body unread.
     */
    @Test
    void aMultipartOutsideTheApiIsRefusedUnreadOnTheSessionChain() {
        Map<String, String> ownPair = Map.of("Cookie", "XSRF-TOKEN=made-up", "X-XSRF-TOKEN", "made-up");
        assertRefusedUnread(RawHttp.upload(port, "/login/oauth2/code/any", ownPair, "a.bin", 20 * MIB), 415,
                "the OIDC sign-in's callback");
        assertRefusedUnread(RawHttp.upload(port, "/login/saml2/sso/any", ownPair, "a.bin", 20 * MIB), 415,
                "the SAML sign-in's answer");
        assertRefusedUnread(RawHttp.upload(port, "/any/page/of/the/web/app", ownPair, "a.bin", 20 * MIB), 415,
                "a page of the web app");
        // Under /api/** the made-up pair passes the CSRF check, and the request is 401 before its body is read.
        Bench bench = bench();
        RawHttp.Upload api = RawHttp.upload(port, files(bench, conversation(bench)), ownPair, "a.bin", 20 * MIB);
        assertThat(api.answer()).as("an answer arrived").isNotNull();
        assertThat(api.answer().status()).as("%s", api.answer()).isEqualTo(401);
        assertThat(api.sentAll()).as("the body was not read to its end").isFalse();
    }

    private static void assertRefusedUnread(RawHttp.Upload upload, int status, String what) {
        assertThat(upload.answer()).as("%s: an answer arrived", what).isNotNull();
        assertThat(upload.answer().status()).as("%s: %s", what, upload.answer()).isEqualTo(status);
        assertThat(upload.answer().header("Content-Type")).as(what).startsWith("application/problem+json");
        JsonNode problem = JSON.readTree(upload.answer().body());
        if (status == 403) {
            assertThat(problem.get("detail").asString()).as(what).isEqualTo("Missing or invalid CSRF token");
        }
        assertThat(upload.sentAll()).as("%s: the body was not read to its end", what).isFalse();
    }

    /** The session and CSRF cookies of a signed-in client, without the header. */
    private static String cookies(ApiClient client) {
        return "TINFIRM_SESSION=" + client.cookie("TINFIRM_SESSION").orElseThrow().getValue() + "; XSRF-TOKEN="
                + client.cookie("XSRF-TOKEN").orElseThrow().getValue();
    }

    /** The files a multipart parse leaves in the container's temporary directory while its request lasts. */
    private Set<String> uploads() {
        File[] files = ((File) servletContext.getAttribute(ServletContext.TEMPDIR))
            .listFiles((dir, name) -> name.startsWith("upload_"));
        return files == null ? Set.of() : Arrays.stream(files).map(File::getName).collect(Collectors.toSet());
    }
}
