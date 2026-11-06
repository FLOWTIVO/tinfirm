package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The model relay's runner host check (the package 2 contract, open point 6): the host's own token must be sent, and
 * be the run token's host's. Every host of runner protocol 2 sends it, so there is no exception for any version
 * (ADR-0109); the check does not even see one.
 */
class ModelRelayHostCheckTest {

    private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void onlyTheRunTokensOwnHostsTokenPasses() {
        assertThat(ModelRelayService.hostBound(HOST, HOST)).isTrue();
        assertThat(ModelRelayService.hostBound(HOST, OTHER)).isFalse();
        assertThat(ModelRelayService.hostBound(HOST, null)).isFalse();
    }
}
