package com.flowtivo.tinfirm.run.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The bundles' objects in a bucket that speaks the S3 API (ADR-0097): DigitalOcean Spaces, Ceph RGW (OpenShift Data
 * Foundation's object gateway), MinIO, AWS. Each object is {@code <prefix><uuid>}; the bucket is private and holds
 * nothing else, and the server's key reaches that bucket only.
 */
final class S3BundleObjects implements BundleObjects, AutoCloseable {

    /**
     * An upload's part (ADR-0101): S3 takes at least 5 MiB a part but the last. An object smaller than one part is
     * written with one request, without a multipart upload.
     */
    static final int PART_BYTES = 8 * 1024 * 1024;

    private final S3Client s3;

    private final String bucket;

    private final String prefix;

    S3BundleObjects(SessionBundleProperties.S3 settings) {
        var builder = S3Client.builder()
            .region(Region.of(settings.region()))
            .credentialsProvider(StaticCredentialsProvider
                .create(AwsBasicCredentials.create(settings.accessKeyId(), settings.secretAccessKey())))
            .forcePathStyle(settings.pathStyle())
            .httpClientBuilder(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(10))
                .socketTimeout(Duration.ofSeconds(60)));
        if (settings.endpoint() != null && !settings.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(settings.endpoint()));
        }
        this.s3 = builder.build();
        this.bucket = settings.bucket();
        this.prefix = settings.prefix();
    }

    private String key(UUID name) {
        return prefix + name;
    }

    @Override
    public void put(UUID name, byte[] content) throws IOException {
        try {
            s3.putObject(request -> request.bucket(bucket).key(key(name)).contentType("application/octet-stream"),
                    RequestBody.fromBytes(content));
        }
        catch (SdkException ex) {
            throw new IOException("Cannot write the session bundle object " + name + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public Upload upload(UUID name) {
        return new StreamedUpload(key(name), name);
    }

    @Override
    public int abandoned(Instant before) throws IOException {
        int removed = 0;
        try {
            String keyMarker = null;
            String uploadIdMarker = null;
            ListMultipartUploadsResponse response;
            do {
                String keys = keyMarker;
                String ids = uploadIdMarker;
                response = s3.listMultipartUploads(request -> request.bucket(bucket)
                    .prefix(prefix)
                    .keyMarker(keys)
                    .uploadIdMarker(ids));
                for (MultipartUpload upload : response.uploads()) {
                    if (upload.initiated() != null && upload.initiated().isBefore(before)) {
                        s3.abortMultipartUpload(request -> request.bucket(bucket)
                            .key(upload.key())
                            .uploadId(upload.uploadId()));
                        removed++;
                    }
                }
                keyMarker = response.nextKeyMarker();
                uploadIdMarker = response.nextUploadIdMarker();
            }
            while (Boolean.TRUE.equals(response.isTruncated()));
            return removed;
        }
        catch (SdkException ex) {
            throw new IOException("Cannot remove the session bundles' abandoned uploads: " + ex.getMessage(), ex);
        }
    }

    /**
     * An object written as it streams: in one request when it fits in a part, else as a multipart upload of
     * {@value #PART_BYTES} bytes a part, which a close without a commit aborts.
     */
    private final class StreamedUpload implements Upload {

        private final String key;

        private final UUID name;

        private final List<CompletedPart> parts = new ArrayList<>();

        private final ByteArrayOutputStream part = new ByteArrayOutputStream(PART_BYTES);

        private @Nullable String uploadId;

        private boolean committed;

        private final OutputStream stream = new OutputStream() {

            @Override
            public void write(int b) throws IOException {
                write(new byte[] { (byte) b }, 0, 1);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                while (length > 0) {
                    int n = Math.min(length, PART_BYTES - part.size());
                    part.write(buffer, offset, n);
                    offset += n;
                    length -= n;
                    if (part.size() == PART_BYTES) {
                        sendPart();
                    }
                }
            }
        };

        StreamedUpload(String key, UUID name) {
            this.key = key;
            this.name = name;
        }

        @Override
        public OutputStream stream() {
            return stream;
        }

        private void sendPart() throws IOException {
            try {
                if (uploadId == null) {
                    uploadId = s3.createMultipartUpload(request -> request.bucket(bucket)
                        .key(key)
                        .contentType("application/octet-stream")).uploadId();
                }
                int number = parts.size() + 1;
                String id = uploadId;
                String etag = s3.uploadPart(request -> request.bucket(bucket).key(key).uploadId(id).partNumber(number),
                        RequestBody.fromBytes(part.toByteArray()))
                    .eTag();
                parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
                part.reset();
            }
            catch (SdkException ex) {
                throw new IOException("Cannot write the session bundle object " + name + ": " + ex.getMessage(), ex);
            }
        }

        @Override
        public void commit() throws IOException {
            if (uploadId == null) {
                put(name, part.toByteArray());
                committed = true;
                return;
            }
            if (part.size() > 0) {
                sendPart();
            }
            try {
                String id = uploadId;
                s3.completeMultipartUpload(request -> request.bucket(bucket)
                    .key(key)
                    .uploadId(id)
                    .multipartUpload(upload -> upload.parts(parts)));
                committed = true;
            }
            catch (SdkException ex) {
                throw new IOException("Cannot write the session bundle object " + name + ": " + ex.getMessage(), ex);
            }
        }

        @Override
        public void close() throws IOException {
            part.reset();
            if (committed || uploadId == null) {
                return;
            }
            try {
                String id = uploadId;
                s3.abortMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(id));
            }
            catch (SdkException ex) {
                // The sweeper removes it ({@link #abandoned}).
                throw new IOException("Cannot abort the upload of the session bundle object " + name + ": "
                        + ex.getMessage(), ex);
            }
        }
    }

    @Override
    public Optional<InputStream> open(UUID name) throws IOException {
        try {
            return Optional.of(s3.getObject(request -> request.bucket(bucket).key(key(name))));
        }
        catch (NoSuchKeyException ex) {
            return Optional.empty();
        }
        catch (SdkException ex) {
            throw new IOException("Cannot read the session bundle object " + name + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public void delete(UUID name) throws IOException {
        try {
            s3.deleteObject(request -> request.bucket(bucket).key(key(name)));
        }
        catch (NoSuchKeyException ex) {
            // Gone already.
        }
        catch (SdkException ex) {
            throw new IOException("Cannot delete the session bundle object " + name + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<StoredObject> list(@Nullable UUID after, int limit) throws IOException {
        try {
            int wanted = Math.max(limit, 1);
            List<StoredObject> objects = new ArrayList<>();
            String token = null;
            // Other keys under the prefix (a console's folder marker) are skipped: the listing goes on until it
            // has the objects asked for or ends, so that the sweeper does not take a short page for the end.
            do {
                ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .maxKeys(Math.min(wanted - objects.size(), 1000));
                if (token != null) {
                    request.continuationToken(token);
                }
                else if (after != null) {
                    request.startAfter(key(after));
                }
                ListObjectsV2Response response = s3.listObjectsV2(request.build());
                for (S3Object object : response.contents()) {
                    UUID name = parse(object.key().substring(Math.min(prefix.length(), object.key().length())));
                    if (name != null && object.lastModified() != null && objects.size() < wanted) {
                        objects.add(new StoredObject(name, object.lastModified()));
                    }
                }
                token = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
            }
            while (objects.size() < wanted && token != null);
            return objects;
        }
        catch (SdkException ex) {
            throw new IOException("Cannot list the session bundle objects: " + ex.getMessage(), ex);
        }
    }

    private static @Nullable UUID parse(String name) {
        if (name.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(name);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    public void close() {
        s3.close();
    }
}
