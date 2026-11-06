package com.flowtivo.tinfirm.company;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Port: the company runner host that a declared company names ({@code TINFIRM_COMPANIES_<KEY>_RUNNER_HOST_*},
 * ADR-0101, decision 8), which the deployment runs itself, as the Helm chart's runner pod. Implemented by the runner
 * module, which keeps runner hosts; the company module does not depend on it. Called at every start for each declared
 * company that is applied, in the declarations' transaction, as actor SYSTEM.
 */
public interface DeclaredRunnerHosts {

    /**
     * The company has a company runner host of the declared name whose token is the declared one: registered when it
     * has none, its token replaced when it has another (the earlier token stops working at once). A host of that name
     * that was registered in the app is never taken over: the start fails. A host that an earlier declaration
     * registered under another name stays, and is named in a warning.
     *
     * @param variable the variables' prefix, {@code TINFIRM_COMPANIES_<KEY>_RUNNER_HOST}, for the messages
     * @param host     the declared host, or null when the declaration names none
     */
    void apply(UUID companyId, String variable, @Nullable Host host);

    /**
     * A declared runner host.
     *
     * @param name  its name, as a company host's: letters, digits, {@code .}, {@code _} and {@code -}, starting with a
     *              letter or digit, at most 100 characters
     * @param token its token, {@code tfr_} + 43 base64url characters; never in a message or the log
     */
    record Host(String name, String token) {

        @Override
        public String toString() {
            return "Host[name=" + name + ", token=***]";
        }
    }
}
