package com.flowtivo.tinfirm.shared.security;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The actor of an audited action. Only type and id: personal data such as names is resolved at read time.
 *
 * @param type the kind of actor
 * @param id   the user, agent or SCIM token id; null for {@link ActorType#SYSTEM} and for anonymous callers (a failed login)
 */
public record Actor(ActorType type, @Nullable UUID id) {

    public static final Actor SYSTEM = new Actor(ActorType.SYSTEM, null);

    /** An unauthenticated caller, e.g. a failed login for an unknown email. */
    public static final Actor ANONYMOUS = new Actor(ActorType.USER, null);

    public static Actor user(UUID userId) {
        return new Actor(ActorType.USER, userId);
    }

    /** An agent acting in a run, e.g. requesting a repository (CONVENTIONS §8). */
    public static Actor agent(UUID agentId) {
        return new Actor(ActorType.AGENT, agentId);
    }

    /** A company's SCIM token, for what the identity provider changes through it (ADR-0087, decision 3). */
    public static Actor scim(UUID tokenId) {
        return new Actor(ActorType.SCIM, tokenId);
    }
}
