package com.flowtivo.tinfirm.system.internal.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.net.AddressGuard;
import com.flowtivo.tinfirm.shared.net.BoundedBodies;
import com.flowtivo.tinfirm.shared.net.GuardedHosts;
import com.flowtivo.tinfirm.shared.net.GuardedResolverProvider;
import com.flowtivo.tinfirm.shared.net.OutboundProxy;
import com.flowtivo.tinfirm.shared.net.OutboundUrls;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.system.CallResult;

/**
 * The one outbound HTTP client of calls to systems (CONVENTIONS §14.4): https only unless the operator allows http,
 * never following redirects, never calling loopback, link-local or private addresses unless the operator allows them
 * for company systems, with the configured timeouts, and only to the origin of the base URL a call names. A company
 * system on one of the operator's internal hosts ({@code TINFIRM_CONNECTION_INTERNAL_HOSTS}, ADR-0066) may reach
 * private addresses, never loopback, link-local, unspecified or multicast ones, and may use http. The client of
 * a personal MCP server ({@link #personal()}) never calls private addresses, whatever the operator allows or names:
 * any member registers one (the security review of the redesign, row 12). Nor does the test-only
 * {@code tinfirm.connection.allowed-private-hosts} reach it, and the server refuses to start with that property set
 * outside the {@code test} profile ({@link #refuseTestSettingsOutsideTests}), since Spring's relaxed binding would
 * take it from a variable ({@code TINFIRM_CONNECTION_ALLOWEDPRIVATEHOSTS}); the tests' personal servers reach their
 * stubs through a bean only the tests define ({@link PersonalStubHosts}). The host is resolved and every address checked before
 * each request, and while the request is in flight the JVM's resolver leaves those addresses out of the lookup the
 * client connects with ({@link GuardedResolverProvider}), so a DNS answer that changes in between cannot reach them. A
 * read ({@code GET}) that the system answers with 502, 503 or 504, or that fails to connect, is tried again at most
 * twice, after 0.5 s and 2 s (a 503's {@code Retry-After} of at most 5 s instead); a write is never tried again. A
 * response body is read up to 16 MiB, and the timeout covers the request and the body together ({@link BoundedBodies}):
 * a system that begins an answer and does not end it fails the call as one that never answered. Failures come back as
 * values, never as exceptions, and never hold a token. A
 * call never runs inside a database transaction ({@link NoTransaction}): the caller reads before it and writes after it
 * in short transactions of their own.
 */
@Component
public class OutboundHttp {

    private static final Logger log = LoggerFactory.getLogger(OutboundHttp.class);

    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    /** The waits before the second and the third attempt of a read. */
    static final List<Duration> READ_BACKOFF = List.of(Duration.ofMillis(500), Duration.ofSeconds(2));

    /** The longest {@code Retry-After} of a 503 that a read waits for. */
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(5);

    /** Waits between the attempts of a read; tests count the waits instead. */
    @FunctionalInterface
    interface Sleeper {

        Sleeper REAL = duration -> Thread.sleep(duration);

        void sleep(Duration duration) throws InterruptedException;
    }

    private final OutboundProperties properties;

    /** The profile of the tests, the only one the test-only settings may be set in. */
    static final String TEST_PROFILE = "test";

    /** The test-only property, refused outside the {@code test} profile. */
    static final String ALLOWED_PRIVATE_HOSTS = "tinfirm.connection.allowed-private-hosts";

    /**
     * The test-only host names that may resolve to private addresses for a company system ({@link OutboundProperties});
     * never for a personal server.
     */
    private final Set<String> allowedPrivateHosts;

    /** TEST ONLY: the host names a personal server may resolve to private addresses at ({@link PersonalStubHosts}). */
    private final Set<String> personalStubHosts;

    /**
     * The operator's internal hosts (ADR-0066), already normalized: a company system on one reaches the private network
     * and may use http; a personal server never does.
     */
    private final Set<String> internalHosts;

    private final HttpClient http;

    private final Sleeper sleeper;

    /** Whether this is the client of a personal MCP server, which never reaches private addresses. */
    private final boolean personal;

    private final @Nullable OutboundHttp personalClient;

    /**
     * The server's client.
     *
     * @throws IllegalStateException when a test-only setting is set outside the {@code test} profile
     */
    @Autowired
    public OutboundHttp(OutboundProperties properties, Environment environment,
            ObjectProvider<PersonalStubHosts> personalStubHosts) {
        this(refuseTestSettingsOutsideTests(properties, environment, personalStubHosts.getIfAvailable()),
                Sleeper.REAL, personalStubHosts.getIfAvailable(() -> new PersonalStubHosts(Set.of())).hosts(),
                OutboundProxy.selector(environment));
    }

    /** A client without a proxy, for the tests. */
    OutboundHttp(OutboundProperties properties, Sleeper sleeper, Set<String> personalStubHosts) {
        this(properties, sleeper, personalStubHosts, HttpClient.Builder.NO_PROXY);
    }

    /**
     * @param proxy {@link HttpClient.Builder#NO_PROXY} unless the operator names one ({@link OutboundProxy}): the JVM's
     *              proxy settings never apply, since a proxy would resolve the host past the connect-time guard
     */
    OutboundHttp(OutboundProperties properties, Sleeper sleeper, Set<String> personalStubHosts, ProxySelector proxy) {
        this.properties = properties;
        this.sleeper = sleeper;
        this.allowedPrivateHosts = normalized(properties.allowedPrivateHosts());
        this.personalStubHosts = normalized(personalStubHosts);
        this.internalHosts = properties.internalHosts();
        this.http = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(proxy)
            .build();
        this.personal = false;
        installGuardedResolver();
        if (!properties.allowPrivateAddresses() && !GuardedResolverProvider.installed()) {
            throw new IllegalStateException("The connect-time address guard of the outbound client is not the JVM's "
                    + "resolver (java.net.spi.InetAddressResolverProvider), so calls to systems are not safe against "
                    + "DNS rebinding. Start the server with its classes on the class path or from its executable jar, "
                    + "or allow private addresses (TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES).");
        }
        this.personalClient = new OutboundHttp(this);
    }

    /** The client of personal servers, sharing the HTTP client of the company's. */
    private OutboundHttp(OutboundHttp company) {
        this.properties = company.properties;
        this.allowedPrivateHosts = company.allowedPrivateHosts;
        this.personalStubHosts = company.personalStubHosts;
        this.internalHosts = company.internalHosts;
        this.http = company.http;
        this.sleeper = company.sleeper;
        this.personal = true;
        this.personalClient = null;
    }

    /**
     * Refuses the test-only settings outside the {@code test} profile: {@code tinfirm.connection.allowed-private-hosts},
     * which Spring would also take from a variable ({@code TINFIRM_CONNECTION_ALLOWEDPRIVATEHOSTS} as this record binds
     * it, {@code TINFIRM_CONNECTION_ALLOWED_PRIVATE_HOSTS} as the model gateway's {@code @Value} resolves it), and a
     * {@link PersonalStubHosts} bean. Answers the properties, for the constructor's chain.
     */
    static OutboundProperties refuseTestSettingsOutsideTests(OutboundProperties properties, Environment environment,
            @Nullable PersonalStubHosts personalStubHosts) {
        if (environment.matchesProfiles(TEST_PROFILE)) {
            return properties;
        }
        String raw = environment.getProperty(ALLOWED_PRIVATE_HOSTS);
        if (!properties.allowedPrivateHosts().isEmpty() || raw != null && !raw.isBlank()) {
            throw new IllegalStateException("tinfirm.connection.allowed-private-hosts (or the variable "
                    + "TINFIRM_CONNECTION_ALLOWEDPRIVATEHOSTS) is set, but it is for the tests only and lets named "
                    + "hosts reach private addresses: remove it. An operator who wants company systems to reach a "
                    + "service on the deployment's own network names its host in TINFIRM_CONNECTION_INTERNAL_HOSTS "
                    + "instead, or allows every private address with TINFIRM_CONNECTION_ALLOW_PRIVATE_ADDRESSES.");
        }
        if (personalStubHosts != null && !personalStubHosts.hosts().isEmpty()) {
            throw new IllegalStateException("A PersonalStubHosts bean is defined outside the test profile: it is for "
                    + "the tests only, and a personal MCP server never reaches a private address.");
        }
        return properties;
    }

    private static Set<String> normalized(Set<String> hosts) {
        return hosts.stream().map(GuardedHosts::normalize).collect(Collectors.toUnmodifiableSet());
    }

    /** The JDK installs the resolver with the first lookup that needs one. */
    private static void installGuardedResolver() {
        try {
            InetAddress.getAllByName("localhost");
        }
        catch (UnknownHostException ex) {
            // Resolving localhost failed, but the resolver was installed by trying.
        }
    }

    /** The client of a personal MCP server: private addresses are never allowed, whatever the operator allows. */
    public OutboundHttp personal() {
        return personalClient != null ? personalClient : this;
    }

    /** The client of a system: a personal one's, or the company's. */
    public OutboundHttp of(boolean personalSystem) {
        return personalSystem ? personal() : this;
    }

    /** A response of a system: the status, the headers and the body as text. */
    public record Response(int status, HttpHeaders headers, String body) {

        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }

        public boolean success() {
            return status >= 200 && status <= 299;
        }
    }

    /**
     * Why the URL may not be a system's base URL or an endpoint it names, or null when it may: not https (or http
     * where allowed), user info, a query or a fragment, no host, a host that cannot be resolved or resolves to a
     * loopback, link-local or private address where those are not allowed.
     */
    public @Nullable String urlProblem(String url) {
        String problem = OutboundUrls.problem(url, policy(hostOf(url)));
        return "it must not contain credentials".equals(problem)
                ? "it must not contain credentials; connect an account for the token" : problem;
    }

    /**
     * The guard's policy for a URL on the host: http where the operator allows it, or for a company system on one of
     * the operator's internal hosts (ADR-0066), whose traffic stays on the deployment's private network.
     */
    private OutboundUrls.Policy policy(@Nullable String host) {
        return new OutboundUrls.Policy(properties.allowHttp() || internal(host), reach(host),
                "the operator can allow http with TINFIRM_CONNECTION_ALLOW_HTTP, or for one internal host with "
                        + "TINFIRM_CONNECTION_INTERNAL_HOSTS");
    }

    /**
     * The addresses the host may resolve to. For a company system: any, where the operator allows private addresses
     * or a test names the host; the private network, for one of the operator's internal hosts (ADR-0066); else
     * public ones. For a personal server public ones only, but for a test's stub ({@link PersonalStubHosts}):
     * neither the operator's switch, nor the internal hosts, nor {@code tinfirm.connection.allowed-private-hosts}
     * reaches it.
     */
    AddressGuard.Reach reach(@Nullable String host) {
        String name = host != null ? GuardedHosts.normalize(host) : null;
        if (personal) {
            return name != null && personalStubHosts.contains(name) ? AddressGuard.Reach.ANY
                    : AddressGuard.Reach.PUBLIC;
        }
        if (properties.allowPrivateAddresses() || name != null && allowedPrivateHosts.contains(name)) {
            return AddressGuard.Reach.ANY;
        }
        return internal(host) ? AddressGuard.Reach.PRIVATE_NETWORK : AddressGuard.Reach.PUBLIC;
    }

    /** Whether the host is one of the operator's internal hosts, for a company system; never for a personal server. */
    private boolean internal(@Nullable String host) {
        return !personal && host != null && internalHosts.contains(GuardedHosts.normalize(host));
    }

    private static @Nullable String hostOf(String url) {
        try {
            return new URI(url).getHost();
        }
        catch (URISyntaxException ex) {
            return null;
        }
    }

    /** A request builder for the URI, with the timeout and the User-Agent. */
    public HttpRequest.Builder request(String uri) {
        return HttpRequest.newBuilder(URI.create(uri)).timeout(properties.timeout()).header("User-Agent", "Tinfirm");
    }

    /**
     * Sends the request, which must go to the origin of {@code baseUrl}. The host is resolved and checked before the
     * call, and guarded while it is in flight; a read is tried again as the class says. Never inside a database
     * transaction, which would wait on the system with its connection held.
     *
     * @throws IllegalStateException inside a database transaction ({@link NoTransaction})
     */
    public CallResult<Response> send(String baseUrl, HttpRequest request) {
        return send(baseUrl, request, MAX_BODY_BYTES);
    }

    /**
     * As {@link #send(String, HttpRequest)}, with the answer's body read up to {@code maxBodyBytes} (at most the
     * client's 16 MiB): a larger one fails {@code INVALID} and its connection is closed ({@link BoundedBodies}).
     */
    public CallResult<Response> send(String baseUrl, HttpRequest request, int maxBodyBytes) {
        NoTransaction.require("A call to a system");
        int limit = Math.min(maxBodyBytes, MAX_BODY_BYTES);
        URI target = request.uri();
        String originProblem = originProblem(baseUrl, target);
        if (originProblem != null) {
            return CallResult.failed(CallResult.Kind.INVALID, originProblem);
        }
        String host = target.getHost();
        AddressGuard.Reach reach = reach(host);
        String addressProblem = AddressGuard.problem(host, reach);
        if (addressProblem != null) {
            return CallResult.failed(CallResult.Kind.INVALID, "The request was not sent: " + addressProblem);
        }
        if (reach != AddressGuard.Reach.ANY && !GuardedResolverProvider.installed()) {
            return CallResult.failed(CallResult.Kind.INVALID, "The request was not sent: the connect-time address "
                    + "guard is not installed");
        }
        boolean read = "GET".equalsIgnoreCase(request.method());
        try (GuardedHosts.Registration guard = GuardedHosts.GLOBAL.open(host, reach)) {
            for (int attempt = 0;; attempt++) {
                Attempt result = attempt(request, host, limit);
                Duration wait = read && attempt < READ_BACKOFF.size() ? retryAfter(result, attempt) : null;
                if (wait == null) {
                    return result.result();
                }
                log.info("{} {} on {}: trying the read again in {} ms (attempt {} of {})", request.method(),
                        result.status() != null ? "answered " + result.status() : "could not connect", host,
                        wait.toMillis(), attempt + 2, READ_BACKOFF.size() + 1);
                try {
                    sleeper.sleep(wait);
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return CallResult.failed(CallResult.Kind.UNAVAILABLE, "Interrupted while calling " + host);
                }
            }
        }
    }

    /** A response of a system whose body is bytes (a file), read up to the caller's limit. */
    public record BytesResponse(int status, HttpHeaders headers, byte[] body) {
    }

    /**
     * Sends the request once (never again: a file is large), which must go to the origin of {@code baseUrl}, and reads
     * the body as bytes, at most {@code maxBytes} ({@code INVALID} beyond). Guarded as {@link #send}.
     *
     * @throws IllegalStateException inside a database transaction ({@link NoTransaction})
     */
    public CallResult<BytesResponse> sendForBytes(String baseUrl, HttpRequest request, long maxBytes) {
        NoTransaction.require("A call to a system");
        URI target = request.uri();
        String originProblem = originProblem(baseUrl, target);
        if (originProblem != null) {
            return CallResult.failed(CallResult.Kind.INVALID, originProblem);
        }
        String host = target.getHost();
        AddressGuard.Reach reach = reach(host);
        String addressProblem = AddressGuard.problem(host, reach);
        if (addressProblem != null) {
            return CallResult.failed(CallResult.Kind.INVALID, "The request was not sent: " + addressProblem);
        }
        if (reach != AddressGuard.Reach.ANY && !GuardedResolverProvider.installed()) {
            return CallResult.failed(CallResult.Kind.INVALID, "The request was not sent: the connect-time address "
                    + "guard is not installed");
        }
        try (GuardedHosts.Registration guard = GuardedHosts.GLOBAL.open(host, reach)) {
            HttpResponse<byte @Nullable []> response = http.send(request, BoundedBodies.ofByteArray(
                    (int) Math.min(Integer.MAX_VALUE - 8, maxBytes), properties.timeout()));
            byte[] body = response.body();
            if (body == null) {
                return CallResult.failed(CallResult.Kind.INVALID, "The file is larger than " + maxBytes + " bytes");
            }
            return CallResult.ok(new BytesResponse(response.statusCode(), response.headers(), body));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return CallResult.failed(CallResult.Kind.UNAVAILABLE, "Interrupted while calling " + host);
        }
        catch (HttpTimeoutException ex) {
            return CallResult.failed(CallResult.Kind.UNAVAILABLE, host + " did not answer in time ("
                    + ex.getClass().getSimpleName() + ")");
        }
        catch (IOException ex) {
            return CallResult.failed(CallResult.Kind.UNAVAILABLE, host + " could not be reached ("
                    + ex.getClass().getSimpleName() + ")");
        }
        catch (IllegalArgumentException ex) {
            return CallResult.failed(CallResult.Kind.INVALID, "The request URL is not usable");
        }
    }

    /**
     * Opens a WebSocket (Slack's Socket Mode, the package 5 contract §5.2): {@code wss}, or {@code ws} only where the
     * operator allows http; a host the caller's profile allows; its addresses checked before and guarded while it
     * connects, as {@link #send}'s are. Answers the open socket, whose frames go to the listener.
     *
     * @throws IllegalStateException inside a database transaction ({@link NoTransaction})
     */
    public CallResult<java.net.http.WebSocket> webSocket(String url, java.util.function.Predicate<String> hostAllowed,
            java.net.http.WebSocket.Listener listener) {
        NoTransaction.require("A connection to a system");
        URI target;
        try {
            target = new URI(url);
        }
        catch (URISyntaxException ex) {
            return CallResult.failed(CallResult.Kind.INVALID, "The socket's URL is not usable");
        }
        String scheme = target.getScheme() != null ? target.getScheme().toLowerCase(Locale.ROOT) : "";
        String schemeProblem = OutboundUrls.schemeProblem("wss".equals(scheme) ? "https"
                : "ws".equals(scheme) ? "http" : scheme, policy(target.getHost()));
        if (schemeProblem != null || target.getHost() == null || target.getRawUserInfo() != null) {
            return CallResult.failed(CallResult.Kind.INVALID, "The socket was not opened: its URL must be wss with a "
                    + "host and no credentials");
        }
        String host = target.getHost();
        if (!hostAllowed.test(host)) {
            return CallResult.failed(CallResult.Kind.INVALID, "The socket was not opened: " + host
                    + " is not a host of the system");
        }
        AddressGuard.Reach reach = reach(host);
        String addressProblem = AddressGuard.problem(host, reach);
        if (addressProblem != null) {
            return CallResult.failed(CallResult.Kind.INVALID, "The socket was not opened: " + addressProblem);
        }
        if (reach != AddressGuard.Reach.ANY && !GuardedResolverProvider.installed()) {
            return CallResult.failed(CallResult.Kind.INVALID, "The socket was not opened: the connect-time address "
                    + "guard is not installed");
        }
        try (GuardedHosts.Registration guard = GuardedHosts.GLOBAL.open(host, reach)) {
            java.net.http.WebSocket socket = http.newWebSocketBuilder()
                .connectTimeout(properties.timeout())
                .header("User-Agent", "Tinfirm")
                .buildAsync(target, listener)
                .get(properties.timeout().toMillis() + 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            return CallResult.ok(socket);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return CallResult.failed(CallResult.Kind.UNAVAILABLE, "Interrupted while connecting to " + host);
        }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            return CallResult.failed(CallResult.Kind.UNAVAILABLE, host + " could not be reached ("
                    + (ex.getCause() != null ? ex.getCause().getClass().getSimpleName()
                            : ex.getClass().getSimpleName())
                    + ")");
        }
    }

    /**
     * One attempt: its result, the status of an answer, and whether it could not connect.
     *
     * @param connectFailed the system was not reached or dropped the connection before answering (a read may retry)
     */
    private record Attempt(CallResult<Response> result, @Nullable Integer status, boolean connectFailed) {

        static Attempt answered(Response response) {
            return new Attempt(CallResult.ok(response), response.status(), false);
        }

        static Attempt failed(CallResult.Kind kind, String message, boolean connectFailed) {
            return new Attempt(CallResult.failed(kind, message), null, connectFailed);
        }
    }

    private Attempt attempt(HttpRequest request, String host, int maxBodyBytes) {
        try {
            // The handler is made right before the send: its deadline covers the request and the body together.
            HttpResponse<@Nullable String> response = http.send(request,
                    BoundedBodies.ofString(maxBodyBytes, properties.timeout()));
            String body = response.body();
            if (body == null) {
                return Attempt.failed(CallResult.Kind.INVALID, "The answer is larger than " + size(maxBodyBytes),
                        false);
            }
            return Attempt.answered(new Response(response.statusCode(), response.headers(), body));
        }
        catch (UnknownHostException ex) {
            return Attempt.failed(CallResult.Kind.INVALID, "The host " + host + " is unknown"
                    + (reach(host) == AddressGuard.Reach.ANY ? ""
                            : ", or resolves only to addresses an outbound request may not go to"),
                    false);
        }
        catch (SSLException ex) {
            return Attempt.failed(CallResult.Kind.INVALID, "The TLS connection to " + host + " failed ("
                    + ex.getClass().getSimpleName() + ")", false);
        }
        catch (HttpConnectTimeoutException | ConnectException ex) {
            return Attempt.failed(CallResult.Kind.UNAVAILABLE, host + " could not be reached ("
                    + ex.getClass().getSimpleName() + ")", true);
        }
        catch (HttpTimeoutException ex) {
            // The system took the request and did not answer in time, or began its answer and did not end it: asking
            // again would only wait as long again.
            return Attempt.failed(CallResult.Kind.UNAVAILABLE, host + " did not answer in time ("
                    + ex.getClass().getSimpleName() + ")", false);
        }
        catch (IOException ex) {
            // The connection was reset or closed before the answer.
            return Attempt.failed(CallResult.Kind.UNAVAILABLE, host + " could not be reached ("
                    + ex.getClass().getSimpleName() + ")", true);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Attempt.failed(CallResult.Kind.UNAVAILABLE, "Interrupted while calling " + host, false);
        }
        catch (IllegalArgumentException ex) {
            return Attempt.failed(CallResult.Kind.INVALID, "The request URL is not usable", false);
        }
    }

    /** A size in MiB, or in KiB below one MiB. */
    static String size(int bytes) {
        return bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0 ? bytes / 1024 / 1024 + " MiB"
                : bytes / 1024 + " KiB";
    }

    /**
     * How long to wait before reading again, or null to keep this result: a connection that failed, and 502, 503
     * and 504, are tried again after the backoff; a 503 with a {@code Retry-After} after that time instead, unless it
     * is longer than {@link #MAX_RETRY_AFTER}.
     */
    private static @Nullable Duration retryAfter(Attempt attempt, int number) {
        Duration backoff = READ_BACKOFF.get(number);
        if (attempt.connectFailed()) {
            return backoff;
        }
        Integer status = attempt.status();
        if (status == null || (status != 502 && status != 503 && status != 504)) {
            return null;
        }
        if (status == 503) {
            Response response = Objects.requireNonNull(attempt.result().value());
            Duration asked = response.header("Retry-After").map(OutboundHttp::retryAfter).orElse(null);
            if (asked != null) {
                return asked.compareTo(MAX_RETRY_AFTER) <= 0 ? asked : null;
            }
        }
        return backoff;
    }

    /** A {@code Retry-After} in seconds or as an HTTP date, from now; null when it is neither. */
    public static @Nullable Duration retryAfter(String value) {
        String trimmed = value.strip();
        if (trimmed.matches("[0-9]{1,9}")) {
            return Duration.ofSeconds(Long.parseLong(trimmed));
        }
        try {
            Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration until = Duration.between(Instant.now(), at);
            return until.isNegative() ? Duration.ZERO : until;
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** The failure kind of a status that is not 2xx: redirects and client errors are settings, 5xx may pass. */
    public static CallResult.Kind kindOf(int status) {
        if (status >= 300 && status < 400) {
            return CallResult.Kind.INVALID;
        }
        return switch (status) {
            case 401, 403 -> CallResult.Kind.UNAUTHORIZED;
            case 404 -> CallResult.Kind.NOT_FOUND;
            case 429 -> CallResult.Kind.RATE_LIMITED;
            case 400, 405, 409, 422 -> CallResult.Kind.INVALID;
            default -> status >= 500 ? CallResult.Kind.UNAVAILABLE : CallResult.Kind.OTHER;
        };
    }

    /** Why the request's URI is not on the origin of the base URL, or null when it is. */
    @Nullable String originProblem(String baseUrl, URI target) {
        URI base;
        try {
            base = new URI(baseUrl);
        }
        catch (URISyntaxException ex) {
            return "The base URL is not usable";
        }
        String scheme = target.getScheme() != null ? target.getScheme().toLowerCase(Locale.ROOT) : "";
        String schemeProblem = OutboundUrls.schemeProblem(scheme, policy(target.getHost()));
        if (schemeProblem != null) {
            return "The request was not sent: " + schemeProblem;
        }
        if (target.getHost() == null || target.getRawUserInfo() != null) {
            return "The request was not sent: its URL has no host or carries credentials";
        }
        if (!scheme.equals(base.getScheme() != null ? base.getScheme().toLowerCase(Locale.ROOT) : "")
                || !target.getHost().equalsIgnoreCase(base.getHost()) || port(target) != port(base)) {
            return "The request was not sent: " + target.getScheme() + "://" + target.getRawAuthority()
                    + " is not the system's origin";
        }
        return null;
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }
}
