package com.flowtivo.tinfirm.audit.internal;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Checkpoints as objects of an S3-compatible bucket with object lock (ADR-0111, decision 3):
 * {@code <prefix><instance>/<number, 12 digits>.json}, each written once ({@code If-None-Match: *}) in compliance mode
 * with a retain-until date, so that nobody, the server included, deletes or changes it before its retention ends. The
 * bucket keeps versions (object lock needs them): a second version of a checkpoint is an overwrite, which the reader
 * reports, and it reads the first.
 */
final class S3AnchorSink implements AnchorSink {

    private static final Pattern NAME = Pattern.compile("(\\d{12})\\.json");

    private final S3Client s3;

    private final String bucket;

    private final String prefix;

    private final @Nullable Duration retention;

    private final boolean requireLock;

    private final Clock clock;

    /**
     * @param retention   each object's retention; null for a reader, which writes nothing
     * @param requireLock whether the start refuses a bucket without object lock
     */
    S3AnchorSink(AuditAnchorProperties.S3 settings, @Nullable Duration retention, boolean requireLock, Clock clock) {
        var builder = S3Client.builder()
            .region(Region.of(settings.region()))
            .credentialsProvider(StaticCredentialsProvider
                .create(AwsBasicCredentials.create(settings.accessKeyId(), settings.secretAccessKey())))
            .forcePathStyle(settings.pathStyle())
            .httpClientBuilder(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(10))
                .socketTimeout(Duration.ofSeconds(30)));
        if (settings.endpoint() != null && !settings.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(settings.endpoint()));
        }
        this.s3 = builder.build();
        this.bucket = settings.bucket();
        this.prefix = settings.prefix();
        this.retention = retention;
        this.requireLock = requireLock;
        this.clock = clock;
    }

    private String folder(UUID instance) {
        return prefix + instance + "/";
    }

    private String key(UUID instance, long number) {
        return folder(instance) + AnchorSink.name(number);
    }

    @Override
    public String location() {
        return "s3://" + bucket + "/" + prefix;
    }

    @Override
    public void checkAtStart() {
        if (!requireLock) {
            return;
        }
        ObjectLockEnabled enabled;
        try {
            enabled = s3.getObjectLockConfiguration(request -> request.bucket(bucket))
                .objectLockConfiguration()
                .objectLockEnabled();
        }
        catch (S3Exception ex) {
            if (ex.statusCode() == 404 || "ObjectLockConfigurationNotFoundError".equals(errorCode(ex))) {
                throw new IllegalStateException("The audit anchor's bucket " + bucket + " has no object lock: make a"
                        + " bucket with object lock, or set TINFIRM_AUDIT_ANCHOR_S3_REQUIRE_LOCK=false for a store"
                        + " without it (ADR-0111)", ex);
            }
            // Another answer (a store that cannot be reached, a refused key): the first write tells again.
            return;
        }
        catch (SdkException ex) {
            return;
        }
        if (enabled != ObjectLockEnabled.ENABLED) {
            throw new IllegalStateException("The audit anchor's bucket " + bucket + " has object lock off (ADR-0111)");
        }
    }

    @Override
    public String write(UUID instance, long number, String document) throws IOException {
        if (retention == null) {
            throw new IllegalStateException("A reader writes no checkpoint");
        }
        String key = key(instance, number);
        Instant retainUntil = clock.instant().plus(retention);
        try {
            s3.putObject(request -> {
                request.bucket(bucket).key(key).contentType("application/json").ifNoneMatch("*");
                if (requireLock) {
                    request.objectLockMode(ObjectLockMode.COMPLIANCE).objectLockRetainUntilDate(retainUntil);
                }
            }, RequestBody.fromString(document + "\n", StandardCharsets.UTF_8));
        }
        catch (SdkException ex) {
            throw new IOException("Cannot write the checkpoint " + key + " to the bucket " + bucket + ": "
                    + ex.getMessage(), ex);
        }
        return "s3://" + bucket + "/" + key;
    }

    @Override
    public boolean readable() {
        return true;
    }

    @Override
    public List<Long> numbers(UUID instance, long after) throws IOException {
        List<Long> numbers = new ArrayList<>();
        String folder = folder(instance);
        String startAfter = after > 0 ? key(instance, after) : null;
        String token = null;
        try {
            ListObjectsV2Response response;
            do {
                String continuation = token;
                response = s3.listObjectsV2(request -> {
                    request.bucket(bucket).prefix(folder).continuationToken(continuation);
                    if (continuation == null && startAfter != null) {
                        request.startAfter(startAfter);
                    }
                });
                for (S3Object object : response.contents()) {
                    Matcher matcher = NAME.matcher(object.key().substring(folder.length()));
                    if (matcher.matches()) {
                        long number = Long.parseLong(matcher.group(1));
                        if (number > after) {
                            numbers.add(number);
                        }
                    }
                }
                token = response.nextContinuationToken();
            }
            while (Boolean.TRUE.equals(response.isTruncated()));
        }
        catch (SdkException ex) {
            throw new IOException("Cannot list the checkpoints in " + location() + ": " + ex.getMessage(), ex);
        }
        numbers.sort(Long::compare);
        return numbers;
    }

    @Override
    public Optional<Stored> read(UUID instance, long number) throws IOException {
        String key = key(instance, number);
        try {
            ObjectVersion first = null;
            int versions = 0;
            try {
                ListObjectVersionsResponse listed = s3.listObjectVersions(request -> request.bucket(bucket)
                    .prefix(key));
                List<ObjectVersion> ofKey = listed.versions().stream()
                    .filter(version -> version.key().equals(key))
                    .sorted(Comparator.comparing(ObjectVersion::lastModified))
                    .toList();
                versions = ofKey.size();
                first = ofKey.isEmpty() ? null : ofKey.getFirst();
            }
            catch (S3Exception ex) {
                // A store without versions' listing: the object as it is.
                versions = 1;
            }
            ObjectVersion version = first;
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(request -> {
                request.bucket(bucket).key(key);
                if (version != null && version.versionId() != null && !"null".equals(version.versionId())) {
                    request.versionId(version.versionId());
                }
            });
            return Optional.of(new Stored(object.asUtf8String(), object.response().lastModified(),
                    Math.max(versions, 1)));
        }
        catch (NoSuchKeyException ex) {
            return Optional.empty();
        }
        catch (SdkException ex) {
            throw new IOException("Cannot read the checkpoint " + key + " from " + location() + ": "
                    + ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        s3.close();
    }

    private static @Nullable String errorCode(S3Exception ex) {
        return ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorCode() : null;
    }
}
