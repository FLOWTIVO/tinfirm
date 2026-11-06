package com.flowtivo.tinfirm.identity;

import java.util.Set;
import java.util.UUID;

/**
 * The identity module's events about a user's standing at the instance (the package 6 contract, §5.4) and their
 * sign-ins (ADR-0075). Ids only. Published in the transaction of the change or the login, after its audit event.
 */
public final class UserEvents {

    private UserEvents() {
    }

    /** An instance admin deactivated the user: their principal is suspended in every company they are a member of. */
    public record UserDeactivated(UUID userId) {
    }

    /** An instance admin reactivated the user: the suspensions the deactivation made are lifted, and only those. */
    public record UserReactivated(UUID userId) {
    }

    /**
     * An instance admin set the user's password (the owner's decision Q7 of 2026-10-27): the company module records
     * {@code member.password_reset_by_instance_admin} in the chain of every company the user belongs to, naming the
     * instance admin, in the transaction of the reset.
     */
    public record PasswordSetByInstanceAdmin(UUID userId, UUID instanceAdminId) {
    }

    /**
     * The user signed in, in the transaction of the login. A listener after the commit
     * ({@code @TransactionalEventListener}) makes them the OWNER of a declared company that has none and names their
     * email as its owner (ADR-0075), but only for a local login, whose account an instance admin made, or a login
     * through a provider of that company: another company's provider must not claim it by asserting the email.
     *
     * @param local              a local login, with a password
     * @param providerCompanyIds the companies a login's provider fills (its own, and those its role claim fills,
     *                           ADR-0089), when that login asserted the user's email; empty otherwise
     */
    public record UserSignedIn(UUID userId, boolean local, Set<UUID> providerCompanyIds) {

        public UserSignedIn {
            providerCompanyIds = Set.copyOf(providerCompanyIds);
        }
    }
}
