package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** Each gateway's calls go to the adapter of its vendor (ADR-0094), and every vendor has one. */
class GatewayAdaptersTest {

    private final GatewayAdapters adapters = new GatewayAdapters(litellm(),
            new GenericGateway(mock(GatewayHttp.class)));

    private static LiteLlm litellm() {
        LiteLlm litellm = mock(LiteLlm.class);
        when(litellm.vendor()).thenReturn(GatewayVendor.LITELLM);
        return litellm;
    }

    @Test
    void everyVendorHasItsAdapter() {
        for (GatewayVendor vendor : GatewayVendor.values()) {
            Gateway gateway = new Gateway("https://gateway.example.com", "sk-TEST-ONLY-admin-key", false,
                    "https://gateway.example.com", vendor);
            assertThat(adapters.of(gateway).vendor()).isEqualTo(vendor);
        }
    }

    @Test
    void theDispatcherIsNoVendorOfItsOwn() {
        assertThatThrownBy(adapters::vendor).isInstanceOf(UnsupportedOperationException.class);
    }
}
