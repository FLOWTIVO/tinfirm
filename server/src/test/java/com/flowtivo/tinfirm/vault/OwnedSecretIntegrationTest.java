package com.flowtivo.tinfirm.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * Secrets a person owns (ADR-0049 §5: the token of their own account on a connection): never listed, changed or
 * deleted through the Secrets API, never named to another module as a secret of the company, and revealed only for
 * a call that acts as their owner, with the owner in the audit. The values are test fixtures.
 */
class OwnedSecretIntegrationTest extends IntegrationTest {

    @Autowired
    SecretWriter secretWriter;

    @Autowired
    SecretCatalog catalog;

    @Autowired
    VaultService vault;

    @Autowired
    SystemTransactions transactions;

    @Test
    void anOwnedSecretIsInvisibleToTheSecretsApiAndToOtherModules() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID companySecret = createSecret(owner, company.id(), "A_COMPANY_KEY", "test-company-value");
        UUID memberId = company.userId("MEMBER");
        UUID owned = transactions.inCompany(company.id(), () -> secretWriter.createOwned(company.id(), memberId,
                "PERSON_ACCOUNT_TEST", "A member's own account", "test-owned-value"));

        JsonNode listed = owner.get(company.path("/secrets?limit=200")).json().get("items");
        assertThat(listed).extracting(secret -> secret.get("name").asString()).containsExactly("A_COMPANY_KEY");
        assertThat(owner.put(company.path("/secrets/" + owned), Map.of("value", "x")).status()).isEqualTo(404);
        assertThat(owner.delete(company.path("/secrets/" + owned)).status()).isEqualTo(404);
        // No other module may name it as a secret of the company.
        assertThat(transactions.inCompany(company.id(), () -> catalog.names(company.id(), Set.of(owned,
                companySecret)))).containsOnlyKeys(companySecret);
        // The company's writer does not reach it either.
        assertThat(transactions.inCompany(company.id(), () -> secretWriter.replace(company.id(), owned, "x")))
            .isFalse();
        transactions.runInCompany(company.id(), () -> secretWriter.delete(company.id(), owned));
        try (RevealedSecret value = SystemContext.call(() -> vault.revealOwned(company.id(), owned, memberId,
                "test:still-there"))) {
            assertThat(value.asString()).isEqualTo("test-owned-value");
        }
    }

    @Test
    void anOwnedSecretIsRevealedForItsOwnerOnly() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID companySecret = createSecret(owner, company.id(), "A_COMPANY_KEY", "test-company-value");
        UUID memberId = company.userId("MEMBER");
        UUID adminId = company.userId("ADMIN");
        UUID owned = transactions.inCompany(company.id(), () -> secretWriter.createOwned(company.id(), memberId,
                "PERSON_ACCOUNT_TEST", "A member's own account", "test-owned-value"));

        try (RevealedSecret value = SystemContext.call(() -> vault.revealOwned(company.id(), owned, memberId,
                "run-tool:test"))) {
            assertThat(value.asString()).isEqualTo("test-owned-value");
        }
        // For another person, or as a secret of the company: refused, never a value.
        assertThatThrownBy(() -> SystemContext.call(() -> vault.revealOwned(company.id(), owned, adminId,
                "run-tool:test"))).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("test-owned");
        assertThatThrownBy(() -> SystemContext.call(() -> vault.reveal(company.id(), owned, "run-tool:test")))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SystemContext.call(() -> vault.revealOwned(company.id(), companySecret, memberId,
                "run-tool:test"))).isInstanceOf(IllegalStateException.class);

        // Its owner's writer replaces and deletes it; another person's does nothing.
        assertThat(transactions.inCompany(company.id(), () -> secretWriter.replaceOwned(company.id(), adminId, owned,
                "x"))).isFalse();
        assertThat(transactions.inCompany(company.id(), () -> secretWriter.replaceOwned(company.id(), memberId,
                owned, "test-owned-value-2"))).isTrue();
        transactions.runInCompany(company.id(), () -> secretWriter.deleteOwned(company.id(), adminId, owned));
        try (RevealedSecret value = SystemContext.call(() -> vault.revealOwned(company.id(), owned, memberId,
                "run-tool:test"))) {
            assertThat(value.asString()).isEqualTo("test-owned-value-2");
        }
        transactions.runInCompany(company.id(), () -> secretWriter.deleteOwned(company.id(), memberId, owned));
        assertThatThrownBy(() -> SystemContext.call(() -> vault.revealOwned(company.id(), owned, memberId,
                "run-tool:test"))).hasMessageContaining("not found");

        // Every event of an owned secret names its owner; the company secret's events do not; no value anywhere.
        List<JsonNode> events = new java.util.ArrayList<>();
        owner.get(company.path("/audit-events?limit=200")).json().get("items").forEach(event -> {
            if (event.get("action").asString().startsWith("secret.")) {
                events.add(event);
            }
        });
        assertThat(events).filteredOn(event -> owned.toString().equals(event.get("targetId").asString()))
            .extracting(event -> event.get("action").asString())
            .containsExactly("secret.deleted", "secret.accessed", "secret.updated", "secret.accessed",
                    "secret.created");
        assertThat(events).filteredOn(event -> owned.toString().equals(event.get("targetId").asString()))
            .allSatisfy(event -> assertThat(event.get("details").get("userId").asString())
                .isEqualTo(memberId.toString()));
        assertThat(events).filteredOn(event -> companySecret.toString().equals(event.get("targetId").asString()))
            .allSatisfy(event -> assertThat(event.get("details").has("userId")).isFalse());
        assertThat(events.toString()).doesNotContain("test-owned-value", "test-company-value");
    }
}
