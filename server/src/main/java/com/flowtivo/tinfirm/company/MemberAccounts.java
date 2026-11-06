package com.flowtivo.tinfirm.company;

import java.util.List;
import java.util.UUID;

/**
 * Port: a member's accounts as the company's admins see them on the Members page (the package 1 contract, I4: whether
 * a member holds an account on each system and its status, never more), implemented by the account module, which keeps
 * accounts; the company module does not depend on it. Runs in the caller's transaction and tenant context.
 */
public interface MemberAccounts {

    /**
     * A member's account on a company system.
     *
     * @param systemKind the system's kind, as {@code SystemKind} names it (the company module stands below the system
     *                   module)
     * @param status     {@code OK}, {@code RECONNECT} or {@code ERROR}
     */
    record Held(UUID systemId, String systemName, String systemKind, String status) {
    }

    /**
     * The member's accounts on the company's systems, by system name: never one on a personal MCP server, which only
     * its owner sees, and nothing of who the account is on the system. Empty for a user who is not a member.
     */
    List<Held> accountsOf(UUID companyId, UUID userId);
}
