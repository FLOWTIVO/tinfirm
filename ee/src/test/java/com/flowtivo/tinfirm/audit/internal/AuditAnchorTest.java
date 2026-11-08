package com.flowtivo.tinfirm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit chains anchored outside the database (ADR-0111), with the {@code file} sink in a directory of the test's:
 * the writer signs a checkpoint of the heads that moved and writes it to the sink; a chain that whoever writes the
 * database rewrote and recomputed, or cut, then fails the daily check ({@code ANCHOR_MISMATCH}), the verification on
 * demand and the offline tool, though it recomputes; a failing sink stops no audit event, is counted, and the next
 * write succeeds; Community writes nothing and its status is {@code 403 EDITION_REQUIRED}, Pro writes.
 */
@ExtendWith(EnterpriseLicence.class)
class AuditAnchorTest extends IntegrationTest {

    private static final KeyPair KEY = AnchorKeys.generate();

    private static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("audit-anchor-test");
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @DynamicPropertySource
    static void anchor(DynamicPropertyRegistry registry) {
        registry.add("tinfirm.audit.anchor.sink", () -> "file");
        registry.add("tinfirm.audit.anchor.file.dir", DIR::toString);
        registry.add("tinfirm.audit.anchor.active-key", () -> "k1test");
        // TEST FIXTURE ONLY: a key made for this run.
        registry.add("tinfirm.audit.anchor.keys.k1test.private",
                () -> AnchorKeys.pem("PRIVATE KEY", KEY.getPrivate().getEncoded()));
    }

    @Autowired
    private AuditAnchorService anchors;

    @Autowired
    private AuditCheckService checks;

    @Autowired
    private AuditCheckpointStore store;

    @Autowired
    private AuditEventStore events;

    @Autowired
    private SystemTransactions transactions;

    @Autowired
    private Licensing licensing;

    @Autowired
    private TimeSource time;

    @Autowired
    private TinfirmProperties tinfirm;

    @Autowired
    private AuditAnchorProperties properties;

    private UUID instance() {
        return transactions.instanceWide(store::state).orElseThrow().instanceId();
    }

    private Checkpoint.Signed checkpoint(long number) throws IOException {
        Path file = DIR.resolve(instance().toString()).resolve(AnchorSink.name(number));
        return Checkpoint.parse(Files.readString(file), AnchorKeys.verifyingOnly(Map.of("k1test", KEY.getPublic())));
    }

    private long latestNumber() {
        return transactions.instanceWide(store::latest).orElseThrow().number();
    }

    private long headSeq(UUID companyId) {
        return transactions.inCompany(companyId, () -> events.head(companyId)).orElseThrow().lastSeq();
    }

    @Test
    void aSignedCheckpointOfTheHeadsThatMovedGoesToTheSinkAndNothingWhenNoneMoved() throws IOException {
        TestCompany idle = companyWithRoles();
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));

        assertThat(anchors.write()).isIn(AuditAnchorService.Outcome.WRITTEN, AuditAnchorService.Outcome.UNCHANGED);
        long first = latestNumber();
        Checkpoint.Signed signed = checkpoint(first);
        assertThat(signed.verified()).as(signed.reason()).isTrue();
        assertThat(signed.checkpoint().keyId()).isEqualTo("k1test");
        assertThat(anchors.write()).isEqualTo(AuditAnchorService.Outcome.UNCHANGED);
        assertThat(latestNumber()).isEqualTo(first);

        owner.patch(company.path(""), Map.of("name", unique("Renamed")));
        assertThat(anchors.write()).isEqualTo(AuditAnchorService.Outcome.WRITTEN);

        Checkpoint.Signed next = checkpoint(first + 1);
        assertThat(next.verified()).isTrue();
        assertThat(next.checkpoint().prev()).isEqualTo(signed.checkpoint().digest());
        assertThat(next.checkpoint().chain(company.id()).orElseThrow().seq()).isEqualTo(headSeq(company.id()));
        // Only the heads that moved: the idle company's is in an earlier checkpoint, not this one.
        assertThat(next.checkpoint().chain(idle.id())).isEmpty();

        JsonNode verification = owner.get(company.path("/audit-events/verification")).json();
        assertThat(verification.get("valid").asBoolean()).isTrue();
        assertThat(verification.get("checkpoint").get("number").asLong()).isEqualTo(first + 1);
        assertThat(verification.get("checkpoint").get("seq").asLong()).isEqualTo(headSeq(company.id()));
        assertThat(verification.get("checkpoint").get("keyId").asString()).isEqualTo("k1test");
        JsonNode exportHeader = JsonMapper.builder().build().readTree(
                owner.get(company.path("/audit-events/export")).body().lines().findFirst().orElseThrow());
        assertThat(exportHeader.get("checkpoint").get("number").asLong()).isEqualTo(first + 1);

        JsonNode status = admin().get("/api/admin/audit-anchor").json();
        assertThat(status.get("inForce").asBoolean()).isTrue();
        assertThat(status.get("sink").asString()).isEqualTo("FILE");
        assertThat(status.get("activeKeyId").asString()).isEqualTo("k1test");
        assertThat(status.get("instanceId").asString()).isEqualTo(instance().toString());
        assertThat(status.get("publicKeys").get(0).get("publicKey").asString())
            .isEqualTo(AnchorKeys.publicPem(KEY.getPublic()));
        assertThat(status.get("lastCheckpoint").get("number").asLong()).isGreaterThanOrEqualTo(first + 1);
        assertThat(status.get("consecutiveFailures").asInt()).isZero();
        assertThat(status.toString()).doesNotContain("PRIVATE");
        assertThat(loginAs(company.email("OWNER")).get("/api/admin/audit-anchor").status()).isEqualTo(403);
    }

    @Test
    void aChainRewrittenAndRecomputedFailsTheDailyCheckTheVerificationAndTheTool() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        owner.patch(company.path(""), Map.of("name", unique("Before")));
        assertThat(anchors.write()).isEqualTo(AuditAnchorService.Outcome.WRITTEN);
        long number = latestNumber();
        long anchoredSeq = checkpoint(number).checkpoint().chain(company.id()).orElseThrow().seq();

        // Whoever writes the database changes the second event and recomputes every hash after it (and the daily
        // checks' rows, which hold the heads they verified, as well: no check ran here).
        rewrite(company.id(), 2);
        AuditChainVerifier recomputes = new AuditChainVerifier(events, new NoAnchors());
        assertThat(transactions.inCompany(company.id(), () -> recomputes.verify(company.id())).getValid()).isTrue();

        JsonNode verification = owner.get(company.path("/audit-events/verification")).json();
        assertThat(verification.get("valid").asBoolean()).isFalse();
        assertThat(verification.get("firstInvalidSeq").asLong()).isEqualTo(anchoredSeq);
        AuditCheckStore.CheckRow failed = checks.check(company.id()).orElseThrow();
        assertThat(failed.ok()).isFalse();
        assertThat(failed.failure()).isEqualTo("ANCHOR_MISMATCH");
        assertThat(failed.firstInvalidSeq()).isEqualTo(anchoredSeq);
        assertThat(failed.anchorCheckpoint()).isEqualTo(number);
        JsonNode listed = owner.get(company.path("/audit-events/checks")).json().get("items").get(0);
        assertThat(listed.get("failure").asString()).isEqualTo("ANCHOR_MISMATCH");
        assertThat(listed.get("anchorCheckpoint").asLong()).isEqualTo(number);

        Path publicKey = Files.writeString(DIR.resolve("k1test.pub"), AnchorKeys.publicPem(KEY.getPublic()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int status = AuditAnchorTool.run(new String[] { "verify", "--checkpoints",
                DIR.resolve(instance().toString()).toString(), "--public-key", "k1test=" + publicKey, "--jdbc-url",
                TestDatabase.jdbcUrl(), "--jdbc-user", TestDatabase.APP },
                new PrintStream(output, true, StandardCharsets.UTF_8),
                Map.of("TINFIRM_AUDIT_VERIFY_JDBC_PASSWORD", TestDatabase.APP_PASSWORD));
        String report = output.toString(StandardCharsets.UTF_8);
        assertThat(status).as(report).isEqualTo(1);
        assertThat(report).contains("chain " + company.id())
            .contains("MISMATCH at seq " + anchoredSeq + ": the hash differs from checkpoint " + number);
    }

    @Test
    void aChainCutBelowItsCheckpointFailsTheDailyCheck() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        owner.patch(company.path(""), Map.of("name", unique("Before")));
        assertThat(anchors.write()).isEqualTo(AuditAnchorService.Outcome.WRITTEN);
        long head = headSeq(company.id());
        String hashBefore = transactions.inCompany(company.id(), () -> events.at(company.id(), head - 2))
            .orElseThrow()
            .hash();

        // The newest two events removed and the head moved back: a valid chain, only shorter.
        tamper(company.id(), "DELETE FROM audit_event WHERE company_id = ? AND seq > " + (head - 2),
                "UPDATE audit_chain_head SET last_seq = " + (head - 2) + ", last_hash = '" + hashBefore
                        + "' WHERE company_id = ?");

        AuditCheckStore.CheckRow failed = checks.check(company.id()).orElseThrow();
        assertThat(failed.failure()).isEqualTo("ANCHOR_MISMATCH");
        assertThat(failed.firstInvalidSeq()).isEqualTo(head);
    }

    @Test
    void aFailingSinkStopsNoAuditEventIsCountedAndTheNextWriteSucceeds() throws IOException {
        AtomicBoolean failing = new AtomicBoolean(true);
        AnchorSink files = new FileAnchorSink(DIR);
        AnchorSink flaky = new AnchorSink() {
            @Override
            public String location() {
                return "flaky";
            }

            @Override
            public void checkAtStart() {
            }

            @Override
            public String write(UUID instance, long number, String document) throws IOException {
                if (failing.get()) {
                    throw new IOException("the store is down");
                }
                return files.write(instance, number, document);
            }

            @Override
            public boolean readable() {
                return true;
            }

            @Override
            public List<Long> numbers(UUID instance, long after) throws IOException {
                return files.numbers(instance, after);
            }

            @Override
            public Optional<Stored> read(UUID instance, long number) throws IOException {
                return files.read(instance, number);
            }
        };
        AuditAnchorService service = new AuditAnchorService(properties, flaky, store, events, transactions,
                licensing, time, tinfirm);
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        long before = latestNumber();

        assertThat(service.write()).isEqualTo(AuditAnchorService.Outcome.FAILED);
        AuditCheckpointStore.State state = transactions.instanceWide(store::state).orElseThrow();
        assertThat(state.consecutiveFailures()).isPositive();
        assertThat(state.lastFailure()).contains("the store is down");
        assertThat(latestNumber()).isEqualTo(before);
        // The audit goes on: a change is made and recorded while the sink fails.
        String name = unique("While down");
        ApiResponse renamed = owner.patch(company.path(""), Map.of("name", name));
        assertThat(renamed.status()).as("%s", renamed).isEqualTo(200);
        assertThat(auditEvents(owner, company, "company.updated")).isNotEmpty();
        // The next try waits for the retry, then succeeds and takes the next number.
        assertThat(service.writeIfDue()).isEqualTo(AuditAnchorService.Outcome.SKIPPED);
        assertThat(admin().get("/api/admin/audit-anchor").json().get("lastFailure").asString())
            .contains("the store is down");

        failing.set(false);
        assertThat(service.write()).isEqualTo(AuditAnchorService.Outcome.WRITTEN);
        assertThat(latestNumber()).isEqualTo(before + 1);
        assertThat(transactions.instanceWide(store::state).orElseThrow().consecutiveFailures()).isZero();
        assertThat(checkpoint(before + 1).checkpoint().chain(company.id()).orElseThrow().seq())
            .isEqualTo(headSeq(company.id()));
    }

    @Test
    void communityWritesNothingAndProWrites() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            TestLicences.clear();
            assertThat(anchors.write()).isEqualTo(AuditAnchorService.Outcome.SKIPPED);
            ApiResponse refused = admin().get("/api/admin/audit-anchor");
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.problemCode()).isEqualTo("EDITION_REQUIRED");
            assertThat(anchors.compare(null)).isEmpty();

            TestLicences.hold("PRO", 100_000, today.minusMonths(1), today.plusYears(1));
            assertThat(anchors.write()).isIn(AuditAnchorService.Outcome.WRITTEN,
                    AuditAnchorService.Outcome.UNCHANGED);
            assertThat(admin().get("/api/admin/audit-anchor").status()).isEqualTo(200);
        }
        finally {
            TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.plusYears(1));
        }
    }

    /** The anchor switched off, to show that the rewritten chain recomputes on its own. */
    private static final class NoAnchors implements AuditAnchors {

        @Override
        public Optional<Mismatch> compare(UUID companyId) {
            return Optional.empty();
        }

        @Override
        public Optional<Anchored> anchored(UUID companyId, Long atOrBeforeSeq) {
            return Optional.empty();
        }

        @Override
        public com.flowtivo.tinfirm.shared.api.model.AuditAnchorStatus status() {
            throw new UnsupportedOperationException();
        }
    }

    /** As the database's owner: changes one event and recomputes the chain from it, so that it verifies alone. */
    private static void rewrite(UUID companyId, long seq) throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner()) {
            owner.setAutoCommit(false);
            try (Statement ddl = owner.createStatement()) {
                ddl.execute("ALTER TABLE audit_event NO FORCE ROW LEVEL SECURITY");
                ddl.execute("ALTER TABLE audit_chain_head NO FORCE ROW LEVEL SECURITY");
                ddl.execute("ALTER TABLE audit_chain_head DISABLE TRIGGER audit_chain_head_advance_ck_trg");
            }
            List<StoredAuditEvent> chain = new ArrayList<>();
            try (PreparedStatement select = owner.prepareStatement("SELECT " + AuditEventStore.COLUMNS
                    + " FROM audit_event WHERE company_id = ? ORDER BY seq")) {
                select.setObject(1, companyId);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        chain.add(AuditEventStore.map(rows, 0));
                    }
                }
            }
            String previous = seq == 1 ? AuditCanonicalForm.GENESIS_HASH : chain.get((int) seq - 2).hash();
            for (StoredAuditEvent event : chain.subList((int) seq - 1, chain.size())) {
                String details = event.seq() == seq ? "{\"rewritten\":true}" : event.detailsJson();
                String hash = AuditCanonicalForm.hash(previous, AuditCanonicalForm.canonical(
                        new AuditCanonicalForm.Content(companyId, event.seq(), event.occurredAt(), event.actorType(),
                                event.actorId(), event.action(), event.targetType(), event.targetId(),
                                AuditCanonicalForm.parseDetails(details))));
                try (PreparedStatement update = owner.prepareStatement("UPDATE audit_event SET details = ?::jsonb,"
                        + " prev_hash = ?, hash = ? WHERE company_id = ? AND seq = ?")) {
                    update.setString(1, details);
                    update.setString(2, previous);
                    update.setString(3, hash);
                    update.setObject(4, companyId);
                    update.setLong(5, event.seq());
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }
                previous = hash;
            }
            try (PreparedStatement head = owner.prepareStatement(
                    "UPDATE audit_chain_head SET last_hash = ? WHERE company_id = ?")) {
                head.setString(1, previous);
                head.setObject(2, companyId);
                head.executeUpdate();
            }
            try (Statement ddl = owner.createStatement()) {
                ddl.execute("ALTER TABLE audit_chain_head ENABLE TRIGGER audit_chain_head_advance_ck_trg");
                ddl.execute("ALTER TABLE audit_event FORCE ROW LEVEL SECURITY");
                ddl.execute("ALTER TABLE audit_chain_head FORCE ROW LEVEL SECURITY");
            }
            owner.commit();
        }
    }

    private static void tamper(UUID companyId, String... statements) throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner()) {
            owner.setAutoCommit(false);
            try (Statement ddl = owner.createStatement()) {
                ddl.execute("ALTER TABLE audit_event NO FORCE ROW LEVEL SECURITY");
                ddl.execute("ALTER TABLE audit_chain_head NO FORCE ROW LEVEL SECURITY");
                ddl.execute("ALTER TABLE audit_chain_head DISABLE TRIGGER audit_chain_head_advance_ck_trg");
                for (String sql : statements) {
                    try (PreparedStatement change = owner.prepareStatement(sql)) {
                        change.setObject(1, companyId);
                        assertThat(change.executeUpdate()).as(sql).isPositive();
                    }
                }
                ddl.execute("ALTER TABLE audit_chain_head ENABLE TRIGGER audit_chain_head_advance_ck_trg");
                ddl.execute("ALTER TABLE audit_event FORCE ROW LEVEL SECURITY");
                ddl.execute("ALTER TABLE audit_chain_head FORCE ROW LEVEL SECURITY");
            }
            owner.commit();
        }
    }
}
