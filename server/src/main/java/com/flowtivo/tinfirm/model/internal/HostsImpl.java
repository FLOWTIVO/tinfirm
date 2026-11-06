package com.flowtivo.tinfirm.model.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.model.Hosts;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.model.RunModelContext;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RunnerVersions;

/**
 * {@link Hosts#eligible} (the package 2 contract, §5.2; M4, M5): a personal host takes only its owner's work (their
 * budget or subscription, and only their own workspace accounts); a company host takes any budget-paid run and no
 * subscription. A budget-paid run also needs the always-on model proxy ({@link RunnerVersions}).
 */
@Component
class HostsImpl implements Hosts {

    private final RunnerDirectory runners;

    HostsImpl(RunnerDirectory runners) {
        this.runners = runners;
    }

    @Override
    @Transactional(readOnly = true)
    public Eligibility eligible(RunModelContext context) {
        UUID payer = context.payingPrincipalId();
        List<RunnerHostInfo> hosts = runners.hosts(context.companyId());
        return switch (context.access()) {
            case ModelAccessChoice.Budget budget -> budget(context, payer, hosts);
            case ModelAccessChoice.Subscription subscription -> subscription(context, payer, subscription, hosts);
        };
    }

    private static Eligibility budget(RunModelContext context, UUID payer, List<RunnerHostInfo> hosts) {
        // A workspace account that is not the payer's own keeps the run off the payer's personal hosts (M4).
        boolean ownWorkspace = Hosts.personalHostMayRun(payer, context.workspaceAccountHolders());
        List<RunnerHostInfo> candidates = hosts.stream()
            .filter(host -> host.serves(context.runtime()))
            .filter(host -> host.company() || ownWorkspace && host.personalOf(payer))
            .toList();
        List<RunnerHostInfo> eligible = candidates.stream().filter(RunnerHostInfo::gatewayRuns).toList();
        if (!eligible.isEmpty()) {
            return new Eligibility.Eligible(eligible, null);
        }
        String which = ownWorkspace ? "No company runner host, and no personal host of the paying principal,"
                : "No company runner host";
        String whyNone = candidates.isEmpty() ? which + " can run the runtime " + context.runtime()
                : "No runner host that can run the runtime " + context.runtime() + " has runner version "
                        + RunnerVersions.MODEL_PROXY_ALWAYS_ON + " or later, whose model proxy keeps the run's "
                        + "token out of the sandbox";
        return new Eligibility.Eligible(List.of(), whyNone);
    }

    private static Eligibility subscription(RunModelContext context, UUID payer,
            ModelAccessChoice.Subscription subscription, List<RunnerHostInfo> hosts) {
        Optional<RunnerHostInfo> host = hosts.stream()
            .filter(candidate -> candidate.id().equals(subscription.runnerHostId()))
            .findFirst();
        if (host.isEmpty() || !host.get().personalOf(payer)) {
            return nowhere("A subscription runs only on its owner's personal runner host, and this one is not the "
                    + "paying member's");
        }
        if (!Hosts.personalHostMayRun(payer, context.workspaceAccountHolders())) {
            return nowhere("A subscription runs only on its owner's personal runner host, which takes only its "
                    + "owner's own accounts; a workspace account of this run is someone else's");
        }
        if (!host.get().serves(context.runtime())) {
            return new Eligibility.Eligible(List.of(), "The personal runner host " + host.get().name()
                    + " cannot run the runtime " + context.runtime());
        }
        return new Eligibility.Eligible(List.of(host.get()), null);
    }

    private static Eligibility nowhere(String detail) {
        return new Eligibility.Nowhere(
                new RunModelAccess.Refuse(RunModelAccess.RefuseReason.CANNOT_RUN_ANYWHERE, detail));
    }
}
