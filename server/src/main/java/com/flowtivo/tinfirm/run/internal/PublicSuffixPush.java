package com.flowtivo.tinfirm.run.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.PublicSuffixList;
import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The Public Suffix List for runner hosts (ADR-0044, protocol v2's {@code psl.update}): the list the server holds, the
 * bundled copy ({@link PublicSuffixList}), goes to a v2 host after its {@code hello} when the host holds none or
 * another version ({@code hello.publicSuffixList.sha256}), so that the host refuses the same network wildcards as the
 * server. The server reads no other list, so it never changes while the server runs and no other push exists.
 * <p>
 * The frame is built once: the gzip of the list's bytes in base64, its SHA-256 and number of rules, and as
 * {@code fetchedAt} the start of this server, when it took the bundled copy up. A host keeps a list whose
 * {@code fetchedAt} is later than the one of an update; a server's start is later than any list an older server sent,
 * so a host takes the list of the server it talks to.
 */
@Component
class PublicSuffixPush {

    private final PublicSuffixList list = PublicSuffixList.bundled();

    private final RunnerMessages.PslUpdate frame;

    PublicSuffixPush(TimeSource time) {
        this.frame = new RunnerMessages.PslUpdate(list.sha256(), time.now().toString(), list.ruleCount(),
                Base64.getEncoder().encodeToString(gzip(list.bytes())));
    }

    /** The {@code psl.update} for a host that said {@code hello} with this version; empty when it needs none. */
    Optional<RunnerMessages.PslUpdate> forHost(ConnectedHost host, @Nullable String publicSuffixSha256) {
        if (!host.v2() || list.sha256().equals(publicSuffixSha256)) {
            return Optional.empty();
        }
        return Optional.of(frame);
    }

    static byte[] gzip(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 3);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }
}
