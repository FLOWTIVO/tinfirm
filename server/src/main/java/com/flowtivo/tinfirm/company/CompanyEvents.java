package com.flowtivo.tinfirm.company;

import java.util.UUID;

/**
 * The company module's events about the company's kill switch (the package 6 contract, §5.4). Ids only. Published in
 * the transaction that pulled or lifted the switch, after its audit event.
 */
public final class CompanyEvents {

    private CompanyEvents() {
    }

    /** The company's runs were stopped. */
    public record RunsStopped(UUID companyId) {
    }

    /** The company's stop was lifted: the queue goes on (the run module dispatches after the commit). */
    public record RunsResumed(UUID companyId) {
    }
}
