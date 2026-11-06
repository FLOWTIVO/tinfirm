package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The rules of key rotation as functions (CONVENTIONS §15.4): the interval, grace and settling time the server
 * accepts, the aliases of a principal's keys, when a key is due, and what of an old key's spend goes over to its
 * successor.
 */
class KeyRotationRulesTest {

    private static final UUID COMPANY = UUID.fromString("0b1a6a52-4d3e-4c6f-9a51-2f7f3c0d9e11");

    private static final UUID PRINCIPAL = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private static ModelProperties properties(Duration interval, Duration grace) {
        return properties(interval, grace, Duration.ofMinutes(1));
    }

    private static ModelProperties properties(Duration interval, Duration grace, Duration settle) {
        return new ModelProperties(null, null, Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofMinutes(10),
                67_108_864L, 16, 16, interval, grace, settle, 268_435_456L, false, null, Duration.ofSeconds(30), null,
                null, GatewayVendor.LITELLM);
    }

    @Test
    void theIntervalIsAtLeastADayAndTheGraceAtMostAnHour() {
        assertThat(properties(Duration.ofDays(30), Duration.ofMinutes(10)).keyRotationInterval())
            .isEqualTo(Duration.ofDays(30));
        assertThat(properties(Duration.ofDays(1), Duration.ZERO).keyRotationGrace()).isZero();
        assertThat(properties(Duration.ofDays(1), Duration.ofHours(1)).keyRotationGrace()).isEqualTo(Duration.ofHours(1));
        assertThatThrownBy(() -> properties(Duration.ofHours(23), Duration.ofMinutes(10)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("key-rotation-interval");
        assertThatThrownBy(() -> properties(Duration.ofDays(30), Duration.ofMinutes(61)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("key-rotation-grace");
        assertThatThrownBy(() -> properties(Duration.ofDays(30), Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(properties(Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofHours(1)).keyRotationSettle())
            .isEqualTo(Duration.ofHours(1));
        assertThatThrownBy(() -> properties(Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofMinutes(61)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("key-rotation-settle");
        assertThat(ModelSettingsService.iso(Duration.ofDays(30))).isEqualTo("P30D");
        assertThat(ModelSettingsService.iso(Duration.ofMinutes(10))).isEqualTo("PT10M");
        assertThat(ModelSettingsService.iso(Duration.ofHours(36))).isEqualTo("PT36H");
        assertThat(ModelSettingsService.iso(Duration.ZERO)).isEqualTo("PT0S");
    }

    @Test
    void eachGenerationHasItsOwnAliasWhichNamesThePrincipal() {
        String prefix = "tinfirm-" + COMPANY + "-";
        assertThat(Gateway.keyAlias(COMPANY, PRINCIPAL)).isEqualTo(prefix + PRINCIPAL);
        assertThat(Gateway.keyAlias(COMPANY, PRINCIPAL, 0)).isEqualTo(prefix + PRINCIPAL);
        assertThat(Gateway.keyAlias(COMPANY, PRINCIPAL, 3)).isEqualTo(prefix + PRINCIPAL + "-r3");
        assertThat(Gateway.principalOf(prefix + PRINCIPAL, COMPANY)).contains(PRINCIPAL);
        assertThat(Gateway.principalOf(prefix + PRINCIPAL + "-r12", COMPANY)).contains(PRINCIPAL);
        assertThat(Gateway.principalOf(prefix + "not-a-principal", COMPANY)).isEmpty();
        assertThat(Gateway.principalOf("tinfirm-" + UUID.randomUUID() + "-" + PRINCIPAL, COMPANY)).isEmpty();
    }

    @Test
    void aKeyIsDueWhenAsOldAsTheIntervalOrAskedFor() {
        Duration interval = Duration.ofDays(30);
        assertThat(KeyRotation.rotationDue(row(NOW.minus(Duration.ofDays(29)), null, true), NOW, interval)).isFalse();
        assertThat(KeyRotation.rotationDue(row(NOW.minus(Duration.ofDays(30)), null, true), NOW, interval)).isTrue();
        assertThat(KeyRotation.rotationDue(row(NOW.minus(Duration.ofMinutes(1)), NOW, true), NOW, interval)).isTrue();
        // Without a key there is nothing to rotate: the sync creates one, which answers the request.
        assertThat(KeyRotation.rotationDue(row(null, NOW, false), NOW, interval)).isFalse();
    }

    @Test
    void anOldKeysLateSpendGoesOverOnceAndAllOfItAfterItsPeriodRestarted() {
        Instant resets = Instant.parse("2026-11-01T00:00:00Z");
        ModelKeyStore.Rotation carried = retiring(new BigDecimal("7.5"), resets);
        assertThat(KeyRotation.lateSpend(state(8.25, resets), carried)).isEqualTo(0.75);
        assertThat(KeyRotation.lateSpend(state(7.5, resets), carried)).isZero();
        // A spend below what was carried (LiteLLM never lowers it in a period) moves nothing.
        assertThat(KeyRotation.lateSpend(state(7.0, resets), carried)).isZero();
        // The period restarted between the rotation and the old key's end: all it spent since is the new period's.
        assertThat(KeyRotation.lateSpend(state(0.4, Instant.parse("2026-12-01T00:00:00Z")), carried)).isEqualTo(0.4);
    }

    private static ModelKeyStore.KeyRow row(Instant keyCreatedAt, Instant requestedAt, boolean hasKey) {
        return new ModelKeyStore.KeyRow(KeyHolder.principal(COMPANY, PRINCIPAL), hasKey ? UUID.randomUUID() : null,
                hasKey ? "a".repeat(64) : null, null, null, ModelKeyStore.KeyStatus.OK, null, null, null, null, null,
                NOW, NOW, new ModelKeyStore.Rotation(0, keyCreatedAt, null, requestedAt, null, null, null, null, null,
                        null),
                null);
    }

    private static ModelKeyStore.Rotation retiring(BigDecimal spend, Instant resetsAt) {
        return new ModelKeyStore.Rotation(1, NOW, NOW, null, null, "b".repeat(64), NOW.plus(Duration.ofMinutes(10)),
                null, spend, resetsAt);
    }

    private static GatewayAdapter.KeyState state(double spend, Instant resetsAt) {
        return new GatewayAdapter.KeyState("b".repeat(64), "alias", true, spend, 25.0, "30d", resetsAt, List.of());
    }
}
