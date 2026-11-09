package com.flowtivo.tinfirm.audit.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Checkpoints as syslog messages to a collector the operator keeps apart (ADR-0111, decision 3), the first step to a
 * SIEM: one RFC 5424 message per checkpoint, {@code APP-NAME} {@code tinfirm}, {@code MSGID} {@code audit-checkpoint},
 * the signed document as the message, over TLS only (RFC 5425: the JVM's trust store, the collector's host name
 * checked, octet counting). Plain TCP and UDP are not offered: a sink that a network attacker can read, change or drop
 * unseen is no anchor, and UDP cannot tell a delivered checkpoint from a lost one. Write-only: the server cannot read
 * a collector back, so the app's comparison uses what it recorded, and the offline tool reads the collector's messages
 * exported to a file.
 */
final class SyslogAnchorSink implements AnchorSink {

    /** Facility 13 (log audit), severity 5 (notice). */
    static final int PRIORITY = 13 * 8 + 5;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
        .withZone(ZoneOffset.UTC);

    private static final int TIMEOUT_MILLIS = 10_000;

    private final String host;

    private final int port;

    private final SSLSocketFactory sockets;

    private final Clock clock;

    SyslogAnchorSink(AuditAnchorProperties.Syslog settings, Clock clock) {
        this(settings, (SSLSocketFactory) SSLSocketFactory.getDefault(), clock);
    }

    /** With the caller's TLS settings: the tests' collector with a certificate of its own. */
    SyslogAnchorSink(AuditAnchorProperties.Syslog settings, SSLSocketFactory sockets, Clock clock) {
        this.host = settings.host();
        this.port = settings.port();
        this.sockets = sockets;
        this.clock = clock;
    }

    @Override
    public String location() {
        return "syslog+tls://" + host + ":" + port;
    }

    @Override
    public void checkAtStart() {
        // A collector is checked by the first write.
    }

    @Override
    public String write(UUID instance, long number, String document) throws IOException {
        byte[] message = message(instance, number, document).getBytes(StandardCharsets.UTF_8);
        try (SSLSocket socket = (SSLSocket) sockets.createSocket()) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            // Octet counting (RFC 5425 §4.3): the length, a space, the message.
            out.write((message.length + " ").getBytes(StandardCharsets.US_ASCII));
            out.write(message);
            out.flush();
        }
        return location() + " #" + number;
    }

    /**
     * {@code <PRI>1 TIMESTAMP HOSTNAME tinfirm - audit-checkpoint - document}: no structured data, the document
     * names its instance and number itself.
     */
    String message(UUID instance, long number, String document) {
        return "<" + PRIORITY + ">1 " + TIMESTAMP.format(clock.instant().truncatedTo(ChronoUnit.MICROS)) + " "
                + hostName() + " tinfirm - audit-checkpoint - " + document;
    }

    private static String hostName() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            return name.isBlank() || name.chars().anyMatch(c -> c <= 32 || c > 126) ? "-" : name;
        }
        catch (UnknownHostException ex) {
            return "-";
        }
    }

    @Override
    public boolean readable() {
        return false;
    }

    @Override
    public List<Long> numbers(UUID instance, long after) {
        return List.of();
    }

    @Override
    public Optional<Stored> read(UUID instance, long number) {
        return Optional.empty();
    }
}
