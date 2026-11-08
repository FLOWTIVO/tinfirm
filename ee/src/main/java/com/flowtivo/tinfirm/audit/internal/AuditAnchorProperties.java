package com.flowtivo.tinfirm.audit.internal;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

/**
 * The anchor of the audit chains outside the database ({@code tinfirm.audit.anchor.*}, ADR-0111, CONVENTIONS §4).
 *
 * @param sink         {@code none} (no checkpoints), {@code s3}, {@code file} or {@code syslog}
 * @param interval     a checkpoint is written this often while some chain's head moved: at least 5 minutes, at
 *                     most a day
 * @param keys         the signing keys by key id ({@code TINFIRM_AUDIT_ANCHOR_KEYS_<KID>_PRIVATE}, an Ed25519
 *                     PKCS#8 PEM) and the retired ones that only verify ({@code _PUBLIC}, an X.509 PEM)
 * @param activeKey    the key id that signs
 * @param s3           the bucket of {@code s3}, with object lock
 * @param file         the directory of {@code file}
 * @param syslog       the collector of {@code syslog}
 * @param tick         how often a {@code worker} replica looks whether a checkpoint is due; not an operator's setting
 * @param initialDelay the first look after start
 * @param retry        how soon a write that failed is tried again
 */
@ConfigurationProperties("tinfirm.audit.anchor")
record AuditAnchorProperties(@DefaultValue("none") Sink sink, @DefaultValue("PT1H") Duration interval,
        @DefaultValue Map<String, Key> keys, @Nullable String activeKey, @DefaultValue S3 s3,
        @DefaultValue File file, @DefaultValue Syslog syslog, @DefaultValue("PT1M") Duration tick,
        @DefaultValue("PT1M") Duration initialDelay, @DefaultValue("PT5M") Duration retry) {

    /** A key id: lowercase letters and digits, as an environment variable's part gives it. */
    static final Pattern KEY_ID = Pattern.compile("[a-z0-9]{1,32}");

    /** Where the checkpoints go. */
    enum Sink {
        NONE, S3, FILE, SYSLOG
    }

    /**
     * A key: the private half signs (the active key only), the public half verifies. A key with a private half needs
     * no public one; it is derived, and checked against the public one when both are given.
     */
    record Key(@Name("private") @Nullable String privateKey, @Name("public") @Nullable String publicKey) {

        @Override
        public String toString() {
            // Never the private key.
            return "Key[private=" + (privateKey != null) + ", public=" + (publicKey != null) + "]";
        }
    }

    /**
     * An S3-compatible bucket with object lock: AWS, MinIO, Ceph RGW, and others that implement it.
     *
     * @param endpoint        the service's URL; empty for AWS's own
     * @param region          the region the requests are signed for
     * @param bucket          a bucket of its own, made with object lock
     * @param pathStyle       path-style addressing, which MinIO and Ceph need
     * @param prefix          the objects' prefix; the instance's id and the checkpoint's number follow it
     * @param accessKeyId     a key that reaches this bucket only, without the right to delete
     * @param secretAccessKey its secret
     * @param retention       each object's retention in compliance mode
     * @param requireLock     whether the start refuses a bucket without object lock
     */
    record S3(@Nullable String endpoint, @DefaultValue("us-east-1") String region, @Nullable String bucket,
            @DefaultValue("false") boolean pathStyle, @DefaultValue("audit-checkpoints/") String prefix,
            @Nullable String accessKeyId, @Nullable String secretAccessKey, @DefaultValue("P400D") Duration retention,
            @DefaultValue("true") boolean requireLock) {

        @Override
        public String toString() {
            // Never the secret.
            return "S3[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + ", pathStyle="
                    + pathStyle + ", prefix=" + prefix + ", retention=" + retention + "]";
        }
    }

    /** @param dir the directory of the {@code file} sink */
    record File(@Nullable Path dir) {
    }

    /**
     * @param host     the collector
     * @param port     its port
     * @param protocol {@code tls} (RFC 5425, the JVM's trust store), {@code tcp} (RFC 6587's octet counting) or
     *                 {@code udp} (RFC 5426)
     */
    record Syslog(@Nullable String host, @DefaultValue("6514") int port, @DefaultValue("tls") String protocol) {
    }

    AuditAnchorProperties {
        Map<String, Key> byId = new TreeMap<>();
        for (Map.Entry<String, Key> entry : keys.entrySet()) {
            String id = entry.getKey().toLowerCase(Locale.ROOT);
            if (!KEY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("tinfirm.audit.anchor.keys: the key id " + entry.getKey()
                        + " is not 1 to 32 lowercase letters and digits");
            }
            if (byId.put(id, entry.getValue()) != null) {
                throw new IllegalArgumentException("tinfirm.audit.anchor.keys: two keys under the id " + id);
            }
        }
        keys = Map.copyOf(byId);
        activeKey = activeKey == null || activeKey.isBlank() ? null : activeKey.strip().toLowerCase(Locale.ROOT);
        if (sink != Sink.NONE) {
            if (interval.compareTo(Duration.ofMinutes(5)) < 0 || interval.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException(
                        "tinfirm.audit.anchor.interval must be between PT5M and P1D, not " + interval);
            }
            if (activeKey == null) {
                throw new IllegalArgumentException("tinfirm.audit.anchor.active-key (TINFIRM_AUDIT_ANCHOR_ACTIVE_KEY)"
                        + " names the key that signs the checkpoints of the " + sink.name().toLowerCase(Locale.ROOT)
                        + " sink");
            }
            Key active = keys.get(activeKey);
            if (active == null || active.privateKey() == null || active.privateKey().isBlank()) {
                throw new IllegalArgumentException("tinfirm.audit.anchor.active-key " + activeKey
                        + " has no private key (TINFIRM_AUDIT_ANCHOR_KEYS_" + activeKey.toUpperCase(Locale.ROOT)
                        + "_PRIVATE)");
            }
        }
        switch (sink) {
            case S3 -> {
                if (blank(s3.bucket()) || blank(s3.accessKeyId()) || blank(s3.secretAccessKey())) {
                    throw new IllegalArgumentException(
                            "tinfirm.audit.anchor.s3 needs a bucket, an access key id and its secret");
                }
                if (s3.retention().toDays() < 1) {
                    throw new IllegalArgumentException("tinfirm.audit.anchor.s3.retention must be at least a day");
                }
            }
            case FILE -> {
                if (file.dir() == null || file.dir().toString().isBlank()) {
                    throw new IllegalArgumentException(
                            "tinfirm.audit.anchor.file.dir (TINFIRM_AUDIT_ANCHOR_FILE_DIR) names the directory");
                }
            }
            case SYSLOG -> {
                if (blank(syslog.host())) {
                    throw new IllegalArgumentException(
                            "tinfirm.audit.anchor.syslog.host (TINFIRM_AUDIT_ANCHOR_SYSLOG_HOST) names the collector");
                }
                if (!java.util.Set.of("tls", "tcp", "udp").contains(syslog.protocol().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            "tinfirm.audit.anchor.syslog.protocol is tls, tcp or udp, not " + syslog.protocol());
                }
            }
            case NONE -> {
                // Nothing to write; keys may be configured ahead of the sink.
            }
        }
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
