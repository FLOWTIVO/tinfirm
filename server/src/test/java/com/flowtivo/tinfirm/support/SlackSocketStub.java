package com.flowtivo.tinfirm.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slack's Socket Mode WebSocket for the tests (TEST FIXTURE ONLY): a minimal RFC 6455 server on {@code localhost}
 * that takes the server's connections at {@code /link/{teamId}}, sends them envelopes as Slack does
 * ({@code hello}, {@code events_api}, {@code interactive}, {@code disconnect}) and records the acknowledgements
 * ({@code {"envelope_id"}}) they send back. Text frames only, a message in one frame or split into several; enough for
 * the server's {@code java.net.http} client.
 */
public final class SlackSocketStub implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** One connection the server opened. */
    public static final class Connection {

        final String team;

        final Socket socket;

        final OutputStream out;

        public final List<JsonNode> received = new CopyOnWriteArrayList<>();

        volatile boolean closed;

        Connection(String team, Socket socket) throws IOException {
            this.team = team;
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        public void send(Map<String, Object> envelope) {
            send(envelope, 1);
        }

        /** Sends the envelope as one message in this many frames, a text frame and its continuations. */
        public synchronized void send(Map<String, Object> envelope, int frames) {
            try {
                byte[] payload = JSON.writeValueAsBytes(envelope);
                int size = Math.ceilDiv(payload.length, frames);
                for (int index = 0; index < frames; index++) {
                    int from = Math.min(payload.length, index * size);
                    int to = Math.min(payload.length, from + size);
                    int opcode = index == 0 ? 0x1 : 0x0;
                    int fin = index == frames - 1 ? 0x80 : 0;
                    out.write(frame(fin | opcode, Arrays.copyOfRange(payload, from, to)));
                }
                out.flush();
            }
            catch (IOException ex) {
                closed = true;
            }
        }

        private static byte[] frame(int first, byte[] payload) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(first);
            if (payload.length < 126) {
                frame.write(payload.length);
            }
            else if (payload.length < 65536) {
                frame.write(126);
                frame.write(payload.length >>> 8);
                frame.write(payload.length & 0xff);
            }
            else {
                frame.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    frame.write((int) (((long) payload.length >>> shift) & 0xff));
                }
            }
            frame.writeBytes(payload);
            return frame.toByteArray();
        }

        /** Closes the connection as a network failure would, without a close frame. */
        public void drop() {
            closed = true;
            try {
                socket.close();
            }
            catch (IOException ex) {
                // Closed.
            }
        }

        public boolean open() {
            return !closed && !socket.isClosed();
        }

        /** The acknowledgements received, by envelope id. */
        public List<String> acknowledged() {
            List<String> ids = new ArrayList<>();
            for (JsonNode frame : received) {
                if (frame.hasNonNull("envelope_id")) {
                    ids.add(frame.get("envelope_id").asString());
                }
            }
            return ids;
        }
    }

    private final ServerSocket server;

    private final Map<String, List<Connection>> connections = new ConcurrentHashMap<>();

    private volatile boolean refuse;

    public SlackSocketStub() {
        try {
            // A test stub of Slack's Socket Mode on the loopback address: plain ws, as the tests connect to it.
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()); // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        Thread.ofVirtual().name("slack-socket-stub").start(this::accept);
    }

    /** The URL {@code apps.connections.open} names for the workspace. */
    public String url(String team) {
        return "ws://localhost:" + server.getLocalPort() + "/link/" + team;
    }

    /** Refuses the next handshakes (403) while set. */
    public void refuse(boolean refuse) {
        this.refuse = refuse;
    }

    /** The workspace's connections, oldest first. */
    public List<Connection> connections(String team) {
        return connections.getOrDefault(team, List.of());
    }

    /** The workspace's open connections. */
    public List<Connection> open(String team) {
        return connections(team).stream().filter(Connection::open).toList();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().name("slack-socket-stub-conn").start(() -> serve(socket));
            }
            catch (IOException ex) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            String head = readHead(in);
            String path = head.lines().findFirst().orElse("").split(" ")[1];
            String key = head.lines()
                .filter(line -> line.toLowerCase().startsWith("sec-websocket-key:"))
                .map(line -> line.substring(line.indexOf(':') + 1).strip())
                .findFirst()
                .orElse("");
            OutputStream out = socket.getOutputStream();
            if (refuse || !path.startsWith("/link/")) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
                socket.close();
                return;
            }
            // Sec-WebSocket-Accept is SHA-1 by RFC 6455 (section 4.2.2), not a signature.
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1") // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
                .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String team = path.substring("/link/".length());
            Connection connection = new Connection(team, socket);
            connections.computeIfAbsent(team, ignored -> new CopyOnWriteArrayList<>()).add(connection);
            connection.send(Map.of("type", "hello", "num_connections", 1, "connection_info",
                    Map.of("app_id", "A" + team)));
            read(connection, in);
        }
        catch (IOException | NoSuchAlgorithmException | RuntimeException ex) {
            // The connection ends.
        }
        finally {
            try {
                socket.close();
            }
            catch (IOException ex) {
                // Closed.
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = { '\r', '\n', '\r', '\n' };
        while (matched < 4) {
            int next = in.read();
            if (next < 0) {
                throw new IOException("closed");
            }
            head.write(next);
            matched = next == end[matched] ? matched + 1 : next == '\r' ? 1 : 0;
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    /** Reads the client's frames: text frames are recorded, a close frame ends the connection. */
    private static void read(Connection connection, InputStream in) throws IOException {
        while (true) {
            int first = in.read();
            int second = in.read();
            if (first < 0 || second < 0) {
                connection.closed = true;
                return;
            }
            int opcode = first & 0x0f;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7f;
            if (length == 126) {
                length = (in.read() << 8) | in.read();
            }
            else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | in.read();
                }
            }
            byte[] mask = masked ? in.readNBytes(4) : new byte[4];
            byte[] payload = in.readNBytes((int) length);
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
            if (opcode == 0x8) {
                connection.closed = true;
                connection.socket.close();
                return;
            }
            if (opcode == 0x9) {
                synchronized (connection) {
                    connection.out.write(new byte[] { (byte) 0x8a, 0 });
                    connection.out.flush();
                }
                continue;
            }
            if (opcode == 0x1) {
                connection.received.add(JSON.readTree(payload));
            }
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        }
        catch (IOException ex) {
            // Closed.
        }
        connections.values().forEach(list -> list.forEach(Connection::drop));
    }
}
