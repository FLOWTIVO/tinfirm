package com.flowtivo.tinfirm.shared.access;

/**
 * A member's role in one company (the platform redesign, Part 8; ADR-0077): OWNER (everything an admin may, and the
 * company's settings), ADMIN (systems and their apps, service identities and who may use them, grants, groups, company
 * runner hosts), MEMBER (personal MCP servers, their own accounts, agents, triggers and conversations) or VIEWER (reads
 * what the company shows its members and their own inbox; starts or continues no work and decides nothing, and holds
 * no seat).
 */
public enum CompanyRole {
    OWNER, ADMIN, MEMBER, VIEWER
}
