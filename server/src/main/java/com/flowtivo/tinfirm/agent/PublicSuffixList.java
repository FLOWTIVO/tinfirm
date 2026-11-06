package com.flowtivo.tinfirm.agent;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The Public Suffix List bundled with the server (ADR-0044; {@code psl/public_suffix_list.dat}, which
 * {@link NetworkHosts} reads) as runner hosts get it: its bytes, their SHA-256 (lower-case hex, the version a v2 host
 * reports in {@code hello.publicSuffixList}) and its number of rules. The run module pushes it to a host that holds
 * another version ({@code psl.update}), so that the host refuses the same wildcards as the server.
 */
public final class PublicSuffixList {

    private static final PublicSuffixList BUNDLED = new PublicSuffixList(PublicSuffixes.bundled());

    private final byte[] bytes;

    private final String sha256;

    private final int ruleCount;

    private PublicSuffixList(PublicSuffixes list) {
        this.bytes = list.bytes();
        this.ruleCount = list.ruleCount();
        try {
            this.sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The list the server holds: the bundled copy, the only one it reads. */
    public static PublicSuffixList bundled() {
        return BUNDLED;
    }

    /** The list as the file holds it, UTF-8 text. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** The SHA-256 of {@link #bytes()}, lower-case hex. */
    public String sha256() {
        return sha256;
    }

    /** The rules of the list, comments and blank lines left out. */
    public int ruleCount() {
        return ruleCount;
    }
}
