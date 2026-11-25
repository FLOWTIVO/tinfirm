package com.flowtivo.tinfirm.shared.tx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.flowtivo.tinfirm.support.ConnectionWatch;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;

/**
 * The safety net of {@link AfterTransaction} at the end of real work: a request, a run of a scheduled job and a runner
 * host's frame, each with a transaction left counted and work left kept by the test in the middle of it (the fixture
 * {@link ConnectionWatch#plantAt}): the net runs the work and resets the thread. The context's scheduled jobs run on a
 * {@code ScheduledExecutorService} bean and no {@code TaskScheduler} (Spring Boot then makes none): the application
 * starts, and the net still ends each run.
 * <p>
 * The context is closed after this class: the test framework pauses a cached context between classes by stopping its
 * lifecycle beans, and a plain {@code ScheduledExecutorService} is none, so this context's jobs would keep running
 * beside every later class's own, on the same database.
 */
@Import(EndOfWorkIntegrationTest.ExecutorOnly.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EndOfWorkIntegrationTest extends IntegrationTest {

    /** TEST FIXTURE: the scheduled jobs' executor, a {@code ScheduledExecutorService} in place of a TaskScheduler. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ExecutorOnly {

        @Bean(destroyMethod = "shutdownNow")
        ScheduledExecutorService jobs() {
            return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("test-jobs").factory());
        }
    }

    @Autowired
    private ApplicationContext context;

    /** A transaction never ended and work kept inside it, left on the thread that asks for its next connection. */
    private static Runnable leftover(CountDownLatch ran, List<String> where) {
        return () -> {
            AfterTransaction.began(new SimpleTransactionStatus(), false);
            AfterTransaction.run(() -> {
                where.add(Thread.currentThread().getName());
                ran.countDown();
            });
        };
    }

    @Test
    void theApplicationStartsWithAScheduledExecutorServiceAndNoTaskScheduler() {
        assertThat(context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(ScheduledExecutorService.class)).containsKey("jobs");
    }

    @Test
    void theNetEndsARealRequest() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        List<String> where = new java.util.concurrent.CopyOnWriteArrayList<>();
        ConnectionWatch.plantAt("com.flowtivo.tinfirm.identity.internal.ActiveUserFilter", leftover(ran, where));

        assertThat(admin().get("/api/me").status()).isEqualTo(200);

        assertThat(ran.await(10, TimeUnit.SECONDS)).as("the work left kept ran at the request's end").isTrue();
    }

    @Test
    void theNetEndsARealRunOfAScheduledJob() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        List<String> where = new java.util.concurrent.CopyOnWriteArrayList<>();
        ConnectionWatch.plantAt("com.flowtivo.tinfirm.run.internal.RunReaper.scheduledReap", leftover(ran, where));

        assertThat(ran.await(30, TimeUnit.SECONDS)).as("the work left kept ran at the job's end").isTrue();
        assertThat(where).containsExactly("test-jobs");
    }

    @Test
    void theNetEndsARealFrameOfARunnerHost() throws Exception {
        TestCompany company = companyWithRoles();
        RunnerHostRef host = registerRunnerHost(loginAs(company.email("MEMBER")), company.id(), unique("host"));
        CountDownLatch ran = new CountDownLatch(1);
        List<String> where = new java.util.concurrent.CopyOnWriteArrayList<>();
        ConnectionWatch.plantAt("com.flowtivo.tinfirm.runner.internal.RunnerWebSocketHandler.lambda$handleTextMessage",
                leftover(ran, where));

        try (FakeRunner runner = connectRunner(host, List.of())) {
            assertThat(ran.await(10, TimeUnit.SECONDS)).as("the work left kept ran at the frame's end").isTrue();
        }
    }
}
