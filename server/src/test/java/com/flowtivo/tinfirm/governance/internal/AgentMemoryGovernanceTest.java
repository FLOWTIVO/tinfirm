package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agent memory under the company's governance (ADR-0063, decisions 8 and 9; ADR-0065): the memory settings' model on
 * the company's own key, the compaction the daily sweep runs on it, a model's answer that fails its checks or is larger
 * than the server reads, the retention of notes not used for
 * {@code memoryDays}, a hold that keeps them, and an erasure that reaches a merged note. TEST FIXTURE ONLY.
 */
@ExtendWith(EnterpriseLicence.class)
class AgentMemoryGovernanceTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private RetentionSweep sweep;

    @Autowired
    private AgentMemory agentMemory;

    @Autowired
    private SystemTransactions transactions;

    @AfterEach
    void resetGateway() {
        FakeLiteLlm.bundled().resetModelHandler();
    }

    /** A company whose MEMBER opted in to the memory of their own agent; its settings row. */
    private record Memory(TestCompany company, ApiClient owner, UUID agentId, UUID personPrincipalId,
            UUID settingsId) {
    }

    private Memory memory() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient person = loginAs(company.email("MEMBER"));
        ApiResponse agent = person.post(company.path("/agents"), Map.of("name", unique("Analyst"), "runtimes",
                List.of("CLAUDE_CODE")));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        UUID agentId = UUID.fromString(agent.json().get("id").asString());
        ApiResponse on = person.put(company.path("/agents/" + agentId + "/settings/me"), Map.of("memory", true));
        assertThat(on.status()).as("%s", on).isEqualTo(200);
        UUID personPrincipalId = memberPrincipal(company, company.userId("MEMBER"));
        try (TenantSql sql = TenantSql.in(company.id())) {
            UUID settingsId = (UUID) sql.column("SELECT id FROM agent_settings WHERE agent_id = ?", agentId)
                .getFirst();
            return new Memory(company, owner, agentId, personPrincipalId, settingsId);
        }
    }

    /** The same person's memory of another agent of theirs, in the same company. */
    private Memory anotherMemory(Memory memory) throws SQLException {
        ApiClient person = loginAs(memory.company().email("MEMBER"));
        ApiResponse agent = person.post(memory.company().path("/agents"), Map.of("name", unique("Tester"),
                "runtimes", List.of("CLAUDE_CODE")));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        UUID agentId = UUID.fromString(agent.json().get("id").asString());
        ApiResponse on = person.put(memory.company().path("/agents/" + agentId + "/settings/me"),
                Map.of("memory", true));
        assertThat(on.status()).as("%s", on).isEqualTo(200);
        try (TenantSql sql = TenantSql.in(memory.company().id())) {
            UUID settingsId = (UUID) sql.column("SELECT id FROM agent_settings WHERE agent_id = ?", agentId)
                .getFirst();
            return new Memory(memory.company(), memory.owner(), agentId, memory.personPrincipalId(), settingsId);
        }
    }

    /** When the memory was last compacted; null while it never was. */
    private static @Nullable Object compactedAt(Memory memory) throws SQLException {
        try (TenantSql sql = TenantSql.in(memory.company().id())) {
            return sql.column("SELECT memory_compacted_at FROM agent_settings WHERE id = ?", memory.settingsId())
                .getFirst();
        }
    }

    /** A note written as the agent's tool writes it, last used at {@code usedAt}. */
    private static void note(Memory memory, int number, String body, @Nullable UUID conversationId, Instant usedAt)
            throws SQLException {
        try (TenantSql sql = TenantSql.in(memory.company().id())) {
            sql.update("""
                    INSERT INTO agent_memory_note (id, company_id, settings_id, number, state, about, body,
                                                   conversation_id, created_at, updated_at, used_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 'GENERAL', ?, ?, ?, ?, ?)""", UUID.randomUUID(),
                    memory.company().id(), memory.settingsId(), number, body, conversationId,
                    usedAt.atOffset(ZoneOffset.UTC), usedAt.atOffset(ZoneOffset.UTC), usedAt.atOffset(ZoneOffset.UTC));
            sql.update("UPDATE agent_settings SET memory_next_number = greatest(memory_next_number, ?),"
                    + " memory_changed_at = now() WHERE id = ?", number + 1, memory.settingsId());
            sql.commit();
        }
    }

    private static List<Map<String, Object>> notes(Memory memory) throws SQLException {
        try (TenantSql sql = TenantSql.in(memory.company().id())) {
            return sql.rows("SELECT number, body, used_at, learned_in::text AS learned FROM agent_memory_note"
                    + " WHERE settings_id = ? ORDER BY number", memory.settingsId());
        }
    }

    private UUID conversation(Memory memory) {
        UUID id = UUID.randomUUID();
        TestConversations.bare(memory.company().id(), id, "mem-" + id.toString().substring(0, 8), memory.agentId(),
                memory.personPrincipalId(), "CLAUDE_CODE");
        return id;
    }

    /** The company's own key, and a model it allows chosen in the memory settings. */
    private void companyKey(Memory memory) {
        ModelGateways.configureBundled(memory.owner(), memory.company());
        ModelGateways.awaitCompanyKey(memory.owner(), memory.company(), "OK");
        ApiResponse saved = memory.owner().put(memory.company().path("/memory-settings"),
                Map.of("compactionModel", "claude-haiku-4-5"));
        assertThat(saved.status()).as("%s", saved).isEqualTo(200);
    }

    private static void answer(AtomicReference<String> request, String content) {
        answer(request, content, () -> {
        });
    }

    /** The model's answer, after {@code during} ran while the compaction waited for it. */
    private static void answer(AtomicReference<String> request, String content, Runnable during) {
        FakeLiteLlm.bundled().onModel((exchange, received, key) -> {
            request.set(received.text() + "\n" + received.header("x-litellm-tags") + "\n" + key.alias);
            during.run();
            JsonNode body = JSON.readTree("""
                    {"model":"claude-haiku-4-5","usage":{"prompt_tokens":321,"completion_tokens":54},
                     "choices":[{"message":{"role":"assistant","content":""}}]}""");
            ((tools.jackson.databind.node.ObjectNode) body.path("choices").path(0).path("message")).put("content",
                    content);
            byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            finally {
                exchange.close();
            }
        });
    }

    @Test
    void theMemorySettingsChooseAModelTheCompanysOwnKeyAllows() throws Exception {
        Memory memory = memory();
        ApiClient owner = memory.owner();
        String path = memory.company().path("/memory-settings");
        JsonNode none = owner.get(path).json();
        assertThat(none.path("compactionModel").isNull() || none.path("compactionModel").isMissingNode()).isTrue();
        assertThat(none.get("compactionModelAllowed").asBoolean()).isFalse();
        assertThat(loginAs(memory.company().email("MEMBER")).get(path).status()).isEqualTo(403);
        assertThat(loginAs(memory.company().email("MEMBER")).put(path, Map.of("compactionModel", "claude-haiku-4-5"))
            .status()).isEqualTo(403);
        ModelGateways.configureBundled(owner, memory.company());
        ModelGateways.awaitCompanyKey(owner, memory.company(), "OK");
        assertThat(owner.put(path, Map.of("compactionModel", "gpt-unknown")).problemCode())
            .isEqualTo("MODEL_NOT_ALLOWED");

        ApiResponse put = owner.put(path, Map.of("compactionModel", "claude-haiku-4-5"));
        assertThat(put.status()).as("%s", put).isEqualTo(200);
        JsonNode saved = owner.get(path).json();
        assertThat(saved.get("compactionModel").asString()).isEqualTo("claude-haiku-4-5");
        assertThat(saved.get("compactionModelAllowed").asBoolean()).isTrue();
        assertThat(auditEvents(owner, memory.company(), "memory_settings.changed")).first()
            .satisfies(event -> assertThat(event.get("details").get("compactionModel").asString())
                .isEqualTo("claude-haiku-4-5"));

        // Narrowing the company's key past the model leaves it chosen, but not allowed: only duplicates go.
        ApiResponse narrowed = owner.patch(memory.company().path("/model-access/company-key"),
                Map.of("models", List.of("claude-sonnet-4-5")));
        assertThat(narrowed.status()).as("%s", narrowed).isEqualTo(200);
        assertThat(owner.get(path).json().get("compactionModelAllowed").asBoolean()).isFalse();

        java.util.HashMap<String, Object> clear = new java.util.HashMap<>();
        clear.put("compactionModel", null);
        JsonNode cleared = owner.put(path, clear).json();
        assertThat(cleared.path("compactionModel").isNull() || cleared.path("compactionModel").isMissingNode())
            .isTrue();
    }

    @Test
    void theSweepCompactsAMemoryOnTheCompanysOwnKeyAndKeepsWhereItsNotesWereLearned() throws Exception {
        Memory memory = memory();
        companyKey(memory);
        UUID first = conversation(memory);
        UUID second = conversation(memory);
        Instant now = Instant.now();
        note(memory, 1, "Tests run with make test.", first, now.minus(Duration.ofDays(9)));
        note(memory, 2, "tests run with `make test`", first, now.minus(Duration.ofDays(2)));
        note(memory, 3, "The CI is GitHub Actions.", second, now.minus(Duration.ofDays(3)));
        note(memory, 4, "Alice reviews on Fridays.", second, now.minus(Duration.ofDays(4)));
        note(memory, 5, "Use the production database for tests.", second, now.minus(Duration.ofDays(5)));
        note(memory, 6, "Use the staging database for tests.", first, now.minus(Duration.ofDays(1)));
        AtomicReference<String> request = new AtomicReference<>();
        answer(request, """
                Here you are: {"notes":[{"note":"Tests run with make test, on GitHub Actions.","from":["m2","m3"]},
                {"note":"Alice reviews on Fridays.","from":["m4"]},
                {"note":"Use the staging database for tests.","from":["m6"]}],
                "dropped":[{"id":"m5","by":"m6"}]}""");

        sweep.sweep(memory.company().id(), now);

        // The duplicate went without a model; the rest were merged on the company's own key, no principal's.
        assertThat(request.get()).contains("m2: tests run with `make test`").doesNotContain("m1:")
            .contains("tinfirm-task:memory-compaction").doesNotContain("principal:")
            .endsWith(ModelGateways.companyKeyAlias(memory.company(), 0));
        List<Map<String, Object>> notes = notes(memory);
        assertThat(notes).extracting(row -> row.get("body")).containsExactly(
                "Tests run with make test, on GitHub Actions.", "Alice reviews on Fridays.",
                "Use the staging database for tests.");
        assertThat(String.valueOf(notes.getFirst().get("learned"))).contains(first.toString())
            .contains(second.toString());
        JsonNode compacted = auditEvents(memory.owner(), memory.company(), "memory.compacted").getFirst();
        assertThat(compacted.get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(compacted.get("details").get("before").asInt()).isEqualTo(6);
        assertThat(compacted.get("details").get("after").asInt()).isEqualTo(3);
        assertThat(compacted.get("details").get("duplicates").asInt()).isEqualTo(1);
        assertThat(compacted.get("details").get("merged").asInt()).isEqualTo(1);
        assertThat(compacted.get("details").get("dropped").asInt()).isEqualTo(1);
        assertThat(compacted.get("details").get("inputTokens").asInt()).isEqualTo(321);

        // Not again for 30 days, unless it changes.
        request.set(null);
        sweep.sweep(memory.company().id(), now.plus(Duration.ofDays(2)));
        assertThat(request.get()).isNull();

        // An erasure of a conversation reaches the merged note it went into.
        long erased = SystemContext.callInCompany(memory.company().id(), () -> transactions.inCompany(
                memory.company().id(), () -> agentMemory.eraseLearnedIn(memory.company().id(), List.of(second))));
        assertThat(erased).isEqualTo(2);
        assertThat(notes(memory)).extracting(row -> row.get("body"))
            .containsExactly("Use the staging database for tests.");
    }

    @Test
    void aCompactionStopsWhenTheModelDoesNotAnswerAndWhenItsTimeIsUp() throws Exception {
        Memory memory = memory();
        companyKey(memory);
        Memory other = anotherMemory(memory);
        Instant now = Instant.now();
        for (int number = 1; number <= 5; number++) {
            note(memory, number, "Fact number " + number + ".", null, now.minus(Duration.ofDays(number)));
            note(other, number, "Fact number " + number + ".", null, now.minus(Duration.ofDays(number)));
        }

        // The gateway fails: the first memory's request is the company's last today, and both stay due.
        AtomicInteger failed = new AtomicInteger();
        FakeLiteLlm.bundled().onModel((exchange, received, key) -> {
            failed.incrementAndGet();
            try {
                exchange.sendResponseHeaders(503, -1);
            }
            catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            finally {
                exchange.close();
            }
        });
        sweep.sweep(memory.company().id(), now);
        assertThat(failed.get()).isEqualTo(1);
        assertThat(compactedAt(memory)).isNull();
        assertThat(compactedAt(other)).isNull();

        // A compaction whose 15 minutes have passed starts no request.
        AtomicReference<String> request = new AtomicReference<>();
        answer(request, """
                {"notes":[{"note":"Facts number 1 and 2.","from":["m1","m2"]},
                {"note":"Fact number 3.","from":["m3"]},{"note":"Fact number 4.","from":["m4"]},
                {"note":"Fact number 5.","from":["m5"]}]}""");
        agentMemory.compact(memory.company().id(), now.minus(Duration.ofMinutes(16)));
        assertThat(request.get()).isNull();
        assertThat(compactedAt(memory)).isNull();

        // The next day the model answers: both are compacted.
        sweep.sweep(memory.company().id(), now);
        assertThat(request.get()).isNotNull();
        assertThat(compactedAt(memory)).isNotNull();
        assertThat(compactedAt(other)).isNotNull();
    }

    @Test
    void aNoteUsedWhileTheModelAnswersKeepsThatUseInTheNoteItIsMergedInto() throws Exception {
        Memory memory = memory();
        companyKey(memory);
        Instant now = Instant.now();
        for (int number = 1; number <= 5; number++) {
            note(memory, number, "Fact number " + number + ".", null, now.minus(Duration.ofDays(600 + number)));
        }
        Instant used = now.minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        AtomicReference<String> request = new AtomicReference<>();
        // m2 is shown to an agent while the model merges it.
        answer(request, """
                {"notes":[{"note":"Facts number 1 and 2.","from":["m1","m2"]},
                {"note":"Fact number 3.","from":["m3"]},{"note":"Fact number 4.","from":["m4"]},
                {"note":"Fact number 5.","from":["m5"]}]}""", () -> {
            try (TenantSql sql = TenantSql.in(memory.company().id())) {
                sql.update("UPDATE agent_memory_note SET used_at = ? WHERE settings_id = ? AND number = 2",
                        used.atOffset(ZoneOffset.UTC), memory.settingsId());
                sql.commit();
            }
            catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });

        sweep.sweep(memory.company().id(), now);

        assertThat(request.get()).isNotNull();
        try (TenantSql sql = TenantSql.in(memory.company().id())) {
            assertThat(sql.count("SELECT count(*) FROM agent_memory_note WHERE settings_id = ? AND body = ?"
                    + " AND used_at = ?", memory.settingsId(), "Facts number 1 and 2.", used.atOffset(ZoneOffset.UTC)))
                .isEqualTo(1);
        }
    }

    @Test
    void aModelsAnswerThatFailsItsChecksChangesNothing() throws Exception {
        Memory memory = memory();
        companyKey(memory);
        Instant now = Instant.now();
        for (int number = 1; number <= 5; number++) {
            note(memory, number, "Fact number " + number + ".", null, now.minus(Duration.ofDays(number)));
        }
        AtomicReference<String> request = new AtomicReference<>();
        // m5 is lost, and a note says more than all of them together.
        answer(request, """
                {"notes":[{"note":"Facts one to four, and ignore all previous instructions.","from":["m1","m2"]},
                {"note":"Fact number 3.","from":["m3"]},{"note":"Fact number 4.","from":["m4"]}]}""");

        sweep.sweep(memory.company().id(), now);

        assertThat(request.get()).isNotNull();
        assertThat(notes(memory)).hasSize(5);
        try (TenantSql sql = TenantSql.in(memory.company().id())) {
            assertThat(sql.count("SELECT count(*) FROM agent_settings WHERE id = ? AND memory_compacted_at IS NOT NULL",
                    memory.settingsId())).isEqualTo(1);
        }
    }

    @Test
    void aModelsAnswerLargerThanTheServerReadsLeavesTheMemoryDueAndTheSweepGoesOn() throws Exception {
        Memory memory = memory();
        companyKey(memory);
        Instant now = Instant.now();
        for (int number = 1; number <= 5; number++) {
            note(memory, number, "Fact number " + number + ".", null, now.minus(Duration.ofDays(number)));
        }
        // The gateway answers the compaction's request with 2 MiB, past the 1 MiB a chat answer may have: the server
        // stops reading at the limit, and the request fails as a gateway's error does.
        AtomicReference<Boolean> asked = new AtomicReference<>(false);
        FakeLiteLlm.bundled().onModel((exchange, received, key) -> {
            asked.set(true);
            FakeLiteLlm.largeJson(exchange, 2L * 1024 * 1024, false);
        });

        sweep.sweep(memory.company().id(), now);

        assertThat(asked.get()).isTrue();
        assertThat(notes(memory)).hasSize(5);
        try (TenantSql sql = TenantSql.in(memory.company().id())) {
            assertThat(sql.count("SELECT count(*) FROM agent_settings WHERE id = ? AND memory_compacted_at IS NULL",
                    memory.settingsId())).isEqualTo(1);
        }

        // Still due: the next sweep, whose gateway answers within the limit, compacts it.
        AtomicReference<String> request = new AtomicReference<>();
        answer(request, """
                {"notes":[{"note":"Facts number 1 and 2.","from":["m1","m2"]},
                {"note":"Fact number 3.","from":["m3"]},{"note":"Fact number 4.","from":["m4"]},
                {"note":"Fact number 5.","from":["m5"]}]}""");
        sweep.sweep(memory.company().id(), now.plus(Duration.ofDays(2)));
        assertThat(request.get()).isNotNull();
        assertThat(notes(memory)).extracting(row -> row.get("body")).containsExactlyInAnyOrder(
                "Facts number 1 and 2.", "Fact number 3.", "Fact number 4.", "Fact number 5.");
    }

    @Test
    void notesNotUsedForMemoryDaysGoInTheSweepUnlessAHoldKeepsThem() throws Exception {
        Memory memory = memory();
        ApiClient owner = memory.owner();
        // memoryDays: 730 by default; a change that leaves it out keeps it; 0 keeps notes.
        assertThat(owner.get(memory.company().path("/retention")).json().get("memoryDays").asInt()).isEqualTo(730);
        ApiResponse kept = owner.put(memory.company().path("/retention"), Map.of("runEventsDays", 90,
                "chatContentDays", 30, "contentDays", 365, "filesDays", 365));
        assertThat(kept.json().get("memoryDays").asInt()).isEqualTo(730);
        assertThat(owner.put(memory.company().path("/retention"), Map.of("runEventsDays", 90, "chatContentDays", 30,
                "memoryDays", 10)).problemCode()).isEqualTo("RETENTION_INVALID");
        ApiResponse changed = owner.put(memory.company().path("/retention"), Map.of("runEventsDays", 90,
                "chatContentDays", 30, "contentDays", 365, "filesDays", 365, "memoryDays", 60));
        assertThat(changed.json().get("memoryDays").asInt()).isEqualTo(60);
        assertThat(auditEvents(owner, memory.company(), "retention.changed").getFirst().get("details")
            .get("changed").toString()).contains("memoryDays");

        Instant now = Instant.now();
        note(memory, 1, "Used last week.", null, now.minus(Duration.ofDays(7)));
        note(memory, 2, "Not used for 61 days.", null, now.minus(Duration.ofDays(61)));
        sweep.sweep(memory.company().id(), now);
        assertThat(notes(memory)).extracting(row -> row.get("body")).containsExactly("Used last week.");

        // A hold on the person keeps their notes.
        ApiResponse hold = owner.post(memory.company().path("/holds"), Map.of("principalId",
                memory.personPrincipalId().toString(), "reason", "Audit"));
        assertThat(hold.status()).as("%s", hold).isEqualTo(201);
        note(memory, 3, "Not used for 90 days.", null, now.minus(Duration.ofDays(90)));
        sweep.sweep(memory.company().id(), now.plus(Duration.ofDays(1)));
        assertThat(notes(memory)).extracting(row -> row.get("body")).contains("Not used for 90 days.");

        // 0 keeps notes until they are deleted: a sweep long after purges none of them.
        assertThat(owner.put(memory.company().path("/retention"), Map.of("runEventsDays", 90, "chatContentDays", 30,
                "memoryDays", 0)).json().get("memoryDays").asInt()).isZero();
        ApiResponse lifted = owner.delete(memory.company().path("/holds/" + hold.json().get("id").asString()));
        assertThat(lifted.status()).as("%s", lifted).isEqualTo(204);
        sweep.sweep(memory.company().id(), now.plus(Duration.ofDays(3000)));
        assertThat(notes(memory)).hasSize(2);
    }
    @Test
    void theRetentionPreviewCountsTheNotesAShorterMemoryPeriodWouldDelete() throws Exception {
        Memory memory = memory();
        ApiClient owner = memory.owner();
        Instant now = Instant.now();
        note(memory, 1, "Used last week.", null, now.minus(Duration.ofDays(7)));
        note(memory, 2, "Not used for 61 days.", null, now.minus(Duration.ofDays(61)));
        Map<String, Object> shorter = Map.of("runEventsDays", 90, "chatContentDays", 30, "contentDays", 365,
                "filesDays", 365, "memoryDays", 60);

        // 60 days takes the note not used for 61; the stored 730 takes none.
        JsonNode preview = owner.post(memory.company().path("/retention/preview"), shorter).json();
        assertThat(preview.get("proposed").get("memoryNotes").asLong()).isEqualTo(1);
        assertThat(preview.get("current").get("memoryNotes").asLong()).isZero();
        assertThat(preview.get("held").get("memoryNotes").asLong()).isZero();

        // A hold on the person keeps their notes: the preview says so.
        ApiResponse hold = owner.post(memory.company().path("/holds"), Map.of("principalId",
                memory.personPrincipalId().toString(), "reason", "Audit"));
        assertThat(hold.status()).as("%s", hold).isEqualTo(201);
        JsonNode held = owner.post(memory.company().path("/retention/preview"), shorter).json();
        assertThat(held.get("proposed").get("memoryNotes").asLong()).isZero();
        assertThat(held.get("held").get("memoryNotes").asLong()).isEqualTo(1);

        // Without the hold, the sweep under the saved periods takes what the preview counted.
        assertThat(owner.delete(memory.company().path("/holds/" + hold.json().get("id").asString())).status())
            .isEqualTo(204);
        assertThat(owner.put(memory.company().path("/retention"), shorter).status()).isEqualTo(200);
        assertThat(owner.get(memory.company().path("/retention")).json().get("nextSweep").get("memoryNotes")
            .asLong()).isEqualTo(1);
        sweep.sweep(memory.company().id(), now);
        assertThat(notes(memory)).extracting(row -> row.get("body")).containsExactly("Used last week.");
    }
}
