package com.flowtivo.tinfirm.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version of the server under test, as {@code GET /api/system/info} and the error reports name it: Maven's
 * {@code project.version}, which the surefire configuration passes as {@code tinfirm.test.version}; outside Maven, the
 * version {@code pom.xml} gives {@code tinfirm-server}. The release workflow changes it with every release, so a test
 * compares with this, never with a literal.
 */
public final class BuildVersion {

    private static final Pattern POM_VERSION = Pattern
        .compile("<artifactId>tinfirm-server</artifactId>\\s*<version>([^<]+)</version>");

    private BuildVersion() {
    }

    public static String expected() {
        String configured = System.getProperty("tinfirm.test.version");
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        try {
            Matcher version = POM_VERSION.matcher(Files.readString(Path.of("pom.xml")));
            if (version.find()) {
                return version.group(1).strip();
            }
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Without tinfirm.test.version, the version is read from pom.xml", ex);
        }
        throw new IllegalStateException("Without tinfirm.test.version, pom.xml names no version of tinfirm-server");
    }
}
