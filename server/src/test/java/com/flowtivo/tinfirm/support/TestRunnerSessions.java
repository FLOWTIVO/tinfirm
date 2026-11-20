package com.flowtivo.tinfirm.support;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RunnerSessionListener;

/**
 * The run side of the runner protocol as the integration tests stand it in (TEST FIXTURE ONLY): the primary
 * {@link RunnerSessionListener}, which hands every call to the run module's own and, for a host a test names, fails
 * the next reconciliation of a {@code hello} or the next end of its answer once, as a database error would.
 */
@Component
@Primary
public class TestRunnerSessions implements RunnerSessionListener {

    private static final Set<UUID> FAIL_RECONCILIATION = ConcurrentHashMap.newKeySet();

    private static final Set<UUID> FAIL_END = ConcurrentHashMap.newKeySet();

    private final ApplicationContext context;

    public TestRunnerSessions(ApplicationContext context) {
        this.context = context;
    }

    /** The next reconciliation of a hello of the host (its row's id) fails, once. */
    public static void failNextReconciliation(UUID runnerHostId) {
        FAIL_RECONCILIATION.add(runnerHostId);
    }

    /** The next end of a hello's answer of the host (its row's id) fails, once. */
    public static void failNextEnd(UUID runnerHostId) {
        FAIL_END.add(runnerHostId);
    }

    private RunnerSessionListener real() {
        return context.getBeansOfType(RunnerSessionListener.class)
            .values()
            .stream()
            .filter(bean -> !(bean instanceof TestRunnerSessions))
            .findFirst()
            .orElseThrow();
    }

    @Override
    public List<RunnerMessages.Outbound> hostConnected(ConnectedHost host, List<RunnerMessages.ActiveRun> activeRuns,
            @Nullable String publicSuffixSha256) {
        if (FAIL_RECONCILIATION.remove(host.runnerHostId())) {
            throw new IllegalStateException("A test fails this reconciliation");
        }
        return real().hostConnected(host, activeRuns, publicSuffixSha256);
    }

    @Override
    public List<RunnerMessages.Outbound> runMessage(ConnectedHost host, RunnerMessages.RunMessage message) {
        return real().runMessage(host, message);
    }

    @Override
    public void revocationsWritten(UUID companyId, List<RunnerMessages.RunGitRevoke> written) {
        real().revocationsWritten(companyId, written);
    }

    @Override
    public List<RunnerMessages.Outbound> beforeCancelOfForeignRun(UUID companyId, UUID personalHostId,
            RunnerMessages.ActiveRun run, boolean takesRevocations) {
        return real().beforeCancelOfForeignRun(companyId, personalHostId, run, takesRevocations);
    }

    @Override
    public void endHelloAnswer(Map<ConnectedHost, List<RunnerMessages.ActiveRun>> listed,
            Predicate<RunnerMessages.Outbound> write) {
        if (listed.keySet().stream().anyMatch(host -> FAIL_END.remove(host.runnerHostId()))) {
            throw new IllegalStateException("A test fails this end of a hello's answer");
        }
        real().endHelloAnswer(listed, write);
    }

    @Override
    public void hostConfigured(ConnectedHost host) {
        real().hostConfigured(host);
    }

    @Override
    public void hostHealthChanged(ConnectedHost host, boolean healthy) {
        real().hostHealthChanged(host, healthy);
    }

    @Override
    public void hostActive(ConnectedHost host) {
        real().hostActive(host);
    }

    @Override
    public void hostDisconnected(ConnectedHost host) {
        real().hostDisconnected(host);
    }
}
