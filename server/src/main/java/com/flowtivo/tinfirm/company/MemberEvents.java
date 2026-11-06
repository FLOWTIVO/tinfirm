package com.flowtivo.tinfirm.company;

import java.util.UUID;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * The company module's events about members' roles, for the packages that react to them (ADR-0077, decision 5: who
 * decides a request depends on whether its candidates are Viewers). Ids only. Published in the transaction that made
 * the change, after its audit event: a listener reacts inside it with {@code @EventListener}.
 */
public final class MemberEvents {

    private MemberEvents() {
    }

    /** A member's role changed from {@code previous} to {@code role}. */
    public record MemberRoleChanged(UUID companyId, UUID userId, CompanyRole previous, CompanyRole role) {
    }
}
