package com.flowtivo.tinfirm.system;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Systems for other modules (the package 1 contract, §3.3 to §3.5). Runs in the caller's transaction and tenant
 * context: row-level security limits every lookup to the company of that context, and a system of another company is
 * absent. Whether the caller may see a personal server is the caller's rule (its owner only).
 */
public interface Systems {

    Optional<SystemInfo> find(UUID companyId, UUID systemId);

    /**
     * The system, with its row locked {@code FOR SHARE} until the caller's transaction (a mandatory one) ends: no
     * change of the row meanwhile, and no move of an MCP server to another endpoint (ADR-0079), which locks the row
     * {@code FOR UPDATE} and removes every account and credential on it. For a module that stores a credential it had
     * checked at the system's endpoint before the transaction, and compares the row's {@code baseUrl} with the one it
     * checked at: the system's row comes before the account rows it then writes (CONVENTIONS §10, the lock order).
     * Empty when the system is gone.
     */
    Optional<SystemInfo> lockShared(UUID companyId, UUID systemId);

    /** The systems among {@code systemIds}; unknown ids are absent. */
    Map<UUID, SystemInfo> findAll(UUID companyId, Collection<UUID> systemIds);

    /** The company's systems, and the personal servers of the member's principal when one is named; by name. */
    List<SystemInfo> visibleTo(UUID companyId, @Nullable UUID memberPrincipalId);

    /**
     * Whether members may use and register personal MCP servers (contract §10): on by default. While off, a personal
     * server binds for nobody.
     */
    boolean personalSystemsOn(UUID companyId);

    /** The tools the system serves: its fixed catalogue, or its MCP catalogue as the last refresh found it. */
    List<ToolInfo> tools(UUID companyId, UUID systemId);

    /** The repositories of a code host, by name. */
    List<RepositoryInfo> repositories(UUID companyId, UUID systemId);

    /** A repository of any code host of the company, by its id. */
    Optional<RepositoryInfo> repository(UUID companyId, UUID repositoryId);

    /**
     * Forgets the principal as the tester of the systems' last tests, and the account of theirs a test used, for the
     * erasure of a member who left (the package 6 contract, §5.9): no system's page names them any more; when the tests
     * ran and how they ended stay. In the caller's transaction, which must lock these systems' rows before any
     * conversation, as a pause does (CONVENTIONS §10). Answers how many tests named them.
     */
    int forgetTester(UUID companyId, UUID principalId);

    /** What {@link #forgetTester} would forget now, counted, for the erasure's dry run; changes nothing. */
    int countTester(UUID companyId, UUID principalId);
}
