package com.flowtivo.tinfirm.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.github.tomakehurst.wiremock.verification.notmatched.NotMatchedRenderer;

/**
 * What the WireMock servers of the tests share. Most of them live for the whole test run, and every test adds stubs of
 * its own, so they hold thousands of stubs by its end. Two of WireMock's defaults cost more with each one:
 * <ul>
 * <li>an unmatched request is answered with a diff against its nearest stubs, which WireMock finds by measuring the
 * request against every stub, and keeps in the request journal. A {@code 404} with the request line costs nothing,
 * and is all a server under test reads of it;</li>
 * <li>the request journal keeps every request of the run. {@link #forgetRequests()} empties it after each test class:
 * a test verifies only the requests it made itself.</li>
 * </ul>
 * TEST FIXTURE ONLY.
 */
public final class StubServers {

    private StubServers() {
    }

    /** WireMock's options with the cheap answer to an unmatched request; a server adds its port and the rest. */
    public static WireMockConfiguration options() {
        return WireMockConfiguration.options().notMatchedRendererFactory(extensions -> new Unmatched());
    }

    /**
     * Empties the request journals of the servers shared by the whole run. Their stubs stay: a server under test may
     * still call a system of an earlier test in the background (its own context lives on in Spring's cache).
     */
    public static void forgetRequests() {
        for (WireMockServer server : new WireMockServer[] { JiraStub.server(), GitHubStub.server(),
                GitLabStub.server(), McpStub.server(), FakeSlack.server(), FakeBotFramework.server(),
                SentryStub.server(), FakeGoogle.server() }) {
            server.resetRequests();
        }
    }

    /** {@code 404} with the request's method and URL, without searching the stubs for the nearest ones. */
    private static final class Unmatched extends NotMatchedRenderer {

        @Override
        protected ResponseDefinition render(Admin admin, ServeEvent serveEvent) {
            LoggedRequest request = serveEvent.getRequest();
            return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(404)
                .withHeader("Content-Type", "text/plain")
                .withBody("No stub matches " + request.getMethod() + " " + request.getUrl())
                .build();
        }
    }
}
