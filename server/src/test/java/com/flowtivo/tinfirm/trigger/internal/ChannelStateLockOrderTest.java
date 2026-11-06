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
import com.flowtivo.tinfirm.trigger.TriggerGate;

import tools.jackson.databind.JsonNode;

/**
 * Review finding (package 5b, CONVENTIONS §10's lock order): {@code ChannelTriggersImpl.channelState} updates a
 * channel's {@code trigger_channel} rows first and locks the trigger's row after, through the gate; the editor's
 * change locks the trigger's row first ({@code TriggerService}, {@code store.lock}) and then replaces its
 * {@code trigger_channel} rows. The two orders meet when a channel's state changes (a lifecycle event, a post the
 * platform refused, a mention from an unknown channel, or the channel list's refresh, which any member runs) while
 * the trigger is being edited: a deadlock, which PostgreSQL ends with {@code 40P01} for one of them. The fix: lock the
 * channel's triggers (in the order of their ids) before the rows, as the order asks.
 */
class ChannelStateLockOrderTest extends ChatTestBench {

    @Autowired
    private TriggerStore store;

    @Autowired
    private TriggerGate gate;

    @Autowired
    private SystemTransactions transactions;

    @Autowired
    private TimeSource time;

    @Test
    void aChannelsStateChangeAndTheEditorsChangeOfTheTriggersChannelsNeverDeadlock() throws Exception {
        Bench bench = bench(true);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String support = workspace.channel("support", false);
        String author = workspace.member("Max Member");
        linkSlack(bench, slack, bench.member(), author);
        workspace.inChannels(author, support);
        // The channel list reads the bot's channels from Slack, so that the editor knows the channel.
        assertThat(bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels")).status())
            .isEqualTo(200);
        String[] agent = agent(bench.member(), bench, "Support", List.of());
        JsonNode agentJson = bench.member().get(bench.path("/agents/" + agent[0])).json();
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Desk"));
        body.put("source", "CHANNEL");
        body.put("systemId", slack.systemId().toString());
        body.put("channels", List.of(Map.of("channelId", support, "default", true)));
        body.put("agentId", agent[0]);
        body.put("agentVersion", agentJson.get("version").asInt());
        body.put("instruction", "Answer the support channel.");
        body.put("accounts", List.of());
        body.put("payerPrincipalId", bench.memberPrincipal().toString());
        body.put("acknowledgeOpenEvents", true);
        ApiResponse created = bench.member().post(bench.path("/triggers"), withModel(body));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID companyId = bench.id();
        UUID systemId = slack.systemId();
        UUID triggerId = UUID.fromString(created.json().get("id").asString());
        UUID agentId = UUID.fromString(agent[0]);

        CountDownLatch triggerLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // The editor's side, as TriggerService.change runs it: the trigger's row, then its channels replaced, once
            // the chat's side waits on a lock (on the trigger's row now; on the trigger's row after taking the
            // channel's rows before the fix, which the replacement then waits on: the deadlock).
            Future<?> editor = executor.submit(() -> transactions.runInCompany(companyId, () -> {
                assertThat(store.lock(companyId, triggerId)).isPresent();
                triggerLocked.countDown();
                awaitAWaitingLock(companyId);
                store.replaceChannels(companyId, triggerId, systemId, agentId,
                        List.of(new TriggerEditor.ChannelInput(support, true, "support")), Set.of(), null,
                        time.now());
            }));
            // The chat's side, as ChannelTriggersImpl.channelState runs it: the channel's state, then the gate.
            Future<?> channelState = executor.submit(() -> {
                await(triggerLocked);
                transactions.runInCompany(companyId, () -> {
                    List<UUID> changed = store.channelState(companyId, systemId, support, false);
                    assertThat(changed).containsExactly(triggerId);
                    gate.check(companyId, triggerId);
                });
            });
            editor.get(30, TimeUnit.SECONDS);
            channelState.get(30, TimeUnit.SECONDS);
        }
        finally {
            executor.shutdownNow();
        }
        assertThat(rows(companyId, "SELECT state FROM trigger_channel WHERE trigger_id = ?", triggerId))
            .extracting(row -> row.get("state"))
            .containsExactly("UNAVAILABLE");
    }

    /** Until a session of the database waits on a lock (at most 10 seconds; then on regardless). */
    private static void awaitAWaitingLock(UUID companyId) {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < until && rows(companyId, "SELECT 1 FROM pg_locks WHERE NOT granted").isEmpty()) {
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(Duration.ofSeconds(20).toSeconds(), TimeUnit.SECONDS)).isTrue();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
