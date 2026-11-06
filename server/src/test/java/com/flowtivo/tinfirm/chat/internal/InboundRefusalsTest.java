package com.flowtivo.tinfirm.chat.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Slack's endpoints refuse an address with more than 60 refused requests for a system in a minute (the security review
 * of 2026-10-27, L6), keyed by the system id's canonical form, whatever spelling the path used.
 */
class InboundRefusalsTest {

    @Test
    void anAddressPastSixtyRefusalsAMinuteIsFloodedForThatSystemOnly() {
        InboundRefusals refusals = new InboundRefusals();
        UUID system = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        for (int i = 0; i <= InboundRefusals.REFUSALS_PER_MINUTE; i++) {
            assertThat(refusals.flooded(system, "203.0.113.9", 1_000)).isFalse();
            refusals.refused(system, "203.0.113.9", 1_000);
        }
        assertThat(refusals.flooded(system, "203.0.113.9", 1_000)).isTrue();
        assertThat(refusals.flooded(system, "198.51.100.7", 1_000)).isFalse();
        assertThat(refusals.flooded(other, "203.0.113.9", 1_000)).isFalse();
        // A minute later the address is admitted again.
        assertThat(refusals.flooded(system, "203.0.113.9", 61_000)).isFalse();
    }

    @Test
    void thePathsIdIsReadInItsCanonicalFormOnly() {
        UUID id = UUID.randomUUID();
        assertThat(ChatInboundController.canonicalId(id.toString().toUpperCase(java.util.Locale.ROOT))).isEqualTo(id);
        assertThat(ChatInboundController.canonicalId("1-2-3-4-5")).isNull();
        assertThat(ChatInboundController.canonicalId("not-a-uuid")).isNull();
    }
}
