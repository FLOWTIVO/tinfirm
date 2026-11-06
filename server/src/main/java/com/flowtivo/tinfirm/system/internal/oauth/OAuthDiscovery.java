package com.flowtivo.tinfirm.system.internal.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.mcp.McpClient;

import tools.jackson.databind.JsonNode;

/**
 * The OAuth discovery of an MCP server (the package 1 contract, §3.3: its app's endpoints are discovered), as the MCP
 * specification's authorization section has a client do it, every request through the system's outbound client and its
 * guard (each URL a document names is checked like a system's base URL before it is called, and again at connect
 * time; a personal server's never reach private addresses):
 * <ol>
 * <li>An {@code initialize} to the endpoint without a token. A {@code 2xx} means the server needs no token from
 * people: it offers no OAuth, and that is no error. A {@code 401} names the protected-resource metadata in
 * {@code WWW-Authenticate: Bearer resource_metadata="…"}; without it the server tries
 * {@code /.well-known/oauth-protected-resource{path}} of the endpoint, then the one at its root.</li>
 * <li>The protected-resource metadata (RFC 9728) must name the endpoint, exactly, as its {@code resource}; it names
 * the authorization servers, and the first usable one is taken.</li>
 * <li>An authorization server's metadata is {@code /.well-known/oauth-authorization-server} with the path insertion of
 * RFC 8414, else OpenID Connect's {@code /.well-known/openid-configuration} (inserted, then appended). It must name
 * its own issuer, exactly as the resource named it, an authorization and a token endpoint, and list {@code S256} in
 * {@code code_challenge_methods_supported}; its endpoints must pass the guard and share the issuer's origin (scheme,
 * host and port), unless the operator allows their origin for a split-hosted provider
 * ({@link OAuthDiscoveryProperties}): a server that named a real provider's authorization endpoint and its own token
 * endpoint would otherwise receive the codes and PKCE verifiers of that provider's consent (the security review of
 * 2026-10-27, M8). The revocation and registration endpoints, {@code scopes_supported} and
 * {@code client_id_metadata_document_supported} are kept.</li>
 * </ol>
 * A failure is a value with a reason of at most {@value #MAX_ERROR} characters that names the status or the rule,
 * never a document's body or a token; the system then offers no OAuth.
 */
public class OAuthDiscovery {

    /** The longest reason kept ({@code oauth_discovery_error}). */
    public static final int MAX_ERROR = 500;

    /** The longest {@code scopes_supported} kept, space-separated ({@code oauth_scopes_supported}). */
    public static final int MAX_SCOPES = 2000;

    /** A metadata document larger than this is refused. */
    static final int MAX_DOCUMENT_BYTES = 64 * 1024;

    /** At most this many authorization servers of one resource are tried. */
    static final int MAX_AUTHORIZATION_SERVERS = 5;

    static final String PROTECTED_RESOURCE = "/.well-known/oauth-protected-resource";

    static final String AUTHORIZATION_SERVER = "/.well-known/oauth-authorization-server";

    static final String OPENID_CONFIGURATION = "/.well-known/openid-configuration";

    private static final Pattern RESOURCE_METADATA = Pattern.compile(
            "resource_metadata\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))", Pattern.CASE_INSENSITIVE);

    private final OutboundHttp http;

    /** The origins the operator allows endpoints on besides the issuer's, normalized ({@link #origin}). */
    private final Set<String> allowedEndpointOrigins;

    public OAuthDiscovery(OutboundHttp http) {
        this(http, Set.of());
    }

    public OAuthDiscovery(OutboundHttp http, Set<String> allowedEndpointOrigins) {
        this.http = http;
        this.allowedEndpointOrigins = Set.copyOf(allowedEndpointOrigins);
    }

    /**
     * What the discovery found: the endpoints of the authorization server Tinfirm will use.
     *
     * @param scopesSupported its {@code scopes_supported}, space-separated, or null
     * @param cimdSupported   whether it takes a Client ID Metadata Document as a client id
     */
    public record Discovered(String authorizationEndpoint, String tokenEndpoint, @Nullable String revocationEndpoint,
            @Nullable String registrationEndpoint, @Nullable String scopesSupported, boolean cimdSupported) {
    }

    /**
     * The outcome: the discovered endpoints; or neither (the server needs no token, so it offers no OAuth); or why
     * the discovery failed.
     */
    public record Outcome(@Nullable Discovered discovered, @Nullable String error) {

        static Outcome noOAuth() {
            return new Outcome(null, null);
        }

        static Outcome failed(String error) {
            return new Outcome(null, error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR - 1) + "…");
        }
    }

    /** Runs the discovery against the endpoint; never throws for anything the server answers. */
    public Outcome discover(String endpoint) {
        CallResult<OutboundHttp.Response> probe = http.send(endpoint, initialize(endpoint));
        if (probe.failure() != null) {
            return Outcome.failed("Tinfirm could not ask the server without a token: " + probe.failureMessage());
        }
        OutboundHttp.Response answer = Objects.requireNonNull(probe.value());
        if (answer.success()) {
            closeSession(endpoint, answer);
            return Outcome.noOAuth();
        }
        if (answer.status() != 401) {
            return Outcome.failed("The server answered " + answer.status() + " to a request without a token, not "
                    + "401 with an OAuth challenge");
        }
        String named = resourceMetadata(String.join(", ", answer.headers().allValues("WWW-Authenticate")));
        List<String> candidates = named != null ? List.of(named) : protectedResourceUrls(endpoint);
        JsonNode resource = null;
        for (String url : candidates) {
            Fetched fetched = fetch(url, "protected-resource metadata");
            if (fetched.notFound()) {
                continue;
            }
            if (fetched.problem() != null) {
                return Outcome.failed(fetched.problem());
            }
            resource = fetched.document();
            break;
        }
        if (resource == null) {
            return Outcome.failed("The server asks for a token but publishes no protected-resource metadata "
                    + "(RFC 9728) at " + String.join(" or ", candidates));
        }
        // RFC 9728 §3.3: the metadata is for this resource only when it names it, exactly.
        if (!endpoint.equals(Json.text(resource, "resource"))) {
            return Outcome.failed("The protected-resource metadata names another resource than the endpoint "
                    + endpoint);
        }
        List<String> servers = texts(resource, "authorization_servers");
        if (servers.isEmpty()) {
            return Outcome.failed("The protected-resource metadata names no authorization_servers");
        }
        String reason = null;
        for (String issuer : servers.subList(0, Math.min(servers.size(), MAX_AUTHORIZATION_SERVERS))) {
            Attempt attempt = authorizationServer(issuer);
            if (attempt.discovered() != null) {
                return new Outcome(attempt.discovered(), null);
            }
            if (reason == null) {
                reason = attempt.problem();
            }
        }
        return Outcome.failed(Objects.requireNonNull(reason));
    }

    // ---- the authorization server ----------------------------------------------------------------------------------

    /** One authorization server: its endpoints, or why it is not usable. */
    private record Attempt(@Nullable Discovered discovered, @Nullable String problem) {

        static Attempt refused(String problem) {
            return new Attempt(null, problem);
        }
    }

    private Attempt authorizationServer(String issuer) {
        String issuerProblem = http.urlProblem(issuer);
        if (issuerProblem != null) {
            return Attempt.refused("The authorization server " + issuer + " is not allowed: " + issuerProblem);
        }
        List<String> urls = authorizationServerUrls(issuer);
        for (String url : urls) {
            Fetched fetched = fetch(url, "authorization server metadata");
            if (fetched.notFound()) {
                continue;
            }
            if (fetched.problem() != null) {
                return Attempt.refused(fetched.problem());
            }
            return usable(issuer, Objects.requireNonNull(fetched.document()));
        }
        return Attempt.refused("The authorization server " + issuer + " publishes no metadata (RFC 8414 or OpenID "
                + "Connect discovery)");
    }

    /** The metadata of the issuer as Tinfirm uses it, or why it cannot. */
    private Attempt usable(String issuer, JsonNode metadata) {
        // RFC 8414 §3.3: the issuer named in the metadata is exactly the one it was fetched for.
        String named = Json.text(metadata, "issuer");
        if (named == null || !named.equals(issuer)) {
            return Attempt.refused("The metadata of the authorization server " + issuer + " names another issuer");
        }
        String authorization = Json.text(metadata, "authorization_endpoint");
        String token = Json.text(metadata, "token_endpoint");
        if (authorization == null || token == null) {
            return Attempt.refused("The authorization server " + issuer + " names no authorization_endpoint or "
                    + "token_endpoint");
        }
        if (!texts(metadata, "code_challenge_methods_supported").contains("S256")) {
            return Attempt.refused("The authorization server " + issuer + " does not list S256 in "
                    + "code_challenge_methods_supported, so it cannot be used with PKCE");
        }
        String revocation = Json.text(metadata, "revocation_endpoint");
        String registration = Json.text(metadata, "registration_endpoint");
        for (String[] endpoint : new String[][] { { "authorization_endpoint", authorization },
                { "token_endpoint", token }, { "revocation_endpoint", revocation },
                { "registration_endpoint", registration } }) {
            if (endpoint[1] != null) {
                // The origin first: it needs no lookup of a host that is not to be called anyway.
                String problem = originProblem(issuer, endpoint[1]);
                if (problem == null) {
                    problem = http.urlProblem(endpoint[1]);
                }
                if (problem != null) {
                    return Attempt.refused("The " + endpoint[0] + " of " + issuer + " is not allowed: " + problem);
                }
            }
        }
        return new Attempt(new Discovered(authorization, token, revocation, registration,
                scopes(texts(metadata, "scopes_supported")), Json.flag(metadata, "client_id_metadata_document_supported")),
                null);
    }

    /**
     * Why an endpoint the issuer's metadata names may not be used for its origin, or null: it shares the issuer's
     * scheme, host and port, or the operator allows its origin.
     */
    @Nullable String originProblem(String issuer, String endpoint) {
        String endpointOrigin = origin(endpoint);
        if (endpointOrigin == null) {
            return "it is not a URL with an origin";
        }
        if (endpointOrigin.equals(origin(issuer)) || allowedEndpointOrigins.contains(endpointOrigin)) {
            return null;
        }
        return "it is on " + endpointOrigin + ", not on the issuer's origin; Tinfirm sends codes and tokens only to "
                + "the issuer's origin (the operator may allow a split-hosted provider's origin with "
                + "TINFIRM_OAUTH_DISCOVERY_ALLOWEDENDPOINTORIGINS)";
    }

    /**
     * A URL's origin as {@code scheme://host:port}, the scheme and the host in lower case and the default port named
     * (443 for https, 80 for http); null for a URL without a scheme or a host.
     */
    public static @Nullable String origin(String url) {
        URI uri;
        try {
            uri = new URI(url.strip());
        }
        catch (URISyntaxException ex) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80
                : -1;
        if (port == -1) {
            return null;
        }
        String name = host.toLowerCase(Locale.ROOT);
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return scheme + "://" + name + ":" + port;
    }

    // ---- fetching ----------------------------------------------------------------------------------------------------

    /** A fetched JSON document; {@code notFound} for a 404, where the next candidate is tried. */
    private record Fetched(@Nullable JsonNode document, @Nullable String problem, boolean notFound) {
    }

    private Fetched fetch(String url, String what) {
        String problem = http.urlProblem(url);
        if (problem != null) {
            return new Fetched(null, "The URL of the " + what + " is not allowed: " + problem, false);
        }
        HttpRequest request = http.request(url).header("Accept", "application/json").GET().build();
        CallResult<OutboundHttp.Response> sent = http.send(url, request);
        if (sent.failure() != null) {
            return new Fetched(null, "The " + what + " could not be read: " + sent.failureMessage(), false);
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        if (response.status() == 404) {
            return new Fetched(null, null, true);
        }
        if (response.status() != 200) {
            return new Fetched(null, "The " + what + " at " + url + " answered " + response.status()
                    + (response.status() / 100 == 3 ? " (redirects are not followed)" : ""), false);
        }
        if (response.body().getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            return new Fetched(null, "The " + what + " at " + url + " is larger than " + MAX_DOCUMENT_BYTES / 1024
                    + " KiB", false);
        }
        JsonNode document = Json.parse(response.body());
        if (document == null || !document.isObject() || document.isEmpty()) {
            return new Fetched(null, "The " + what + " at " + url + " is not a JSON object", false);
        }
        return new Fetched(document, null, false);
    }

    /** The {@code initialize} a client sends first, here without a token. */
    private HttpRequest initialize(String endpoint) {
        String body = Json.write(java.util.Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params",
                java.util.Map.of("protocolVersion", McpClient.PROTOCOL_VERSION, "capabilities", java.util.Map.of(),
                        "clientInfo", java.util.Map.of("name", "tinfirm", "version", "1"))));
        return http.request(endpoint)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", McpClient.PROTOCOL_VERSION)
            .build();
    }

    /** A server that answered without a token may have opened a session: end it; nothing depends on the answer. */
    private void closeSession(String endpoint, OutboundHttp.Response answer) {
        answer.header("Mcp-Session-Id").filter(id -> !id.isBlank()).ifPresent(id -> http.send(endpoint,
                http.request(endpoint).DELETE().header("Mcp-Session-Id", id).build()));
    }

    // ---- URLs ----------------------------------------------------------------------------------------------------------

    /** The {@code resource_metadata} parameter of a {@code WWW-Authenticate} header, or null. */
    static @Nullable String resourceMetadata(String header) {
        Matcher matcher = RESOURCE_METADATA.matcher(header);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return value != null && !value.isBlank() ? value.strip() : null;
    }

    /**
     * Where an endpoint's protected-resource metadata is when its 401 names none (RFC 9728 §3.1): the well-known name
     * inserted between the host and the path, then at the root.
     */
    static List<String> protectedResourceUrls(String endpoint) {
        URI uri = URI.create(trimmed(endpoint));
        String origin = origin(uri);
        String path = uri.getRawPath() != null ? trimmed(uri.getRawPath()) : "";
        List<String> urls = new ArrayList<>();
        if (!path.isEmpty()) {
            urls.add(origin + PROTECTED_RESOURCE + path);
        }
        urls.add(origin + PROTECTED_RESOURCE);
        return urls;
    }

    /**
     * Where an issuer's metadata is, in the order the MCP specification tries them: RFC 8414's well-known name inserted
     * before the issuer's path, then OpenID Connect's inserted, then appended; for an issuer without a path, the two
     * well-known names at its root.
     */
    static List<String> authorizationServerUrls(String issuer) {
        URI uri = URI.create(trimmed(issuer));
        String origin = origin(uri);
        String path = uri.getRawPath() != null ? trimmed(uri.getRawPath()) : "";
        if (path.isEmpty()) {
            return List.of(origin + AUTHORIZATION_SERVER, origin + OPENID_CONFIGURATION);
        }
        return List.of(origin + AUTHORIZATION_SERVER + path, origin + OPENID_CONFIGURATION + path,
                origin + path + OPENID_CONFIGURATION);
    }

    private static String origin(URI uri) {
        try {
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT), uri.getRawAuthority(), null, null, null)
                .toString();
        }
        catch (URISyntaxException ex) {
            throw new IllegalArgumentException("not a URL with an origin", ex);
        }
    }

    private static String trimmed(String url) {
        String trimmed = url.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** The strings of an array member, at most 200. */
    private static List<String> texts(JsonNode document, String field) {
        JsonNode value = document.get(field);
        List<String> texts = new ArrayList<>();
        if (value != null && value.isArray()) {
            for (JsonNode element : value) {
                if (element.isString() && !element.stringValue().isBlank() && texts.size() < 200) {
                    texts.add(element.stringValue().strip());
                }
            }
        }
        return texts;
    }

    /** The scopes, space-separated, whole scopes only up to {@value #MAX_SCOPES} characters; null for none. */
    static @Nullable String scopes(List<String> scopes) {
        StringBuilder joined = new StringBuilder();
        for (String scope : scopes) {
            if (scope.chars().anyMatch(Character::isWhitespace)
                    || joined.length() + scope.length() + 1 > MAX_SCOPES) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            joined.append(scope);
        }
        return joined.isEmpty() ? null : joined.toString();
    }
}
