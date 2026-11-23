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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /**
     * The end of a streamed upload: whether the whole body went out before the server closed the connection, and the
     * answer the client read while it sent, or null when none arrived.
     */
    public record Upload(boolean sentAll, Answer answer) {
    }

    /**
     * Sends a {@code multipart/form-data} body of one file part named {@code file} with {@code size} zero bytes, as a
     * client sends a file: the body is written in pieces while the answer is read on another thread, so that an
     * answer the server sends early is seen. Nothing of the body is held in memory beyond one piece.
     */
    public static Upload upload(int port, String target, Map<String, String> headers, String fileName, long size) {
        String boundary = "tinfirm-" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        // Plain HTTP to the test server on the loopback, as the test's client speaks it: nothing crosses a network.
        try (Socket socket = new Socket("localhost", port)) { // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            socket.setSoTimeout(30_000);
            StringBuilder request = new StringBuilder("POST ").append(target).append(" HTTP/1.1\r\nHost: localhost\r\n")
                .append("Content-Type: multipart/form-data; boundary=").append(boundary).append("\r\n")
                .append("Content-Length: ").append(head.length + size + tail.length).append("\r\n")
                .append("Accept: application/json\r\n");
            headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
            request.append("\r\n");
            CompletableFuture<Answer> answer = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return read(socket.getInputStream());
                    }
                    catch (IOException ex) {
                        return null;
                    }
                });
            boolean sentAll;
            try {
                OutputStream out = socket.getOutputStream();
                out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(head);
                byte[] piece = new byte[64 * 1024];
                for (long left = size; left > 0; left -= piece.length) {
                    out.write(piece, 0, (int) Math.min(piece.length, left));
                }
                out.write(tail);
                out.flush();
                sentAll = true;
            }
            catch (IOException ex) {
                // The server closed the connection while the body was still on its way.
                sentAll = false;
            }
            return new Upload(sentAll, answer.get(30, TimeUnit.SECONDS));
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
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
