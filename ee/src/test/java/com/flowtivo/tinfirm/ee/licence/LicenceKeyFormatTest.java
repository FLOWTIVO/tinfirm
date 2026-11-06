package com.flowtivo.tinfirm.ee.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.licence.Edition;

/** ADR-0077, decision 6: the key's form, its signature and its payload's rules. */
class LicenceKeyFormatTest {

    private static final Map<String, PublicKey> TRUSTED = TestLicences.trusted();

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    private static final LocalDate END = LocalDate.of(2026, 12, 31);

    @Test
    void verifiesAKeySignedByATrustedKey() throws Exception {
        String key = TestLicences.key(TestLicences.payload("L-1", 1, "Acme", "PRO", 10, null, START, END));

        LicencePayload payload = LicenceKeyFormat.verify(key, TRUSTED);

        assertThat(payload).isEqualTo(new LicencePayload("L-1", 1, "Acme", Edition.PRO, 10, null, START, END));
        assertThat(LicenceKeyFormat.normalise(" \n" + key + "\t")).isEqualTo(key);
    }

    @Test
    void refusesAKeyOfAnotherSigner() {
        String key = TestLicences.key(TestLicences.payload("L-1", 1, "Acme", "PRO", 10, null, START, END),
                TestLicences.pair().getPrivate());

        assertThatThrownBy(() -> LicenceKeyFormat.verify(key, TRUSTED))
            .isInstanceOf(LicenceKeyFormat.InvalidKey.class)
            .hasMessageContaining("not signed by FLOWTIVO");
    }

    @Test
    void refusesAKeyWhoseKeyIdIsNotTrusted() {
        String key = TestLicences.key(withKeyId(TestLicences.payload("L-1", 1, "Acme", "PRO", 10, null, START, END),
                "flowtivo-2099"));

        assertThatThrownBy(() -> LicenceKeyFormat.verify(key, TRUSTED))
            .isInstanceOf(LicenceKeyFormat.InvalidKey.class)
            .hasMessageContaining("does not trust (flowtivo-2099)");
    }

    /** Version 1, without a key id, is refused. */
    @Test
    void refusesAKeyWithoutAKeyId() {
        String key = TestLicences.key("{\"v\":1,\"id\":\"L-1\",\"rev\":1,\"customer\":\"Acme\",\"edition\":\"PRO\","
                + "\"seats\":10,\"companies\":null,\"startsAt\":\"2026-01-01\",\"endsAt\":\"2026-12-31\"}");

        assertThatThrownBy(() -> LicenceKeyFormat.verify(key, TRUSTED))
            .isInstanceOf(LicenceKeyFormat.InvalidKey.class)
            .hasMessageContaining("does not name the FLOWTIVO key");
    }

    /** Rotation: each key verifies against the public key its id names, and against no other. */
    @Test
    void verifiesAgainstThePublicKeyTheKeyIdNames() throws Exception {
        java.security.KeyPair next = TestLicences.pair();
        Map<String, PublicKey> trusted = Map.of(LicenceTrust.TEST_KEY_ID, TestLicences.publicKey(), "flowtivo-next",
                next.getPublic());
        String payload = TestLicences.payload("L-1", 1, "Acme", "PRO", 10, null, START, END);

        assertThat(LicenceKeyFormat.verify(TestLicences.key(payload), trusted).id()).isEqualTo("L-1");
        assertThat(LicenceKeyFormat.verify(TestLicences.key(withKeyId(payload, "flowtivo-next"), next.getPrivate()),
                trusted).id()).isEqualTo("L-1");
        assertThatThrownBy(() -> LicenceKeyFormat.verify(TestLicences.key(payload, next.getPrivate()), trusted))
            .isInstanceOf(LicenceKeyFormat.InvalidKey.class)
            .hasMessageContaining("not signed by FLOWTIVO");
    }

    @Test
    void refusesAChangedPayload() {
        String key = TestLicences.key(TestLicences.payload("L-1", 1, "Acme", "PRO", 10, null, START, END));
        String[] parts = key.split("\\.");
        String more = Base64.getUrlEncoder().withoutPadding().encodeToString(
                TestLicences.payload("L-1", 1, "Acme", "PRO", 1000, null, START, END).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> LicenceKeyFormat.verify(parts[0] + "." + more + "." + parts[2], TRUSTED))
            .isInstanceOf(LicenceKeyFormat.InvalidKey.class);
    }

    @Test
    void refusesWhatIsNotAKey() {
        for (String text : List.of("", "TINFIRM1.", "TINFIRM2.abc.def", "TINFIRM1.abc", "TINFIRM1.a+b.c",
                "TINFIRM1.abc.def.ghi")) {
            assertThatThrownBy(() -> LicenceKeyFormat.verify(text, TRUSTED)).as(text)
                .isInstanceOf(LicenceKeyFormat.InvalidKey.class);
        }
    }

    @Test
    void refusesPayloadsOutsideTheRules() {
        List<String> payloads = List.of(
                TestLicences.payload("L-1", 1, "Acme", "PRO", null, null, START, END),
                TestLicences.payload("L-1", 1, "Acme", "PRO", 10, 2, START, END),
                TestLicences.payload("L-1", 1, "Acme", "TEAM", 10, null, START, END),
                TestLicences.payload("L-1", 1, "Acme", "PRO", 0, null, START, END),
                TestLicences.payload("L-1", 1, "Acme", "PRO", 10, null, END, START),
                TestLicences.payload("L 1", 1, "Acme", "PRO", 10, null, START, END),
                TestLicences.payload("L-1", 1, " ", "PRO", 10, null, START, END),
                withKeyId(TestLicences.payload("L-1", 1, "Acme", "PRO", 10, null, START, END), "Not A Key Id"),
                "{\"v\":2,\"kid\":\"test\",\"id\":\"L-1\",\"customer\":\"Acme\",\"edition\":\"PRO\",\"seats\":10,"
                        + "\"companies\":null,\"startsAt\":\"2026-01-01\",\"endsAt\":\"2026-12-31\"}",
                TestLicences.payload("L-1", 0, "Acme", "PRO", 10, null, START, END),
                "{\"v\":3,\"kid\":\"test\",\"id\":\"L-1\",\"rev\":1,\"customer\":\"Acme\",\"edition\":\"PRO\","
                        + "\"seats\":10,\"companies\":null,\"startsAt\":\"2026-01-01\",\"endsAt\":\"2026-12-31\"}",
                "{\"v\":2,\"kid\":\"test\",\"id\":\"L-1\",\"rev\":1,\"customer\":\"Acme\",\"edition\":\"PRO\","
                        + "\"seats\":10,\"companies\":null,\"startsAt\":\"2026-01-01\",\"endsAt\":\"2026-12-31\","
                        + "\"extra\":true}",
                "{\"v\":2,\"kid\":\"test\",\"id\":\"L-1\",\"rev\":1,\"customer\":\"Acme\",\"edition\":\"PRO\","
                        + "\"seats\":10,\"companies\":null,\"startsAt\":\"2026-01-01\",\"endsAt\":\"31.12.2026\"}",
                "[]", "not json");
        for (String payload : payloads) {
            assertThatThrownBy(() -> LicenceKeyFormat.verify(TestLicences.key(payload), TRUSTED)).as(payload)
                .isInstanceOf(LicenceKeyFormat.InvalidKey.class);
        }
    }

    @Test
    void readsAnEnterpriseKeyWithoutCounts() throws Exception {
        String key = TestLicences.key(TestLicences.payload("E-1", 1, "Big", "ENTERPRISE", null, null, START, END));

        assertThat(LicenceKeyFormat.verify(key, TRUSTED))
            .isEqualTo(new LicencePayload("E-1", 1, "Big", Edition.ENTERPRISE, null, null, START, END));
    }

    /** The end-to-end harness's public key: trusted under the {@code e2e} profile, refused at the start without it. */
    @Test
    void theTestPublicKeyIsTrustedOnlyUnderTheE2eOrTestProfile() throws Exception {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(TestLicences.publicKey().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
        var none = new org.springframework.beans.factory.support.StaticListableBeanFactory()
            .getBeanProvider(TestLicenceTrust.class);

        assertThatThrownBy(() -> new LicenceTrust(new org.springframework.mock.env.MockEnvironment(), none, pem))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TINFIRM_LICENCE_TEST_PUBLIC_KEY");

        var e2e = new org.springframework.mock.env.MockEnvironment();
        e2e.setActiveProfiles("e2e");
        LicenceTrust trust = new LicenceTrust(e2e, none, pem);
        String key = TestLicences.key(withKeyId(TestLicences.payload("L-1", 1, "Acme", "PRO", 10, null, START, END),
                LicenceTrust.E2E_KEY_ID));
        assertThat(trust.keys()).hasSize(LicenceTrust.bundled().size() + 1).containsKey(LicenceTrust.E2E_KEY_ID);
        assertThat(LicenceKeyFormat.verify(key, trust.keys()).id()).isEqualTo("L-1");
    }

    @Test
    void theBundledPublicKeysAreEd25519KeysNamedByTheirKeyIds() {
        Map<String, PublicKey> keys = new LicenceTrust(new org.springframework.mock.env.MockEnvironment(),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                    .getBeanProvider(TestLicenceTrust.class), null).keys();

        assertThat(keys).isNotEmpty().doesNotContainKeys(LicenceTrust.TEST_KEY_ID, LicenceTrust.E2E_KEY_ID);
        keys.forEach((id, key) -> {
            assertThat(id).matches(LicenceKeyFormat.KEY_ID);
            assertThat(key.getAlgorithm()).isIn("Ed25519", "EdDSA");
        });
    }

    private static String withKeyId(String payload, String keyId) {
        return payload.replace("\"kid\":\"" + LicenceTrust.TEST_KEY_ID + "\"", "\"kid\":\"" + keyId + "\"");
    }
}
