package com.flowtivo.tinfirm.shared.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The proxy of the guarded outbound clients (the security review of 2026-10-27, L12): none, whatever the JVM's own
 * settings say, unless the operator names one in {@code tinfirm.outbound.proxy}.
 */
class OutboundProxyTest {

    @Test
    void withoutTheSettingNoProxyIsUsed() {
        assertThat(OutboundProxy.selector(new MockEnvironment())).isSameAs(HttpClient.Builder.NO_PROXY);
        assertThat(OutboundProxy.selector(" ", Set.of())).isSameAs(HttpClient.Builder.NO_PROXY);
        assertThat(HttpClient.Builder.NO_PROXY.select(URI.create("https://jira.example.com/")))
            .containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void anExplicitProxyCarriesEveryHostButTheDirectOnes() {
        ProxySelector selector = OutboundProxy.selector(new MockEnvironment()
            .withProperty(OutboundProxy.PROPERTY, "http://127.0.0.1:3128"));
        Proxy proxy = selector.select(URI.create("https://jira.example.com/rest")).getFirst();
        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(((InetSocketAddress) proxy.address()).getPort()).isEqualTo(3128);

        ProxySelector withDirect = OutboundProxy.selector("http://127.0.0.1:3128", Set.of("LiteLLM.internal"));
        assertThat(withDirect.select(URI.create("http://litellm.internal:4000/key/info")))
            .containsExactly(Proxy.NO_PROXY);
        assertThat(withDirect.select(URI.create("https://gateway.example.com/")).getFirst().type())
            .isEqualTo(Proxy.Type.HTTP);
    }

    @Test
    void aMalformedProxyRefusesToStartWithoutEchoingIt() {
        for (String value : new String[] { "127.0.0.1:3128", "https://127.0.0.1:3128", "http://127.0.0.1",
                "http://user:secret@127.0.0.1:3128", "http://127.0.0.1:3128/path", "socks5://127.0.0.1:1080" }) {
            assertThatThrownBy(() -> OutboundProxy.selector(value, Set.of())).as(value)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http://host:port")
                .hasMessageNotContaining("secret");
        }
    }
}
