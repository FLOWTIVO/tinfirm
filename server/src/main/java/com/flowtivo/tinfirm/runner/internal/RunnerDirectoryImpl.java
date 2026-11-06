package com.flowtivo.tinfirm.runner.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.runner.HostCredentialInfo;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;

@Component
class RunnerDirectoryImpl implements RunnerDirectory {

    private final RunnerHostStore store;

    RunnerDirectoryImpl(RunnerHostStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunnerHostInfo> hosts(UUID companyId) {
        return store.hostInfos(companyId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunnerHostInfo> host(UUID companyId, UUID runnerHostId) {
        return store.hostInfo(companyId, runnerHostId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HostCredentialInfo> credentials(UUID companyId, Collection<UUID> runnerHostIds) {
        return store.credentials(companyId, runnerHostIds);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RunnerHostInfo> lockHostsForDispatch(Collection<UUID> hostIds) {
        return store.lockHostInfos(hostIds);
    }
}
