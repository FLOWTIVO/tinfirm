package com.flowtivo.tinfirm.run.internal;

import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the session bundles ({@code tinfirm.session-bundles.*}, ADR-0097): where the bundles are kept, and for
 * how long.
 *
 * @param store     {@code none} (no bundles: the runner hosts keep their homes), {@code filesystem} (a
 *                  directory: Compose and the tests) or {@code s3} (any storage that speaks the S3 API)
 * @param directory the directory of {@code filesystem}
 * @param s3        the bucket of {@code s3}
 * @param retention a bundle goes this long after its conversation's last turn, and at once when the conversation
 *                  ends
 * @param orphanAge an object of the store that no bundle names is deleted once it is this old: an upload in flight
 *                  writes its object before its row
 * @param sweep     how often the sweeper runs
 */
@Validated
@ConfigurationProperties("tinfirm.session-bundles")
record SessionBundleProperties(@NotNull @DefaultValue("none") Store store, @Nullable Path directory,
        @DefaultValue S3 s3, @NotNull @DefaultValue("P30D") Duration retention,
        @NotNull @DefaultValue("PT1H") Duration orphanAge, @NotNull @DefaultValue("PT1H") Duration sweep) {

    /** Where the bundles are kept. */
    enum Store {
        NONE, FILESYSTEM, S3
    }

    /**
     * An S3-compatible bucket: DigitalOcean Spaces, Ceph RGW, MinIO, AWS.
     *
     * @param endpoint        the service's URL, for example {@code https://fra1.digitaloceanspaces.com}; empty for
     *                        AWS's own
     * @param region          the region the requests are signed for ({@code us-east-1} where the service has none)
     * @param bucket          a private bucket that holds nothing but the bundles
     * @param pathStyle       path-style addressing ({@code endpoint/bucket/key}), which MinIO and Ceph need
     * @param accessKeyId     a key that reaches this bucket only
     * @param secretAccessKey its secret
     * @param prefix          the objects' prefix in the bucket
     */
    record S3(@Nullable String endpoint, @DefaultValue("us-east-1") String region, @Nullable String bucket,
            @DefaultValue("false") boolean pathStyle, @Nullable String accessKeyId, @Nullable String secretAccessKey,
            @DefaultValue("session-bundles/") String prefix) {

        @Override
        public String toString() {
            // Never the secret.
            return "S3[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + ", pathStyle=" + pathStyle
                    + ", prefix=" + prefix + "]";
        }
    }

    SessionBundleProperties {
        if (store == Store.FILESYSTEM && (directory == null || directory.toString().isBlank())) {
            throw new IllegalArgumentException(
                    "tinfirm.session-bundles.directory names the directory of the filesystem store");
        }
        if (store == Store.S3 && (s3.bucket() == null || s3.bucket().isBlank() || s3.accessKeyId() == null
                || s3.accessKeyId().isBlank() || s3.secretAccessKey() == null || s3.secretAccessKey().isBlank())) {
            throw new IllegalArgumentException("tinfirm.session-bundles.s3 needs a bucket, an access key id and its "
                    + "secret");
        }
        if (retention.toHours() < 1) {
            throw new IllegalArgumentException("tinfirm.session-bundles.retention must be at least an hour");
        }
        if (orphanAge.toMinutes() < 5) {
            throw new IllegalArgumentException("tinfirm.session-bundles.orphan-age must be at least 5 minutes");
        }
    }

    /** Whether the server keeps bundles. */
    boolean enabled() {
        return store != Store.NONE;
    }
}
