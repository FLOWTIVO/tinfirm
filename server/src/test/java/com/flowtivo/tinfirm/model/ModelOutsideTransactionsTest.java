package com.flowtivo.tinfirm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.model.internal.KeyReconciliation;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;

/**
 * CONVENTIONS §10: no call to LiteLLM runs inside a database transaction (the package 2 contract's admin calls and
 * the relay's forwarding alike). The model module's client refuses one ({@link IllegalStateException}) before it
 * sends anything, so the tests of the paths that call LiteLLM (the settings' test, the keys' sync, the reconciliation,
 * the relay) would fail should one of them call it inside a transaction. Work after a commit that calls LiteLLM (a
 * key's sync, its deletion, a reconciliation) runs on the model module's own threads, never in the committing
 * transaction's synchronisation.
 */
class ModelOutsideTransactionsTest extends IntegrationTest {

    @Autowired
    private KeyReconciliation reconciliation;

    @Autowired
    private SystemTransactions transactions;

    @Test
    void liteLlmIsNeverCalledInsideATransaction() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        ModelGateways.awaitStatus(owner, company, member, "OK");
        FakeLiteLlm litellm = FakeLiteLlm.bundled();
        int before = litellm.requests().size();

        assertThatThrownBy(() -> transactions.inCompany(company.id(), () -> {
            reconciliation.reconcile(company.id());
            return null;
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside a database transaction");

        // Outside any transaction the reconciliation reaches LiteLLM: the team's keys are listed.
        reconciliation.reconcile(company.id());
        assertThat(litellm.requests().stream().skip(before))
            .anyMatch(request -> request.path().startsWith("/key/list?team_id=tinfirm-" + company.id()));
        ModelGateways.awaitStatus(owner, company, member, "OK");
    }
}
