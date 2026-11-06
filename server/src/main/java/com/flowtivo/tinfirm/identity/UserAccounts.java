package com.flowtivo.tinfirm.identity;

/**
 * User provisioning for trusted callers (bootstrap). Callers are responsible for authorisation; the admin HTTP
 * endpoints check for an instance admin themselves. Audited with the current actor.
 */
public interface UserAccounts {

    boolean anyUserExists();

    /**
     * Creates a local user.
     * @throws com.flowtivo.tinfirm.shared.web.ApiException with code DUPLICATE_EMAIL if the email is taken
     */
    UserSummary createLocalUser(NewLocalUser user);

    /** A local user to create. {@link #toString()} never shows the password. */
    record NewLocalUser(String email, String displayName, String password, boolean instanceAdmin) {

        @Override
        public String toString() {
            return "NewLocalUser[email=" + email + ", instanceAdmin=" + instanceAdmin + ", password=***]";
        }
    }
}
