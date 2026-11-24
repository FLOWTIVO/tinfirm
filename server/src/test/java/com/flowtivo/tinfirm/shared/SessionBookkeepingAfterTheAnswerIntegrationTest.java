package com.flowtivo.tinfirm.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.ConnectionWatch;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Session's bookkeeping when the pool is dry: its commit at the end of its filter, after the answer was
 * committed whole, which must not cut the answer; and its transactions after the request found no free connection,
 * which do not ask the pool again. The pool's refusal is the test fixture's ({@link ConnectionWatch#timeOutAt}).
 */
@ExtendWith(OutputCaptureExtension.class)
class SessionBookkeepingAfterTheAnswerIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The save of the session when the response is about to be committed. */
    private static final String SAVE_AT_COMMIT = "org.springframework.session.web.http"
            + ".SessionRepositoryFilter$SessionRepositoryResponseWrapper.onResponseCommitted";

    /** Spring Session's commit of the session: at the response's commit, and again at the end of its filter. */
    private static final String COMMIT = "org.springframework.session.web.http"
            + ".SessionRepositoryFilter$SessionRepositoryRequestWrapper.commitSession";

    /** Spring Session's lookup of the requested session. */
    private static final String LOOKUP = "org.springframework.session.jdbc.JdbcIndexedSessionRepository.findById";

    /** Instructions larger than the response's buffer (8 KiB), with a mark to look for in a body. */
    private static final String MARK = "instructions-mark-";

    private record Setup(TestCompany company, ApiClient member, String name, String instructions) {
    }

    private Setup setup() {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        String instructions = (MARK + UUID.randomUUID() + " ").repeat(1_500);
        return new Setup(company, member, unique("Helper"), instructions);
    }

    private ApiResponse createAgent(Setup setup) {
        return setup.member().post(setup.company().path("/agents"), agent(setup));
    }

    private static Map<String, Object> agent(Setup setup) {
        return Map.of("name", setup.name(), "runtimes", List.of("CLAUDE_CODE"), "instructions",
                setup.instructions(), "systems", List.of(), "repositories", List.of(), "shareGroupIds", List.of());
    }

    // ---- (b) the commit at the end of Spring Session's filter, after the answer was committed ---------------------

    @Test
    void aReadAnsweredWholeIsNotCutWhenTheSessionsCommitAfterItFindsNoConnection() throws Exception {
        Setup setup = setup();
        ApiResponse created = createAgent(setup);
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        String path = setup.company().path("/agents/" + created.json().get("id").asString());

        try (Socket socket = new Socket("localhost", port)) { // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            socket.setSoTimeout(20_000);
            Raw read;
        try (AutoCloseable busy = ConnectionWatch.timeOutAt(COMMIT, SAVE_AT_COMMIT)) {
                read = exchange(socket, setup.member(), "GET", path, null);
            }

            assertThat(read.status()).isEqualTo(200);
            assertThat(JSON.readTree(read.body()).get("instructions").asString()).isEqualTo(setup.instructions());
            // The connection was ended properly: the next request on it is answered.
            assertThat(exchange(socket, setup.member(), "GET", path, null).status()).isEqualTo(200);
        }
    }

    @Test
    void aWriteAnsweredWholeIsNotCutWhenTheSessionsCommitAfterItFindsNoConnection() throws Exception {
        Setup setup = setup();
        String body = JSON.writeValueAsString(agent(setup));

        try (Socket socket = new Socket("localhost", port)) { // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            socket.setSoTimeout(20_000);
            Raw created;
        try (AutoCloseable busy = ConnectionWatch.timeOutAt(COMMIT, SAVE_AT_COMMIT)) {
                created = exchange(socket, setup.member(), "POST", setup.company().path("/agents"), body);
            }

            assertThat(created.status()).isEqualTo(201);
            JsonNode agent = JSON.readTree(created.body());
            assertThat(agent.get("instructions").asString()).isEqualTo(setup.instructions());
            Raw again = exchange(socket, setup.member(), "GET",
                    setup.company().path("/agents/" + agent.get("id").asString()), null);
            assertThat(again.status()).isEqualTo(200);
        }
    }

    // ---- what a request asks for once it found the pool dry -----------------------------------------------------

    /**
     * A busy request with a session: answered once, in the ordinary way, with one log line and no stack trace, and
     * without asking the pool again for Spring Session's bookkeeping (the save when the busy answer is committed, the
     * lookup and save at the end of its filter): each asking would be a wait of its own. Two reads: one whose own work
     * finds no connection, and one whose session's save inside the write of its answer finds none, as in a dry pool.
     */
    @Test
    void aBusyRequestWithASessionAsksForNoMoreConnectionsAndLogsOneLine(CapturedOutput output) throws Exception {
        Setup setup = setup();
        ApiResponse created = createAgent(setup);
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        String path = setup.company().path("/agents/" + created.json().get("id").asString());
        ConnectionWatch.askedAfterRefusal();

        int start = output.getOut().length();
        ApiResponse read;
        try (AutoCloseable busy = ConnectionWatch.timeOutAt("com.flowtivo.tinfirm.agent.internal.AgentsController")) {
            read = setup.member().get(path);
        }
        assertBusy(read);
        assertThat(askedAfterRefusal()).as("asked for after the first refusal").isEmpty();
        assertOneLine(output.getOut().substring(start));

        start = output.getOut().length();
        ApiResponse again;
        try (AutoCloseable busy = ConnectionWatch.timeOutAt(SAVE_AT_COMMIT)) {
            again = setup.member().get(path);
        }
        assertBusy(again);
        assertThat(askedAfterRefusal()).as("asked for after the first refusal").isEmpty();
        assertOneLine(output.getOut().substring(start));

        // The session's first lookup, before anything of the request's own work.
        start = output.getOut().length();
        ApiResponse third;
        try (AutoCloseable busy = ConnectionWatch.timeOutAt(LOOKUP, COMMIT)) {
            third = setup.member().get(path);
        }
        assertBusy(third);
        assertThat(askedAfterRefusal()).as("asked for after the first refusal").isEmpty();
        assertOneLine(output.getOut().substring(start));
        assertThat(setup.member().get(path).status()).isEqualTo(200);
    }

    /**
     * A sign-in whose new session cannot be saved is not answered as signed in: the answer is the rule's, and the
     * browser holds no session afterwards.
     */
    @Test
    void aSignInWhoseSessionCannotBeSavedIsNotAnsweredAsSignedIn(CapturedOutput output) throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient browser = client();
        browser.fetchCsrfToken();
        ConnectionWatch.askedAfterRefusal();

        int start = output.getOut().length();
        ApiResponse signedIn;
        try (AutoCloseable busy = ConnectionWatch
            .timeOutAt("org.springframework.session.jdbc.JdbcIndexedSessionRepository$JdbcSession.save")) {
            signedIn = browser.login(company.email("MEMBER"), PASSWORD);
        }

        assertThat(signedIn.status()).as("%s", abbreviated(signedIn)).isIn(500, 503);
        assertThat(askedAfterRefusal()).as("asked for after the first refusal").isEmpty();
        String logged = output.getOut().substring(start);
        assertThat(logged.lines()).noneMatch(line -> line.contains("Failure in @ExceptionHandler"));
        assertThat(browser.get("/api/me").status()).isEqualTo(401);
    }

    private static void assertBusy(ApiResponse answer) {
        assertThat(answer.status()).as("%s", abbreviated(answer)).isEqualTo(503);
        assertThat(answer.problemCode()).isEqualTo("SERVER_BUSY");
        assertThat(answer.header("Retry-After")).isEqualTo("2");
        assertThat(answer.body()).doesNotContain(MARK);
    }

    /** One "answered 503" event, no failure of a handler, and no stack trace of the pool's exception. */
    private static void assertOneLine(String logged) {
        List<String> events = logged.lines().toList();
        assertThat(events.stream().filter(line -> line.contains("answered 503 SERVER_BUSY"))).as(logged).hasSize(1);
        assertThat(events).noneMatch(line -> line.contains("Failure in @ExceptionHandler")
                || line.contains("Failure while trying to resolve exception"));
        assertThat(events).noneMatch(line -> line.contains("stack_trace")
                && (line.contains("SQLTransientConnectionException") || line.contains("CannotCreateTransaction")));
    }

    // ---- the raw exchange, to see the connection itself ---------------------------------------------------------

    private record Raw(int status, Map<String, String> headers, String body) {
    }

    /** One request on the socket, with the client's cookies, and its whole answer; EOF when the answer was cut. */
    private static Raw exchange(Socket socket, ApiClient client, String method, String path, String json)
            throws IOException {
        byte[] content = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: localhost:"
                + socket.getPort() + "\r\nAccept: application/json, application/problem+json\r\nX-Forwarded-For: "
                + client.clientIp()
                + "\r\nCookie: " + client.cookieHeader() + "\r\n");
        if (json != null) {
            head.append("Content-Type: application/json\r\nContent-Length: ").append(content.length).append("\r\n");
            client.cookie("XSRF-TOKEN").ifPresent(token -> head.append("X-XSRF-TOKEN: ").append(token.getValue())
                .append("\r\n"));
        }
        head.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(content);
        out.flush();
        InputStream in = socket.getInputStream();
        String status = line(in);
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line = line(in); !line.isEmpty(); line = line(in)) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            for (int size = Integer.parseInt(line(in).split(";")[0].trim(), 16); size > 0;
                    size = Integer.parseInt(line(in).split(";")[0].trim(), 16)) {
                body.write(exactly(in, size));
                line(in);
            }
            while (!line(in).isEmpty()) {
                // Trailers, none expected.
            }
        }
        else if (headers.containsKey("content-length")) {
            body.write(exactly(in, Integer.parseInt(headers.get("content-length"))));
        }
        return new Raw(Integer.parseInt(status.split(" ")[1]), headers, body.toString(StandardCharsets.UTF_8));
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        for (int next = in.read(); ; next = in.read()) {
            if (next < 0) {
                throw new EOFException("The answer ended before its end: " + line);
            }
            if (previous == '\r' && next == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            line.write(next);
            previous = next;
        }
    }

    private static byte[] exactly(InputStream in, int size) throws IOException {
        byte[] bytes = in.readNBytes(size);
        if (bytes.length < size) {
            throw new EOFException("The answer ended after " + bytes.length + " of " + size + " bytes");
        }
        return bytes;
    }

    /** What was asked for after the first refusal, once the request is done: its last steps follow its answer. */
    private static List<String> askedAfterRefusal() throws InterruptedException {
        Thread.sleep(500);
        return ConnectionWatch.askedAfterRefusal();
    }

    private static String abbreviated(ApiResponse response) {
        String body = response.body();
        return response.status() + " " + (body.length() > 400 ? body.substring(0, 400) + "…" : body);
    }
}
