package com.flowtivo.tinfirm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * The lock order of a principal and its model key (CONVENTIONS §10): the principal's row, then its key's row
 * ({@code principal_model_key}), then the company's audit chain head. A change of the principal (its users, its status)
 * locks its row and then records its audit; a member's leaving and a service identity's deletion lock it and then its
 * key. A sync of the key writes the key's row twice when it makes the key again, and records a budget's crossed
 * threshold, and each checks the principal's row (their foreign keys) after the key's row, the first even after the
 * sync's audit records: while the principal's row was locked {@code FOR UPDATE}, the sync and the change waited on each
 * other ({@code 40P01}), and one of them was lost. The sync locks the principal first, and every change but a deletion
 * locks it {@code FOR NO KEY UPDATE}, on which the sync does not wait at all. Each test holds one side at the point
 * where it waited for the other, so that the order decides.
 */
@ExtendWith(OutputCaptureExtension.class)
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ModelKeyLockOrderTest extends IntegrationTest {

    private final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    private ModelAccess modelAccess;

    private record Bot(TestCompany company, ApiClient owner, UUID id, FakeLiteLlm.Key key) {
    }

    /** A company whose bundled gateway passed its test, and a service identity with its key. */
    private Bot bot() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID bot = createServiceIdentity(owner, company, unique("bot"));
        FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, company, bot);
        ModelGateways.awaitStatus(owner, company, bot, "OK");
        return new Bot(company, owner, bot, key);
    }

    @Test
    void aKeyMadeAgainAndAChangeOfTheServiceIdentitysUsersNeverDeadlock(CapturedOutput output) throws Exception {
        Bot bot = bot();
        UUID group = createGroup(bot.owner(), bot.company(), unique("users"), List.of());
        // LiteLLM lost the key: the next sync makes it again, with its secret, its audit records and its state.
        litellm.forget(bot.key().token);
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try (TenantSql holder = TenantSql.in(bot.company().id())) {
            // The change of the users holds the service identity's row and waits before its audit record: the group's
            // row, which its new user row names, is held here.
            holder.rows("SELECT id FROM user_group WHERE id = ? FOR UPDATE", group);
            Future<ApiResponse> users = threads.submit(() -> bot.owner().put(bot.company().path(
                    "/service-identities/" + bot.id() + "/users"), Map.of("groupIds", List.of(group))));
            await().atMost(ModelGateways.WAIT).until(() -> waiting(bot.company().id(), "%INSERT INTO service_identity_user%") == 1);

            // Meanwhile the key is made again: its sync goes through while the change waits (the change locks the row
            // FOR NO KEY UPDATE), or waits for the service identity's row too, never for the change's audit.
            modelAccess.refresh(bot.company().id(), bot.id());
            await().atMost(ModelGateways.WAIT).until(() -> waiting(bot.company().id(), "%principal_model_key%")
                    + waiting(bot.company().id(), "%FOR KEY SHARE%") == 1 || madeAgain(bot));
            holder.commit();

            ApiResponse changed = users.get(60, TimeUnit.SECONDS);
            assertThat(changed.status()).as("%s", changed).isEqualTo(200);
        }
        finally {
            threads.shutdownNow();
        }
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
        // Both went through: the users changed, and the key was made again and recorded.
        FakeLiteLlm.Key again = await().atMost(ModelGateways.WAIT).until(() -> litellm.keyOfAlias(
                ModelGateways.alias(bot.company(), bot.id())).filter(key -> !key.key.equals(bot.key().key)).orElse(null),
                key -> key != null);
        await().atMost(ModelGateways.WAIT).until(() -> again.token.equals(keyId(bot.company().id(), bot.id())));
        ModelGateways.awaitStatus(bot.owner(), bot.company(), bot.id(), "OK");
    }

    @Test
    void aBudgetsCrossedThresholdAndAPrincipalsDeletionNeverDeadlock(CapturedOutput output) throws Exception {
        Bot bot = bot();
        // The period's spend reached 90 % of the budget: the next sync records the 80 % threshold.
        bot.key().spend = bot.key().maxBudget * 0.9;
        try (TenantSql deletion = TenantSql.in(bot.company().id())) {
            // A deletion (or a member's leaving) locks the principal, and then its key (KeyLifecycle.retire).
            deletion.update("SET LOCAL lock_timeout = '20s'");
            deletion.rows("SELECT id FROM principal WHERE id = ? FOR UPDATE", bot.id());
            modelAccess.refresh(bot.company().id(), bot.id());
            await().atMost(ModelGateways.WAIT).until(() -> waiting(bot.company().id(), "%budget_alert%")
                    + waiting(bot.company().id(), "%FOR KEY SHARE%") == 1);
            List<Map<String, Object>> key = deletion.rows("SELECT principal_id FROM principal_model_key "
                    + "WHERE principal_id = ? FOR UPDATE", bot.id());
            assertThat(key).hasSize(1);
            deletion.commit();
        }
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
        // The sync went on after the deletion's transaction (here: nothing deleted) and recorded the threshold.
        await().atMost(ModelGateways.WAIT).until(() -> alerts(bot.company().id(), bot.id()) == 1);
    }

    /** Whether the bot's key was made again at LiteLLM and recorded, its row free. */
    private boolean madeAgain(Bot bot) throws SQLException {
        FakeLiteLlm.Key again = litellm.keyOfAlias(ModelGateways.alias(bot.company(), bot.id()))
            .filter(key -> !key.key.equals(bot.key().key))
            .orElse(null);
        if (again == null || !again.token.equals(keyId(bot.company().id(), bot.id()))) {
            return false;
        }
        try (TenantSql sql = TenantSql.in(bot.company().id())) {
            return sql.count("SELECT count(*) FROM principal_model_key WHERE principal_id = ? "
                    + "AND sync_lease_until IS NULL", bot.id()) == 1;
        }
    }

    /** The backends of this database that wait for a lock while running a statement like this. */
    private static long waiting(UUID companyId, String like) throws SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE ?""", like);
        }
    }

    private static String keyId(UUID companyId, UUID principalId) throws SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return (String) sql.column("SELECT litellm_key_id FROM principal_model_key WHERE principal_id = ?",
                    principalId).getFirst();
        }
    }

    private static long alerts(UUID companyId, UUID principalId) throws SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM budget_alert WHERE principal_id = ?", principalId);
        }
    }
}
