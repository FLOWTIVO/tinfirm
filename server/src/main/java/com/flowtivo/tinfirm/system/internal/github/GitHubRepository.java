package com.flowtivo.tinfirm.system.internal.github;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A repository on GitHub, {@code owner/repo}: a subject's key's part, or the path of a repository's clone URL. */
public record GitHubRepository(String owner, String name) {

    /** An owner (a user or an organisation) and a repository name. */
    private static final Pattern OWNER_REPO = Pattern
        .compile("^([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)/([A-Za-z0-9._-]+)$");

    private static final int MAX_LENGTH = 140;

    /** {@code owner/repo}, at most 140 characters; the name is neither {@code .} nor {@code ..}. */
    public static Optional<GitHubRepository> parse(String path) {
        String repository = path.strip();
        Matcher matcher = OWNER_REPO.matcher(repository);
        if (repository.length() > MAX_LENGTH || !matcher.matches() || matcher.group(2).equals(".")
                || matcher.group(2).equals("..")) {
            return Optional.empty();
        }
        return Optional.of(new GitHubRepository(matcher.group(1), matcher.group(2)));
    }

    /** The repository of an HTTPS clone URL {@code https://{host}/{owner}/{repo}(.git)}, whatever the host. */
    public static Optional<GitHubRepository> fromUrl(String url) {
        try {
            String path = new URI(url.strip()).getPath();
            if (path == null) {
                return Optional.empty();
            }
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - ".git".length());
            }
            return parse(path.startsWith("/") ? path.substring(1) : path);
        }
        catch (URISyntaxException ex) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return owner + "/" + name;
    }
}
