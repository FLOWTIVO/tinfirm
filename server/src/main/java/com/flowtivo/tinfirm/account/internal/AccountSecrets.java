package com.flowtivo.tinfirm.account.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.vault.RevealedSecret;
import com.flowtivo.tinfirm.vault.SecretWriter;
import com.flowtivo.tinfirm.vault.VaultService;

/**
 * Where an account keeps its credential (the package 1 contract, §3.6 and I3; ADR-0072): a member's own account in a
 * secret the member owns, which the Secrets API never lists, reveals or changes, and which is revealed only for a call
 * that acts as that member; a credential the system holds in a company secret it manages ({@code SYSTEM_CREDENTIAL},
 * or {@code SERVICE_ACCOUNT} for one that was a service identity's account before V049, listed as managed). The value
 * is the token, or the OAuth grant's JSON.
 */
@Component
class AccountSecrets {

    /** The name of a credential's secret, which the vault lists as managed. */
    static final String CREDENTIAL_SECRET_NAME = "SYSTEM_CREDENTIAL";

    /** The name of a member's own account secret, which nobody sees but the audit log. */
    static final String MEMBER_SECRET_NAME = "MEMBER_ACCOUNT";

    private final SecretWriter secrets;

    private final VaultService vault;

    AccountSecrets(SecretWriter secrets, VaultService vault) {
        this.secrets = secrets;
        this.vault = vault;
    }

    /**
     * Stores the value as a new version of the account's secret, or as a new secret when it has none.
     *
     * @param member the member whose own account it is; null for a credential the system holds
     * @param label  what the new secret's description names: the system's handle, and a credential's name
     */
    UUID store(UUID companyId, @Nullable PrincipalInfo member, @Nullable UUID secretId, String label, String value) {
        UUID owner = member != null ? member.userId() : null;
        if (secretId != null) {
            boolean replaced = owner != null ? secrets.replaceOwned(companyId, owner, secretId, value)
                    : secrets.replace(companyId, secretId, value);
            if (replaced) {
                return secretId;
            }
        }
        if (owner != null) {
            return secrets.createOwned(companyId, owner, MEMBER_SECRET_NAME, "A member's own account on the system "
                    + label, value);
        }
        return secrets.create(companyId, CREDENTIAL_SECRET_NAME, "The credential " + label, value);
    }

    void delete(UUID companyId, @Nullable PrincipalInfo member, @Nullable UUID secretId) {
        if (secretId == null) {
            return;
        }
        UUID owner = member != null ? member.userId() : null;
        if (owner != null) {
            secrets.deleteOwned(companyId, owner, secretId);
        }
        else {
            secrets.delete(companyId, secretId);
        }
    }

    /**
     * Reveals the account's secret, audited as {@code secret.accessed} with the purpose: a member's for that member
     * (the vault refuses any other), a credential as the company's.
     */
    RevealedSecret reveal(UUID companyId, @Nullable PrincipalInfo member, UUID secretId, String purpose) {
        UUID owner = member != null ? member.userId() : null;
        return owner != null ? vault.revealOwned(companyId, secretId, owner, purpose)
                : vault.reveal(companyId, secretId, purpose);
    }
}
