package com.flowtivo.tinfirm.model;

import java.util.List;
import java.util.UUID;

/**
 * The model access of a run (the package 2 contract, §5.1), for package 3's dispatcher. Runs in the caller's
 * transaction and tenant context; it calls nothing outside the server, so it fits a dispatching transaction.
 */
public interface ModelAccess {

    /**
     * Who pays for the run, as {@code context} names it (already authorised by the caller):
     * <ul>
     * <li>a principal's budget: {@code Budget(principal, models)} when the key is usable;
     * {@code Wait(BUDGET_SPENT, resetsAt)}; {@code Wait(KEY_ERROR, reason)} (LiteLLM refused it or is unreachable, or
     * it is being created); {@code Refuse(PRINCIPAL_INACTIVE)} for a suspended or left principal;
     * {@code Refuse(GATEWAY_NOT_CONFIGURED)} without a tested LiteLLM;</li>
     * <li>a member's subscription: {@code Subscription(host, label)} when the member's personal host has it;
     * {@code Wait(HOST_OFFLINE)} while the host is not connected; {@code Refuse(SUBSCRIPTION_GONE)} when the
     * credential no longer exists or is not the paying member's.</li>
     * </ul>
     * Nobody else ever pays (M3).
     */
    RunModelAccess forRun(RunModelContext context);

    /**
     * The models the principal's key may use (the package 3 contract, §2.1 and §5.2: the model of a person's settings
     * is one they allow, by {@link RunModelAccess#allows}): the principal's override, else the company's default.
     * Empty allows every model the gateway serves, as LiteLLM reads an empty list. Reads only Tinfirm's rows, in the
     * caller's transaction.
     */
    List<String> models(UUID companyId, UUID principalId);

    /**
     * Reads the principal's spend and budget from LiteLLM again, after the commit and off the caller's thread: after a
     * run ends, and when a queued run waits for a budget that should have reset.
     */
    void refresh(UUID companyId, UUID principalId);
}
