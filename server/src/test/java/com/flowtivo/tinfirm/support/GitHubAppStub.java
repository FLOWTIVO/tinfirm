package com.flowtivo.tinfirm.support;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A GitHub App at a fake GitHub (ADR-0116), under an API base of its own on {@link GitHubStub}'s server, as a GitHub
 * Enterprise Server's {@code https://{host}/api/v3} would be: {@code GET /app} for its id and key, its installations,
 * and {@code POST /app/installations/{id}/access_tokens}, each answered only for a JWT the App's key signed (RS256,
 * {@code iss} the App's id, at most ten minutes); and what GitHub answers an installation's token itself, 403 to
 * {@code GET /user} and its repositories to {@code GET /installation/repositories}. Tokens are test values; the key is
 * made for the test.
 */
public final class GitHubAppStub {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String path;

    private final long appId;

    private final KeyPair keys;

    private GitHubAppStub(String path, long appId, KeyPair keys) {
        this.path = path;
        this.appId = appId;
        this.keys = keys;
    }

    /** A new App with a key of its own, its API base unique to the test. */
    public static GitHubAppStub create(long appId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            GitHubAppStub stub = new GitHubAppStub("/ghe-" + UUID.randomUUID().toString().substring(0, 8) + "/api/v3",
                    appId, generator.generateKeyPair());
            GitHubStub.server().stubFor(get(urlEqualTo(stub.path + "/app"))
                .withHeader("Authorization", matching("Bearer .+"))
                .willReturn(GitHubStub.json(200, Map.of("id", appId, "slug", "tinfirm-test", "name", "Tinfirm test"))));
            return stub;
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The API base URL of the App's fake GitHub. */
    public String baseUrl() {
        return GitHubStub.url() + path;
    }

    public long appId() {
        return appId;
    }

    /** The App's private key as PEM PKCS#8 ({@code BEGIN PRIVATE KEY}). */
    public String privateKeyPem() {
        return pem("PRIVATE KEY", keys.getPrivate().getEncoded());
    }

    /** The App's private key as PEM PKCS#1 ({@code BEGIN RSA PRIVATE KEY}), as GitHub's settings page downloads it. */
    public String privateKeyPkcs1Pem() {
        byte[] pkcs8 = keys.getPrivate().getEncoded();
        // A 2048-bit key's PKCS#8 wraps its PKCS#1 key after a header of 26 bytes.
        return pem("RSA PRIVATE KEY", java.util.Arrays.copyOfRange(pkcs8, 26, pkcs8.length));
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    /** An installation on the account, with every repository, granting the App these permissions. */
    public void installation(long installationId, String login, Map<String, String> permissions) {
        Map<String, Object> body = installationBody(installationId, login, permissions);
        GitHubStub.server().stubFor(get(urlEqualTo(path + "/app/installations/" + installationId))
            .willReturn(GitHubStub.json(200, body)));
    }

    /** As {@link #installation}, answered only after {@code delayMillis}: a race with the call's caller. */
    public void installationSlowly(long installationId, String login, Map<String, String> permissions,
            int delayMillis) {
        Map<String, Object> body = installationBody(installationId, login, permissions);
        GitHubStub.server().stubFor(get(urlEqualTo(path + "/app/installations/" + installationId))
            .willReturn(GitHubStub.json(200, body).withFixedDelay(delayMillis)));
    }

    /** How often the installation was asked for. */
    public int installationAsked(long installationId) {
        return GitHubStub.server().findAll(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlEqualTo(path + "/app/installations/" + installationId))).size();
    }

    /**
     * {@code GET /app} at this App's API base answers another App's id from now on: an admin who sets that App on the
     * same system (the key is not checked by the fake).
     */
    public void answersAs(long otherAppId) {
        GitHubStub.server().stubFor(get(urlEqualTo(path + "/app"))
            .withHeader("Authorization", matching("Bearer .+"))
            .willReturn(GitHubStub.json(200, Map.of("id", otherAppId, "slug", "tinfirm-other", "name",
                    "Tinfirm other"))));
    }

    /** The App's installations, as one page. */
    public void installations(List<Map<String, Object>> installations) {
        GitHubStub.server().stubFor(get(urlPathEqualTo(path + "/app/installations"))
            .willReturn(GitHubStub.json(200, installations)));
    }

    public static Map<String, Object> installationBody(long installationId, String login,
            Map<String, String> permissions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", installationId);
        body.put("account", Map.of("login", login, "type", "Organization"));
        body.put("repository_selection", "all");
        body.put("permissions", permissions);
        return body;
    }

    /** {@code POST …/access_tokens} answers this token, valid an hour from now; replaces an earlier one. */
    public StubMapping mints(long installationId, String token) {
        return GitHubStub.server().stubFor(post(urlEqualTo(path + "/app/installations/" + installationId
                + "/access_tokens"))
            .withHeader("Authorization", matching("Bearer .+"))
            .willReturn(GitHubStub.json(201, Map.of("token", token, "expires_at",
                    Instant.now().plusSeconds(3600).toString(), "permissions", Map.of("contents", "write")))));
    }

    /** {@code POST …/access_tokens} answers 422, as GitHub does for a repository the installation does not cover. */
    public StubMapping refusesMint(long installationId) {
        return GitHubStub.server().stubFor(post(urlEqualTo(path + "/app/installations/" + installationId
                + "/access_tokens"))
            .willReturn(GitHubStub.json(422, Map.of("message", "There is at least one repository that does not "
                    + "exist or is not accessible to the parent installation."))));
    }

    /**
     * {@code POST …/access_tokens} answers 422 for every set of repositories that names this one, as GitHub does once
     * the installation no longer covers it; a set without it is answered as before, whatever is stubbed after this.
     */
    public StubMapping refusesMintOf(long installationId, String repository) {
        return GitHubStub.server().stubFor(post(urlEqualTo(path + "/app/installations/" + installationId
                + "/access_tokens"))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.repositories[?(@ == '" + repository + "')]"))
            .willReturn(GitHubStub.json(422, Map.of("message", "There is at least one repository that does not "
                    + "exist or is not accessible to the parent installation."))));
    }

    /**
     * {@code POST …/access_tokens} answers 401, as GitHub does while it does not take the App's JWT (a key deleted at
     * GitHub, a clock that runs ahead); replaces an earlier answer.
     */
    public StubMapping refusesTheApp(long installationId) {
        return GitHubStub.server().stubFor(post(urlEqualTo(path + "/app/installations/" + installationId
                + "/access_tokens"))
            .willReturn(GitHubStub.json(401, Map.of("message", "Bad credentials"))));
    }

    /**
     * An installation's token as GitHub treats one: {@code GET /user} answers it 403, since that endpoint is for a
     * user's token, and {@code GET /installation/repositories} lists the account's repositories for it.
     */
    public void installationToken(String token, String login, List<String> repositories) {
        GitHubStub.server().stubFor(get(urlEqualTo(path + "/user"))
            .withHeader("Authorization", equalTo("Bearer " + token))
            .willReturn(GitHubStub.json(403, Map.of("message", "Resource not accessible by integration"))));
        List<Map<String, Object>> listed = repositories.stream()
            .map(name -> Map.<String, Object>of("name", name, "full_name", login + "/" + name, "owner",
                    Map.of("login", login, "type", "Organization")))
            .toList();
        GitHubStub.server().stubFor(get(urlPathEqualTo(path + "/installation/repositories"))
            .withHeader("Authorization", equalTo("Bearer " + token))
            .willReturn(GitHubStub.json(200, Map.of("total_count", listed.size(), "repository_selection", "all",
                    "repositories", listed))));
    }

    /** GitHub takes the installation's token no longer: {@code GET /installation/repositories} answers it 401. */
    public void refusesToken(String token) {
        GitHubStub.server().stubFor(get(urlPathEqualTo(path + "/installation/repositories"))
            .withHeader("Authorization", equalTo("Bearer " + token))
            .willReturn(GitHubStub.json(401, Map.of("message", "Bad credentials"))));
    }

    /** A user's token at the App's fake GitHub: {@code GET /user} answers the login for it. */
    public void user(String token, String login) {
        GitHubStub.server().stubFor(get(urlEqualTo(path + "/user"))
            .withHeader("Authorization", equalTo("Bearer " + token))
            .willReturn(GitHubStub.json(200, Map.of("id", Math.abs((long) login.hashCode()) + 1, "login", login))));
    }

    /** The bodies of the mints of the installation so far, oldest first. */
    public List<JsonNode> mintBodies(long installationId) {
        return GitHubStub.server().findAll(postRequestedFor(urlEqualTo(path + "/app/installations/"
                + installationId + "/access_tokens")))
            .stream()
            .map(LoggedRequest::getBodyAsString)
            .map(JSON::readTree)
            .toList();
    }

    /** The JWTs the App's requests carried so far. */
    public List<String> jwts() {
        return GitHubStub.server().getAllServeEvents().stream()
            .map(event -> event.getRequest())
            .filter(request -> request.getUrl().startsWith(path + "/app"))
            .map(request -> request.getHeader("Authorization"))
            .filter(header -> header != null && header.startsWith("Bearer "))
            .map(header -> header.substring("Bearer ".length()))
            .toList();
    }

    /** Whether the JWT is the App's: RS256 signed by its key, {@code iss} its id, at most ten minutes long. */
    public boolean signedByTheApp(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify((RSAPublicKey) keys.getPublic());
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                return false;
            }
            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return "RS256".equals(header.get("alg").asString()) && claims.get("iss").asLong() == appId
                    && claims.get("exp").asLong() - claims.get("iat").asLong() <= 600;
        }
        catch (Exception ex) {
            return false;
        }
    }
}
