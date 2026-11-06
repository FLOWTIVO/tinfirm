package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.SamlTestKeys;

/** The service provider's keys (ADR-0086, decision 2): what stops the start, and the active credential first. */
class SamlSpCredentialsTest {

    private static final SamlTestKeys K1 = SamlTestKeys.generate("sp-k1");

    private static final SamlTestKeys K2 = SamlTestKeys.generate("sp-k2");

    private final Instant now = Instant.now();

    @Test
    void everyCredentialIsReadAndTheActiveOneComesFirst() {
        SamlSpCredentials credentials = SamlSpCredentials.read(sp("k2", Map.of("k1", credential(K1), "k2",
                credential(K2))), now);

        assertThat(credentials.active().kid()).isEqualTo("k2");
        assertThat(credentials.all()).extracting(SamlSpCredentials.Credential::kid).containsExactly("k2", "k1");
        assertThat(credentials.active().certificate()).isEqualTo(K2.certificate());
        assertThat(credentials.activeEndsSoon(now)).isFalse();
    }

    @Test
    void escapedNewlinesAreRead() {
        SamlProperties.Credential escaped = new SamlProperties.Credential(K1.privateKeyPem().replace("\n", "\\n"),
                K1.certificatePem().replace("\n", "\\n"));

        assertThat(SamlSpCredentials.read(sp("k1", Map.of("k1", escaped)), now).active().kid()).isEqualTo("k1");
    }

    @Test
    void noCredentialNoActiveOneOrAnUnknownOneStopTheStart() {
        assertThatIllegalStateException().isThrownBy(() -> SamlSpCredentials.read(sp("k1", Map.of()), now))
            .withMessageContaining("TINFIRM_AUTH_SAML_SP_CREDENTIALS_<KID>_KEY");
        assertThatIllegalStateException()
            .isThrownBy(() -> SamlSpCredentials.read(sp(null, Map.of("k1", credential(K1))), now))
            .withMessageContaining("TINFIRM_AUTH_SAML_SP_ACTIVE_CREDENTIAL is not set");
        assertThatIllegalStateException()
            .isThrownBy(() -> SamlSpCredentials.read(sp("k9", Map.of("k1", credential(K1))), now))
            .withMessageContaining("names k9, which is not one of the credentials [k1]");
    }

    @Test
    void anActiveCertificateThatEndedStopsTheStartAndOneThatEndsSoonWarns() {
        SamlTestKeys ended = SamlTestKeys.generate("ended", now.minus(Duration.ofDays(400)),
                now.minus(Duration.ofDays(1)));
        assertThatIllegalStateException()
            .isThrownBy(() -> SamlSpCredentials.read(sp("old", Map.of("old", credential(ended))), now))
            .withMessageContaining("ended on");

        SamlTestKeys soon = SamlTestKeys.generate("soon", now.minus(Duration.ofDays(300)), now.plus(Duration.ofDays(10)));
        assertThat(SamlSpCredentials.read(sp("soon", Map.of("soon", credential(soon))), now).activeEndsSoon(now))
            .isTrue();
    }

    @Test
    void aCertificateOfAnotherKeyAWeakKeyOrNoPemStopTheStart() {
        assertThatIllegalStateException()
            .isThrownBy(() -> SamlSpCredentials.read(sp("k1", Map.of("k1",
                    new SamlProperties.Credential(K1.privateKeyPem(), K2.certificatePem()))), now))
            .withMessageContaining("_K1_CERT is not the certificate of TINFIRM_AUTH_SAML_SP_CREDENTIALS_K1_KEY");
        assertThatIllegalStateException()
            .isThrownBy(() -> SamlSpCredentials.read(sp("k1", Map.of("k1",
                    new SamlProperties.Credential(rsa2048(), K1.certificatePem()))), now))
            .withMessageContaining("is RSA of 2048 bits: at least 3072");
        assertThatIllegalStateException()
            .isThrownBy(() -> SamlSpCredentials.read(sp("k1", Map.of("k1",
                    new SamlProperties.Credential("not a key", K1.certificatePem()))), now))
            .withMessageContaining("_K1_KEY is not a PEM PRIVATE KEY");
        assertThatIllegalStateException()
            .isThrownBy(() -> SamlSpCredentials.read(sp("K1", Map.of("K1", credential(K1))), now))
            .withMessageContaining("a key id is 1 to 32 lower-case letters and digits");
    }

    private static SamlProperties.Sp sp(String active, Map<String, SamlProperties.Credential> credentials) {
        return new SamlProperties.Sp(credentials, active);
    }

    private static SamlProperties.Credential credential(SamlTestKeys keys) {
        return new SamlProperties.Credential(keys.privateKeyPem(), keys.certificatePem());
    }

    private static String rsa2048() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] der = generator.generateKeyPair().getPrivate().getEncoded();
        return "-----BEGIN PRIVATE" + " KEY-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END PRIVATE"
                + " KEY-----\n";
    }
}
