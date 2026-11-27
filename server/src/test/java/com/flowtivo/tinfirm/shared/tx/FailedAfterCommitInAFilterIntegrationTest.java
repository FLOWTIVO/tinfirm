package com.flowtivo.tinfirm.shared.tx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.web.ServerBusy;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;

/**
 * A step after a commit that fails in a filter, before the dispatcher: the outermost filter answers it by the same
 * rule as the API's handler (shared.web.ServerBusy), once, as a write in which a change may already be saved.
 */
@Import(FailedAfterCommitInAFilterIntegrationTest.FailingFilter.class)
class FailedAfterCommitInAFilterIntegrationTest extends IntegrationTest {

    /** TEST FIXTURE: a filter that commits a written row, and whose kept step then fails, on its own path only. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingFilter {

        @Bean
        FilterRegistrationBean<jakarta.servlet.Filter> failingAfterCommit(PlatformTransactionManager manager,
                JdbcTemplate jdbc) {
            TransactionTemplate transactions = new TransactionTemplate(manager);
            FilterRegistrationBean<jakarta.servlet.Filter> registration = new FilterRegistrationBean<>(
                    (request, response, chain) -> transactions.executeWithoutResult(status -> {
                        // A row written: the request's change, which the commit saves.
                        jdbc.update("INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES (?, now(), "
                                + "now(), 'test fixture')", "test-fixture-" + java.util.UUID.randomUUID());
                        AfterTransaction.run(() -> {
                            throw new IllegalStateException("the step after the commit failed");
                        });
                    }));
            registration.addUrlPatterns("/api/test-fixture/failing-after-commit");
            registration.setOrder(-200);
            return registration;
        }
    }

    @Test
    void aFailureAfterASavedChangeOutOfAFilterIsToldTheChangeMayAlreadyBeSaved() {
        ApiResponse failed = client().post("/api/test-fixture/failing-after-commit", Map.of());

        assertThat(failed.status()).as("%s", failed).isEqualTo(500);
        assertThat(failed.json().get("detail").asString()).isEqualTo(ServerBusy.SAVED_THEN_FAILED);
        assertThat(failed.header("Retry-After")).isNull();
    }

    @Test
    void aReadsFailureAfterACommitOutOfAFilterIsAFailedRead() {
        ApiResponse failed = client().get("/api/test-fixture/failing-after-commit");

        assertThat(failed.status()).as("%s", failed).isEqualTo(500);
        assertThat(failed.json().get("detail").asString()).isEqualTo("Internal server error");
    }
}
