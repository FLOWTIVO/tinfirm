package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.agent.PublicSuffixList;
import com.flowtivo.tinfirm.support.FakeRunner;

import tools.jackson.databind.JsonNode;

/**
 * The Public Suffix List pushed to runner hosts (protocol v2's {@code psl.update}, ADR-0044): a v2 host that holds no
 * list, or another version, gets the server's, the bundled one, right after {@code hello}; one that holds it gets
 * nothing, and so does a v1 host.
 */
class PublicSuffixPushIntegrationTest extends RunTestBench {

    @Test
    void aV2HostWithoutTheServersListGetsItAfterHelloAndOneWithItGetsNothing() throws IOException {
        Bench bench = bench(false);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        PublicSuffixList list = PublicSuffixList.bundled();

        try (FakeRunner none = connect(host, hello(2, null))) {
            JsonNode update = none.expect("psl.update");
            assertThat(update.get("sha256").asString()).isEqualTo(list.sha256());
            assertThat(update.get("ruleCount").asInt()).isEqualTo(list.ruleCount()).isGreaterThan(1000);
            assertThat(Instant.parse(update.get("fetchedAt").asString())).isBefore(Instant.now());
            byte[] gzip = Base64.getDecoder().decode(update.get("content").asString());
            byte[] bytes = new GZIPInputStream(new ByteArrayInputStream(gzip)).readAllBytes();
            assertThat(sha256(bytes)).isEqualTo(list.sha256());
            assertThat(bytes).isEqualTo(list.bytes());
            assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).contains("\ncom\n", "github.io");
            none.assertFramesValid();
        }
        try (FakeRunner other = connect(host, hello(2, "0".repeat(64)))) {
            assertThat(other.expect("psl.update").get("sha256").asString()).isEqualTo(list.sha256());
        }
        try (FakeRunner same = connect(host, hello(2, list.sha256()))) {
            same.expectNone("psl.update", Duration.ofSeconds(1));
        }
        try (FakeRunner v1 = connect(host, hello(1, null))) {
            v1.expectNone("psl.update", Duration.ofSeconds(1));
        }
    }

    private FakeRunner connect(RunnerHostRef host, Map<String, Object> hello) {
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.send(hello);
        runner.expect("welcome");
        return runner;
    }

    private static Map<String, Object> hello(int protocolVersion, @Nullable String sha256) {
        Map<String, Object> hello = new LinkedHashMap<>(protocolVersion == 2
                ? FakeRunner.helloV2(List.of(), List.of(), RUNNER_VERSION, 1) : FakeRunner.hello(List.of(), List.of()));
        if (sha256 != null) {
            hello.put("publicSuffixList", Map.of("sha256", sha256));
        }
        return hello;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
