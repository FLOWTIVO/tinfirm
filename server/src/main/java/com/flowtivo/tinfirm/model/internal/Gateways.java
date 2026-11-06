package com.flowtivo.tinfirm.model.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.vault.RevealedSecret;
import com.flowtivo.tinfirm.vault.VaultService;

/**
 * A company's gateway from its settings: the deployment's bundled LiteLLM and its master key, or the company's own and
 * the admin key its settings keep in the vault. An {@code EXTERNAL} key is revealed (audited as
 * {@code secret.accessed}, purpose {@code model-gateway}) at most every ten minutes per company and kept in memory
 * meanwhile; a change of the settings is a new entry. The teams this process made sure of are remembered per gateway
 * with the alias they were given (the company's name), so that a team is looked up once, and once more after a rename.
 */
@Component
class Gateways {

    static final String PURPOSE = "model-gateway";

    private static final Duration KEEP = Duration.ofMinutes(10);

    private final ModelProperties properties;

    private final VaultService vault;

    private final TimeSource time;

    private final Map<UUID, Cached> external = new ConcurrentHashMap<>();

    /** The teams this process made sure of, by gateway and team, with the alias (the company's name) it gave them. */
    private final Map<String, String> teams = new ConcurrentHashMap<>();

    /**
     * One lock per team, held while a team is made sure of and remembered: one per company this process has seen,
     * whatever gateways its settings named meanwhile, so never pruned (a lock taken away from under its holder would
     * let a second ensure of the team run beside it).
     */
    private final Map<String, Object> teamLocks = new ConcurrentHashMap<>();

    Gateways(ModelProperties properties, VaultService vault, TimeSource time) {
        this.properties = properties;
        this.vault = vault;
        this.time = time;
    }

    private record Cached(Instant updatedAt, UUID secretId, Gateway gateway, Instant until) {
    }

    /**
     * The gateway of the settings; empty when it cannot be reached at all: {@code BUNDLED} on a deployment that bundles
     * none, or {@code EXTERNAL} without its key. Runs in the company's tenant context and a transaction (for the
     * reveal).
     */
    Optional<Gateway> of(ModelSettingsStore.SettingsRow settings) {
        if (settings.gateway() == ModelSettingsStore.GatewayKind.BUNDLED) {
            return bundled();
        }
        if (settings.gateway() == ModelSettingsStore.GatewayKind.PROVIDED) {
            return Optional.of(Gateway.provided(Objects.requireNonNull(settings.modelUrl()), settings.vendor()));
        }
        UUID secretId = settings.adminKeySecretId();
        String url = settings.adminUrl();
        if (secretId == null || url == null) {
            return Optional.empty();
        }
        Instant now = time.now();
        Cached cached = external.get(settings.companyId());
        if (cached != null && cached.updatedAt().equals(settings.updatedAt()) && cached.secretId().equals(secretId)
                && now.isBefore(cached.until())) {
            return Optional.of(cached.gateway());
        }
        try (RevealedSecret key = vault.reveal(settings.companyId(), secretId, PURPOSE)) {
            Gateway gateway = new Gateway(url, key.asString(), false, url, settings.vendor());
            external.put(settings.companyId(), new Cached(settings.updatedAt(), secretId, gateway, now.plus(KEEP)));
            return Optional.of(gateway);
        }
    }

    /** The deployment's bundled gateway; empty when it bundles none. */
    Optional<Gateway> bundled() {
        if (!properties.bundled()) {
            return Optional.empty();
        }
        return Optional.of(new Gateway(Objects.requireNonNull(properties.gatewayAdminUrl()).trim(),
                Objects.requireNonNull(properties.gatewayAdminKey()).trim(), true,
                Objects.requireNonNull(properties.bundledModelUrl()).trim(), properties.gatewayVendor()));
    }

    /** Forgets what this process knows of the company's gateway (its settings changed, or it refused the key). */
    void forget(UUID companyId) {
        external.remove(companyId);
        teams.keySet().removeIf(entry -> entry.endsWith("|" + Gateway.teamId(companyId)));
    }

    /**
     * Makes sure of the team at the gateway with this alias (the company's name) through {@code ensure}, unless this
     * process did so already, and remembers the alias. One at a time per team: two syncs that carry different names
     * (one planned before a rename, one after) would otherwise interleave their calls and their writes here, and the
     * alias remembered could be one LiteLLM does not hold, which no later sync would put right.
     */
    void ensureTeam(Gateway gateway, String teamId, String alias, Runnable ensure) {
        String key = gateway.baseUrl() + "|" + teamId;
        Object lock = teamLocks.computeIfAbsent(teamId, ignored -> new Object());

        synchronized (lock) {
            if (alias.equals(teams.get(key))) {
                return;
            }
            ensure.run();
            teams.put(key, alias);
        }
    }


    void teamUnknown(Gateway gateway, String teamId) {
        teams.remove(gateway.baseUrl() + "|" + teamId);
    }

    /** The admin URL of an {@code EXTERNAL} gateway as shown, or null for the bundled one. */
    static @Nullable String shownUrl(ModelSettingsStore.SettingsRow settings) {
        return settings.gateway() == ModelSettingsStore.GatewayKind.EXTERNAL ? settings.adminUrl() : null;
    }
}
