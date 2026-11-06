package com.flowtivo.tinfirm.shared.security;

import java.util.Optional;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.flowtivo.tinfirm.shared.web.ApiException;

/** Access to the caller of the current request (or the system, inside {@link SystemContext}). */
public final class CurrentActor {

    private CurrentActor() {
    }

    /** The logged-in user, if the current request has one. */
    public static Optional<AuthenticatedUser> user() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ActorAuthentication actorAuthentication) {
            return Optional.of(actorAuthentication.getPrincipal());
        }
        return Optional.empty();
    }

    /** The logged-in user; 401 if there is none. */
    public static AuthenticatedUser requireUser() {
        return user().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Authentication required"));
    }

    /** The logged-in user, who must be an instance admin; 403 otherwise. */
    public static AuthenticatedUser requireInstanceAdmin() {
        AuthenticatedUser user = requireUser();
        if (!user.instanceAdmin()) {
            throw ApiException.forbidden("Instance admin only");
        }
        return user;
    }

    /** The actor to record in the audit log for work done by the current request or system task. */
    public static Actor actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ActorAuthentication actorAuthentication) {
            return Actor.user(actorAuthentication.getPrincipal().id());
        }
        if (authentication instanceof SystemAuthentication) {
            return Actor.SYSTEM;
        }
        if (authentication instanceof ScimAuthentication scim) {
            return Actor.scim(scim.tokenId());
        }
        throw new IllegalStateException("No actor: neither a logged-in user, a system context nor a SCIM token");
    }
}
