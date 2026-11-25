package com.flowtivo.tinfirm.system.internal.github;

import java.math.BigInteger;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.GitHubApps;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;

import tools.jackson.databind.JsonNode;

/**
 * A GitHub App's calls at a {@code GITHUB} system's API base URL (ADR-0116), with the App's JSON Web Token: RS256 over
 * {@code {"iat": now - 60 s, "exp": now + 9 min, "iss": appId}}, signed with the App's private key through the JDK
 * ({@link Signature} {@code SHA256withRSA}; no library). GitHub takes a JWT of at most ten minutes, and the minute
 * before allows for clocks that differ. The key is PEM, PKCS#1 ({@code BEGIN RSA PRIVATE KEY}, as GitHub's settings
 * page downloads it) or PKCS#8 ({@code BEGIN PRIVATE KEY}), RSA of at least 2048 bits. Never logs the key, the JWT or a
 * token. GitHub's endpoints, as its REST documentation names them: {@code GET /app}, {@code GET /app/installations},
 * {@code GET /app/installations/{id}}, {@code POST /app/installations/{id}/access_tokens}; a GitHub Enterprise Server
 * has the same under {@code /api/v3} (untested against a real one).
 */
public final class GitHubAppClient {

    /** The PEM of an RSA private key, PKCS#1 or PKCS#8. */
    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN (RSA )?PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END (RSA )?PRIVATE KEY-----");

    /** The smallest RSA key taken, in bits. */
    static final int MIN_KEY_BITS = 2048;

    /** The most installations read, ten pages of 100. */
    static final int MAX_INSTALLATIONS = 1000;

    private final OutboundHttp http;

    private final String baseUrl;

    public GitHubAppClient(OutboundHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** The App's key: its PEM read, or why not. */
    public sealed interface ParsedKey permits Key, BadKey {
    }

    /** A usable key. */
    public record Key(PrivateKey key) implements ParsedKey {

        @Override
        public String toString() {
            return "Key[***]";
        }
    }

    /** Why the PEM is not a usable key, without any of it. */
    public record BadKey(String reason) implements ParsedKey {
    }

    /** Reads a PEM private key: PKCS#1 or PKCS#8, RSA of at least 2048 bits. */
    public static ParsedKey parse(String pem) {
        Matcher matcher = PEM.matcher(pem.strip());
        if (!matcher.matches() || (matcher.group(1) == null) != (matcher.group(3) == null)) {
            return new BadKey("The private key is not a PEM RSA private key (BEGIN RSA PRIVATE KEY or BEGIN PRIVATE "
                    + "KEY), as GitHub's settings page downloads it");
        }
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(matcher.group(2).replaceAll("\\s", ""));
        }
        catch (IllegalArgumentException ex) {
            return new BadKey("The private key's PEM is not Base64");
        }
        byte[] pkcs8 = matcher.group(1) != null ? pkcs1ToPkcs8(der) : der;
        try {
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            if (key instanceof RSAPrivateCrtKey rsa && rsa.getModulus().bitLength() < MIN_KEY_BITS) {
                return new BadKey("The private key has fewer than " + MIN_KEY_BITS + " bits");
            }
            return new Key(key);
        }
        catch (GeneralSecurityException | IllegalArgumentException ex) {
            return new BadKey("The private key is not a readable RSA key");
        }
    }

    /** The App's JWT for one request, RS256, issued a minute ago and valid nine minutes. */
    public static String jwt(long appId, PrivateKey key, Instant now) {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String header = url.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = url.encodeToString(("{\"iat\":" + (now.getEpochSecond() - 60) + ",\"exp\":"
                + (now.getEpochSecond() + 540) + ",\"iss\":" + appId + "}").getBytes(StandardCharsets.UTF_8));
        String signed = header + "." + claims;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + url.encodeToString(signature.sign());
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("The JDK cannot sign with RS256", ex);
        }
    }

    /** What {@code GET /app} answers: the App's id, slug and name. */
    public record AppDetails(long id, @Nullable String slug, @Nullable String name) {
    }

    /** {@code GET /app}: the App the JWT is for. */
    public CallResult<AppDetails> app(String jwt) {
        CallResult<OutboundHttp.Response> sent = http.send(baseUrl, request(baseUrl + "/app", jwt).GET().build());
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        JsonNode body = Json.parse(response.body());
        if (response.status() != 200) {
            return CallResult.failed(GitHubTracker.failure(response, body));
        }
        JsonNode id = body != null ? body.get("id") : null;
        if (id == null || !id.isIntegralNumber()) {
            return CallResult.failed(CallResult.Kind.INVALID, "GitHub answered GET /app without the App's id");
        }
        return CallResult.ok(new AppDetails(id.longValue(), Json.text(body, "slug"), Json.text(body, "name")));
    }

    /** {@code GET /app/installations}, page by page, at most {@value #MAX_INSTALLATIONS}. */
    public CallResult<List<GitHubApps.Installation>> installations(String jwt) {
        List<GitHubApps.Installation> found = new ArrayList<>();
        for (int page = 1; page <= MAX_INSTALLATIONS / 100; page++) {
            CallResult<OutboundHttp.Response> sent = http.send(baseUrl, request(baseUrl
                    + "/app/installations?per_page=100&page=" + page, jwt).GET().build());
            OutboundHttp.Response response = sent.value();
            if (response == null) {
                return sent.failedAs();
            }
            JsonNode body = Json.parse(response.body());
            if (response.status() != 200 || body == null || !body.isArray()) {
                return CallResult.failed(response.status() == 200
                        ? CallResult.Failure.of(CallResult.Kind.INVALID, "GitHub answered the installations without "
                                + "a list")
                        : GitHubTracker.failure(response, body));
            }
            for (JsonNode item : body) {
                GitHubApps.Installation installation = installation(item);
                if (installation != null) {
                    found.add(installation);
                }
            }
            if (body.size() < 100) {
                break;
            }
        }
        return CallResult.ok(found);
    }

    /** {@code GET /app/installations/{id}}. */
    public CallResult<GitHubApps.Installation> installation(String jwt, long installationId) {
        CallResult<OutboundHttp.Response> sent = http.send(baseUrl, request(baseUrl + "/app/installations/"
                + installationId, jwt).GET().build());
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        JsonNode body = Json.parse(response.body());
        if (response.status() != 200) {
            return CallResult.failed(GitHubTracker.failure(response, body));
        }
        GitHubApps.Installation installation = body != null ? installation(body) : null;
        return installation != null ? CallResult.ok(installation)
                : CallResult.failed(CallResult.Kind.INVALID, "GitHub answered an installation without its id or "
                        + "account");
    }

    /**
     * {@code POST /app/installations/{id}/access_tokens} with {@code repositories} (names, without the owner) and
     * {@code permissions}: 201 with {@code token}, {@code expires_at} and the permissions given.
     */
    public CallResult<GitHubApps.InstallationToken> accessToken(String jwt, long installationId,
            @Nullable List<String> repositories, Map<String, String> permissions) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (repositories != null) {
            body.put("repositories", repositories);
        }
        body.put("permissions", permissions);
        HttpRequest request = request(baseUrl + "/app/installations/" + installationId + "/access_tokens", jwt)
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
            .header("Content-Type", "application/json")
            .build();
        CallResult<OutboundHttp.Response> sent = http.send(baseUrl, request);
        OutboundHttp.Response response = sent.value();
        if (response == null) {
            return sent.failedAs();
        }
        JsonNode answer = Json.parse(response.body());
        if (response.status() != 201) {
            return CallResult.failed(GitHubTracker.failure(response, answer));
        }
        String token = Json.text(answer, "token");
        Instant expiresAt = Json.time(Json.text(answer, "expires_at"));
        if (token == null || token.isBlank() || expiresAt == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "GitHub answered a token without its value or expiry");
        }
        return CallResult.ok(new GitHubApps.InstallationToken(token, expiresAt, permissions(answer)));
    }

    private HttpRequest.Builder request(String uri, String jwt) {
        return http.request(uri)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + jwt)
            .header("X-GitHub-Api-Version", GitHubTracker.API_VERSION);
    }

    private static GitHubApps.@Nullable Installation installation(JsonNode item) {
        JsonNode id = item.get("id");
        JsonNode account = item.get("account");
        String login = Json.text(account, "login");
        if (id == null || !id.isIntegralNumber() || login == null) {
            return null;
        }
        String type = Json.text(account, "type");
        String selection = Json.text(item, "repository_selection");
        return new GitHubApps.Installation(id.longValue(), login, type != null ? type : "User",
                selection != null ? selection : "all", permissions(item));
    }

    private static Map<String, String> permissions(@Nullable JsonNode node) {
        Map<String, String> permissions = new LinkedHashMap<>();
        JsonNode given = node != null ? node.get("permissions") : null;
        if (given != null && given.isObject()) {
            given.properties().forEach(entry -> {
                if (entry.getValue().isString()) {
                    permissions.put(entry.getKey(), entry.getValue().stringValue());
                }
            });
        }
        return permissions;
    }

    /**
     * A PKCS#1 {@code RSAPrivateKey} wrapped as PKCS#8's {@code PrivateKeyInfo}: version 0, the algorithm
     * {@code rsaEncryption} (1.2.840.113549.1.1.1) with NULL parameters, and the key as an OCTET STRING.
     */
    static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] algorithm = { 0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01,
                0x01, 0x01, 0x05, 0x00 };
        byte[] version = { 0x02, 0x01, 0x00 };
        byte[] octets = tlv(0x04, pkcs1);
        byte[] content = new byte[version.length + algorithm.length + octets.length];
        System.arraycopy(version, 0, content, 0, version.length);
        System.arraycopy(algorithm, 0, content, version.length, algorithm.length);
        System.arraycopy(octets, 0, content, version.length + algorithm.length, octets.length);
        return tlv(0x30, content);
    }

    private static byte[] tlv(int tag, byte[] value) {
        byte[] length;
        if (value.length < 0x80) {
            length = new byte[] { (byte) value.length };
        }
        else {
            byte[] digits = BigInteger.valueOf(value.length).toByteArray();
            int start = digits[0] == 0 ? 1 : 0;
            length = new byte[digits.length - start + 1];
            length[0] = (byte) (0x80 | (digits.length - start));
            System.arraycopy(digits, start, length, 1, digits.length - start);
        }
        byte[] out = new byte[1 + length.length + value.length];
        out[0] = (byte) tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(value, 0, out, 1 + length.length, value.length);
        return out;
    }
}
