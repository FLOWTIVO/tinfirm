package com.flowtivo.tinfirm.trigger.internal;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.chat.ChatTestBench;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeSlack;

import tools.jackson.databind.JsonNode;

/**
 * Review finding (package 5b): the editor reads the other triggers' claims on a channel without locks; two saves that
 * claim the same channel at once both pass that check, and V030's unique indexes ({@code trigger_channel_agent_uq},
 * {@code trigger_channel_default_uq}) refuse the second. It is answered {@code 409 CHANNEL_TAKEN}, as the editor's own
 * check would have, never {@code 500}.
 */
class ChannelTakenRaceTest extends ChatTestBench {

    @Autowired
    private TriggerStore store;

    @Autowired
    private SystemTransactions transactions;

    @Autowired
    private TimeSource time;

    @Test
    void aSaveThatLosesTheRaceForAChannelIsAConflictNotAFailure() throws Exception {
        Bench bench = bench(true);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String support = workspace.channel("support", false);
        String other = workspace.channel("other", false);
        String author = workspace.member("Max Member");
        linkSlack(bench, slack, bench.member(), author);
        workspace.inChannels(author, support, other);
        assertThat(bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels")).status())
            .isEqualTo(200);
        String[] agent = agent(bench.member(), bench, "Support", List.of());
        JsonNode agentJson = bench.member().get(bench.path("/agents/" + agent[0])).json();
        ApiResponse first = bench.member().post(bench.path("/triggers"), withModel(trigger(bench, slack, agent[0],
                agentJson.get("version").asInt(), other)));
        assertThat(first.status()).as("%s", first).isEqualTo(201);
        UUID companyId = bench.id();
        UUID firstId = UUID.fromString(first.json().get("id").asString());
        UUID agentId = UUID.fromString(agent[0]);

        CountDownLatch claimed = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // The first trigger's save takes the support channel and has not committed yet when the second's editor
            // reads the claims; it commits once the second waits on its row.
            Future<?> firstSave = executor.submit(() -> transactions.runInCompany(companyId, () -> {
                store.replaceChannels(companyId, firstId, slack.systemId(), agentId,
                        List.of(new TriggerEditor.ChannelInput(support, true, "support")), Set.of(), null, time.now());
                claimed.countDown();
                awaitAWaitingLock(companyId);
            }));
            assertThat(claimed.await(20, TimeUnit.SECONDS)).isTrue();
            ApiResponse second = bench.member().post(bench.path("/triggers"), withModel(trigger(bench, slack, agent[0],
                    agentJson.get("version").asInt(), support)));
            firstSave.get(30, TimeUnit.SECONDS);
            assertThat(second.status()).as("%s", second).isEqualTo(409);
            assertThat(second.problemCode()).isEqualTo("CHANNEL_TAKEN");
        }
        finally {
            executor.shutdownNow();
        }
        assertThat(rows(companyId, "SELECT trigger_id FROM trigger_channel WHERE channel_id = ?", support))
            .extracting(row -> row.get("trigger_id"))
            .containsExactly(firstId);
    }

    private static Map<String, Object> trigger(Bench bench, Slack slack, String agentId, int agentVersion,
            String channel) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Desk"));
        body.put("source", "CHANNEL");
        body.put("systemId", slack.systemId().toString());
        body.put("channels", List.of(Map.of("channelId", channel, "default", true)));
        body.put("agentId", agentId);
        body.put("agentVersion", agentVersion);
        body.put("instruction", "Answer the channel.");
        body.put("accounts", List.of());
        body.put("payerPrincipalId", bench.memberPrincipal().toString());
        body.put("acknowledgeOpenEvents", true);
        return body;
    }

    /** Until a session of the database waits on another's transaction (at most 10 seconds; then on regardless). */
    private static void awaitAWaitingLock(UUID companyId) {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < until && rows(companyId,
                "SELECT 1 FROM pg_locks WHERE NOT granted AND locktype = 'transactionid'").isEmpty()) {
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
    }
}
