package com.flowtivo.tinfirm.diagnostics.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** ADR-0070, §5: the destination is a Sentry DSN over https, or http to a loopback address. */
class DsnTest {

    @Test
    void findsTheEnvelopeEndpointAndTheKey() {
        Dsn dsn = Dsn.parse("https://0123456789abcdef0123456789abcdef@errors.example.com/7");
        assertThat(dsn.envelope()).hasToString("https://errors.example.com/api/7/envelope/");
        assertThat(dsn.publicKey()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(dsn.host()).isEqualTo("errors.example.com");
    }

    @Test
    void keepsAPathPrefixAndAPortAndDropsAnOldSecretKey() {
        Dsn dsn = Dsn.parse("https://key-1:secret@sentry.example.com:8443/tracker/12/");
        assertThat(dsn.envelope()).hasToString("https://sentry.example.com:8443/tracker/api/12/envelope/");
        assertThat(dsn.publicKey()).isEqualTo("key-1");
    }

    @Test
    void takesHttpToALoopbackAddressOnly() {
        assertThat(Dsn.parse("http://k@localhost:9000/1").envelope())
            .hasToString("http://localhost:9000/api/1/envelope/");
        assertThat(Dsn.parse("http://k@127.0.0.1:9000/1").host()).isEqualTo("127.0.0.1");
        assertThat(Dsn.parse("http://k@[::1]:9000/1").envelope()).hasToString("http://[::1]:9000/api/1/envelope/");
        assertThatThrownBy(() -> Dsn.parse("http://k@errors.example.com/1"))
            .hasMessageContaining("must use https");
    }

    @Test
    void refusesWhatIsNoDsnWithoutNamingTheKey() {
        assertThatThrownBy(() -> Dsn.parse("https://errors.example.com/1")).hasMessageContaining("public key");
        assertThatThrownBy(() -> Dsn.parse("https://k@errors.example.com/project"))
            .hasMessageContaining("numeric project id");
        assertThatThrownBy(() -> Dsn.parse("https://s3cr3t!key@errors.example.com/1"))
            .hasMessageNotContaining("s3cr3t");
        assertThatThrownBy(() -> Dsn.parse("https://k@errors.example.com/1?x=1")).hasMessageContaining("query");
        assertThat(Dsn.parse("https://k@errors.example.com/1")).hasToString(
                "Dsn[https://errors.example.com/api/1/envelope/]");
    }
}
