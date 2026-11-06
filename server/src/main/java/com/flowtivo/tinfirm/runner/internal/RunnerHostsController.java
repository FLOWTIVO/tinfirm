package com.flowtivo.tinfirm.runner.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.RunnerHostsApi;
import com.flowtivo.tinfirm.shared.api.model.RunnerHost;
import com.flowtivo.tinfirm.shared.api.model.RunnerHostCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.RunnerHostPage;
import com.flowtivo.tinfirm.shared.api.model.RunnerHostRegistration;
import com.flowtivo.tinfirm.shared.api.model.RunnerHostUpdateRequest;

@RestController
class RunnerHostsController implements RunnerHostsApi {

    private final RunnerHostService hosts;

    RunnerHostsController(RunnerHostService hosts) {
        this.hosts = hosts;
    }

    @Override
    public ResponseEntity<RunnerHostPage> listRunnerHosts(UUID companyId, Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(hosts.list(companyId, limit, cursor));
    }

    @Override
    public ResponseEntity<RunnerHostRegistration> registerRunnerHost(UUID companyId,
            RunnerHostCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(hosts.register(companyId, request));
    }

    @Override
    public ResponseEntity<RunnerHost> updateRunnerHost(UUID companyId, UUID runnerHostId,
            RunnerHostUpdateRequest request) {
        return ResponseEntity.ok(hosts.updateHost(companyId, runnerHostId, request));
    }

    @Override
    public ResponseEntity<Void> deleteRunnerHost(UUID companyId, UUID runnerHostId) {
        hosts.delete(companyId, runnerHostId);
        return ResponseEntity.noContent().build();
    }
}
