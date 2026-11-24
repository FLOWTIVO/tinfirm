package com.flowtivo.tinfirm.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.web.ServerBusy;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.ConnectionWatch;
import com.flowtivo.tinfirm.support.IntegrationTest;

/**
 * Spring Session's save when the response is about to be committed, under the rule of the busy answer
 * (shared.web.ServerBusy): for an answer larger than the response's buffer it runs inside the write of the answer,
 * and its failure reaches the API's handler as a failed write. The pool's refusal is the test fixture's
 * ({@link ConnectionWatch#timeOutOnceAt}): once, as when the pool is free again for the next asking.
 */
class SessionSaveInsideTheAnswerIntegrationTest extends IntegrationTest {

    /** The save of the session when the response is about to be committed. */
    private static final String SAVE_AT_COMMIT = "org.springframework.session.web.http"
            + ".SessionRepositoryFilter$SessionRepositoryResponseWrapper.onResponseCommitted";

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

    @Test
    void aReadWhoseSessionSaveInsideTheWriteOfItsAnswerFindsNoConnectionIsToldTheServerIsBusy() throws Exception {
        Setup setup = setup();
        ApiResponse created = createAgent(setup);
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        String agentId = created.json().get("id").asString();

        ApiResponse read;
        try (AutoCloseable busy = ConnectionWatch.timeOutOnceAt(SAVE_AT_COMMIT)) {
            read = setup.member().get(setup.company().path("/agents/" + agentId));
        }

        assertThat(read.status()).as("%s", abbreviated(read)).isEqualTo(503);
        assertThat(read.problemCode()).isEqualTo("SERVER_BUSY");
        assertThat(read.header("Retry-After")).isEqualTo("2");
        assertThat(read.contentType()).startsWith("application/problem+json");
        assertThat(read.body()).doesNotContain(MARK);
        assertThat(setup.member().get(setup.company().path("/agents/" + agentId)).status()).isEqualTo(200);
    }

    @Test
    void aWriteWhoseSessionSaveInsideTheWriteOfItsAnswerFindsNoConnectionIsToldItsChangeMayAlreadyBeSaved()
            throws Exception {
        Setup setup = setup();

        ApiResponse created;
        try (AutoCloseable busy = ConnectionWatch.timeOutOnceAt(SAVE_AT_COMMIT)) {
            created = createAgent(setup);
        }

        assertThat(created.status()).as("%s", abbreviated(created)).isEqualTo(500);
        assertThat(created.json().get("detail").asString()).isEqualTo(ServerBusy.SAVED_THEN_FAILED);
        assertThat(created.header("Retry-After")).isNull();
        assertThat(created.body()).doesNotContain(MARK);
        assertThat(setup.member().get(setup.company().path("/agents")).body()).contains(setup.name());
    }

    private static String abbreviated(ApiResponse response) {
        String body = response.body();
        return response.status() + " " + (body.length() > 400 ? body.substring(0, 400) + "…" : body);
    }
}
