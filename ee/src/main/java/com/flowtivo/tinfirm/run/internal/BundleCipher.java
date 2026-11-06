package com.flowtivo.tinfirm.run.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The encryption of a session bundle's object (ADR-0097): AES-256-GCM in chunks under the bundle's own data key, so
 * that a download decrypts as it streams and checks each chunk before it hands it on.
 * <p>
 * The object is a header, {@code TFSB}, the format's version (1), the chunk size (4 bytes, big endian) and a random
 * nonce prefix of 7 bytes, then the chunks: each the GCM ciphertext and tag of {@value #CHUNK_BYTES} bytes of the
 * bundle, the last of fewer (maybe none). A chunk's nonce is the prefix, its number (4 bytes, big endian) and a byte
 * that is 1 for the last chunk and 0 for the others; its AAD is the header and the object's name. A chunk moved,
 * dropped, cut or taken from another object, and an object cut at a chunk's end, fail the check.
 */
final class BundleCipher {

    static final int CHUNK_BYTES = 1024 * 1024;

    private static final byte[] MAGIC = { 'T', 'F', 'S', 'B' };

    private static final byte VERSION = 1;

    private static final int PREFIX_BYTES = 7;

    private static final int HEADER_BYTES = MAGIC.length + 1 + 4 + PREFIX_BYTES;

    private static final int TAG_BITS = 128;

    private static final int TAG_BYTES = TAG_BITS / 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private BundleCipher() {
    }

    /**
     * Seals a bundle into {@code out} as it is written (ADR-0101), the same object as {@link #encrypt}: a chunk at a
     * time, so that no more than a chunk of the bundle and of its ciphertext is held. {@link Sealing#finish()}
     * writes the last chunk; an object without it fails its check when it is read.
     */
    static Sealing sealing(SecretKey key, UUID name, OutputStream out) throws IOException {
        byte[] prefix = new byte[PREFIX_BYTES];
        RANDOM.nextBytes(prefix);
        byte[] header = header(CHUNK_BYTES, prefix);
        out.write(header);
        return new Sealing(key, aad(header, name), prefix, out);
    }

    /** The object of {@code bundle}, named {@code name}, under {@code key}. */
    static byte[] encrypt(SecretKey key, UUID name, byte[] bundle) {
        byte[] prefix = new byte[PREFIX_BYTES];
        RANDOM.nextBytes(prefix);
        byte[] header = header(CHUNK_BYTES, prefix);
        byte[] aad = aad(header, name);
        int chunks = Math.max(1, (bundle.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        if (bundle.length > 0 && bundle.length % CHUNK_BYTES == 0) {
            // A full last chunk is followed by an empty one, which says that the bundle ends there.
            chunks++;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(header.length + bundle.length + chunks * TAG_BYTES);
        out.writeBytes(header);
        for (int index = 0; index < chunks; index++) {
            int from = Math.min(bundle.length, index * CHUNK_BYTES);
            int to = Math.min(bundle.length, from + CHUNK_BYTES);
            out.writeBytes(gcm(Cipher.ENCRYPT_MODE, key, nonce(prefix, index, index == chunks - 1), aad,
                    Arrays.copyOfRange(bundle, from, to)));
        }
        return out.toByteArray();
    }

    /**
     * The bundle of the object {@code name} that {@code in} reads, decrypted and checked chunk by chunk: a chunk that
     * fails its check, or an object that ends before its last chunk, is an {@link IOException} of the read.
     */
    static InputStream decrypt(SecretKey key, UUID name, InputStream in) throws IOException {
        byte[] header = in.readNBytes(HEADER_BYTES);
        if (header.length != HEADER_BYTES || !Arrays.equals(Arrays.copyOf(header, MAGIC.length), MAGIC)
                || header[MAGIC.length] != VERSION) {
            throw new IOException("Not a session bundle object of a known format");
        }
        int chunkBytes = ByteBuffer.wrap(header, MAGIC.length + 1, 4).getInt();
        if (chunkBytes < 1 || chunkBytes > 16 * CHUNK_BYTES) {
            throw new IOException("A session bundle object with a chunk size of " + chunkBytes);
        }
        byte[] prefix = Arrays.copyOfRange(header, HEADER_BYTES - PREFIX_BYTES, HEADER_BYTES);
        return new Decrypting(key, aad(header, name), prefix, chunkBytes, new PushbackInputStream(in, 1));
    }

    private static byte[] header(int chunkBytes, byte[] prefix) {
        return ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION).putInt(chunkBytes).put(prefix).array();
    }

    private static byte[] aad(byte[] header, UUID name) {
        return ByteBuffer.allocate(header.length + 16)
            .put(header)
            .putLong(name.getMostSignificantBits())
            .putLong(name.getLeastSignificantBits())
            .array();
    }

    private static byte[] nonce(byte[] prefix, int index, boolean last) {
        return ByteBuffer.allocate(12).put(prefix).putInt(index).put(last ? (byte) 1 : (byte) 0).array();
    }

    private static byte[] gcm(int mode, SecretKey key, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException(mode == Cipher.ENCRYPT_MODE ? "Encrypting a session bundle failed"
                    : "A session bundle's chunk failed its check", ex);
        }
    }

    /**
     * The bundle as it is sealed: a full chunk is held until a byte after it comes, since only the last chunk is
     * marked so. A full last chunk is followed by an empty one, as {@link #encrypt} does.
     */
    static final class Sealing extends OutputStream {

        private final SecretKey key;

        private final byte[] aad;

        private final byte[] prefix;

        private final OutputStream out;

        private final byte[] chunk = new byte[CHUNK_BYTES];

        private int length;

        private int index;

        private boolean finished;

        private Sealing(SecretKey key, byte[] aad, byte[] prefix, OutputStream out) {
            this.key = key;
            this.aad = aad;
            this.prefix = prefix;
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int count) throws IOException {
            if (finished) {
                throw new IOException("The session bundle is sealed already");
            }
            while (count > 0) {
                if (length == CHUNK_BYTES) {
                    seal(false);
                }
                int n = Math.min(count, CHUNK_BYTES - length);
                System.arraycopy(buffer, offset, chunk, length, n);
                length += n;
                offset += n;
                count -= n;
            }
        }

        /** Writes the last chunk. */
        void finish() throws IOException {
            if (finished) {
                return;
            }
            if (length == CHUNK_BYTES) {
                seal(false);
            }
            seal(true);
            finished = true;
            out.flush();
        }

        private void seal(boolean last) throws IOException {
            out.write(gcm(Cipher.ENCRYPT_MODE, key, nonce(prefix, index, last), aad, Arrays.copyOf(chunk, length)));
            Arrays.fill(chunk, 0, length, (byte) 0);
            length = 0;
            index++;
        }

        /** Wipes what is held; the stream it writes to is the caller's to close. */
        @Override
        public void close() {
            Arrays.fill(chunk, (byte) 0);
        }
    }

    /** The bundle as it is decrypted: a chunk at a time, each checked before its bytes are read. */
    private static final class Decrypting extends InputStream {

        private final SecretKey key;

        private final byte[] aad;

        private final byte[] prefix;

        private final int chunkBytes;

        private final PushbackInputStream in;

        private byte[] chunk = new byte[0];

        private int position;

        private int index;

        private boolean ended;

        Decrypting(SecretKey key, byte[] aad, byte[] prefix, int chunkBytes, PushbackInputStream in) {
            this.key = key;
            this.aad = aad;
            this.prefix = prefix;
            this.chunkBytes = chunkBytes;
            this.in = in;
        }

        /** Reads and checks the next chunk; false once the last one was read. */
        private boolean next() throws IOException {
            if (ended) {
                return false;
            }
            byte[] sealed = in.readNBytes(chunkBytes + TAG_BYTES);
            if (sealed.length < TAG_BYTES) {
                throw new IOException("A session bundle object ends before its last chunk");
            }
            int following = in.read();
            boolean last = following < 0;
            if (!last) {
                in.unread(following);
            }
            try {
                chunk = gcm(Cipher.DECRYPT_MODE, key, nonce(prefix, index, last), aad, sealed);
            }
            catch (IllegalStateException ex) {
                throw new IOException(last ? "A session bundle object was changed or cut"
                        : "A session bundle object's chunk " + index + " was changed", ex);
            }
            position = 0;
            index++;
            ended = last;
            return true;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (position >= chunk.length) {
                if (!next()) {
                    return -1;
                }
            }
            int n = Math.min(length, chunk.length - position);
            System.arraycopy(chunk, position, buffer, offset, n);
            position += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            Arrays.fill(chunk, (byte) 0);
            in.close();
        }
    }
}
