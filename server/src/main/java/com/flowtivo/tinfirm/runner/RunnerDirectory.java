package com.flowtivo.tinfirm.runner;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runner hosts for other modules. Runs in the caller's transaction and tenant context: row-level security limits every
 * lookup to the company of that context.
 */
public interface RunnerDirectory {

    /** Every runner host of the company, company and personal ones, by name (bounded by the hosts a company registers). */
    List<RunnerHostInfo> hosts(UUID companyId);

    /** One runner host of the company. */
    Optional<RunnerHostInfo> host(UUID companyId, UUID runnerHostId);

    /**
     * The credentials the given personal hosts reported (the package 2 contract, §2), still reported or not, by host
     * and label. A company host has none.
     */
    List<HostCredentialInfo> credentials(UUID companyId, Collection<UUID> runnerHostIds);

    /**
     * Locks the given runner hosts in the order of their ids, so that concurrent dispatchers count their free slots
     * one at a time without deadlocking, and loads them as they are now. Unknown ids are absent.
     */
    List<RunnerHostInfo> lockHostsForDispatch(Collection<UUID> hostIds);
}
