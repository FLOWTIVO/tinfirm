package com.flowtivo.tinfirm.shared.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The development secrets that {@code deploy/compose/.env.example} publishes, known here by their SHA-256 only (the
 * security review of 2026-10-27, L17; threat model 4.3, Q9 closed): the vault's master key {@code dev1}, LiteLLM's
 * master key, and the bootstrap admin's password. Anyone who read the repository knows them, so the server refuses to
 * start with one of them unless {@code TINFIRM_PUBLIC_URL} is a localhost URL ({@link #localUrl}), which is what the
 * Compose stack, the smoke test and the end-to-end tests use. LiteLLM's salt key is published too, but the server never
 * receives it.
 */
public final class PublishedDevSecrets {

    /** SHA-256 of the 32 bytes of the master key {@code dev1} ({@code TINFIRM_VAULT_KEYS} in {@code .env.example}). */
    static final String VAULT_KEY_SHA256 = "613c646886bb3fd2342cc7653cb0f09938965936cae001a6fc79cae2663b3110";

    /** SHA-256 of {@code LITELLM_MASTER_KEY} in {@code .env.example}, which the server gets as its LiteLLM master key. */
    static final String LITELLM_MASTER_KEY_SHA256 = "eed6e1b2ba29e586e722e19c4a2bb3d7cf6f1493fdd115e60e125748ce846919";

    /** SHA-256 of {@code TINFIRM_BOOTSTRAP_ADMIN_PASSWORD} in {@code .env.example}. */
    static final String BOOTSTRAP_PASSWORD_SHA256 = "4a1265ec6cfc321070b0a531fb12ac2754e53538a6682f561a68114273bfb801";

    /** A loopback IPv4 address, {@code 127.0.0.0/8}. */
    private static final Pattern IPV4_LOOPBACK = Pattern.compile("127(?:\\.\\d{1,3}){3}");

    /** An IPv6 literal: a host with a colon is never a name, so parsing it never resolves anything. */
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-f:.]*:[0-9a-f:.]*");

    private PublishedDevSecrets() {
    }

    /** Whether the bytes are the published master key {@code dev1}. */
    public static boolean isPublishedVaultKey(byte[] key) {
        return VAULT_KEY_SHA256.equals(sha256(key));
    }

    /** Whether the value is the published LiteLLM master key. */
    public static boolean isPublishedLiteLlmMasterKey(@Nullable String value) {
        return value != null && LITELLM_MASTER_KEY_SHA256.equals(sha256(value.trim().getBytes(StandardCharsets.UTF_8)));
    }

    /** Whether the value is the published bootstrap password. */
    public static boolean isPublishedBootstrapPassword(@Nullable String value) {
        return value != null && BOOTSTRAP_PASSWORD_SHA256.equals(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Whether the public URL is a localhost URL, where the published development secrets are allowed: the host
     * {@code localhost} or a name under {@code .localhost} (RFC 6761), or a loopback address literal
     * ({@code 127.0.0.0/8}, {@code ::1}). No name is ever resolved.
     */
    public static boolean localUrl(@Nullable URI publicUrl) {
        if (publicUrl == null || publicUrl.getHost() == null) {
            return false;
        }
        String host = publicUrl.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return true;
        }
        if (IPV4_LOOPBACK.matcher(host).matches()) {
            return true;
        }
        if (!IPV6_LITERAL.matcher(host).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        }
        catch (UnknownHostException ex) {
            return false;
        }
    }

    /**
     * Refuses a published development secret off localhost: an {@link IllegalStateException} that names the setting
     * and how to make one of one's own.
     */
    public static void refuseOffLocalhost(@Nullable URI publicUrl, String setting, String generate) {
        if (!localUrl(publicUrl)) {
            throw new IllegalStateException(setting + " is the development value published in deploy/compose/"
                    + ".env.example, and TINFIRM_PUBLIC_URL (" + publicUrl + ") is not a localhost URL. " + generate);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
