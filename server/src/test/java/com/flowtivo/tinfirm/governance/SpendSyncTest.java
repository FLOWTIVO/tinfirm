package com.flowtivo.tinfirm.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.TestContent;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * The spend sync (the package 6 contract, §5.1; G2, G3) against {@code FakeLiteLlm}'s spend logs: a run's tagged
 * requests summed per model and another run's ignored; logs under two keys; logs that come late; none after an hour,
 * then the relay's own count at LiteLLM's prices; LiteLLM unreadable, and the admins' item; an answer larger than the
 * server reads; a subscription's run; a run
 * that relayed nothing; a run that ended while its only answer was still under way, and one that ended while a second
 * answer was, which is settled once with both; two workers on one company, each run's logs read once; and the
 * relay's count itself.
 */
class SpendSyncTest extends SpendBench {

    @Test
    void theRelayCountsEachAnswersUsagePerModelAndKey() {
        Setup setup = setUp();
        Relayed run = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        answerAnthropicStream(100, 20, 5, 42);
        post(MESSAGES, run.token(), SONNET);
        post(MESSAGES, run.token(), SONNET);
        // An answer without usage counts a request and no tokens.
        litellm.resetModelHandler();
        post(MESSAGES, run.token(), "claude-haiku-4-5");

        Relayed chat = running(setup, RunnerMessages.ModelAccessFormat.OPENAI);
        answerOpenAiJson(300, 100, 7);
        post(CHAT, chat.token(), "claude-haiku-4-5");

        List<ModelRelay.ModelUsage> anthropic = transactions.inCompany(setup.companyId(),
                () -> relay.usageByModel(setup.companyId(), run.runId()));
        assertThat(anthropic).containsExactly(
                new ModelRelay.ModelUsage("claude-haiku-4-5", setup.key().token, 1, 0, 0, 0, 0),
                // The model as the request named it; the stream's running totals, not their sum.
                new ModelRelay.ModelUsage(SONNET, setup.key().token, 2, 200, 84, 40, 10));
        assertThat(transactions.inCompany(setup.companyId(), () -> relay.usageByModel(setup.companyId(),
                chat.runId())))
            // OpenAI's prompt counts the cache's tokens: the rest is input.
            .containsExactly(new ModelRelay.ModelUsage("claude-haiku-4-5", setup.key().token, 1, 200, 7, 100, 0));
    }

    @Test
    void aRunsTaggedRequestsAreSummedPerModelAndAnotherRunsAreNot() {
        Setup setup = setUp();
        litellm.price(SONNET, 0.000003, 0.000015);
        Relayed one = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        Relayed other = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        answerAnthropicStream(10, 0, 0, 5);
        post(MESSAGES, one.token(), SONNET);
        post(MESSAGES, one.token(), SONNET);
        post(MESSAGES, other.token(), SONNET);
        Instant now = Instant.now();
        litellm.logSpend(setup.key().token, SONNET, List.of("run:" + one.runId()), now, 0.01, 1000, 100, 200);
        litellm.logSpend(setup.key().token, SONNET, List.of("run:" + one.runId(), "agent:x"), now, 0.02, 2000,
                200, 0);
        litellm.logSpend(setup.key().token, SONNET, List.of("run:" + other.runId()), now, 0.5, 9000, 900, 0);

        end(setup.companyId(), one.runId());
        Map<String, Object> pending = settlement(setup.companyId(), one.runId());
        assertThat(pending.get("state")).isEqualTo("PENDING");
        // Due two minutes after the end, since LiteLLM writes its logs in batches.
        assertThat(((java.sql.Timestamp) pending.get("due_at")).toInstant())
            .isAfter(Instant.now().plus(Duration.ofSeconds(90)));
        assertThat(settle()).as("nothing is due yet").isGreaterThanOrEqualTo(0);
        assertThat(spend(setup.companyId(), one.runId())).isEmpty();

        due(setup.companyId(), one.runId(), Duration.ofMinutes(3));
        settle();

        List<Map<String, Object>> rows = spend(setup.companyId(), one.runId());
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("source")).isEqualTo("GATEWAY");
        assertThat(row.get("model")).isEqualTo(SONNET);
        assertThat(row.get("requests")).isEqualTo(2L);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.03");
        assertThat(row.get("input_tokens")).isEqualTo(2800L);
        assertThat(row.get("cached_input_tokens")).isEqualTo(200L);
        assertThat(row.get("output_tokens")).isEqualTo(300L);
        assertThat(row.get("payer_principal_id")).isEqualTo(setup.payer());
        // The payer is a service identity, which since package 4 pays a trigger's runs only (TestRuns.running puts
        // the run in a trigger's conversation): not personal, and its trigger copied from the run's conversation.
        assertThat(row.get("personal")).isEqualTo(false);
        assertThat(row.get("trigger_id")).isNotNull()
            .isEqualTo(TestContent.column(setup.companyId(), "SELECT trigger_id FROM run WHERE id = ?", one.runId()));
        assertThat(settlement(setup.companyId(), one.runId()).get("state")).isEqualTo("SETTLED");
        assertThat(spend(setup.companyId(), other.runId())).isEmpty();

        // Written once: settling again, or the run's end heard again, changes nothing (G3).
        end2(setup.companyId(), one.runId());
        settle();
        assertThat(spend(setup.companyId(), one.runId())).hasSize(1);
    }

    @Test
    void logsUnderTwoKeysOfOneRunAreBothRead() {
        Setup setup = setUp();
        Relayed run = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        answerAnthropicStream(10, 0, 0, 5);
        post(MESSAGES, run.token(), SONNET);
        // A rotation within the run: its later requests went with another key.
        String rotated = "f".repeat(64);
        sql(setup.companyId(), """
                INSERT INTO relay_usage (company_id, run_id, model, key_id, requests, updated_at)
                VALUES (?, ?, ?, ?, 1, now())""", setup.companyId(), run.runId(), SONNET, rotated);
        Instant now = Instant.now();
        litellm.logSpend(setup.key().token, SONNET, List.of("run:" + run.runId()), now, 0.01, 100, 10, 0);
        litellm.logSpend(rotated, SONNET, List.of("run:" + run.runId()), now, 0.04, 400, 40, 0);

        end(setup.companyId(), run.runId());
        due(setup.companyId(), run.runId(), Duration.ofMinutes(3));
        settle();

        Map<String, Object> row = spend(setup.companyId(), run.runId()).getFirst();
        assertThat(row.get("requests")).isEqualTo(2L);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.05");
    }

    @Test
    void lateLogsAreWaitedForAndAfterAnHourTheRelaysCountSettlesTheRun() {
        Setup setup = setUp();
        litellm.price(SONNET, 0.000003, 0.000015);
        Relayed late = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        Relayed never = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        answerAnthropicStream(1000, 0, 0, 100);
        post(MESSAGES, late.token(), SONNET);
        post(MESSAGES, never.token(), SONNET);
        end(setup.companyId(), late.runId());
        end(setup.companyId(), never.runId());

        due(setup.companyId(), late.runId(), Duration.ofMinutes(3));
        settle();
        Map<String, Object> waiting = settlement(setup.companyId(), late.runId());
        assertThat(waiting.get("state")).isEqualTo("PENDING");
        assertThat(waiting.get("attempts")).isEqualTo(1);
        assertThat(waiting.get("last_error")).as("the logs were read, and held nothing yet").isNull();
        assertThat(((java.sql.Timestamp) waiting.get("due_at")).toInstant())
            .isAfter(Instant.now().plus(Duration.ofSeconds(90)));

        litellm.logSpend(setup.key().token, SONNET, List.of("run:" + late.runId()), Instant.now(), 0.25, 1000,
                100, 0);
        due(setup.companyId(), late.runId(), Duration.ofMinutes(5));
        settle();
        assertThat(spend(setup.companyId(), late.runId())).singleElement()
            .satisfies(row -> assertThat(row.get("source")).isEqualTo("GATEWAY"));

        // No log after an hour: the relay's own count, at LiteLLM's price of the model.
        due(setup.companyId(), never.runId(), Duration.ofMinutes(61));
        settle();
        Map<String, Object> relayed = spend(setup.companyId(), never.runId()).getFirst();
        assertThat(relayed.get("source")).isEqualTo("RELAY");
        assertThat(relayed.get("requests")).isEqualTo(1L);
        assertThat(relayed.get("input_tokens")).isEqualTo(1000L);
        assertThat(relayed.get("output_tokens")).isEqualTo(100L);
        assertThat((BigDecimal) relayed.get("cost_usd")).isEqualByComparingTo("0.0045");
    }

    @Test
    void unreadableLogsAreRetriedWithTheirReasonAndAfterAnHourAdminsAreTold() {
        Setup setup = setUp();
        Relayed run = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        answerAnthropicStream(10, 0, 0, 5);
        post(MESSAGES, run.token(), SONNET);
        end(setup.companyId(), run.runId());
        due(setup.companyId(), run.runId(), Duration.ofMinutes(3));
        litellm.failNext("/spend/logs", 500, 1);
        settle();
        Map<String, Object> failed = settlement(setup.companyId(), run.runId());
        assertThat(failed.get("state")).isEqualTo("PENDING");
        assertThat((String) failed.get("last_error")).contains("500");
        assertThat(failed.get("failing_since")).isNotNull();

        ApiClient admin = loginAs(setup.company().email("ADMIN"));
        ApiClient member = loginAs(setup.company().email("MEMBER"));
        assertThat(kinds(admin, setup)).doesNotContain("SPEND_SYNC");
        sql(setup.companyId(), "UPDATE spend_settlement SET failing_since = now() - interval '61 minutes' "
                + "WHERE run_id = ?", run.runId());
        JsonNode item = items(admin, setup, "SPEND_SYNC").getFirst();
        assertThat(item.get("spendSync").get("lastError").asString()).contains("500");
        assertThat(kinds(member, setup)).doesNotContain("SPEND_SYNC");

        // Read again: the item goes with the failure.
        litellm.logSpend(setup.key().token, SONNET, List.of("run:" + run.runId()), Instant.now(), 0.01, 10, 5, 0);
        due(setup.companyId(), run.runId(), Duration.ofMinutes(5));
        settle();
        assertThat(settlement(setup.companyId(), run.runId()).get("state")).isEqualTo("SETTLED");
        assertThat(kinds(admin, setup)).doesNotContain("SPEND_SYNC");
    }

    @Test
    void spendLogsLargerThanTheServerReadsAreRetriedWithTheirReason() {
        Setup setup = setUp();
        Relayed run = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        answerAnthropicStream(10, 0, 0, 5);
        post(MESSAGES, run.token(), SONNET);
        end(setup.companyId(), run.runId());
        due(setup.companyId(), run.runId(), Duration.ofMinutes(3));
        // The gateway declares more than the 64 MiB an admin answer may have: refused before it is read.
        litellm.answerLargeNext("/spend/logs", 64L * 1024 * 1024 + 1);
        settle();
        Map<String, Object> failed = settlement(setup.companyId(), run.runId());
        assertThat(failed.get("state")).isEqualTo("PENDING");
        assertThat((String) failed.get("last_error")).contains("larger than the 64 MiB Tinfirm reads");

        // Read again within the limit: settled.
        litellm.logSpend(setup.key().token, SONNET, List.of("run:" + run.runId()), Instant.now(), 0.01, 10, 5, 0);
        due(setup.companyId(), run.runId(), Duration.ofMinutes(5));
        settle();
        assertThat(settlement(setup.companyId(), run.runId()).get("state")).isEqualTo("SETTLED");
    }

    @Test
    void aSubscriptionsRunSettlesAtOnceWithItsReportedTokensAndNoCost() {
        Setup setup = setUp();
        UUID runId = UUID.randomUUID();
        TestRuns.running(setup.companyId(), runId, 1, setup.hostId(), setup.payer());
        sql(setup.companyId(), """
                UPDATE run SET model_access = 'SUBSCRIPTION', subscription_label = 'claude-max', model = 'opus',
                               input_tokens = 500, output_tokens = 60, cached_input_tokens = 7, cache_write_tokens = 3,
                               cost_usd = 1.5
                 WHERE id = ?""", runId);
        end(setup.companyId(), runId);

        Map<String, Object> row = spend(setup.companyId(), runId).getFirst();
        assertThat(row.get("source")).isEqualTo("SUBSCRIPTION");
        assertThat(row.get("model")).isEqualTo("opus");
        assertThat(row.get("input_tokens")).isEqualTo(500L);
        assertThat(row.get("output_tokens")).isEqualTo(60L);
        // The sandbox's report sets no cost (G2).
        assertThat(row.get("cost_usd")).isNull();
        assertThat(settlement(setup.companyId(), runId).get("state")).isEqualTo("SETTLED");
    }

    @Test
    void aRunThatRelayedNothingGetsNoSettlement() {
        Setup setup = setUp();
        Relayed run = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        end(setup.companyId(), run.runId());
        assertThat(settlement(setup.companyId(), run.runId())).isEmpty();
        assertThat(spend(setup.companyId(), run.runId())).isEmpty();
    }

    @Test
    void twoWorkersReadEachKeysLogsOnceAndSettleEachRunOnce() throws Exception {
        Setup setup = setUp();
        answerAnthropicStream(10, 0, 0, 5);
        List<UUID> runIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Relayed run = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
            post(MESSAGES, run.token(), SONNET);
            litellm.logSpend(setup.key().token, SONNET, List.of("run:" + run.runId()), Instant.now(), 0.01, 10, 5,
                    0);
            end(setup.companyId(), run.runId());
            runIds.add(run.runId());
        }
        runIds.forEach(runId -> due(setup.companyId(), runId, Duration.ofMinutes(3)));
        String logsOfKey = "/spend/logs?api_key=" + setup.key().token;
        int before = litellm.requests(logsOfKey).size();

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> both = List.of(this::settle, this::settle);
            for (Future<Integer> result : workers.invokeAll(both)) {
                result.get();
            }
        }
        finally {
            workers.shutdownNow();
        }

        for (UUID runId : runIds) {
            assertThat(spend(setup.companyId(), runId)).singleElement()
                .satisfies(row -> assertThat(row.get("requests")).isEqualTo(1L));
        }
        assertThat(litellm.requests(logsOfKey).size() - before).as("the key's logs were read once").isEqualTo(1);
    }

    @Test
    void theCompanysMonthlyAmountTellsAdminsOnce() {
        Setup setup = setUp();
        ApiResponseAssert.ok(setup.owner().put(setup.company().path("/model-settings"),
                Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 50, "monthlyAlertUsd", 0.5)));
        Relayed run = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        answerAnthropicStream(10, 0, 0, 5);
        post(MESSAGES, run.token(), SONNET);
        litellm.logSpend(setup.key().token, SONNET, List.of("run:" + run.runId()), Instant.now(), 0.75, 10, 5, 0);
        ApiClient admin = loginAs(setup.company().email("ADMIN"));
        assertThat(kinds(admin, setup)).doesNotContain("COMPANY_SPEND");

        end(setup.companyId(), run.runId());
        due(setup.companyId(), run.runId(), Duration.ofMinutes(3));
        settle();

        JsonNode item = items(admin, setup, "COMPANY_SPEND").getFirst();
        assertThat(item.get("companySpend").get("totalUsd").decimalValue()).isEqualByComparingTo("0.75");
        assertThat(item.get("companySpend").get("alertUsd").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(kinds(loginAs(setup.company().email("MEMBER")), setup)).doesNotContain("COMPANY_SPEND");
        assertThat(rows(setup.companyId(), "SELECT * FROM company_spend_alert WHERE company_id = ?",
                setup.companyId())).hasSize(1);
    }

    /**
     * A run's end sees no request while its only answer is still under way (the relay counts a request after its
     * answer ended): the count, which comes after the end, gives the run its settlement (§15.12, step 1), and the run
     * settles from the logs as any other.
     */
    @Test
    void aRequestCountedAfterTheRunEndedStillSettlesIt() throws Exception {
        Setup setup = setUp();
        litellm.price(SONNET, 0.000003, 0.000015);
        Relayed run = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        CountDownLatch streaming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        litellm.onModel((exchange, request, key) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"" + SONNET
                        + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
                out.flush();
                streaming.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                out.write("event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            }
        });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> answer = executor.submit(() -> post(MESSAGES, run.token(), SONNET));
            assertThat(streaming.await(30, TimeUnit.SECONDS)).as("the answer is under way").isTrue();

            // The run ends while the answer streams: its end counts no request, so it gets no settlement.
            end(setup.companyId(), run.runId());
            assertThat(settlement(setup.companyId(), run.runId())).isEmpty();

            release.countDown();
            answer.get(30, TimeUnit.SECONDS);
        }
        Map<String, Object> pending = Map.of();
        for (int i = 0; i < 100 && pending.isEmpty(); i++) {
            pending = settlement(setup.companyId(), run.runId());
            if (pending.isEmpty()) {
                Thread.sleep(100);
            }
        }
        assertThat(pending.get("state")).as("the late count gave the run its settlement").isEqualTo("PENDING");
        assertThat(transactions.inCompany(setup.companyId(), () -> relay.usage(setup.companyId(), run.runId()))
            .requests()).isEqualTo(1);

        litellm.logSpend(setup.key().token, SONNET, List.of("run:" + run.runId()), Instant.now(), 0.01, 10, 5, 0);
        due(setup.companyId(), run.runId(), Duration.ofMinutes(3));
        settle();
        List<Map<String, Object>> rows = spend(setup.companyId(), run.runId());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("source")).isEqualTo("GATEWAY");
        assertThat(rows.getFirst().get("requests")).isEqualTo(1L);
        assertThat(settlement(setup.companyId(), run.runId()).get("state")).isEqualTo("SETTLED");

        // The run's end heard again, or another late count, changes nothing.
        end2(setup.companyId(), run.runId());
        assertThat(settlement(setup.companyId(), run.runId()).get("state")).isEqualTo("SETTLED");
        assertThat(spend(setup.companyId(), run.runId())).hasSize(1);
    }

    @Test
    void aRunThatEndedWhileASecondAnswerWasUnderWayIsSettledOnceWithBoth() throws Exception {
        Setup setup = setUp();
        litellm.price(SONNET, 0.000003, 0.000015);
        Relayed run = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        AtomicInteger answers = new AtomicInteger();
        CountDownLatch streaming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        litellm.onModel((exchange, request, key) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"" + SONNET
                        + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (answers.incrementAndGet() == 2) {
                    // The second answer streams until the test lets it end.
                    streaming.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                out.write("event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            }
        });
        post(MESSAGES, run.token(), SONNET);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> second = executor.submit(() -> post(MESSAGES, run.token(), SONNET));
            assertThat(streaming.await(30, TimeUnit.SECONDS)).as("the second answer is under way").isTrue();

            // The run ends while the second answer streams: its end counts the first request, and the settlement
            // waits for the second answer rather than settle without it.
            end(setup.companyId(), run.runId());
            assertThat(settlement(setup.companyId(), run.runId()).get("state")).isEqualTo("PENDING");
            assertThat(transactions.inCompany(setup.companyId(),
                    () -> relay.answersUnderWay(setup.companyId(), run.runId()))).isEqualTo(1);
            litellm.logSpend(setup.key().token, SONNET, List.of("run:" + run.runId()), Instant.now(), 0.01, 10, 5, 0);
            due(setup.companyId(), run.runId(), Duration.ofMinutes(3));
            settle();
            Map<String, Object> waiting = settlement(setup.companyId(), run.runId());
            assertThat(waiting.get("state")).isEqualTo("PENDING");
            assertThat(waiting.get("attempts")).isEqualTo(1);
            assertThat(waiting.get("last_error")).isNull();
            assertThat(spend(setup.companyId(), run.runId())).isEmpty();

            release.countDown();
            second.get(30, TimeUnit.SECONDS);
        }
        // The late count makes the settlement due again, two minutes on.
        Instant dueAgain = null;
        for (int i = 0; i < 100 && dueAgain == null; i++) {
            Map<String, Object> deferred = settlement(setup.companyId(), run.runId());
            Instant dueAt = ((java.sql.Timestamp) deferred.get("due_at")).toInstant();
            if (dueAt.isAfter(Instant.now().plus(Duration.ofSeconds(90)))) {
                dueAgain = dueAt;
            }
            else {
                Thread.sleep(100);
            }
        }
        assertThat(dueAgain).as("the late count made the settlement due again").isNotNull();
        assertThat(transactions.inCompany(setup.companyId(),
                () -> relay.answersUnderWay(setup.companyId(), run.runId()))).isZero();

        litellm.logSpend(setup.key().token, SONNET, List.of("run:" + run.runId()), Instant.now(), 0.02, 10, 5, 0);
        due(setup.companyId(), run.runId(), Duration.ofMinutes(3));
        settle();
        List<Map<String, Object>> rows = spend(setup.companyId(), run.runId());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("source")).isEqualTo("GATEWAY");
        assertThat(rows.getFirst().get("requests")).isEqualTo(2L);
        assertThat((BigDecimal) rows.getFirst().get("cost_usd")).as("both answers, once").isEqualByComparingTo("0.03");
        assertThat(settlement(setup.companyId(), run.runId()).get("state")).isEqualTo("SETTLED");

        // Another late count, or the end heard again, changes nothing once it is settled.
        end2(setup.companyId(), run.runId());
        settle();
        assertThat(settlement(setup.companyId(), run.runId()).get("state")).isEqualTo("SETTLED");
        assertThat(spend(setup.companyId(), run.runId())).hasSize(1);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** The run's end heard a second time (a loss by lease expiry, then resumed), after it settled. */
    private void end2(UUID companyId, UUID runId) {
        transactions.runInCompany(companyId, () -> publisher.publishEvent(new RunEvents.RunEnded(companyId, runId,
                UUID.randomUUID(), RunStatus.SUCCEEDED)));
    }

    static List<String> kinds(ApiClient client, Setup setup) {
        List<String> kinds = new ArrayList<>();
        client.get(setup.company().path("/inbox")).json().get("items")
            .forEach(item -> kinds.add(item.get("kind").asString()));
        return kinds;
    }

    static List<JsonNode> items(ApiClient client, Setup setup, String kind) {
        List<JsonNode> items = new ArrayList<>();
        client.get(setup.company().path("/inbox")).json().get("items").forEach(item -> {
            if (item.get("kind").asString().equals(kind)) {
                items.add(item);
            }
        });
        assertThat(items).as("inbox items of kind %s", kind).isNotEmpty();
        return items;
    }

    /** Small assertions on API answers. */
    static final class ApiResponseAssert {

        private ApiResponseAssert() {
        }

        static void ok(com.flowtivo.tinfirm.support.ApiResponse response) {
            assertThat(response.status()).as("%s", response).isEqualTo(200);
        }
    }
}
