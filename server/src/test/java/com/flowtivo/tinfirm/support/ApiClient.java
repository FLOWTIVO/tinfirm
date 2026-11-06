package com.flowtivo.tinfirm.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;

/**
 * A "browser" for the API: keeps cookies (session and XSRF-TOKEN), sends the CSRF header on mutating requests like
 * Angular's HttpClient, and presents a stable client IP through X-Forwarded-For (honoured because the test server
 * sees the request from a trusted local proxy address).
 */
public final class ApiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI base;

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    private final HttpClient http;

    private String clientIp;

    private boolean sendCsrfHeader = true;

    public ApiClient(int port) {
        this.base = URI.create("http://localhost:" + port);
        this.http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        this.clientIp = randomIp();
    }

    /** A random address from 198.18.0.0/15 (benchmarking range; not a trusted proxy). */
    public static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "198.18." + random.nextInt(0, 256) + "." + random.nextInt(1, 255);
    }

    public ApiClient withClientIp(String ip) {
        this.clientIp = ip;
        return this;
    }

    public ApiClient withoutCsrfHeader() {
        this.sendCsrfHeader = false;
        return this;
    }

    public ApiResponse get(String path) {
        return send("GET", path, null);
    }

    public ApiResponse post(String path, Object body) {
        return send("POST", path, body);
    }

    public ApiResponse put(String path, Object body) {
        return send("PUT", path, body);
    }

    public ApiResponse patch(String path, Object body) {
        return send("PATCH", path, body);
    }

    public ApiResponse delete(String path) {
        return send("DELETE", path, null);
    }

    /** Loads the XSRF-TOKEN cookie like the SPA does before logging in. */
    public ApiResponse fetchCsrfToken() {
        return get("/api/auth/providers");
    }

    public ApiResponse login(String email, String password) {
        if (cookie("XSRF-TOKEN").isEmpty()) {
            fetchCsrfToken();
        }
        return post("/api/auth/login", Map.of("email", email, "password", password));
    }

    public Optional<HttpCookie> cookie(String name) {
        return cookies.getCookieStore()
            .get(base)
            .stream()
            .filter(cookie -> cookie.getName().equals(name) && !cookie.hasExpired())
            .findFirst();
    }

    public void setCookie(String name, String value) {
        HttpCookie cookie = new HttpCookie(name, value);
        cookie.setPath("/");
        cookie.setVersion(0);
        cookies.getCookieStore().add(base, cookie);
    }

    public void removeCookie(String name) {
        cookie(name).ifPresent(cookie -> cookies.getCookieStore().remove(base, cookie));
    }

    /**
     * Opens a Server-Sent Events stream with the session cookie, like the browser's EventSource. The response
     * arrives once the headers are sent; its body is read line by line as the server writes it.
     */
    public HttpResponse<Stream<String>> openEventStream(String path, Map<String, String> extraHeaders) {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
            .header("Accept", "text/event-stream")
            .header("X-Forwarded-For", clientIp)
            .GET();
        extraHeaders.forEach(request::setHeader);
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofLines());
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    public ApiResponse send(String method, String path, Object body) {
        return send(method, path, body, Map.of());
    }

    /**
     * A {@code multipart/form-data} body: one file part named {@code file}, plus plain form fields; without a file
     * name, the fields alone ({@link #fields(Map)}).
     */
    public record Multipart(String fileName, String contentType, byte[] content, Map<String, String> fields) {

        public Multipart(String fileName, String contentType, byte[] content) {
            this(fileName, contentType, content, Map.of());
        }

        /** Plain form fields and no file. */
        public static Multipart fields(Map<String, String> fields) {
            return new Multipart(null, null, null, fields);
        }

        byte[] encode(String boundary) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            fields.forEach((name, value) -> out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                    + name + "\"\r\n\r\n" + value + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (fileName == null) {
                out.writeBytes(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return out.toByteArray();
            }
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.writeBytes(content);
            out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return out.toByteArray();
        }
    }

    /** Uploads a file as {@code multipart/form-data}, like the SPA's FormData. */
    public ApiResponse upload(String path, Multipart body) {
        return send("POST", path, body);
    }

    /** A GET whose body is kept as bytes (file downloads). */
    public HttpResponse<byte[]> download(String path) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
            .header("X-Forwarded-For", clientIp)
            .GET()
            .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    public ApiResponse send(String method, String path, Object body, Map<String, String> extraHeaders) {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
            .header("Accept", "application/json, application/problem+json")
            .header("X-Forwarded-For", clientIp);
        if (body instanceof Multipart multipart) {
            String boundary = "tinfirm-test-" + java.util.UUID.randomUUID();
            request.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(multipart.encode(boundary)));
        }
        else if (body != null) {
            String json = body instanceof String text ? text : JSON.writeValueAsString(body);
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (sendCsrfHeader && !"GET".equals(method)) {
            cookie("XSRF-TOKEN").ifPresent(token -> request.header("X-XSRF-TOKEN", token.getValue()));
        }
        extraHeaders.forEach(request::setHeader);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(response.statusCode(), response.headers(), response.body());
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
