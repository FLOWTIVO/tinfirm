package com.flowtivo.tinfirm.principal;

/**
 * A principal's status (the package 1 contract, §3.1): {@code ACTIVE}; {@code SUSPENDED}, an admin stopped it,
 * reversible; {@code LEFT}, the member left the company, final. A principal that is not {@code ACTIVE} acts for nothing
 * (I7).
 */
public enum PrincipalStatus {
    ACTIVE, SUSPENDED, LEFT
}
