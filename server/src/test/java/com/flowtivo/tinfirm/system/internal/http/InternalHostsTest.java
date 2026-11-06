package com.flowtivo.tinfirm.system.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.flowtivo.tinfirm.shared.net.AddressGuard;
import com.flowtivo.tinfirm.system.CallResult;

/**
 * The operator's internal hosts ({@code TINFIRM_CONNECTION_INTERNAL_HOSTS}, ADR-0066): a company system on one may
 * resolve to private addresses only and use http, never public, loopback or link-local addresses; every other host
 * keeps the guard, and a personal MCP server never honours the list. The hosts are IP literals here, which the guard
 * checks without a lookup, so the test needs no DNS.
 */
class InternalHostsTest {

    private static final String INTERNAL = "10.20.30.40";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboundProperties.class)
    @Import(OutboundHttp.class)
    static class Outbound {
    }

    private static OutboundHttp client(Set<String> internalHosts) {
        return new OutboundHttp(new OutboundProperties(false, false, Duration.ofSeconds(5), Duration.ofSeconds(10),
                Set.of(), internalHosts), OutboundHttp.Sleeper.REAL, Set.of());
    }

    @Test
    void aCompanySystemOnAnInternalHostReachesThePrivateNetworkOverHttp() {
        OutboundHttp company = client(Set.of(INTERNAL, "mcp-server"));

        assertThat(company.reach(INTERNAL)).isEqualTo(AddressGuard.Reach.PRIVATE_NETWORK);
        assertThat(company.reach("MCP-SERVER.")).isEqualTo(AddressGuard.Reach.PRIVATE_NETWORK);
        assertThat(company.urlProblem("http://" + INTERNAL + ":8080/mcp")).isNull();
        assertThat(company.urlProblem("https://" + INTERNAL + "/mcp")).isNull();

        // Another private host is not on the list: neither http nor its address passes.
        assertThat(company.reach("10.20.30.41")).isEqualTo(AddressGuard.Reach.PUBLIC);
        assertThat(company.urlProblem("http://10.20.30.41:8080/mcp")).contains("it must be https")
            .contains("TINFIRM_CONNECTION_INTERNAL_HOSTS");
        assertThat(company.urlProblem("https://10.20.30.41/mcp")).contains("resolves to a private address");
    }

    @Test
    void anInternalHostNeverReachesLoopbackOrLinkLocalAddresses() {
        OutboundHttp company = client(Set.of("127.0.0.1", "169.254.169.254", "0.0.0.0"));

        assertThat(company.urlProblem("http://127.0.0.1:8081/actuator")).contains("resolves to a loopback address");
        assertThat(company.urlProblem("http://169.254.169.254/latest/meta-data"))
            .contains("resolves to a link-local address");
        assertThat(company.urlProblem("http://0.0.0.0/")).contains("resolves to an unspecified address");
        CallResult<OutboundHttp.Response> sent = company.send("http://127.0.0.1:9",
                company.request("http://127.0.0.1:9/mcp").GET().build());
        assertThat(sent.failure()).isNotNull();
        assertThat(sent.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
        assertThat(sent.failureMessage()).startsWith("The request was not sent").contains("loopback");
    }

    @Test
    void anInternalHostNeverReachesAPublicAddress() {
        // Its http would cross the Internet in the clear, with the account's token.
        OutboundHttp company = client(Set.of("140.82.112.3"));

        assertThat(company.reach("140.82.112.3")).isEqualTo(AddressGuard.Reach.PRIVATE_NETWORK);
        assertThat(company.urlProblem("http://140.82.112.3/mcp")).contains("resolves to a public address");
        assertThat(company.urlProblem("https://140.82.112.3/mcp")).contains("resolves to a public address");
        CallResult<OutboundHttp.Response> sent = company.send("http://140.82.112.3",
                company.request("http://140.82.112.3/mcp").GET().build());
        assertThat(sent.failure()).isNotNull();
        assertThat(sent.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
        assertThat(sent.failureMessage()).startsWith("The request was not sent").contains("public address");
    }

    @Test
    void aPersonalServerNeverHonoursTheList() {
        OutboundHttp personal = client(Set.of(INTERNAL)).personal();

        assertThat(personal.reach(INTERNAL)).isEqualTo(AddressGuard.Reach.PUBLIC);
        assertThat(personal.urlProblem("http://" + INTERNAL + ":8080/mcp")).contains("it must be https");
        assertThat(personal.urlProblem("https://" + INTERNAL + "/mcp")).contains("resolves to a private address");
        CallResult<OutboundHttp.Response> sent = personal.send("https://" + INTERNAL,
                personal.request("https://" + INTERNAL + "/mcp").GET().build());
        assertThat(sent.failure()).isNotNull();
        assertThat(sent.failureMessage()).startsWith("The request was not sent").contains("private address");
    }

    @Test
    void theListTakesHostNamesOnly() {
        assertThat(new OutboundProperties(false, false, Duration.ofSeconds(5), Duration.ofSeconds(10), Set.of(),
                Set.of(" Mcp-Server ", "", "mcp-server.default.svc.cluster.local.")).internalHosts())
            .containsExactlyInAnyOrder("mcp-server", "mcp-server.default.svc.cluster.local");
        for (String entry : new String[] { "http://mcp-server", "mcp-server:8080", "*.svc.cluster.local",
                "mcp-server/mcp", "user@mcp-server" }) {
            assertThatThrownBy(() -> new OutboundProperties(false, false, Duration.ofSeconds(5),
                    Duration.ofSeconds(10), Set.of(), Set.of(entry)))
                .as(entry)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TINFIRM_CONNECTION_INTERNAL_HOSTS");
        }
    }

    @Test
    void theServerStartsWithTheListOutsideTheTestProfile() {
        new ApplicationContextRunner().withUserConfiguration(Outbound.class)
            .withPropertyValues("tinfirm.connection.internal-hosts=mcp-server, " + INTERNAL)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(OutboundProperties.class).internalHosts())
                    .containsExactlyInAnyOrder("mcp-server", INTERNAL);
                assertThat(context.getBean(OutboundHttp.class).urlProblem("http://" + INTERNAL + ":8080/mcp"))
                    .isNull();
            });
    }

    @Test
    void aSystemsViewNamesItsBaseUrlsHostInternalAsTheClientDecidesIt() {
        OutboundProperties properties = new OutboundProperties(false, false, Duration.ofSeconds(5),
                Duration.ofSeconds(10), Set.of(), Set.of(INTERNAL, "Mcp-Server."));

        assertThat(properties.internalHost("http://" + INTERNAL + ":8080/mcp")).isTrue();
        assertThat(properties.internalHost("https://mcp-server/mcp")).isTrue();
        assertThat(properties.internalHost("https://MCP-SERVER./mcp")).isTrue();
        assertThat(properties.internalHost("https://10.20.30.41/mcp")).isFalse();
        assertThat(properties.internalHost("https://mcp.vendor.example/mcp")).isFalse();
        assertThat(properties.internalHost("not a url")).isFalse();
        assertThat(properties.internalHost(null)).isFalse();
    }
}
