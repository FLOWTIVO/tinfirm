package com.flowtivo.tinfirm.support;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * One WireMock server for the whole test run, standing in for the destination of error reports (ADR-0070): the
 * envelope endpoint of project 42 of a tracker that speaks Sentry's protocol, at {@link #dsn()}. A test stubs its
 * answer and verifies what was sent. TEST FIXTURE ONLY: the key is a test value.
 */
public final class SentryStub {

    /** The test DSN's public key. */
    public static final String KEY = "0123456789abcdef0123456789abcdef"; // gitleaks:allow (test fixture)

    /** The envelope endpoint of the test DSN's project. */
    public static final String ENVELOPE = "/api/42/envelope/";

    private static final WireMockServer SERVER = new WireMockServer(StubServers.options().dynamicPort());

    static {
        SERVER.start();
    }

    private SentryStub() {
    }

    public static WireMockServer server() {
        return SERVER;
    }

    /** {@code http://<key>@localhost:<port>/42}: http, which the server accepts for a loopback destination only. */
    public static String dsn() {
        return "http://" + KEY + "@localhost:" + SERVER.port() + "/42";
    }
}
