package com.flowtivo.tinfirm.account;

import java.util.UUID;

/**
 * A grant as other modules see it (the package 1 contract, §3.7; ADR-0072): a credential of a system lent to a group.
 *
 * @param accountId the credential it lends
 */
public record GrantInfo(UUID id, UUID companyId, UUID accountId, UUID systemId, UUID groupId, Access access) {

    /** What a grant lets its group do with the credential; a person's own account is always {@code WRITE}. */
    public enum Access {
        READ, WRITE
    }
}
