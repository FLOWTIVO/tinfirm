package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.flowtivo.tinfirm.model.SpendLogs;
import com.flowtivo.tinfirm.shared.litellm.LiteLlmRequests;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The prices the spend sync settles a run with from the relay's count (CONVENTIONS §15.12, step 5), kept an hour per
 * company for the settings they were read under: the same settings within the hour read LiteLLM once; a change of
 * the gateway reads them again at once; LiteLLM failing after a change gives nothing rather than the old gateway's
 * prices, failing without a change the copy kept; and a gateway that has not passed its test gives nothing without a
 * call.
 */
class SpendLogsImplTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    private static final Gateway OLD = new Gateway("http://old.litellm.test", "sk-TEST-ONLY-old", false);

    private static final Gateway NEW = new Gateway("http://new.litellm.test", "sk-TEST-ONLY-new", false);

    private static final Map<String, SpendLogs.Price> OLD_PRICES = Map.of("claude-x",
            new SpendLogs.Price(new BigDecimal("0.000003"), new BigDecimal("0.000015"), null, null));

    private static final Map<String, SpendLogs.Price> NEW_PRICES = Map.of("gpt-5.4",
            new SpendLogs.Price(new BigDecimal("0.000002"), new BigDecimal("0.000008"), null, null));

    private final SystemTransactions transactions = mock(SystemTransactions.class);

    private final ModelSettingsStore settings = mock(ModelSettingsStore.class);

    private final Gateways gateways = mock(Gateways.class);

    private final LiteLlm litellm = mock(LiteLlm.class);

    private final TimeSource time = mock(TimeSource.class);

    private final SpendLogsImpl logs = new SpendLogsImpl(transactions, settings, gateways, litellm, time);

    SpendLogsImplTest() {
        when(transactions.inCompany(any(), ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(time.now()).thenReturn(NOW);
    }

    /** The company's settings as last changed at {@code updatedAt}, naming the gateway; untested without one. */
    private void settings(Instant updatedAt, Gateway gateway) {
        ModelSettingsStore.SettingsRow row = new ModelSettingsStore.SettingsRow(COMPANY,
                ModelSettingsStore.GatewayKind.EXTERNAL, gateway != null ? gateway.baseUrl() : null,
                gateway != null ? UUID.randomUUID() : null, gateway != null, new BigDecimal("50"), "MONTHLY",
                List.of(), gateway != null ? updatedAt : null, null, updatedAt, null,
                LiteLlmRequests.ProviderTools.OFF);
        when(settings.find(COMPANY)).thenReturn(Optional.of(row));
        when(gateways.of(row)).thenReturn(Optional.ofNullable(gateway));
    }

    @Test
    void theSameSettingsWithinTheHourReadLiteLlmOnce() {
        settings(NOW.minus(Duration.ofDays(1)), OLD);
        when(litellm.prices(OLD)).thenReturn(OLD_PRICES);

        assertThat(logs.prices(COMPANY)).isEqualTo(OLD_PRICES);
        when(time.now()).thenReturn(NOW.plus(Duration.ofMinutes(59)));
        assertThat(logs.prices(COMPANY)).isEqualTo(OLD_PRICES);
        verify(litellm, times(1)).prices(OLD);

        // An hour on, they are read again.
        when(time.now()).thenReturn(NOW.plus(Duration.ofMinutes(61)));
        assertThat(logs.prices(COMPANY)).isEqualTo(OLD_PRICES);
        verify(litellm, times(2)).prices(OLD);
    }

    @Test
    void aChangeOfTheGatewayReadsThePricesAgainWithinTheHour() {
        settings(NOW.minus(Duration.ofDays(1)), OLD);
        when(litellm.prices(OLD)).thenReturn(OLD_PRICES);
        assertThat(logs.prices(COMPANY)).isEqualTo(OLD_PRICES);

        settings(NOW.plus(Duration.ofMinutes(1)), NEW);
        when(litellm.prices(NEW)).thenReturn(NEW_PRICES);
        when(time.now()).thenReturn(NOW.plus(Duration.ofMinutes(2)));
        assertThat(logs.prices(COMPANY)).isEqualTo(NEW_PRICES);
        verify(litellm, times(1)).prices(OLD);
        verify(litellm, times(1)).prices(NEW);
    }

    @Test
    void liteLlmFailingAfterAChangeGivesNothingNotTheOldGatewaysPrices() {
        settings(NOW.minus(Duration.ofDays(1)), OLD);
        when(litellm.prices(OLD)).thenReturn(OLD_PRICES);
        assertThat(logs.prices(COMPANY)).isEqualTo(OLD_PRICES);

        settings(NOW.plus(Duration.ofMinutes(1)), NEW);
        when(litellm.prices(NEW)).thenThrow(new LiteLlmException(503, "LiteLLM answered 503"));
        when(time.now()).thenReturn(NOW.plus(Duration.ofMinutes(2)));
        assertThat(logs.prices(COMPANY)).isEmpty();
    }

    @Test
    void liteLlmFailingWithoutAChangeGivesTheCopyKept() {
        settings(NOW.minus(Duration.ofDays(1)), OLD);
        when(litellm.prices(OLD)).thenReturn(OLD_PRICES);
        assertThat(logs.prices(COMPANY)).isEqualTo(OLD_PRICES);

        when(litellm.prices(OLD)).thenThrow(new LiteLlmException(503, "LiteLLM answered 503"));
        when(time.now()).thenReturn(NOW.plus(Duration.ofHours(2)));
        assertThat(logs.prices(COMPANY)).isEqualTo(OLD_PRICES);
        verify(litellm, times(2)).prices(OLD);
    }

    @Test
    void aGatewayThatHasNotPassedItsTestGivesNothingWithoutACall() {
        settings(NOW.minus(Duration.ofDays(1)), null);
        assertThat(logs.prices(COMPANY)).isEmpty();

        when(settings.find(COMPANY)).thenReturn(Optional.empty());
        assertThat(logs.prices(COMPANY)).isEmpty();
        verifyNoInteractions(litellm);
    }
}
