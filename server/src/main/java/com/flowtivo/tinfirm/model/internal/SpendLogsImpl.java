package com.flowtivo.tinfirm.model.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.model.SpendLogs;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * {@link SpendLogs} over the company's gateway: the settings and the admin key are read in a short transaction of the
 * company, and LiteLLM is called outside any (CONVENTIONS §10). The prices are kept an hour per company, for the
 * settings they were read under ({@code model_settings.updated_at}, as the module keys its other caches of a
 * gateway): a change of the gateway reads them again at once, and a read that fails after a change gives nothing
 * rather than the old gateway's prices.
 */
@Component
class SpendLogsImpl implements SpendLogs {

    private static final Logger log = LoggerFactory.getLogger(SpendLogsImpl.class);

    private static final Duration KEEP_PRICES = Duration.ofHours(1);

    private final SystemTransactions transactions;

    private final ModelSettingsStore settings;

    private final Gateways gateways;

    private final GatewayAdapter adapter;

    private final TimeSource time;

    private final Map<UUID, CachedPrices> prices = new ConcurrentHashMap<>();

    /** The prices read under the settings of {@code updatedAt}, kept until {@code until}. */
    private record CachedPrices(Instant updatedAt, Map<String, Price> prices, Instant until) {
    }

    /** The settings' identity and the gateway they name. */
    private record Target(Instant updatedAt, Gateway gateway) {
    }

    SpendLogsImpl(SystemTransactions transactions, ModelSettingsStore settings, Gateways gateways,
            GatewayAdapter adapter, TimeSource time) {
        this.transactions = transactions;
        this.settings = settings;
        this.gateways = gateways;
        this.adapter = adapter;
        this.time = time;
    }

    @Override
    public List<Entry> of(UUID companyId, Collection<String> keyIds, Instant from, Instant to) {
        NoTransaction.require("Reading LiteLLM's spend logs");
        Gateway gateway = target(companyId).map(Target::gateway)
            .orElseThrow(() -> new Unavailable("The company's model gateway has not passed its test"));
        LocalDate first = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate last = to.atZone(ZoneOffset.UTC).toLocalDate();
        List<Entry> entries = new ArrayList<>();
        for (String keyId : new LinkedHashSet<>(keyIds)) {
            try {
                entries.addAll(adapter.spendLogs(gateway, keyId, first, last));
            }
            catch (LiteLlmException ex) {
                throw new Unavailable(ex.getMessage());
            }
        }
        return entries;
    }

    @Override
    public Map<String, Price> prices(UUID companyId) {
        NoTransaction.require("Reading LiteLLM's model prices");
        Target target = target(companyId).orElse(null);
        if (target == null) {
            prices.remove(companyId);
            return Map.of();
        }
        Instant now = time.now();
        CachedPrices cached = prices.get(companyId);
        // A copy read under other settings (the gateway changed since) is never served.
        CachedPrices same = cached != null && cached.updatedAt().equals(target.updatedAt()) ? cached : null;
        if (same != null && now.isBefore(same.until())) {
            return same.prices();
        }
        try {
            Map<String, Price> read = Map.copyOf(adapter.prices(target.gateway()));
            prices.put(companyId, new CachedPrices(target.updatedAt(), read, now.plus(KEEP_PRICES)));
            return read;
        }
        catch (LiteLlmException ex) {
            log.info("The model prices of company {} could not be read: {}", companyId, ex.getMessage());
            // The stale copy serves only the gateway it was read from.
            return same != null ? same.prices() : Map.of();
        }
    }

    @Override
    public boolean recorded(UUID companyId) {
        return transactions.inCompany(companyId, () -> settings.find(companyId)
            .map(row -> row.gateway() != ModelSettingsStore.GatewayKind.PROVIDED)
            .orElse(true));
    }

    /**
     * The gateway of the company's settings and the settings' identity, read in one short transaction; empty while
     * the gateway has not passed its test or cannot be reached at all, and for a gateway whose records Tinfirm does
     * not read ({@link #recorded}).
     */
    private Optional<Target> target(UUID companyId) {
        return transactions.inCompany(companyId, () -> settings.find(companyId)
            .filter(ModelSettingsStore.SettingsRow::relayEnabled)
            .flatMap(row -> gateways.of(row)
                .filter(Gateway::managed)
                .map(gateway -> new Target(row.updatedAt(), gateway))));
    }
}
