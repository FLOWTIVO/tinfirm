package com.flowtivo.tinfirm.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpRequest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;

/**
 * CONVENTIONS §10: no call to a system runs inside a database transaction, which would wait on the system with its
 * connection, and its locks, held. The outbound client refuses such a call ({@link IllegalStateException}), so every
 * integration test of a path that calls a system (a "who am I", a code exchange, a refresh, a revocation, an MCP
 * catalogue read, a system's test, an app's discovery and registration) fails, with a 500 or an exception, should the
 * path call it inside a transaction. This test shows the refusal itself, also in a transaction's synchronisation after
 * its commit, which still holds the connection; and the same for {@link SystemCalls}.
 */
class OutsideTransactionsTest extends IntegrationTest {

    @Autowired
    private OutboundHttp http;

    @Autowired
    private SystemCalls calls;

    @Autowired
    private Systems systems;

    @Autowired
    private SystemTransactions transactions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void theOutboundClientRefusesACallInsideATransaction() {
        JiraStub.Instance jira = JiraStub.newInstance();
        HttpRequest request = http.request(jira.baseUrl() + "/rest/api/2/serverInfo").GET().build();
        UUID companyId = UUID.randomUUID();

        assertThatThrownBy(() -> transactions.inCompany(companyId, () -> http.send(jira.baseUrl(), request)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside a database transaction");

        // After the commit, the transaction's synchronisations still hold its connection: refused as well.
        AtomicReference<RuntimeException> afterCommit = new AtomicReference<>();
        SystemContext.runInCompany(companyId, () -> new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                http.send(jira.baseUrl(), request);
                            }
                            catch (RuntimeException ex) {
                                afterCommit.set(ex);
                            }
                        }
                    })));
        assertThat(afterCommit.get()).isInstanceOf(IllegalStateException.class);

        // Outside any transaction the request goes (the stub answers 404 to a path it does not serve).
        assertThat(http.send(jira.baseUrl(), request).value()).isNotNull();
    }

    @Test
    void theCallsOfTheAccountModuleRefuseToRunInsideATransaction() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        jira.myself("test-outside-token", "olaf", "UTC");
        SystemCalls.Credential credential = new SystemCalls.Credential("test-outside-token", false);

        assertThatThrownBy(() -> transactions.inCompany(company.id(), () -> calls.whoAmI(
                systems.find(company.id(), systemId).orElseThrow(), credential)))
            .isInstanceOf(IllegalStateException.class);

        SystemInfo system = transactions.inCompany(company.id(),
                () -> systems.find(company.id(), systemId).orElseThrow());
        CallResult<SystemCalls.Identity> identity = SystemContext.callInCompany(company.id(),
                () -> calls.whoAmI(system, credential));
        assertThat(identity.succeeded()).as("%s", identity).isTrue();
        assertThat(identity.value().login()).isEqualTo("olaf");
    }
}
