package com.flowtivo.tinfirm.shared.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.support.ConnectionWatch;
import com.flowtivo.tinfirm.support.IntegrationTest;

/**
 * {@link AfterTransaction} and {@link ReleasingTransactionManager} against the real transaction manager and pool: kept
 * work runs once the thread's outermost transaction has released its connection, in the order it was kept, in the
 * tenant context it was kept in, and before the call that began the transaction returns.
 */
class AfterTransactionTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    private final List<String> steps = new CopyOnWriteArrayList<>();

    /** Each test is a piece of work of its own on the test's thread, and leaves nothing on it for the next class. */
    @BeforeEach
    void newPieceOfWork() {
        AfterTransaction.endOfWork("the previous test");
    }

    @AfterEach
    void endOfPieceOfWork() {
        AfterTransaction.endOfWork("the test");
    }

    private TransactionTemplate transaction(int propagation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(propagation);
        return template;
    }

    private TransactionTemplate transaction() {
        return transaction(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** Kept work that notes its name and the connections the thread holds while it runs. */
    private Runnable step(String name) {
        return () -> steps.add(name + " holding " + ConnectionWatch.heldByCurrentThread());
    }

    @Test
    void withoutATransactionTheWorkRunsAtOnce() {
        AfterTransaction.run(step("work"));
        AfterTransaction.afterCommit(step("after commit"));
        assertThat(steps).containsExactly("work holding 0", "after commit holding 0");
    }

    @Test
    void keptWorkRunsOnceTheConnectionIsReleasedAndBeforeTheCallReturns() {
        transaction().executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT 1", Integer.class);
            AfterTransaction.run(step("first"));
            AfterTransaction.afterCommit(step("second"));
            AfterTransaction.run(step("third"));
            steps.add("body holding " + ConnectionWatch.heldByCurrentThread());
        });
        steps.add("returned");
        assertThat(steps).containsExactly("body holding 1", "first holding 0", "third holding 0", "second holding 0",
                "returned");
        assertThat(AfterTransaction.holding()).isFalse();
    }

    @Test
    void workKeptInATransactionOfItsOwnWaitsForTheOuterOne() {
        transaction().executeWithoutResult(outer -> {
            transaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW).executeWithoutResult(inner -> {
                AfterTransaction.run(step("kept in the inner"));
                steps.add("inner holding " + ConnectionWatch.heldByCurrentThread());
            });
            steps.add("inner ended, holding " + ConnectionWatch.heldByCurrentThread());
        });
        assertThat(steps).containsExactly("inner holding 2", "inner ended, holding 1", "kept in the inner holding 0");
    }

    @Test
    void noSavepointAndAScopeWithoutTransactionLeavesTheWorkToTheOuterTransaction() {
        transaction().executeWithoutResult(outer -> {
            jdbc.queryForObject("SELECT 1", Integer.class);
            // The transaction manager takes no savepoints (Spring's default for JPA): a NESTED one is refused.
            assertThatThrownBy(() -> transaction(TransactionDefinition.PROPAGATION_NESTED)
                .executeWithoutResult(savepoint -> steps.add("never")))
                .isInstanceOf(NestedTransactionNotSupportedException.class);
            AfterTransaction.run(step("kept in the outer"));
            transaction(TransactionDefinition.PROPAGATION_NOT_SUPPORTED).executeWithoutResult(none -> {
                // The outer transaction is suspended, and still holds its connection.
                AfterTransaction.run(step("kept without a transaction"));
                steps.add("not supported, holding " + ConnectionWatch.heldByCurrentThread());
            });
            steps.add("outer");
        });
        assertThat(steps).containsExactly("not supported, holding 1", "outer", "kept in the outer holding 0",
                "kept without a transaction holding 0");
    }

    @Test
    void afterARollbackKeptWorkRunsAfterCommitWorkDoesNotAndTheCallsOwnErrorWins() {
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            AfterTransaction.run(step("record"));
            AfterTransaction.afterCommit(step("after commit"));
            AfterTransaction.run(() -> {
                throw new IllegalStateException("the kept work failed");
            });
            AfterTransaction.run(step("record after the failure"));
            throw new IllegalArgumentException("the call failed");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("the call failed");
        assertThat(steps).containsExactly("record holding 0", "record after the failure holding 0");
    }

    @Test
    void afterACommitAFailureOfKeptWorkFailsTheCallAndTheRestStillRuns() {
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT 1", Integer.class);
            AfterTransaction.run(() -> {
                throw new IllegalStateException("the record could not be written");
            });
            AfterTransaction.run(step("next"));
        })).isInstanceOf(FailedAfterCommit.class)
            .cause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("the record could not be written");
        assertThat(steps).containsExactly("next holding 0");
        assertThat(AfterTransaction.holding()).isFalse();
    }

    @Test
    void afterAReadOnlyTransactionAFailureOfKeptWorkIsThrownAsItIs() {
        TransactionTemplate readOnly = transaction();
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT 1", Integer.class);
            AfterTransaction.run(() -> {
                throw new IllegalStateException("the record could not be written");
            });
        })).isInstanceOf(IllegalStateException.class).hasMessage("the record could not be written");
    }

    @Test
    void afterARollbackTheFailureOfWorkThatFailsTheCallReplacesTheCallsError() {
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            AfterTransaction.runOrFail(() -> {
                throw new IllegalStateException("the refusal could not be kept");
            });
            AfterTransaction.run(step("after it"));
            throw new IllegalArgumentException("the call failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the refusal could not be kept");
        assertThat(steps).containsExactly("after it holding 0");
    }

    @Test
    void afterCommitAskedForInACallbackFollowsTheCurrentTransactionsOutcome() {
        transaction().executeWithoutResult(status -> TransactionSynchronizationManager
            .registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    AfterTransaction.afterCommit(step("asked for in afterCommit"));
                }

                @Override
                public void afterCompletion(int outcome) {
                    AfterTransaction.afterCommit(step("asked for in afterCompletion of a commit"));
                }
            }));
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int outcome) {
                    AfterTransaction.afterCommit(step("asked for in afterCompletion of a rollback"));
                }
            });
            throw new IllegalArgumentException("roll back");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(steps).containsExactly("asked for in afterCommit holding 0",
                "asked for in afterCompletion of a commit holding 0");
    }

    @Test
    void aKeptFailureNeitherSkipsSpringsResumeNorHidesAnEarlierFailure() {
        // The outermost transaction begins inside a scope without one, whose synchronization Spring suspends and
        // resumes once the transaction is cleaned up.
        transaction(TransactionDefinition.PROPAGATION_SUPPORTS).executeWithoutResult(scope -> {
            assertThatThrownBy(() -> transaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
                .executeWithoutResult(status -> AfterTransaction.run(() -> {
                    throw new IllegalStateException("kept");
                }))).isInstanceOf(FailedAfterCommit.class);
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).as("the scope resumed").isTrue();
        });
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    throw new IllegalArgumentException("an afterCommit callback failed");
                }
            });
            AfterTransaction.run(() -> {
                throw new IllegalStateException("kept");
            });
        })).isInstanceOf(FailedAfterCommit.class)
            .cause()
            .hasMessage("an afterCommit callback failed")
            .satisfies(thrown -> assertThat(thrown.getSuppressed()).singleElement()
                .satisfies(suppressed -> assertThat(suppressed).hasMessage("kept")));
        assertThat(AfterTransaction.holding()).isFalse();
    }

    /** A commit whose callback throws after the commit has committed: its afterCommit work runs. */
    @Test
    void aCommitWhoseCallbackThrowsAfterItKeepsItsAfterCommitWork() {
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    throw new IllegalArgumentException("an afterCommit callback failed");
                }
            });
            AfterTransaction.afterCommit(step("after the commit"));
        })).isInstanceOf(FailedAfterCommit.class).cause().hasMessage("an afterCommit callback failed");
        assertThat(steps).containsExactly("after the commit holding 0");
        assertThat(AfterTransaction.savedInThisWork()).isTrue();
    }

    @Test
    void anInnerTransactionsAfterCommitWorkRunsAlthoughTheOuterOneRollsBack() {
        assertThatThrownBy(() -> transaction().executeWithoutResult(outer -> {
            transaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
                .executeWithoutResult(inner -> AfterTransaction.afterCommit(step("after the inner commit")));
            AfterTransaction.afterCommit(step("after the outer commit"));
            throw new IllegalArgumentException("the outer one rolls back");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(steps).containsExactly("after the inner commit holding 0");
    }

    /** A read-only outermost transaction inside which one of its own wrote: a failure after it is after a save. */
    @Test
    void aWriteInAnInnerTransactionMakesAFailureAfterAReadOnlyOneAFailureAfterTheSave() {
        TransactionTemplate readOnly = transaction();
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(outer -> {
            transaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
                .executeWithoutResult(inner -> jdbc.queryForObject("SELECT 1", Integer.class));
            AfterTransaction.run(() -> {
                throw new IllegalStateException("kept");
            });
        })).isInstanceOf(FailedAfterCommit.class);
        assertThat(AfterTransaction.savedInThisWork()).isTrue();
    }

    @Test
    void aStatusCommittedTwiceChangesNothing() {
        transaction().executeWithoutResult(outer -> {
            TransactionStatus inner = transactionManager.getTransaction(requiresNew());
            transactionManager.commit(inner);
            AfterTransaction.run(step("kept by the outer one"));
            assertThatThrownBy(() -> transactionManager.commit(inner))
                .isInstanceOf(IllegalTransactionStateException.class);
            steps.add("outer holding " + ConnectionWatch.heldByCurrentThread() + ", counted " + AfterTransaction.holding());
        });
        assertThat(steps).containsExactly("outer holding 1, counted true", "kept by the outer one holding 0");
    }

    @Test
    void aRollbackAfterAFailedCommitChangesNothing() {
        transaction().executeWithoutResult(outer -> {
            AfterTransaction.run(step("kept"));
            TransactionStatus failing = transactionManager.getTransaction(requiresNew());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalArgumentException("the commit failed");
                }
            });
            assertThatThrownBy(() -> transactionManager.commit(failing)).hasMessage("the commit failed");
            assertThatThrownBy(() -> transactionManager.rollback(failing))
                .isInstanceOf(IllegalTransactionStateException.class);
            steps.add("outer holding " + ConnectionWatch.heldByCurrentThread() + ", counted " + AfterTransaction.holding());
        });
        assertThat(steps).containsExactly("outer holding 1, counted true", "kept holding 0");
    }

    @Test
    void anotherThreadsStatusChangesNothingHere() throws Exception {
        AtomicReference<TransactionStatus> theirs = new AtomicReference<>();
        Thread other = Thread.ofVirtual().start(() -> {
            TransactionStatus status = transactionManager.getTransaction(TransactionDefinition.withDefaults());
            transactionManager.commit(status);
            theirs.set(status);
        });
        other.join(10_000);
        transaction().executeWithoutResult(mine -> {
            AfterTransaction.run(step("mine"));
            assertThatThrownBy(() -> transactionManager.rollback(theirs.get()))
                .isInstanceOf(IllegalTransactionStateException.class);
            steps.add("still counted " + AfterTransaction.holding());
        });
        assertThat(steps).containsExactly("still counted true", "mine holding 0");
    }

    /** Keeping work resolves no deferred security context: resolving it may read the session from the database. */
    @Test
    void keepingWorkResolvesNoDeferredSecurityContextAndTheWorkResolvesItAfterTheRelease() {
        SecurityContext request = new SecurityContextImpl(new TestingAuthenticationToken("request", null));
        SecurityContextHolder.setDeferredContext(() -> {
            steps.add("resolved holding " + ConnectionWatch.heldByCurrentThread());
            jdbc.queryForObject("SELECT 1", Integer.class);
            return request;
        });
        try {
            ConnectionWatch.Mark mark = ConnectionWatch.mark();
            transaction().executeWithoutResult(status -> {
                AfterTransaction.run(() -> steps.add(SecurityContextHolder.getContext().getAuthentication().getName()));
                steps.add("kept");
            });
            assertThat(steps).containsExactly("kept", "resolved holding 0", "request");
            assertThat(mark.at(AfterTransactionTest.class.getName())).isZero();
        }
        finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** A transaction asked for in a callback after the commit joins the finished one: an error, counted. */
    @Test
    void aTransactionJoinedWhileTheOneItJoinsIsCompletingIsAnError() {
        long before = AfterTransaction.joinedWhileCompleting();
        transaction().executeWithoutResult(status -> TransactionSynchronizationManager
            .registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    transaction().executeWithoutResult(joined -> steps.add("joined"));
                }
            }));
        assertThat(steps).containsExactly("joined");
        assertThat(AfterTransaction.joinedWhileCompleting()).isEqualTo(before + 1);
    }

    private static TransactionDefinition requiresNew() {
        org.springframework.transaction.support.DefaultTransactionDefinition definition =
                new org.springframework.transaction.support.DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return definition;
    }

    @Test
    void anErrorOfOneKeptWorkDropsNoneOfTheOthers() {
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            AfterTransaction.run(step("before"));
            AfterTransaction.run(() -> {
                throw new KeptError();
            });
            AfterTransaction.run(step("after"));
        })).isInstanceOf(KeptError.class);
        assertThat(steps).containsExactly("before holding 0", "after holding 0");
        assertThat(AfterTransaction.holding()).isFalse();
    }

    @Test
    void theEndOfAPieceOfWorkRunsWhatWasLeftAndResetsTheThread() {
        TransactionStatus never = transactionManager.getTransaction(TransactionDefinition.withDefaults());
        AfterTransaction.run(step("left"));
        AfterTransaction.afterCommit(step("after a commit that never came"));
        assertThat(AfterTransaction.holding()).isTrue();

        // A transaction that was never ended: the net logs it, runs the work it holds, and resets the count.
        AfterTransaction.endOfWork("the test");

        assertThat(AfterTransaction.holding()).isFalse();
        assertThat(steps).singleElement().asString().startsWith("left holding");
        AfterTransaction.run(step("now"));
        assertThat(steps).last().isEqualTo("now holding 1");
        transactionManager.rollback(never);
        assertThat(AfterTransaction.holding()).isFalse();
    }

    @Test
    void keptWorkRunsUnderTheSecurityContextItWasKeptInAndTheThreadsIsRestored() {
        SecurityContext request = new SecurityContextImpl(new TestingAuthenticationToken("request", null));
        SecurityContext later = new SecurityContextImpl(new TestingAuthenticationToken("later", null));
        SecurityContextHolder.setContext(request);
        try {
            transaction().executeWithoutResult(status -> {
                AfterTransaction.run(() -> steps.add(SecurityContextHolder.getContext().getAuthentication().getName()));
                SecurityContextHolder.setContext(later);
            });
            assertThat(steps).containsExactly("request");
            assertThat(SecurityContextHolder.getContext()).isSameAs(later);
        }
        finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** An {@link Error} a kept work throws. */
    private static final class KeptError extends Error {

        private static final long serialVersionUID = 1L;
    }

    @Test
    void workKeptByKeptWorkRunsInTheSamePass() {
        transaction().executeWithoutResult(status -> AfterTransaction.run(() -> {
            steps.add("outer work");
            transaction().executeWithoutResult(inner -> {
                AfterTransaction.run(step("kept by the work's own transaction"));
                steps.add("work's transaction");
            });
            AfterTransaction.run(step("kept by the work outside a transaction"));
            steps.add("outer work ends");
        }));
        assertThat(steps).containsExactly("outer work", "work's transaction",
                "kept by the work's own transaction holding 0", "kept by the work outside a transaction holding 0",
                "outer work ends");
    }

    @Test
    void anotherThreadsTransactionNeverRunsThisThreadsWork() throws Exception {
        CountDownLatch kept = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        Thread first = Thread.ofPlatform().name("first").start(() -> transaction().executeWithoutResult(status -> {
            AfterTransaction.run(() -> steps.add("first's work on " + Thread.currentThread().getName()));
            kept.countDown();
            await(otherDone);
            steps.add("first commits");
        }));
        assertThat(kept.await(10, TimeUnit.SECONDS)).isTrue();
        Thread other = Thread.ofVirtual().name("other").start(() -> {
            transaction().executeWithoutResult(status -> AfterTransaction.run(() -> steps.add("other's work")));
            otherDone.countDown();
        });
        other.join(10_000);
        first.join(10_000);
        assertThat(steps).containsExactly("other's work", "first commits", "first's work on first");
    }

    @Test
    void onAVirtualThreadAsOnAnyOther() throws Exception {
        Thread.ofVirtual().start(this::keptWorkRunsOnceTheConnectionIsReleasedAndBeforeTheCallReturns).join(10_000);
        assertThat(steps).containsExactly("body holding 1", "first holding 0", "third holding 0", "second holding 0",
                "returned");
    }

    @Test
    void theKeptWorksTransactionHasTheTenantContextItWasKeptInAndNeverAnothers() throws Exception {
        UUID user = UUID.randomUUID();
        UUID company = UUID.randomUUID();
        UUID entered = UUID.randomUUID();
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        AtomicReference<Map<String, Object>> seenByOther = new AtomicReference<>();
        CountDownLatch kept = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        Thread other = Thread.ofVirtual().unstarted(() -> {
            await(kept);
            UUID otherCompany = UUID.randomUUID();
            try (TenantContextHolder.Scope ignored = TenantContextHolder
                .open(TenantContext.forUser(UUID.randomUUID(), true).withCompany(otherCompany))) {
                transaction().executeWithoutResult(status -> AfterTransaction.run(() -> seenByOther
                    .set(transaction().execute(own -> settings()))));
            }
            otherDone.countDown();
        });
        other.start();
        try (TenantContextHolder.Scope ignored = TenantContextHolder
            .open(TenantContext.forUser(user, false).withCompany(company))) {
            transaction().executeWithoutResult(status -> {
                AfterTransaction.run(() -> seen.set(transaction().execute(own -> settings())));
                kept.countDown();
                // The thread enters another context before the commit: the kept work keeps its own.
                try (TenantContextHolder.Scope later = TenantContextHolder
                    .open(TenantContext.forUser(user, false).withCompany(entered))) {
                    await(otherDone);
                }
            });
        }
        other.join(10_000);
        assertThat(seen.get()).containsEntry("company", company.toString())
            .containsEntry("user", user.toString())
            .containsEntry("admin", "false")
            .containsEntry("system", "false");
        assertThat(seenByOther.get().get("company")).isNotEqualTo(company.toString());
        assertThat(seenByOther.get()).containsEntry("admin", "true");
    }

    @Test
    void theApplicationHasThisOneTransactionManagerAndSpringSessionUsesIt() throws Exception {
        Map<String, TransactionManager> managers = context.getBeansOfType(TransactionManager.class);
        assertThat(managers.values()).singleElement().isInstanceOf(ReleasingTransactionManager.class);
        // Spring Boot's customizers and the audit's listener (CONVENTIONS §8) are the wrapped JPA manager's.
        assertThat(((ReleasingTransactionManager) transactionManager).delegate()).isInstanceOf(JpaTransactionManager.class);
        assertThat(((ReleasingTransactionManager) transactionManager).getTransactionExecutionListeners()).isNotEmpty();
        JdbcIndexedSessionRepository sessions = context.getBean(JdbcIndexedSessionRepository.class);
        Field field = JdbcIndexedSessionRepository.class.getDeclaredField("transactionOperations");
        field.setAccessible(true);
        assertThat(field.get(sessions)).isInstanceOfSatisfying(TransactionTemplate.class,
                template -> {
                    assertThat(template.getTransactionManager()).isSameAs(transactionManager);
                    // Spring Session's own transactions are the request's bookkeeping, not its change.
                    assertThat(template.getName()).isEqualTo(AfterTransaction.BOOKKEEPING);
                });
    }

    @Test
    void theNetEndsEveryRequestAndEveryRunOfAScheduledJob() throws Exception {
        assertThat(context.getBean("endOfRequestFilter", FilterRegistrationBean.class).getOrder())
            .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        ScheduledAnnotationBeanPostProcessor scheduled = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
        Field registrar = ScheduledAnnotationBeanPostProcessor.class.getDeclaredField("registrar");
        registrar.setAccessible(true);
        assertThat(((ScheduledTaskRegistrar) registrar.get(scheduled)).getScheduler()).isNotNull()
            .satisfies(scheduler -> assertThat(scheduler.getClass().getName()).endsWith("EndOfRun"));
    }

    private Map<String, Object> settings() {
        return jdbc.queryForMap("""
                SELECT current_setting('app.company_id', true) AS company, current_setting('app.user_id', true) AS user,
                       current_setting('app.instance_admin', true) AS admin, current_setting('app.system', true) AS system""");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
