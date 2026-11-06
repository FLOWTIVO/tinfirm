package com.flowtivo.tinfirm.identity.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.ClientIdMetadataDocumentResolver;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.InvalidClientMetadataException;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.http.converter.OAuth2ClientRegistrationHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.flowtivo.tinfirm.shared.net.BoundedBodies;
import com.flowtivo.tinfirm.shared.net.GuardedHosts;
import com.flowtivo.tinfirm.shared.net.GuardedResolverProvider;
import com.flowtivo.tinfirm.shared.net.OutboundProxy;
import com.flowtivo.tinfirm.shared.net.OutboundUrls;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import io.github.bucket4j.Bucket;

/**
 * Fetches a client's Client ID Metadata Document (ADR-0045) through the outbound guard that connections use
 * (CONVENTIONS §14.4): the client id must be an https URL without user info, query or fragment, its host must
 * resolve only to public addresses (checked before the request, and again at connect time by the JVM's guarded
 * resolver), redirects are not followed, the request and its body together time out ({@link BoundedBodies}), and
 * the document is read up to a size cap. The fetch is a request to a URL the client chose, made before anyone signed
 * in, so every refusal is final.
 * <p>
 * A document is cached by its {@code Cache-Control} ({@code no-store} and {@code max-age=0} are not cached, no
 * header means 5 minutes), never longer than an hour, in a bounded cache. The library's repository is told never
 * to cache (its own cache is unbounded); {@link #firstResolution} tells the caller whether a resolved document is
 * new, so that it is recorded and audited once per fetch.
 * <p>
 * Anyone may make the server fetch a URL this way, before signing in (the security review of 2026-10-27, L11), so the
 * fetches are bounded: at most {@code maxConcurrentFetches} (8) in flight on a replica, one more refused at once rather
 * than queued; at most {@code fetchesPerAddressMinute} (10) fetches of documents not cached per client address and
 * minute (a token bucket per {@code getRemoteAddr()}, as the MCP endpoint's limiter is, in memory per replica); and a
 * refused document stays refused for {@code refusalTtl} (60 s) without being fetched again. A refusal of the limits
 * themselves is not remembered: it says nothing of the document.
 * <p>
 * The client ignores the JVM's proxy settings and uses only an explicit {@code tinfirm.outbound.proxy}
 * ({@link OutboundProxy}); the test-only settings are refused outside the {@code test} profile
 * ({@link #refuseTestSettingsOutsideTests}).
 */
@Component
class CimdDocumentFetcher implements ClientIdMetadataDocumentResolver {

    private static final Logger log = LoggerFactory.getLogger(CimdDocumentFetcher.class);

    static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);

    private static final Pattern MAX_AGE = Pattern.compile("\\bmax-age=(\\d{1,10})\\b", Pattern.CASE_INSENSITIVE);

    private static final String HTTP_HINT = "a client id is always an https URL";

    /** The profile of the tests, the only one the test-only settings may be set in. */
    static final String TEST_PROFILE = "test";

    /** The most client addresses tracked at once; the least recently used bucket is dropped beyond it. */
    static final long MAX_TRACKED_ADDRESSES = 100_000;

    private final OAuthProperties.Cimd settings;

    private final Set<String> allowedPrivateHosts;

    private final HttpClient http;

    private final OAuth2ClientRegistrationHttpMessageConverter converter =
            new OAuth2ClientRegistrationHttpMessageConverter();

    private final Cache<URI, Fetched> cache;

    /** The documents refused lately, with the reason, so that they are not fetched again for a while. */
    private final Cache<URI, String> refusals;

    /** The fetches in flight on this replica. */
    private final Semaphore inFlight;

    /** A token bucket per client address, for the fetches of documents not cached. */
    private final Cache<String, Bucket> addresses;

    /** The address of the client whose request asks for the document, or null outside a request. */
    private final Supplier<@Nullable String> callerAddress;

    /** A fetched document, how long it may be cached, and whether its first resolution was recorded. */
    private record Fetched(OAuth2ClientRegistration registration, Duration ttl, AtomicBoolean recorded) {
    }

    /**
     * The server's fetcher.
     *
     * @throws IllegalStateException when a test-only setting is set outside the {@code test} profile, or the explicit
     *                               proxy is not an {@code http://host:port} URL
     */
    @Autowired
    CimdDocumentFetcher(OAuthProperties properties, Environment environment) {
        this(refuseTestSettingsOutsideTests(properties, environment), OutboundProxy.selector(environment),
                CimdDocumentFetcher::requestAddress);
    }

    /** A fetcher without a proxy, limited by the address of the current request (the tests). */
    CimdDocumentFetcher(OAuthProperties properties) {
        this(properties, HttpClient.Builder.NO_PROXY, CimdDocumentFetcher::requestAddress);
    }

    CimdDocumentFetcher(OAuthProperties properties, ProxySelector proxy, Supplier<@Nullable String> callerAddress) {
        this.settings = properties.cimd();
        this.allowedPrivateHosts = settings.allowedPrivateHosts()
            .stream()
            .map(GuardedHosts::normalize)
            .collect(Collectors.toUnmodifiableSet());
        this.http = HttpClient.newBuilder()
            .connectTimeout(settings.timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(proxy)
            .build();
        this.cache = Caffeine.newBuilder()
            .maximumSize(settings.cacheSize())
            .expireAfter(Expiry.creating((URI uri, Fetched fetched) -> fetched.ttl()))
            .build();
        this.refusals = Caffeine.newBuilder()
            .maximumSize(settings.cacheSize())
            .expireAfterWrite(settings.refusalTtl())
            .build();
        this.inFlight = new Semaphore(settings.maxConcurrentFetches());
        this.addresses = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_ADDRESSES)
            .expireAfterAccess(Duration.ofMinutes(1))
            .build();
        this.callerAddress = callerAddress;
    }

    /**
     * Refuses the test-only settings outside the {@code test} profile, as the outbound client of systems does
     * ({@code OutboundHttp.refuseTestSettingsOutsideTests}): {@code allowHttp} and {@code allowedPrivateHosts}, which
     * Spring would also take from variables. Answers the properties, for the constructor's chain.
     */
    static OAuthProperties refuseTestSettingsOutsideTests(OAuthProperties properties, Environment environment) {
        if (environment.matchesProfiles(TEST_PROFILE)) {
            return properties;
        }
        OAuthProperties.Cimd cimd = properties.cimd();
        if (cimd.allowHttp() || cimd.allowedPrivateHosts().stream().anyMatch(host -> !host.isBlank())) {
            throw new IllegalStateException("tinfirm.oauth.cimd.allow-http or tinfirm.oauth.cimd.allowed-private-hosts "
                    + "(or their variables) is set, but they are for the tests only and let a client id reach http "
                    + "or private addresses: remove them. A client metadata document is always fetched over https "
                    + "from a public address.");
        }
        return properties;
    }

    @Override
    public Result resolve(URI clientId) throws InvalidClientMetadataException {
        Fetched fetched = cache.getIfPresent(clientId);
        if (fetched == null) {
            String refusal = refusals.getIfPresent(clientId);
            if (refusal != null) {
                throw new InvalidClientMetadataException(refusal, clientId.toString(), (String) null);
            }
            fetched = limitedFetch(clientId);
            if (!fetched.ttl().isZero()) {
                cache.put(clientId, fetched);
            }
        }
        // The library's repository must not cache: this cache is bounded, the library's is not.
        return new Result(fetched.registration(), new ResponseAttributes(-1));
    }

    /** A fetch within the client address's bucket and the replica's fetches in flight; a refusal is remembered. */
    private Fetched limitedFetch(URI clientId) throws InvalidClientMetadataException {
        String address = callerAddress.get();
        if (address != null && !addresses.get(address, key -> bucket()).tryConsume(1)) {
            throw refused(clientId, "too many client metadata documents were asked for from this address; try again "
                    + "in a minute");
        }
        if (!inFlight.tryAcquire()) {
            throw refused(clientId, "too many client metadata documents are being fetched; try again");
        }
        try {
            return fetch(clientId);
        }
        catch (InvalidClientMetadataException ex) {
            if (!Thread.currentThread().isInterrupted() && ex.getMessage() != null) {
                refusals.put(clientId, ex.getMessage());
            }
            throw ex;
        }
        finally {
            inFlight.release();
        }
    }

    private Bucket bucket() {
        int perMinute = settings.fetchesPerAddressMinute();
        return Bucket.builder()
            .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
            .build();
    }

    /** The client address of the request on this thread ({@code getRemoteAddr()}), or null outside a request. */
    static @Nullable String requestAddress() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRemoteAddr() : null;
    }

    /**
     * Whether this is the first resolution of the document since it was fetched: true once per cached document, and
     * every time for a document that may not be cached.
     */
    boolean firstResolution(URI clientId) {
        Fetched fetched = cache.getIfPresent(clientId);
        return fetched == null || fetched.recorded().compareAndSet(false, true);
    }

    private Fetched fetch(URI clientId) throws InvalidClientMetadataException {
        String url = clientId.toString();
        String host = clientId.getHost();
        boolean privateAllowed = host != null && allowedPrivateHosts.contains(GuardedHosts.normalize(host));
        String problem = OutboundUrls.problem(url,
                new OutboundUrls.Policy(settings.allowHttp(), privateAllowed, HTTP_HINT));
        if (problem == null && !privateAllowed && !GuardedResolverProvider.installed()) {
            problem = "the connect-time address guard is not installed";
        }
        if (problem != null) {
            throw refused(clientId, "the client id URL is not allowed: " + problem);
        }
        HttpRequest request = HttpRequest.newBuilder(clientId)
            .timeout(settings.timeout())
            .header("Accept", MediaType.APPLICATION_JSON_VALUE)
            .header("User-Agent", "Tinfirm")
            .GET()
            .build();
        try (GuardedHosts.Registration ignored = privateAllowed ? GuardedHosts.Registration.NONE
                : GuardedHosts.GLOBAL.open(host)) {
            // The handler is made right before the send: its deadline covers the request and the body together.
            HttpResponse<byte @Nullable []> response = http.send(request,
                    BoundedBodies.ofByteArray(settings.maxDocumentBytes(), settings.timeout()));
            byte[] body = response.body();
            if (response.statusCode() != 200) {
                throw refused(clientId, "the document answered " + response.statusCode()
                        + (response.statusCode() / 100 == 3 ? " (redirects are not followed)" : ""));
            }
            if (body == null) {
                throw refused(clientId, "the document is larger than " + settings.maxDocumentBytes() + " bytes");
            }
            OAuth2ClientRegistration registration = parse(clientId, body);
            Duration ttl = ttl(response.headers().allValues(HttpHeaders.CACHE_CONTROL));
            log.info("Fetched the client metadata document of {} (cached for {} s)", host, ttl.toSeconds());
            return new Fetched(registration, ttl, new AtomicBoolean(false));
        }
        catch (IOException ex) {
            throw refused(clientId, "the document could not be fetched (" + ex.getClass().getSimpleName() + ")");
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw refused(clientId, "interrupted");
        }
        catch (IllegalArgumentException ex) {
            throw refused(clientId, "the client id URL is not usable");
        }
    }

    private OAuth2ClientRegistration parse(URI clientId, byte[] body) throws InvalidClientMetadataException {
        HttpInputMessage message = new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                return headers;
            }
        };
        try {
            return converter.read(OAuth2ClientRegistration.class, message);
        }
        catch (IOException | RuntimeException ex) {
            throw refused(clientId, "the document is not a client metadata document");
        }
    }

    /** How long a document may be cached, from its {@code Cache-Control}, at most the ceiling. */
    Duration ttl(List<String> cacheControl) {
        String value = String.join(",", cacheControl).toLowerCase(Locale.ROOT);
        Duration ttl = DEFAULT_CACHE_TTL;
        if (value.contains("no-store") || value.contains("no-cache")) {
            ttl = Duration.ZERO;
        }
        else {
            Matcher matcher = MAX_AGE.matcher(value);
            if (matcher.find()) {
                ttl = Duration.ofSeconds(Long.parseLong(matcher.group(1)));
            }
        }
        return ttl.compareTo(settings.cacheCeiling()) > 0 ? settings.cacheCeiling() : ttl;
    }

    private static InvalidClientMetadataException refused(URI clientId, @Nullable String reason) {
        log.info("Refused the client metadata document of {}: {}", clientId.getHost(), reason);
        return new InvalidClientMetadataException("Client metadata refused: " + reason, clientId.toString(),
                (String) null);
    }
}
