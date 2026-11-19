package com.flowtivo.tinfirm.system;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The GitHub App of a {@code GITHUB} system (ADR-0116, decision 6), for the account module, whose credentials of kind
 * {@code APP} are its installations. The App's private key is revealed here only, to sign the App's JWT (RS256) for one
 * request, audited as {@code secret.accessed} with the caller's purpose, and is never returned. The calls to GitHub go
 * to the system's API base URL ({@code https://api.github.com}, or a GitHub Enterprise Server's
 * {@code https://{host}/api/v3}) through the module's outbound client, outside any transaction.
 */
public interface GitHubApps {

    /** Every token Tinfirm mints is without this permission (ADR-0116, decision 7; the threat model's Q11). */
    String WORKFLOWS = "workflows";

    /** The system's App, in the caller's transaction; empty without one. */
    Optional<App> app(UUID companyId, UUID systemId);

    /**
     * The system's App, locked {@code FOR SHARE} until the caller's transaction ends, so that no other App or removal
     * commits before it (ADR-0116); empty without one.
     */
    Optional<App> lockApp(UUID companyId, UUID systemId);

    /** An installation of the system's App, as GitHub answers it; fails {@code NOT_FOUND} without an App. */
    CallResult<Installation> installation(SystemInfo system, long installationId);

    /**
     * An installation of the App {@code expected}, as GitHub answers it, asked with that App's key only: fails
     * {@code OTHER} when the system's App is another one now ({@link App#sameApp}), and {@code NOT_FOUND} without
     * an App. So that what is verified is what the caller stores under the App it read.
     */
    CallResult<Installation> installation(SystemInfo system, App expected, long installationId);

    /**
     * Mints an installation access token at GitHub ({@code POST /app/installations/{id}/access_tokens}).
     *
     * @param repositories the repositories' names on the installation's account, without the owner; null for every
     *                     repository the installation covers
     * @param permissions  by GitHub's name ({@code contents}, {@code pull_requests}), {@code read} or {@code write};
     *                     never {@link #WORKFLOWS}, which is refused here
     * @param purpose      the reveal's purpose of the App's private key: {@code run:{runId}}, {@code account:{id}}
     */
    CallResult<InstallationToken> mint(SystemInfo system, long installationId, @Nullable List<String> repositories,
            Map<String, String> permissions, String purpose);

    /**
     * A system's App, without its key.
     *
     * @param keyId the vault secret that holds its key: replacing the key of the same App keeps it, removing the App
     *              and setting one again makes another
     */
    record App(UUID systemId, long appId, @Nullable String slug, @Nullable String name, Instant setAt, UUID keyId) {

        /** Whether the other is this App with this key's secret: the same id, never removed and set again since. */
        public boolean sameApp(App other) {
            return systemId.equals(other.systemId) && appId == other.appId && keyId.equals(other.keyId);
        }
    }

    /**
     * An installation of the App.
     *
     * @param accountLogin        the login of the user or organisation it is installed on
     * @param accountType         {@code Organization} or {@code User}
     * @param repositorySelection {@code all} or {@code selected}
     * @param permissions         the permissions the installation grants the App, by GitHub's names
     */
    record Installation(long id, String accountLogin, String accountType, String repositorySelection,
            Map<String, String> permissions) {

        public Installation {
            permissions = Map.copyOf(permissions);
        }
    }

    /** A token GitHub minted, valid until {@code expiresAt} (an hour). Never log it. */
    record InstallationToken(String token, Instant expiresAt, Map<String, String> permissions) {

        public InstallationToken {
            permissions = Map.copyOf(permissions);
        }

        @Override
        public String toString() {
            return "InstallationToken[token=***, expiresAt=" + expiresAt + ", permissions=" + permissions + "]";
        }
    }
}
