package com.flowtivo.tinfirm.bootstrap.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import com.flowtivo.tinfirm.identity.UserAccounts;
import com.flowtivo.tinfirm.shared.config.PublishedDevSecretsTest;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;

/** L17: the bootstrap password that {@code .env.example} publishes stops the start off localhost. */
class BootstrapServiceTest {

    @Test
    void thePublishedBootstrapPasswordStopsTheStartOffLocalhost() {
        UserAccounts accounts = mock(UserAccounts.class);
        BootstrapService bootstrap = new BootstrapService(
                new BootstrapProperties("admin@example.com",
                        PublishedDevSecretsTest.published("TINFIRM_BOOTSTRAP_ADMIN_PASSWORD")),
                accounts, mock(JdbcClient.class), mock(PlatformTransactionManager.class),
                new TinfirmProperties(URI.create("https://tinfirm.example.com"), Set.of("api", "worker")));

        assertThatThrownBy(bootstrap::bootstrapIfNeeded).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TINFIRM_BOOTSTRAP_ADMIN_PASSWORD")
            .hasMessageNotContaining(PublishedDevSecretsTest.published("TINFIRM_BOOTSTRAP_ADMIN_PASSWORD"));
        verifyNoInteractions(accounts);
    }
}
