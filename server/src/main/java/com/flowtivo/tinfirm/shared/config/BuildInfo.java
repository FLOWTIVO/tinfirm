package com.flowtivo.tinfirm.shared.config;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

/** The version and commit of this build, as {@code GET /api/system/info} and the error reports (ADR-0070) name it. */
@Component
public class BuildInfo {

    private static final Pattern FULL_REVISION = Pattern.compile("[0-9a-f]{40}");

    private final String version;

    private final @Nullable String commit;

    public BuildInfo(ObjectProvider<BuildProperties> buildProperties, ObjectProvider<GitProperties> gitProperties,
            @Value("${tinfirm.revision:}") String revision) {
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null && build.getVersion() != null ? build.getVersion() : "unknown";
        this.commit = commitOf(revision, gitProperties.getIfAvailable());
    }

    /** The version of the server's build, {@code unknown} without build information. */
    public String version() {
        return version;
    }

    /** The git revision of the build, null when unknown. */
    public @Nullable String commit() {
        return commit;
    }

    /**
     * The full revision from {@code TINFIRM_REVISION}, which the image sets from its build argument; runner hosts
     * on auto-deploy compare it with a release's REVISION. Without it, the short id from {@code git.properties}.
     */
    public static @Nullable String commitOf(@Nullable String revision, @Nullable GitProperties git) {
        if (revision != null && FULL_REVISION.matcher(revision).matches()) {
            return revision;
        }
        return git != null ? git.getShortCommitId() : null;
    }
}
