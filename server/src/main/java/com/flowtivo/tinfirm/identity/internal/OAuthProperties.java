package com.flowtivo.tinfirm.identity.internal;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of Tinfirm's OAuth authorization server (ADR-0045). None is an operator setting: the defaults are the
 * production behaviour, and the test profile relaxes {@link Cimd} so that a metadata document can be served by a
 * local stub.
 *
 * @param cimd the fetch of Client ID Metadata Documents
 */
@Validated
@ConfigurationProperties("tinfirm.oauth")
record OAuthProperties(@Valid @NotNull @DefaultValue Cimd cimd) {

    /**
     * How Client ID Metadata Documents are fetched: through the outbound guard (https only, no redirects, no
     * loopback, link-local or private addresses), with a timeout and a size cap, cached by their HTTP cache headers
     * up to {@code cacheCeiling}.
     *
     * @param allowHttp           TESTS ONLY, never in production: accept {@code http://localhost} client ids (the
     *                            stub of the integration tests); a real client id is always https. The server refuses
     *                            to start with it set outside the {@code test} profile
     *                            ({@link CimdDocumentFetcher#refuseTestSettingsOutsideTests})
     * @param allowedPrivateHosts TESTS ONLY, never in production: host names that may resolve to loopback or private
     *                            addresses (the stub's {@code localhost}); every other host is guarded as for
     *                            connections, and an IP literal always is. Refused outside the {@code test} profile as
     *                            {@code allowHttp} is
     * @param timeout             connect timeout of one fetch, and its whole budget from the request to the end of
     *                            the document's body
     * @param maxDocumentBytes    the largest document read
     * @param cacheCeiling        the longest a document is cached, whatever its {@code Cache-Control} says
     * @param cacheSize           the most documents cached at once, and the most refusals remembered
     * @param maxConcurrentFetches the most fetches in flight at once on a replica; one more is refused at once,
     *                            never queued, so that slow hosts hold at most this many request threads
     * @param fetchesPerAddressMinute the most fetches of documents not cached that one client address may cause in
     *                            a minute (a token bucket); one more is refused without a fetch
     * @param refusalTtl          how long a refused document stays refused without being fetched again
     */
    record Cimd(@DefaultValue("false") boolean allowHttp, @DefaultValue List<String> allowedPrivateHosts,
            @NotNull @DefaultValue("PT5S") Duration timeout, @DefaultValue("16384") int maxDocumentBytes,
            @NotNull @DefaultValue("PT1H") Duration cacheCeiling, @DefaultValue("1000") int cacheSize,
            @Positive @DefaultValue("8") int maxConcurrentFetches,
            @Positive @DefaultValue("10") int fetchesPerAddressMinute,
            @NotNull @DefaultValue("PT60S") Duration refusalTtl) {
    }
}
