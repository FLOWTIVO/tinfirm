package com.flowtivo.tinfirm.run.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flowtivo.tinfirm.support.StubServers;
import com.github.tomakehurst.wiremock.WireMockServer;

/** The bundle store's adapters (ADR-0097): a directory, and an S3 bucket as a fake S3 API answers it. */
class BundleObjectsTest {

    private WireMockServer s3;

    @BeforeEach
    void startS3() {
        s3 = new WireMockServer(StubServers.options().dynamicPort());
        s3.start();
    }

    @AfterEach
    void stopS3() {
        s3.stop();
    }

    @Test
    void aDirectoryKeepsListsAndDeletesTheObjects(@TempDir Path directory) throws IOException {
        FileSystemBundleObjects objects = new FileSystemBundleObjects(directory.resolve("bundles"));
        List<UUID> names = List.of(UUID.fromString("00000000-0000-4000-8000-000000000001"),
                UUID.fromString("00000000-0000-4000-8000-000000000002"),
                UUID.fromString("00000000-0000-4000-8000-000000000003"));
        for (UUID name : names) {
            objects.put(name, name.toString().getBytes());
        }
        // A file that is not an object (an upload's temporary file) is not listed.
        Files.writeString(directory.resolve("bundles").resolve("x.tmp"), "x");
        assertThat(objects.list(null, 2)).extracting(BundleObjects.StoredObject::name)
            .containsExactly(names.get(0), names.get(1));
        assertThat(objects.list(names.get(1), 10)).extracting(BundleObjects.StoredObject::name)
            .containsExactly(names.get(2));
        try (InputStream in = objects.open(names.get(0)).orElseThrow()) {
            assertThat(new String(in.readAllBytes())).isEqualTo(names.get(0).toString());
        }
        objects.put(names.get(0), "again".getBytes());
        try (InputStream in = objects.open(names.get(0)).orElseThrow()) {
            assertThat(new String(in.readAllBytes())).isEqualTo("again");
        }
        objects.delete(names.get(0));
        objects.delete(names.get(0));
        assertThat(objects.open(names.get(0))).isEmpty();
        assertThat(objects.list(null, 10)).hasSize(2);
    }

    @Test
    void aBucketGetsSignedRequestsForItsPrefixedObjects() throws IOException {
        S3BundleObjects objects = new S3BundleObjects(new SessionBundleProperties.S3(s3.baseUrl(), "fra1",
                "tinfirm-bundles", true, "TEST-ONLY-ACCESS-KEY", "TEST-ONLY-SECRET-KEY", "session-bundles/"));
        UUID name = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
        String key = "/tinfirm-bundles/session-bundles/" + name;
        s3.stubFor(put(urlEqualTo(key)).willReturn(aResponse().withStatus(200).withHeader("ETag", "\"e\"")));
        s3.stubFor(get(urlEqualTo(key)).willReturn(aResponse().withStatus(200).withBody("sealed")));
        s3.stubFor(delete(urlEqualTo(key)).willReturn(aResponse().withStatus(204)));
        UUID missing = UUID.randomUUID();
        s3.stubFor(get(urlEqualTo("/tinfirm-bundles/session-bundles/" + missing)).willReturn(aResponse()
            .withStatus(404)
            .withHeader("Content-Type", "application/xml")
            .withBody("<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>")));
        s3.stubFor(get(urlPathEqualTo("/tinfirm-bundles")).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/xml")
            .withBody("<ListBucketResult><Name>tinfirm-bundles</Name><Prefix>session-bundles/</Prefix>"
                    + "<KeyCount>2</KeyCount><MaxKeys>2</MaxKeys><IsTruncated>false</IsTruncated>"
                    + "<Contents><Key>session-bundles/" + name + "</Key>"
                    + "<LastModified>2026-10-16T10:00:00.000Z</LastModified><Size>6</Size></Contents>"
                    + "<Contents><Key>session-bundles/not-an-object</Key>"
                    + "<LastModified>2026-10-16T10:00:00.000Z</LastModified><Size>1</Size></Contents>"
                    + "</ListBucketResult>")));

        objects.put(name, "sealed".getBytes());
        s3.verify(putRequestedFor(urlEqualTo(key))
            .withHeader("Authorization", matching("AWS4-HMAC-SHA256 Credential=TEST-ONLY-ACCESS-KEY/\\d{8}/fra1/s3/"
                    + "aws4_request, .*")));
        Optional<InputStream> read = objects.open(name);
        try (InputStream in = read.orElseThrow()) {
            assertThat(new String(in.readAllBytes())).isEqualTo("sealed");
        }
        assertThat(objects.open(missing)).isEmpty();
        assertThat(objects.list(null, 2)).extracting(BundleObjects.StoredObject::name).containsExactly(name);
        objects.delete(name);
        s3.verify(deleteRequestedFor(urlEqualTo(key)).withHeader("Authorization", matching("AWS4-HMAC-SHA256 .*")));
        s3.verify(putRequestedFor(urlEqualTo(key)).withHeader("Content-Type", equalTo("application/octet-stream")));
        objects.close();
    }

    /** A page of other keys (a console's folder marker) is not the end of the listing: the sweeper sees on. */
    @Test
    void aBucketsListingGoesOnPastKeysThatAreNoObjects() throws IOException {
        S3BundleObjects objects = new S3BundleObjects(new SessionBundleProperties.S3(s3.baseUrl(), "fra1",
                "tinfirm-bundles", true, "TEST-ONLY-ACCESS-KEY", "TEST-ONLY-SECRET-KEY", "session-bundles/"));
        UUID name = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
        s3.stubFor(get(urlPathEqualTo("/tinfirm-bundles")).withQueryParam("continuation-token", absent())
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<ListBucketResult><Name>tinfirm-bundles</Name><Prefix>session-bundles/</Prefix>"
                        + "<KeyCount>1</KeyCount><MaxKeys>2</MaxKeys><IsTruncated>true</IsTruncated>"
                        + "<NextContinuationToken>page-2</NextContinuationToken>"
                        + "<Contents><Key>session-bundles/</Key>"
                        + "<LastModified>2026-10-16T10:00:00.000Z</LastModified><Size>0</Size></Contents>"
                        + "</ListBucketResult>")));
        s3.stubFor(get(urlPathEqualTo("/tinfirm-bundles")).withQueryParam("continuation-token", equalTo("page-2"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<ListBucketResult><Name>tinfirm-bundles</Name><Prefix>session-bundles/</Prefix>"
                        + "<KeyCount>1</KeyCount><MaxKeys>2</MaxKeys><IsTruncated>false</IsTruncated>"
                        + "<Contents><Key>session-bundles/" + name + "</Key>"
                        + "<LastModified>2026-10-16T10:00:00.000Z</LastModified><Size>6</Size></Contents>"
                        + "</ListBucketResult>")));

        assertThat(objects.list(null, 2)).extracting(BundleObjects.StoredObject::name).containsExactly(name);
        s3.verify(2, getRequestedFor(urlPathEqualTo("/tinfirm-bundles")));
        objects.close();
    }

    /** An upload (ADR-0101) is an object only once committed; one closed before, or abandoned, leaves none. */
    @Test
    void aDirectoryUploadIsAnObjectOnceCommittedAndLeavesNothingElse(@TempDir Path directory) throws IOException {
        Path bundles = directory.resolve("bundles");
        FileSystemBundleObjects objects = new FileSystemBundleObjects(bundles);
        UUID name = UUID.randomUUID();
        try (BundleObjects.Upload upload = objects.upload(name)) {
            upload.stream().write("part one, ".getBytes());
            upload.stream().write("part two".getBytes());
            assertThat(objects.open(name)).isEmpty();
            assertThat(objects.list(null, 10)).isEmpty();
            // The part written so far is the server's alone from the start, where the file system has modes.
            if (Files.getFileStore(bundles).supportsFileAttributeView(PosixFileAttributeView.class)) {
                try (var files = Files.list(bundles)) {
                    assertThat(files.map(f -> {
                        try {
                            return PosixFilePermissions.toString(Files.getPosixFilePermissions(f));
                        }
                        catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    })).containsExactly("rw-------");
                }
            }
            upload.commit();
        }
        try (InputStream in = objects.open(name).orElseThrow()) {
            assertThat(new String(in.readAllBytes())).isEqualTo("part one, part two");
        }
        UUID discarded = UUID.randomUUID();
        try (BundleObjects.Upload upload = objects.upload(discarded)) {
            upload.stream().write("never kept".getBytes());
        }
        assertThat(objects.open(discarded)).isEmpty();
        try (var files = Files.list(bundles)) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly(name.toString());
        }
        // What a server that stopped during an upload left goes once it is old enough.
        Path old = bundles.resolve(UUID.randomUUID() + "." + UUID.randomUUID() + ".tmp");
        Path recent = bundles.resolve(UUID.randomUUID() + "." + UUID.randomUUID() + ".tmp");
        Files.writeString(old, "half");
        Files.writeString(recent, "half");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        assertThat(objects.abandoned(Instant.now().minus(Duration.ofHours(1)))).isEqualTo(1);
        assertThat(old).doesNotExist();
        assertThat(recent).exists();
        assertThat(objects.list(null, 10)).extracting(BundleObjects.StoredObject::name).containsExactly(name);
    }

    /**
     * An object larger than a part goes up as a multipart upload, completed only at the commit; one closed before is
     * aborted, and the sweeper aborts what an earlier server left.
     */
    @Test
    void aBucketUploadIsAMultipartUploadCommittedOrAborted() throws IOException {
        S3BundleObjects objects = new S3BundleObjects(new SessionBundleProperties.S3(s3.baseUrl(), "fra1",
                "tinfirm-bundles", true, "TEST-ONLY-ACCESS-KEY", "TEST-ONLY-SECRET-KEY", "session-bundles/"));
        UUID name = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
        String key = "/tinfirm-bundles/session-bundles/" + name;
        s3.stubFor(post(urlEqualTo(key + "?uploads")).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/xml")
            .withBody("<InitiateMultipartUploadResult><Bucket>tinfirm-bundles</Bucket><Key>session-bundles/" + name
                    + "</Key><UploadId>upload-1</UploadId></InitiateMultipartUploadResult>")));
        s3.stubFor(put(urlPathEqualTo(key)).withQueryParam("uploadId", equalTo("upload-1"))
            .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"part\"")));
        s3.stubFor(post(urlEqualTo(key + "?uploadId=upload-1")).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/xml")
            .withBody("<CompleteMultipartUploadResult><Bucket>tinfirm-bundles</Bucket><Key>session-bundles/" + name
                    + "</Key><ETag>\"all\"</ETag></CompleteMultipartUploadResult>")));
        s3.stubFor(delete(urlEqualTo(key + "?uploadId=upload-1")).willReturn(aResponse().withStatus(204)));

        byte[] content = new byte[S3BundleObjects.PART_BYTES + 10];
        Arrays.fill(content, (byte) 7);
        try (BundleObjects.Upload upload = objects.upload(name)) {
            upload.stream().write(content);
            s3.verify(1, putRequestedFor(urlPathEqualTo(key)).withQueryParam("partNumber", equalTo("1")));
            upload.commit();
        }
        s3.verify(1, putRequestedFor(urlPathEqualTo(key)).withQueryParam("partNumber", equalTo("2")));
        s3.verify(1, postRequestedFor(urlEqualTo(key + "?uploadId=upload-1")));
        s3.verify(0, deleteRequestedFor(urlEqualTo(key + "?uploadId=upload-1")));

        // Closed before its commit: aborted, so no object and no parts stay.
        try (BundleObjects.Upload upload = objects.upload(name)) {
            upload.stream().write(content);
        }
        s3.verify(1, deleteRequestedFor(urlEqualTo(key + "?uploadId=upload-1")));

        // One that fits in a part is one request, never a multipart upload.
        UUID small = UUID.randomUUID();
        s3.stubFor(put(urlEqualTo("/tinfirm-bundles/session-bundles/" + small))
            .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"e\"")));
        try (BundleObjects.Upload upload = objects.upload(small)) {
            upload.stream().write("sealed".getBytes());
            upload.commit();
        }
        s3.verify(1, putRequestedFor(urlEqualTo("/tinfirm-bundles/session-bundles/" + small)));
        s3.verify(0, postRequestedFor(urlEqualTo("/tinfirm-bundles/session-bundles/" + small + "?uploads")));

        // The uploads an earlier server left, older than the cutoff, are aborted.
        s3.stubFor(get(urlPathEqualTo("/tinfirm-bundles")).withQueryParam("uploads", matching(".*"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("<ListMultipartUploadsResult><Bucket>tinfirm-bundles</Bucket>"
                        + "<IsTruncated>false</IsTruncated>"
                        + "<Upload><Key>session-bundles/old</Key><UploadId>old-1</UploadId>"
                        + "<Initiated>2026-10-16T10:00:00.000Z</Initiated></Upload>"
                        + "<Upload><Key>session-bundles/new</Key><UploadId>new-1</UploadId>"
                        + "<Initiated>2026-10-16T12:00:00.000Z</Initiated></Upload>"
                        + "</ListMultipartUploadsResult>")));
        s3.stubFor(delete(urlEqualTo("/tinfirm-bundles/session-bundles/old?uploadId=old-1"))
            .willReturn(aResponse().withStatus(204)));
        assertThat(objects.abandoned(Instant.parse("2026-10-16T11:00:00Z"))).isEqualTo(1);
        s3.verify(1, deleteRequestedFor(urlEqualTo("/tinfirm-bundles/session-bundles/old?uploadId=old-1")));
        s3.verify(0, deleteRequestedFor(urlEqualTo("/tinfirm-bundles/session-bundles/new?uploadId=new-1")));

        // A store that refuses the abort says so; the sweeper aborts it later.
        UUID refused = UUID.fromString("00000000-0000-4000-8000-0000000000dd");
        String refusedKey = "/tinfirm-bundles/session-bundles/" + refused;
        s3.stubFor(post(urlEqualTo(refusedKey + "?uploads")).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/xml")
            .withBody("<InitiateMultipartUploadResult><UploadId>upload-2</UploadId></InitiateMultipartUploadResult>")));
        s3.stubFor(put(urlPathEqualTo(refusedKey)).willReturn(aResponse().withStatus(200).withHeader("ETag", "\"p\"")));
        s3.stubFor(delete(urlEqualTo(refusedKey + "?uploadId=upload-2")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> {
            try (BundleObjects.Upload upload = objects.upload(refused)) {
                upload.stream().write(content);
            }
        }).isInstanceOf(IOException.class).hasMessageContaining("abort");
        objects.close();
    }
}
