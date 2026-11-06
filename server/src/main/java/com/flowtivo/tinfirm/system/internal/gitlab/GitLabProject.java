package com.flowtivo.tinfirm.system.internal.gitlab;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Pattern;

import com.flowtivo.tinfirm.system.internal.http.Json;

/**
 * A project on a GitLab instance, by its full path {@code group/subgroup/project} with up to ten segments (ADR-0023):
 * a subject's key's part, or the path of a repository's clone URL.
 */
public record GitLabProject(String path) {

    private static final int MAX_LENGTH = 140;

    private static final int MAX_SEGMENTS = 10;

    /** A GitLab path segment: letters, digits, '_', '-' and '.', not starting with '-'. */
    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.-]*$");

    /** A path of two to ten valid segments, at most 140 characters. */
    public static Optional<GitLabProject> parse(String value) {
        String path = value.strip();
        if (path.isEmpty() || path.length() > MAX_LENGTH || !path.contains("/")) {
            return Optional.empty();
        }
        String[] segments = path.split("/", -1);
        if (segments.length > MAX_SEGMENTS) {
            return Optional.empty();
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
                return Optional.empty();
            }
        }
        return Optional.of(new GitLabProject(path));
    }

    /** The project of an HTTPS clone URL {@code https://{host}/{namespace}/{project}(.git)}. */
    public static Optional<GitLabProject> fromUrl(String url) {
        try {
            String path = new URI(url.strip()).getPath();
            if (path == null) {
                return Optional.empty();
            }
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - ".git".length());
            }
            return parse(path);
        }
        catch (URISyntaxException ex) {
            return Optional.empty();
        }
    }

    /** The project id in API paths: the URL-encoded path, {@code group%2Fsubgroup%2Fproject}. */
    public String id() {
        return Json.encode(path);
    }

    @Override
    public String toString() {
        return path;
    }
}
