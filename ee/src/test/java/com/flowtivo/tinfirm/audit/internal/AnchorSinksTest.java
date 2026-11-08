package com.flowtivo.tinfirm.audit.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * The sinks of ADR-0111, decision 3: a directory never overwrites a checkpoint and lists them as numbers; syslog sends
 * one RFC 5424 message per checkpoint, octet-counted over TCP and one datagram over UDP; S3 writes each object once
 * ({@code If-None-Match}) under object lock in compliance mode, and the start refuses a bucket without object lock.
 */
class AnchorSinksTest {

    private static final UUID INSTANCE = UUID.fromString("7f1d2c3b-4a59-4e6f-8a7b-9c0d1e2f3a4b");

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);

    private final WireMockServer s3 = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeEach
    void start() {
        s3.start();
    }

    @AfterEach
    void stop() {
        s3.stop();
    }

    @Test
    void theFileSinkWritesEachCheckpointOnceAndListsThemAsNumbers(@TempDir Path dir) throws IOException {
        FileAnchorSink sink = new FileAnchorSink(dir.resolve("checkpoints"));
        sink.checkAtStart();

        String first = sink.write(INSTANCE, 1, "{\"checkpoint\":1}");
        sink.write(INSTANCE, 2, "{\"checkpoint\":2}");
        sink.write(INSTANCE, 10, "{\"checkpoint\":10}");

        assertThat(first).endsWith(INSTANCE + "/000000000001.json");
        assertThat(sink.numbers(INSTANCE, 0)).containsExactly(1L, 2L, 10L);
        assertThat(sink.numbers(INSTANCE, 1)).containsExactly(2L, 10L);
        assertThat(sink.read(INSTANCE, 2).orElseThrow().document()).isEqualTo("{\"checkpoint\":2}\n");
        assertThat(sink.read(INSTANCE, 3)).isEmpty();
        assertThatThrownBy(() -> sink.write(INSTANCE, 2, "{\"forged\":true}"))
            .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(sink.read(INSTANCE, 2).orElseThrow().document()).contains("\"checkpoint\":2");
        try (var files = Files.list(dir.resolve("checkpoints").resolve(INSTANCE.toString()))) {
            assertThat(files.map(file -> file.getFileName().toString())).noneMatch(name -> name.endsWith(".tmp"));
        }
        assertThat(sink.numbers(UUID.randomUUID(), 0)).isEmpty();
    }

    @Test
    void theFileSinkRefusesADirectoryItCannotWrite(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("not-a-directory"), "x");

        assertThatThrownBy(() -> new FileAnchorSink(file).checkAtStart()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSyslogSinkSendsOneOctetCountedMessageOverTcp() throws Exception {
        try (ServerSocket collector = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = collector.accept(); InputStream in = socket.getInputStream()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    in.transferTo(bytes);
                    return bytes.toString(StandardCharsets.UTF_8);
                }
                catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
            SyslogAnchorSink sink = new SyslogAnchorSink(new AuditAnchorProperties.Syslog("127.0.0.1",
                    collector.getLocalPort(), "tcp"), CLOCK);

            String location = sink.write(INSTANCE, 7, "{\"checkpoint\":{\"number\":7}}");

            String message = received.get(10, TimeUnit.SECONDS);
            String body = message.substring(message.indexOf(' ') + 1);
            assertThat(Integer.parseInt(message.substring(0, message.indexOf(' '))))
                .isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
            assertThat(body).startsWith("<109>1 2026-11-07T10:00:00.000000Z ")
                .contains(" tinfirm - audit-checkpoint - {\"checkpoint\":{\"number\":7}}");
            assertThat(location).isEqualTo("syslog+tcp://127.0.0.1:" + collector.getLocalPort() + " #7");
            assertThat(sink.readable()).isFalse();
        }
    }

    @Test
    void theSyslogSinkSendsOneDatagramOverUdp() throws Exception {
        try (DatagramSocket collector = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            collector.setSoTimeout(10_000);
            SyslogAnchorSink sink = new SyslogAnchorSink(new AuditAnchorProperties.Syslog("127.0.0.1",
                    collector.getLocalPort(), "udp"), CLOCK);

            sink.write(INSTANCE, 8, "{\"checkpoint\":{\"number\":8}}");

            DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
            collector.receive(packet);
            assertThat(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8))
                .startsWith("<109>1 ")
                .endsWith("audit-checkpoint - {\"checkpoint\":{\"number\":8}}");
        }
    }

    @Test
    void theSyslogSinkFailsWhenTheCollectorIsGone() throws IOException {
        int port;
        try (ServerSocket closed = new ServerSocket(0)) {
            port = closed.getLocalPort();
        }
        SyslogAnchorSink sink = new SyslogAnchorSink(new AuditAnchorProperties.Syslog("127.0.0.1", port, "tcp"),
                CLOCK);

        assertThatThrownBy(() -> sink.write(INSTANCE, 9, "{}")).isInstanceOf(IOException.class);
    }

    private S3AnchorSink s3Sink(boolean requireLock) {
        return new S3AnchorSink(new AuditAnchorProperties.S3(s3.baseUrl(), "us-east-1", "checkpoints", true,
                "audit/", "test-access-key", "test-secret", Duration.ofDays(400), requireLock),
                Duration.ofDays(400), requireLock, CLOCK);
    }

    @Test
    void theS3SinkWritesOnceUnderObjectLockInComplianceMode() throws IOException {
        s3.stubFor(put(urlPathEqualTo("/checkpoints/audit/" + INSTANCE + "/000000000003.json"))
            .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"etag\"")));

        try (S3AnchorSink sink = s3Sink(true)) {
            String location = sink.write(INSTANCE, 3, "{\"checkpoint\":3}");

            assertThat(location).isEqualTo("s3://checkpoints/audit/" + INSTANCE + "/000000000003.json");
        }
        s3.verify(putRequestedFor(urlPathEqualTo("/checkpoints/audit/" + INSTANCE + "/000000000003.json"))
            .withHeader("If-None-Match", equalTo("*"))
            .withHeader("x-amz-object-lock-mode", equalTo("COMPLIANCE"))
            .withHeader("x-amz-object-lock-retain-until-date", matching("2027-12-12T10:00:00(\\.000)?Z")));
    }

    @Test
    void theS3SinkFailsAWriteTheStoreRefusesAsWhenTheObjectExists() {
        s3.stubFor(put(urlPathEqualTo("/checkpoints/audit/" + INSTANCE + "/000000000003.json"))
            .willReturn(aResponse().withStatus(412).withBody("<Error><Code>PreconditionFailed</Code></Error>")));

        try (S3AnchorSink sink = s3Sink(true)) {
            assertThatThrownBy(() -> sink.write(INSTANCE, 3, "{}")).isInstanceOf(IOException.class)
                .hasMessageContaining("checkpoints");
        }
    }

    @Test
    void theS3SinksStartRefusesABucketWithoutObjectLock() {
        s3.stubFor(get(urlMatching("/checkpoints/?\\?object-lock.*")).willReturn(aResponse().withStatus(404)
            .withBody("<Error><Code>ObjectLockConfigurationNotFoundError</Code><Message>none</Message></Error>")));

        try (S3AnchorSink sink = s3Sink(true)) {
            assertThatThrownBy(sink::checkAtStart).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no object lock");
        }
        try (S3AnchorSink sink = s3Sink(false)) {
            sink.checkAtStart();
        }
    }

    @Test
    void theS3SinksStartAcceptsABucketWithObjectLock() {
        s3.stubFor(get(urlMatching("/checkpoints/?\\?object-lock.*")).willReturn(aResponse().withStatus(200)
            .withBody("<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled>"
                    + "</ObjectLockConfiguration>")));

        try (S3AnchorSink sink = s3Sink(true)) {
            sink.checkAtStart();
        }
    }
}
