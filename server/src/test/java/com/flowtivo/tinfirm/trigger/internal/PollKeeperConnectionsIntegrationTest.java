package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.ConnectionWatch;
import com.flowtivo.tinfirm.support.JiraStub;

/**
 * The poll keeper makes a trigger's poll, and lets the polls go on after a resume, in a transaction of its own once
 * the transaction that told it has released its connection: the request never holds one pooled connection while it
 * asks for a second. TEST FIXTURE ONLY: tokens are test values.
 */
class PollKeeperConnectionsIntegrationTest extends PollingTestSupport {

    private static final String KEEPER = PollKeeper.class.getName();

    @Test
    void aTrackerTriggersCreationMakesItsPollWithOneConnectionAtATime() {
        Bench bench = bench();
        JiraStub.Instance stub = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", stub.baseUrl(), false);
        String token = unique("alice-token");
        UUID account = connectToken(bench.alice(), bench.path("/me/accounts/" + system), stub, token, "alice");

        ConnectionWatch.Mark mark = ConnectionWatch.mark();
        UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Mine"), system, account,
                bench.alicePrincipal(), List.of(event("ASSIGNED")), "ISSUE", "SHOP", null));

        assertThat(pollOf(bench, trigger)).as("the poll, made before the creation was answered").isNotNull();
        assertThat(mark.at(KEEPER)).as("second connections asked for by the poll keeper").isZero();
    }

    @Test
    void aSystemsResumeLetsItsPollsGoOnWithOneConnectionAtATime() throws Exception {
        Bench bench = bench();
        JiraStub.Instance stub = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", stub.baseUrl(), false);
        String token = unique("alice-token");
        UUID account = connectToken(bench.alice(), bench.path("/me/accounts/" + system), stub, token, "alice");
        UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Mine"), system, account,
                bench.alicePrincipal(), List.of(event("ASSIGNED")), "ISSUE", "SHOP", null));
        UUID poll = pollOf(bench, trigger);
        ApiResponse paused = bench.owner().post(bench.path("/systems/" + system + "/pause"), Map.of("reason", "Test"));
        assertThat(paused.status()).as("%s", paused).isEqualTo(200);

        ConnectionWatch.Mark mark = ConnectionWatch.mark();
        ApiResponse resumed = bench.owner().post(bench.path("/systems/" + system + "/resume"), Map.of());
        assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);

        assertThat(pollRow(bench, poll).get("status")).as("the poll, active before the resume was answered")
            .isEqualTo("ACTIVE");
        assertThat(mark.at(KEEPER)).as("second connections asked for by the poll keeper").isZero();
    }
}
