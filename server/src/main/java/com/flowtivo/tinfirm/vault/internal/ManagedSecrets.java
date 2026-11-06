package com.flowtivo.tinfirm.vault.internal;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The secrets another part of Tinfirm writes and keeps (the package 1 contract, §3.3 and §3.6): the token or OAuth
 * grant of a service identity's account on a system, which the account manages, and a company system's OAuth client
 * secret. The Secrets page lists them as managed; their value is never replaced by hand (a pasted value would bypass
 * the check of a token, or break a grant), and while the account or the app keeps one it is not deleted there either
 * ({@link com.flowtivo.tinfirm.vault.SecretKeeper}, {@code 409 SECRET_MANAGED}). Known by name, as the account and
 * system modules name them; the vault suffixes a second one ({@code _2}, {@code _3}). The vault knows only names, so
 * what maintains a secret is derived from the name's prefix. The secrets a member owns (their own accounts, a personal
 * system's client secret) are not listed at all.
 */
final class ManagedSecrets {

    /** A managed secret's name and what maintains it, as the Secrets page shows it. */
    private record Kind(String name, String managedBy) {
    }

    private static final List<Kind> KINDS = List.of(
            // A system's credential (ADR-0072); one that was a service identity's account before V049 keeps its name.
            new Kind("SYSTEM_CREDENTIAL", "a system's credential"),
            new Kind("SERVICE_ACCOUNT", "a system's credential"),
            new Kind("SYSTEM_OAUTH_CLIENT_SECRET", "a system's OAuth client"),
            // A Slack system's own secrets (the package 5 contract, §3.1): written on the system's page, never here.
            new Kind("SLACK_SIGNING_SECRET", "a chat system's"),
            new Kind("SLACK_APP_TOKEN", "a chat system's"),
            // A webhook system's generated secret, rotated on the system's page (the package 5 contract, §3.1), named
            // WEBHOOK_SECRET_{HANDLE} from package 5b's review: matched by the prefix.
            new Kind("WEBHOOK_SECRET", "a webhook system's"));

    /** The names the account and system modules give the secrets they keep. */
    static final List<String> NAMES = KINDS.stream().map(Kind::name).toList();

    private ManagedSecrets() {
    }

    /** Whether the secret's name is one of {@link #NAMES}, or one of them followed by {@code _} and a suffix. */
    static boolean managed(String name) {
        return kind(name) != null;
    }

    /**
     * What maintains the secret named {@code name} ({@code a service identity's account on a system} or {@code a
     * system's OAuth client}); null for a secret that is not managed.
     */
    static @Nullable String managedBy(String name) {
        Kind kind = kind(name);
        return kind != null ? kind.managedBy() : null;
    }

    private static @Nullable Kind kind(String name) {
        for (Kind kind : KINDS) {
            if (name.equals(kind.name()) || name.startsWith(kind.name() + "_")) {
                return kind;
            }
        }
        return null;
    }
}
