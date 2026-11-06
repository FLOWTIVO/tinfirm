package com.flowtivo.tinfirm.support;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * A minimal OpenID provider for tests: discovery, JWKS, an authorization endpoint that immediately "logs in" the
 * identity set with {@link #nextLogin}, a token endpoint that checks client authentication and PKCE (S256), and a
 * userinfo endpoint. An endpoint's answers can be padded ({@link #pad}), to answer more than the server reads.
 * TEST FIXTURE ONLY.
 */
public final class OidcProviderStub implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;

    private final RSAKey signingKey;

    private final String clientId;

    private final String clientSecret;

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    private final Map<String, Identity> accessTokens = new ConcurrentHashMap<>();

    private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();

    private volatile Identity nextLogin;

    private volatile List<String> tokenAuthMethods = List.of("client_secret_basic");

    private final Map<String, Padding> padding = new ConcurrentHashMap<>();

    /**
     * The identity the IdP asserts: the subject and the other claims of the ID token and the userinfo response.
     * {@code claims} may hold arrays (lists), as Entra ID sends {@code verified_primary_email}.
     */
    public record Identity(String subject, Map<String, Object> claims) {

        public Identity {
            claims = Map.copyOf(claims);
        }

        /** Keycloak style: {@code email}, {@code email_verified} and {@code name}. */
        public Identity(String subject, String email, boolean emailVerified, String name) {
            this(subject, Map.of("email", email, "email_verified", emailVerified, "name", name));
        }
    }

    private record Grant(Identity identity, String nonce, String codeChallenge) {
    }

    private record Padding(int characters, boolean declared) {
    }

    public OidcProviderStub(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        try {
            this.signingKey = new RSAKeyGenerator(2048).keyID("stub-key").generate();
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        }
        catch (JOSEException | IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.createContext("/.well-known/openid-configuration", this::discovery);
        server.createContext("/jwks", exchange -> respond(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toJSONObject()));
        server.createContext("/authorize", this::authorize);
        server.createContext("/token", this::token);
        server.createContext("/userinfo", this::userInfo);
        server.start();
    }

    public String issuer() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public void nextLogin(Identity identity) {
        this.nextLogin = identity;
    }

    /** What the discovery document advertises as {@code token_endpoint_auth_methods_supported}. */
    public void tokenAuthMethods(List<String> methods) {
        this.tokenAuthMethods = List.copyOf(methods);
    }

    /**
     * The answers of an endpoint ({@code /token}, {@code /jwks} or {@code /userinfo}) carry a {@code padding} member of
     * this many characters, sent with their length declared, or chunked; {@code 0} stops it.
     */
    public void pad(String endpoint, int characters, boolean declared) {
        if (characters == 0) {
            padding.remove(endpoint);
        }
        else {
            padding.put(endpoint, new Padding(characters, declared));
        }
    }

    /** Form parameters of every token request, with the {@code Authorization} header under "authorization". */
    public List<Map<String, String>> tokenRequests() {
        return tokenRequests;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void discovery(HttpExchange exchange) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer());
        metadata.put("authorization_endpoint", issuer() + "/authorize");
        metadata.put("token_endpoint", issuer() + "/token");
        metadata.put("userinfo_endpoint", issuer() + "/userinfo");
        metadata.put("jwks_uri", issuer() + "/jwks");
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("subject_types_supported", List.of("public"));
        metadata.put("id_token_signing_alg_values_supported", List.of("RS256"));
        metadata.put("token_endpoint_auth_methods_supported", tokenAuthMethods);
        metadata.put("grant_types_supported", List.of("authorization_code"));
        metadata.put("scopes_supported", List.of("openid", "email", "profile"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        respond(exchange, 200, metadata);
    }

    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
        String code = UUID.randomUUID().toString();
        grants.put(code, new Grant(nextLogin, query.get("nonce"), query.get("code_challenge")));
        String location = query.get("redirect_uri") + "?code=" + code + "&state="
                + URLEncoder.encode(query.get("state"), StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        Map<String, String> recorded = new LinkedHashMap<>(form);
        recorded.put("authorization", authorization);
        tokenRequests.add(recorded);

        String expectedAuth = "Basic " + Base64.getEncoder()
            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        Grant grant = grants.remove(form.get("code"));
        if (!expectedAuth.equals(authorization)) {
            respond(exchange, 401, Map.of("error", "invalid_client"));
            return;
        }
        if (grant == null || grant.codeChallenge() == null || form.get("code_verifier") == null
                || !grant.codeChallenge().equals(s256(form.get("code_verifier")))) {
            respond(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        String accessToken = UUID.randomUUID().toString();
        accessTokens.put(accessToken, grant.identity());
        respond(exchange, 200, Map.of("access_token", accessToken, "token_type", "Bearer", "expires_in", 300,
                "scope", "openid email profile", "id_token", idToken(grant)));
    }

    private void userInfo(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        Identity identity = authorization == null ? null : accessTokens.get(authorization.replace("Bearer ", ""));
        if (identity == null) {
            respond(exchange, 401, Map.of("error", "invalid_token"));
            return;
        }
        respond(exchange, 200, claims(identity));
    }

    private String idToken(Grant grant) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer())
            .audience(clientId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("nonce", grant.nonce());
        claims(grant.identity()).forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims.build());
        try {
            jwt.sign(new RSASSASigner(signingKey));
        }
        catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
        return jwt.serialize();
    }

    private static Map<String, Object> claims(Identity identity) {
        Map<String, Object> claims = new LinkedHashMap<>(identity.claims());
        claims.put("sub", identity.subject());
        return claims;
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, String> parse(String encoded) {
        Map<String, String> params = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return params;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    private void respond(HttpExchange exchange, int status, Map<String, Object> body) {
        Padding padded = padding.get(exchange.getHttpContext().getPath());
        Map<String, Object> answer = body;
        if (padded != null) {
            answer = new LinkedHashMap<>(body);
            answer.put("padding", "x".repeat(padded.characters()));
        }
        try {
            byte[] bytes = JSON.writeValueAsBytes(answer);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, padded != null && !padded.declared() ? 0 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        catch (IOException ex) {
            if (padded == null) {
                throw new UncheckedIOException(ex);
            }
            // The server stopped reading a padded answer and closed the connection.
            exchange.close();
        }
    }
}
