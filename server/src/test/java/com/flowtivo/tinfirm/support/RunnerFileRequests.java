package com.flowtivo.tinfirm.support;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A runner host's requests to the file endpoints of its runs (TEST FIXTURE ONLY), as the runner host sends them:
 * {@code GET /api/runner/v1/runs/{runId}/attachments/{attachmentId}?epoch=} and
 * {@code POST /api/runner/v1/runs/{runId}/attachments?epoch=…}, with the host's token as bearer.
 */
public final class RunnerFileRequests {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private RunnerFileRequests() {
    }

    /** A download of a file of the run's conversation; no token when null. */
    public static HttpResponse<byte[]> download(int port, @Nullable String token, UUID runId, UUID attachmentId,
            long epoch) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/runner/v1/runs/" + runId + "/attachments/" + attachmentId + "?epoch=" + epoch))
            .timeout(TIMEOUT)
            .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * An upload of the agent's file: {@code multipart/form-data} with the file, its name in {@code filename*}.
     *
     * @param query what follows {@code epoch=}, e.g. {@code &artifact=true&source=output}
     */
    public static HttpResponse<String> upload(int port, String token, UUID runId, long epoch, String query,
            String fileName, String contentType, byte[] content) {
        return upload(port, token, runId, epoch, query, "file",
                URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"), contentType, content);
    }

    /**
     * An upload with the file's name as the runner host's {@code multipart} sends it ({@code attachments.rs}): an
     * ASCII fallback in {@code filename} (every other character {@code _}) and every byte of its UTF-8 but letters and
     * digits percent-encoded in {@code filename*}, the largest part header a name makes.
     */
    public static HttpResponse<String> uploadAsTheRunnerHost(int port, String token, UUID runId, long epoch,
            String query, String fileName, String contentType, byte[] content) {
        StringBuilder fallback = new StringBuilder();
        fileName.codePoints().forEach(c -> fallback.append(c > 0x20 && c < 0x7f && c != '"' && c != '\\' || c == ' '
                ? (char) c : '_'));
        StringBuilder encoded = new StringBuilder();
        for (byte b : fileName.getBytes(StandardCharsets.UTF_8)) {
            if (b >= '0' && b <= '9' || b >= 'A' && b <= 'Z' || b >= 'a' && b <= 'z') {
                encoded.append((char) b);
            }
            else {
                encoded.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return upload(port, token, runId, epoch, query, fallback.toString(), encoded.toString(), contentType,
                content);
    }

    private static HttpResponse<String> upload(int port, String token, UUID runId, long epoch, String query,
            String fallback, String encoded, String contentType, byte[] content) {
        String boundary = "tinfirm-" + UUID.randomUUID().toString().replace("-", "");
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fallback
                + "\"; filename*=UTF-8''" + encoded + "\r\nContent-Type: " + contentType + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] body = new byte[head.length() + content.length + tail.length()];
        System.arraycopy(head.getBytes(StandardCharsets.US_ASCII), 0, body, 0, head.length());
        System.arraycopy(content, 0, body, head.length(), content.length);
        System.arraycopy(tail.getBytes(StandardCharsets.US_ASCII), 0, body, head.length() + content.length,
                tail.length());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/runner/v1/runs/"
                + runId + "/attachments?epoch=" + epoch + query))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        return send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return HTTP.send(request, handler);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
