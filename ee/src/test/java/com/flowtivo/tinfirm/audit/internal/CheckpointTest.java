package com.flowtivo.tinfirm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * A checkpoint's form and signature (ADR-0111, decisions 1 and 7): canonical, chained by its digest, signed with the
 * active key and verified with the public keys alone; a changed head, a key the reader does not trust, and an added
 * field fail; a retired key still verifies what it signed; the configuration refuses what it must.
 */
class CheckpointTest {

    private static final UUID INSTANCE = UUID.fromString("7f1d2c3b-4a59-4e6f-8a7b-9c0d1e2f3a4b");

    private static final UUID COMPANY = UUID.fromString("3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c");

    private final KeyPair current = AnchorKeys.generate();

    private final KeyPair previous = AnchorKeys.generate();

    private Checkpoint checkpoint(long number, String keyId) {
        return new Checkpoint(INSTANCE, number, Instant.parse("2026-11-07T10:00:00.123456789Z"),
                Checkpoint.NO_PREVIOUS, keyId,
                List.of(new Checkpoint.Head(COMPANY, 88123, "a".repeat(64)), new Checkpoint.Head(null, 5512,
                        "b".repeat(64))));
    }

    @Test
    void theCanonicalFormSortsKeysAndPutsTheInstanceChainFirst() {
        String canonical = checkpoint(1, "k2027").canonical();

        assertThat(canonical).isEqualTo("{\"chains\":[{\"companyId\":null,\"hash\":\"" + "b".repeat(64)
                + "\",\"seq\":5512},{\"companyId\":\"" + COMPANY + "\",\"hash\":\"" + "a".repeat(64)
                + "\",\"seq\":88123}],\"instance\":\"" + INSTANCE + "\",\"issuedAt\":\"2026-11-07T10:00:00.123456Z\","
                + "\"keyId\":\"k2027\",\"kind\":\"tinfirm.audit.checkpoint\",\"number\":1,\"prev\":\""
                + "0".repeat(64) + "\",\"v\":1}");
        assertThat(checkpoint(1, "k2027").digest()).hasSize(64).isEqualTo(checkpoint(1, "k2027").digest());
        assertThat(checkpoint(2, "k2027").digest()).isNotEqualTo(checkpoint(1, "k2027").digest());
    }

    @Test
    void aSignedCheckpointVerifiesWithThePublicKeyAloneAndRoundTrips() {
        AnchorKeys signer = AnchorFixtures.keys("k2027", AnchorFixtures.keyMap("k2027", AnchorFixtures.signing(current)));
        String document = checkpoint(1, "k2027").signedDocument(signer);
        AnchorKeys reader = AnchorKeys.verifyingOnly(Map.of("k2027", current.getPublic()));

        Checkpoint.Signed signed = Checkpoint.parse(document, reader);

        assertThat(signed.verified()).as(signed.reason()).isTrue();
        assertThat(signed.checkpoint()).isEqualTo(checkpoint(1, "k2027"));
        assertThat(signed.checkpoint().chain(null).orElseThrow().seq()).isEqualTo(5512);
        assertThat(signed.checkpoint().chain(COMPANY).orElseThrow().seq()).isEqualTo(88123);
        assertThat(document).doesNotContain("\n").doesNotContain("PRIVATE");
    }

    @Test
    void aChangedHeadAnAddedFieldAndAnUntrustedKeyDoNotVerify() {
        AnchorKeys signer = AnchorFixtures.keys("k2027", AnchorFixtures.keyMap("k2027", AnchorFixtures.signing(current)));
        String document = checkpoint(1, "k2027").signedDocument(signer);
        AnchorKeys reader = AnchorKeys.verifyingOnly(Map.of("k2027", current.getPublic()));

        Checkpoint.Signed changed = Checkpoint.parse(document.replace("88123", "88122"), reader);
        Checkpoint.Signed added = Checkpoint.parse(document.replace("\"v\":1}", "\"v\":1,\"x\":1}"), reader);
        Checkpoint.Signed untrusted = Checkpoint.parse(document,
                AnchorKeys.verifyingOnly(Map.of("k2027", previous.getPublic())));
        Checkpoint.Signed unknown = Checkpoint.parse(document,
                AnchorKeys.verifyingOnly(Map.of("k2026", current.getPublic())));

        assertThat(changed.verified()).isFalse();
        assertThat(changed.reason()).contains("does not verify");
        assertThat(added.verified()).isFalse();
        assertThat(added.reason()).contains("canonical");
        assertThat(untrusted.verified()).isFalse();
        assertThat(unknown.verified()).isFalse();
        assertThat(unknown.reason()).contains("not trusted");
        assertThatThrownBy(() -> Checkpoint.parse("{\"kind\":\"export\"}", reader))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void afterARotationTheRetiredKeyStillVerifiesWhatItSignedAndOnlyTheActiveKeySigns() {
        AnchorKeys before = AnchorFixtures.keys("k2026", AnchorFixtures.keyMap("k2026",
                AnchorFixtures.signing(previous)));
        String old = checkpoint(1, "k2026").signedDocument(before);
        // The rotation: the next key signs, the old one's private half is gone and its public half stays.
        AnchorKeys after = AnchorFixtures.keys("k2027", AnchorFixtures.keyMap("k2027", AnchorFixtures.signing(current),
                "k2026", AnchorFixtures.retired(previous)));
        String next = checkpoint(2, "k2027").signedDocument(after);

        assertThat(after.activeId()).contains("k2027");
        assertThat(after.verifying()).containsOnlyKeys("k2027", "k2026");
        assertThat(Checkpoint.parse(old, after).verified()).isTrue();
        assertThat(Checkpoint.parse(next, after).verified()).isTrue();
        assertThatThrownBy(() -> AnchorFixtures.keys("k2026", AnchorFixtures.keyMap("k2027",
                AnchorFixtures.signing(current), "k2026", AnchorFixtures.retired(previous))).sign(new byte[1]))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPrivateKeyGivesItsPublicKeyEvenWithItsLineBreaksEscapedAndAMismatchedPairIsRefused() {
        String escaped = AnchorKeys.pem("PRIVATE KEY", current.getPrivate().getEncoded()).replace("\n", "\\n");
        AnchorKeys keys = AnchorFixtures.keys("k2027", AnchorFixtures.keyMap("k2027",
                new AuditAnchorProperties.Key(escaped, null)));

        assertThat(keys.verifying().get("k2027").getEncoded()).isEqualTo(current.getPublic().getEncoded());
        assertThatThrownBy(() -> AnchorFixtures.keys("k2027", AnchorFixtures.keyMap("k2027",
                new AuditAnchorProperties.Key(AnchorKeys.pem("PRIVATE KEY", current.getPrivate().getEncoded()),
                        AnchorKeys.publicPem(previous.getPublic())))))
            .hasMessageContaining("not the private key's");
        assertThatThrownBy(() -> AnchorFixtures.keys("k2027", AnchorFixtures.keyMap("k2027",
                new AuditAnchorProperties.Key("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----", null))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theConfigurationRefusesASinkWithoutAnActiveKeyAWrongKeyIdOrInterval() {
        Map<String, AuditAnchorProperties.Key> keys = AnchorFixtures.keyMap("k2027", AnchorFixtures.signing(current));

        assertThatThrownBy(() -> AnchorFixtures.properties(AuditAnchorProperties.Sink.FILE, java.nio.file.Path.of(
                "/tmp"), "", keys)).hasMessageContaining("TINFIRM_AUDIT_ANCHOR_ACTIVE_KEY");
        assertThatThrownBy(() -> AnchorFixtures.properties(AuditAnchorProperties.Sink.FILE, java.nio.file.Path.of(
                "/tmp"), "k2026", keys)).hasMessageContaining("has no private key");
        assertThatThrownBy(() -> AnchorFixtures.properties(AuditAnchorProperties.Sink.FILE, null, "k2027", keys))
            .hasMessageContaining("TINFIRM_AUDIT_ANCHOR_FILE_DIR");
        assertThatThrownBy(() -> AnchorFixtures.properties(AuditAnchorProperties.Sink.NONE, null, "",
                AnchorFixtures.keyMap("k-2027", AnchorFixtures.signing(current))))
            .hasMessageContaining("lowercase letters and digits");
        assertThatThrownBy(() -> new AuditAnchorProperties(AuditAnchorProperties.Sink.FILE,
                java.time.Duration.ofMinutes(1), keys, "k2027", null, new AuditAnchorProperties.File(
                        java.nio.file.Path.of("/tmp")), null, java.time.Duration.ofMinutes(1),
                java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(5)))
            .hasMessageContaining("between PT5M and P1D");
        // Keys are read case-insensitively, as the environment's names give them.
        assertThat(AnchorFixtures.properties(AuditAnchorProperties.Sink.NONE, null, "K2027",
                AnchorFixtures.keyMap("K2027", AnchorFixtures.signing(current))).activeKey()).isEqualTo("k2027");
    }
}
