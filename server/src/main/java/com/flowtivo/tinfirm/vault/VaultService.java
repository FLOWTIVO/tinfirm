package com.flowtivo.tinfirm.vault;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Internal vault operations. Nothing here is exposed over HTTP except the admin rewrap. */
public interface VaultService {

    /**
     * Decrypts the current value of a secret for server-side use (e.g. the model key the relay forwards for a run,
     * or an external gateway's admin key).
     * Audited as {@code secret.accessed} with the given purpose and updates {@code lastAccessedAt}.
     * <p>
     * This is a trusted internal API: the caller is responsible for deciding that {@code purpose} justifies access.
     * The value never leaves the server through the API. Close the result to wipe the plaintext.
     *
     * A secret a person owns (ADR-0049 §5) is never revealed here: that is an {@link IllegalStateException}, never a
     * value; {@link #revealOwned} reveals it for its owner.
     * <p>
     * A value that cannot be decrypted (the master key its data key is wrapped under is not configured any more, or
     * the data was tampered with) is a {@link RuntimeException} that leaves the caller's transaction usable: nothing
     * is written before the decryption, so a caller that can do without the value (the revocation of an account's
     * grant as the account goes) may catch it and go on. As for {@link #revealOwned}.
     *
     * @param purpose why the value is needed (non-personal, e.g. {@code run:<id>}), recorded in the audit log
     */
    RevealedSecret reveal(UUID companyId, UUID secretId, String purpose);

    /**
     * Decrypts the current value of a secret that {@code userId} owns (ADR-0049 §5): the token of the person's own
     * account on a connection, for a call that acts as that person (an interactive agent's tool call, the write-back
     * of the person's thread). Audited as {@code secret.accessed} with the purpose and {@code userId}, and updates
     * {@code lastAccessedAt}, as {@link #reveal} does. A secret of the company, or one another person owns, is an
     * {@link IllegalStateException}, never a value; an unknown id is 404 as for {@link #reveal}.
     *
     * @param userId  the person the call acts as, who must own the secret
     * @param purpose why the value is needed, e.g. {@code run-tool:<runId>}, recorded in the audit log
     */
    RevealedSecret revealOwned(UUID companyId, UUID secretId, UUID userId, String purpose);

    /**
     * Scans content for credentials before it is stored (ADR-0014): the current value of every secret of the
     * company, in plain, base64 (standard and URL-safe, every alignment), hex and URL-encoded form, and the known
     * credential formats. Zip and gzip content is scanned inside as well.
     * <p>
     * The values are decrypted inside the vault module and wiped after the scan; they never leave it. The scan
     * writes no {@code secret.accessed} event: the caller audits the scan with its own action.
     */
    ScanReport scan(UUID companyId, byte[] content);

    /**
     * A scan as {@link #scan} for content that streams in, such as a session bundle while its host uploads it
     * (ADR-0101): made in the company's transaction, which reads the values, and used after it, so that no database
     * connection waits on the content. The values stay in this module, in the scanner, which
     * {@link ContentScanner#close()} wipes.
     */
    ContentScanner scanner(UUID companyId);

    /** One scan of content that streams in ({@link VaultService#scanner}). */
    interface ContentScanner extends AutoCloseable {

        /**
         * Scans {@code content} to its end as {@link VaultService#scan} scans content held whole. The time it waits
         * for the content does not count against the scan's own bound; a failure to read it is thrown, not reported
         * as unscannable.
         */
        ScanReport scan(InputStream content) throws IOException;

        @Override
        void close();
    }

    /**
     * Re-wraps every data key that is not under the active master key, of company secrets, of the instance's keys
     * ({@link InstanceKeys}) and of other modules' content ({@link StoredDataKeys}: the session bundles). Idempotent
     * and audited as {@code vault.rewrapped}. Instance admins only.
     */
    RewrapResult rewrapAll();

    /** Result of {@link #rewrapAll()}. */
    record RewrapResult(String activeKeyId, long rewrapped) {
    }
}
