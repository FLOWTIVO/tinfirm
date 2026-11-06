package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class BundleCipherTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static SecretKey key() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return new SecretKeySpec(key, "AES");
    }

    private static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] decrypt(SecretKey key, UUID name, byte[] sealed) throws IOException {
        try (InputStream in = BundleCipher.decrypt(key, name, new ByteArrayInputStream(sealed))) {
            return in.readAllBytes();
        }
    }

    @Test
    void everySizeComesBackAsItWas() throws IOException {
        SecretKey key = key();
        UUID name = UUID.randomUUID();
        int chunk = BundleCipher.CHUNK_BYTES;
        for (int size : new int[] { 0, 1, 4096, chunk - 1, chunk, chunk + 1, 3 * chunk, 3 * chunk + 17 }) {
            byte[] bundle = bytes(size);
            byte[] sealed = BundleCipher.encrypt(key, name, bundle);
            assertThat(decrypt(key, name, sealed)).as("size %d", size).isEqualTo(bundle);
            // No plaintext in the object.
            if (size >= 4096) {
                assertThat(indexOf(sealed, Arrays.copyOf(bundle, 64))).isNegative();
            }
        }
    }

    /** A bundle sealed as it streams (ADR-0101), in pieces of any size, is the object {@code encrypt} makes. */
    @Test
    void aBundleSealedAsItStreamsReadsBackAsItWas() throws IOException {
        SecretKey key = key();
        UUID name = UUID.randomUUID();
        int chunk = BundleCipher.CHUNK_BYTES;
        for (int size : new int[] { 0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 17 }) {
            byte[] bundle = bytes(size);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (BundleCipher.Sealing sealing = BundleCipher.sealing(key, name, out)) {
                for (int from = 0; from < size; from += 333_333) {
                    sealing.write(bundle, from, Math.min(333_333, size - from));
                }
                sealing.finish();
            }
            byte[] sealed = out.toByteArray();
            assertThat(decrypt(key, name, sealed)).as("size %d", size).isEqualTo(bundle);
            assertThat(sealed).as("size %d", size).hasSameSizeAs(BundleCipher.encrypt(key, name, bundle));
        }
        // Without its last chunk the object fails its check.
        java.io.ByteArrayOutputStream cut = new java.io.ByteArrayOutputStream();
        try (BundleCipher.Sealing sealing = BundleCipher.sealing(key, name, cut)) {
            sealing.write(bytes(chunk + 5));
        }
        assertThatThrownBy(() -> decrypt(key, name, cut.toByteArray())).isInstanceOf(IOException.class);
    }

    @Test
    void aChangedByteAnotherObjectsNameOrKeyFailsTheRead() {
        SecretKey key = key();
        UUID name = UUID.randomUUID();
        byte[] bundle = bytes(2 * BundleCipher.CHUNK_BYTES + 100);
        byte[] sealed = BundleCipher.encrypt(key, name, bundle);
        for (int at : new int[] { 20, BundleCipher.CHUNK_BYTES + 40, sealed.length - 1 }) {
            byte[] changed = sealed.clone();
            changed[at] ^= 1;
            assertThatThrownBy(() -> decrypt(key, name, changed)).as("byte %d", at).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> decrypt(key, UUID.randomUUID(), sealed)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> decrypt(key(), name, sealed)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> decrypt(key, name, Arrays.copyOf(sealed, 10))).isInstanceOf(IOException.class);
    }

    @Test
    void anObjectCutAtAChunksEndOrWithItsChunksSwappedFails() {
        SecretKey key = key();
        UUID name = UUID.randomUUID();
        int chunk = BundleCipher.CHUNK_BYTES;
        byte[] sealed = BundleCipher.encrypt(key, name, bytes(3 * chunk));
        int header = 16;
        int sealedChunk = chunk + 16;
        // Cut after the first two full chunks: they were not the last ones.
        assertThatThrownBy(() -> decrypt(key, name, Arrays.copyOf(sealed, header + 2 * sealedChunk)))
            .isInstanceOf(IOException.class);
        // The first two chunks swapped.
        byte[] swapped = sealed.clone();
        System.arraycopy(sealed, header, swapped, header + sealedChunk, sealedChunk);
        System.arraycopy(sealed, header + sealedChunk, swapped, header, sealedChunk);
        assertThatThrownBy(() -> decrypt(key, name, swapped)).isInstanceOf(IOException.class);
    }

    @Test
    void anObjectOfAnotherFormatIsRefusedAtOnce() {
        SecretKey key = key();
        assertThatThrownBy(() -> BundleCipher.decrypt(key, UUID.randomUUID(),
                new ByteArrayInputStream("not a bundle object".getBytes())))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("format");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
