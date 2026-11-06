package com.flowtivo.tinfirm.vault;

import java.util.UUID;

/**
 * Secrets that another module writes for a member who typed a value into its own form (a system's OAuth client
 * secret), rather than on the Secrets page. A trusted internal API: the caller decides that the
 * current user may do this, in its own transaction and the company's tenant context. The value is encrypted as every
 * secret is (CONVENTIONS §9), and each write is audited as on the Secrets page ({@code secret.created},
 * {@code secret.updated}, {@code secret.deleted}) with the current actor. It also writes the secrets a person owns
 * (the package 1 contract, I3: the token of a member's own account on a system), which only these owned operations and
 * {@link VaultService#revealOwned} reach.
 */
public interface SecretWriter {

    /**
     * Stores a new secret of the company under {@code name}, or under {@code name_2}, {@code name_3}, ... when the
     * company already has a secret of that name. The current actor must be a user.
     *
     * @param name a secret name ({@code ^[A-Za-z_][A-Za-z0-9_]{0,63}$}, at most 60 characters so a suffix fits)
     * @return the new secret's id
     */
    UUID create(UUID companyId, String name, String description, String value);

    /**
     * Stores a new version of a secret of the company.
     *
     * @return false when the company has no such secret (deleted on the Secrets page), or a person owns it
     */
    boolean replace(UUID companyId, UUID secretId, String value);

    /** Deletes a secret of the company with its versions; nothing when there is none, or a person owns it. */
    void delete(UUID companyId, UUID secretId);

    // ---- Secrets a person owns (ADR-0049 §5) --------------------------------------------------------------------

    /**
     * Stores a new secret of the company that {@code ownerUserId} owns: the token of the person's own account on a
     * connection. It is never listed, changed or deleted through the Secrets API (its id is 404 there), and it is
     * revealed only for its owner ({@link VaultService#revealOwned}). The current actor may be anyone (the person, or
     * the system refreshing an OAuth grant); the secret's creator is its owner. Named as {@link #create} names a
     * secret, unique in the company; audited as {@code secret.created} with {@code userId}.
     *
     * @param name a secret name ({@code ^[A-Za-z_][A-Za-z0-9_]{0,63}$}, at most 60 characters so a suffix fits),
     *             which nobody sees but the audit log
     * @return the new secret's id
     */
    UUID createOwned(UUID companyId, UUID ownerUserId, String name, String description, String value);

    /**
     * Stores a new version of a secret the person owns (a refreshed OAuth grant); audited as {@code secret.updated}
     * with {@code userId}.
     *
     * @return false when the company has no such secret of that owner
     */
    boolean replaceOwned(UUID companyId, UUID ownerUserId, UUID secretId, String value);

    /**
     * Deletes a secret the person owns with its versions (the account is disconnected or goes); nothing when there is
     * none of that owner. Audited as {@code secret.deleted} with {@code userId}.
     */
    void deleteOwned(UUID companyId, UUID ownerUserId, UUID secretId);

    /**
     * Deletes every secret the person owns in the company with its versions (they leave it, the package 1 contract's
     * I8), each audited as {@code secret.deleted} with {@code userId}.
     */
    void deleteAllOwned(UUID companyId, UUID ownerUserId);

    // ---- Secrets Tinfirm keeps for itself (the package 2 contract, §3.2) ------------------------------------------

    /**
     * Stores a new internal secret of the company: a value Tinfirm keeps for itself and never shows (a principal's
     * LiteLLM key). It is never listed, changed or deleted through the Secrets API (its id is 404 there), no
     * configuration can name it ({@link SecretCatalog}), and only the caller's module reveals it
     * ({@link VaultService#reveal}) and deletes it ({@link #deleteInternal}). The current actor may be the system.
     * Named as {@link #create} names a secret; audited as {@code secret.created}.
     *
     * @return the new secret's id
     */
    UUID createInternal(UUID companyId, String name, String description, String value);

    /**
     * Deletes an internal secret with its versions; nothing when the company has no such internal secret. Audited as
     * {@code secret.deleted}.
     */
    void deleteInternal(UUID companyId, UUID secretId);
}
