package com.flowtivo.tinfirm.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One HTTP/1.1 request written byte for byte to the test server on the loopback, for what {@link ApiClient} cannot
 * send: a query string that is not valid UTF-8 or holds a malformed percent-escape, which {@code java.net.URI}
 * refuses or encodes again; or a {@code Content-Length} it does not send. The answer is read by its own length.
 */
public final class RawHttp {

    private RawHttp() {
    }

    /** A request's answer: the status, the headers (names in lower case) and the body as text. */
    public record Answer(int status, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String toString() {
            return "Answer[" + status + " " + headers.get("content-type") + " " + body + "]";
        }
    }

    /**
     * Sends {@code method target} with the headers and the body ({@code null} for none) and reads the answer.
     *
     * @param target the request target exactly as it goes on the request line, query string included
     */
    public static Answer send(int port, String method, String target, Map<String, String> headers, byte[] body) {
        // Plain HTTP to the test server on the loopback, as the test's client speaks it: nothing crosses a network.
        try (Socket socket = new Socket("localhost", port)) { // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            socket.setSoTimeout(20_000);
            StringBuilder head = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                .append("Host: localhost\r\nConnection: close\r\n");
            headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            if (body != null) {
                head.append("Content-Length: ").append(body.length).append("\r\n");
            }
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            return read(socket.getInputStream());
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Answer read(InputStream in) throws IOException {
        String[] lines = line(in, 64 * 1024).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.putIfAbsent(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            while (true) {
                int size = Integer.parseInt(line(in, 1024).split(";")[0].trim(), 16);
                if (size == 0) {
                    break;
                }
                body.writeBytes(in.readNBytes(size));
                in.readNBytes(2);
            }
        }
        else if (headers.containsKey("content-length")) {
            body.writeBytes(in.readNBytes(Integer.parseInt(headers.get("content-length"))));
        }
        else {
            in.transferTo(body);
        }
        // The answer is read without waiting for the connection's end: a server that refused a body it did not read
        // may wait for the rest of it before it closes.
        return new Answer(status, headers, body.toString(StandardCharsets.UTF_8));
    }

    /** The bytes up to the next CRLF, or up to the end of the head (CRLF CRLF) when it starts the answer. */
    private static String line(InputStream in, int max) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean head = max > 1024;
        while (line.size() < max) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("The connection closed before the answer's end: " + line);
            }
            line.write(b);
            byte[] bytes = line.toByteArray();
            int n = bytes.length;
            if (head ? n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n' && bytes[n - 2] == '\r'
                    && bytes[n - 1] == '\n' : n >= 2 && bytes[n - 2] == '\r' && bytes[n - 1] == '\n') {
                return new String(bytes, 0, n - (head ? 4 : 2), StandardCharsets.ISO_8859_1);
            }
        }
        throw new IOException("The answer's line is too long");
    }
}
