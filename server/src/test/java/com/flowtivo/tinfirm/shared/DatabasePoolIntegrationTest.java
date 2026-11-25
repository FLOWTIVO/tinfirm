package com.flowtivo.tinfirm.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestConversations;

/**
 * The server under more concurrent requests than its pool has connections: with a pool of 4 and a wait of 60 s, 16
 * signed-in requests at once each get their answer, and none waits for anything near the pool's timeout (a slow machine
 * takes seconds; a stall takes the whole wait). A request that held one
 * connection while it asked for a second would let four such requests hold the whole pool and wait for each other until
 * the timeout. OWNER's list of everyone's conversations is such a request when it records, in a transaction of its own,
 * that OWNER read other people's conversations.
 */
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.connection-timeout=60000" })
class DatabasePoolIntegrationTest extends IntegrationTest {

    private static final int CONCURRENT = 16;

    private static final int ROUNDS = 5;

    @Test
    void concurrentListsOfTheOwnerNeverWaitForThePoolsTimeout() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiResponse agent = member.post(company.path("/agents"), Map.of("name", unique("Helper"), "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "", "systems", List.of(), "repositories", List.of(),
                "shareGroupIds", List.of()));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        UUID agentId = UUID.fromString(agent.json().get("id").asString());
        UUID memberPrincipal = memberPrincipal(company, company.userId("MEMBER"));
        for (int i = 0; i < 3; i++) {
            TestConversations.bare(company.id(), UUID.randomUUID(), unique("c"), agentId, memberPrincipal,
                    "CLAUDE_CODE");
        }
        // OWNER lists everyone's conversations: the member's among them, a read that is on record.
        ApiClient owner = loginAs(company.email("OWNER"));
        String path = company.path("/conversations?limit=50");
        ApiResponse first = owner.get(path);
        assertThat(first.status()).as("%s", first).isEqualTo(200);
        assertThat(first.json().get("items")).hasSize(3);

        List<Outcome> outcomes = new ArrayList<>();
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < ROUNDS; round++) {
                List<Future<Outcome>> futures = new ArrayList<>();
                for (int i = 0; i < CONCURRENT; i++) {
                    futures.add(clients.submit(() -> {
                        long start = System.nanoTime();
                        int status;
                        try {
                            status = owner.get(path).status();
                        }
                        catch (UncheckedIOException ex) {
                            // A response cut off while it was written.
                            status = 0;
                        }
                        return new Outcome(status, Duration.ofNanos(System.nanoTime() - start));
                    }));
                }
                for (Future<Outcome> future : futures) {
                    outcomes.add(future.get());
                }
            }
        }

        String summary = "statuses " + outcomes.stream()
            .collect(Collectors.groupingBy(Outcome::status, TreeMap::new, Collectors.counting()))
                + ", slowest " + outcomes.stream().map(Outcome::took).max(Duration::compareTo).orElseThrow().toMillis()
                + " ms";
        System.out.println("DatabasePoolIntegrationTest: " + outcomes.size() + " requests, " + summary);
        assertThat(outcomes).as("every request answered 200: %s", summary).allMatch(outcome -> outcome.status() == 200);
        // A stall waits the whole 60 s; a slow or starved machine takes seconds, far under 30.
        assertThat(outcomes).as("no request waited for the pool's timeout (60 s): %s", summary)
            .allMatch(outcome -> outcome.took().compareTo(Duration.ofSeconds(30)) < 0);
    }

    private record Outcome(int status, Duration took) {
    }
}
