package com.flowtivo.tinfirm.run;

/**
 * Why a QUEUED run waits (the package 3 contract, §3.11, §5.7), in the order of the dispatcher's checks:
 * <ol>
 * <li>the company and its systems (the package 6 contract, §5.4): {@code COMPANY_STOPPED} (until an admin lifts the
 * company's stop), {@code SYSTEM_PAUSED} (the workspace holds a repository of a paused system, until it is resumed);</li>
 * <li>the agent: {@code AGENT_PAUSED} (I18, until it is resumed);</li>
 * <li>the conversation: {@code ACCOUNT_REQUIRED} (a workspace binding waits for its person or an admin);</li>
 * <li>the payer's model access (package 2): {@code BUDGET_SPENT} (until {@code retryAt}, the budget's reset),
 * {@code KEY_ERROR} (LiteLLM refused the key or is unreachable, or the key is being made),
 * {@code HOST_OFFLINE} (a subscription's personal host is not connected), {@code MODEL_ACCESS_UNAVAILABLE} (no
 * gateway, an inactive payer, a subscription gone: package 2's refusals);</li>
 * <li>the hosts: {@code NO_HOST_FOR_CREDENTIALS} (the company has hosts for the runtime, but none may carry the run's
 * credentials), {@code NO_RUNNER_HOST} (none can run it, or none of those may and is connected),
 * {@code RUNNER_HOST_OFFLINE} and {@code RUNNER_HOST_DRAINING} (of the one host that may take it);</li>
 * <li>the slots: {@code CREDENTIAL_POOL_FULL} (a subscription's pool), {@code RUNNER_HOST_FULL};</li>
 * <li>{@code ASSIGNED}: sent to its host, waiting for {@code run.accepted}. {@code PREVIOUS_RUN_ACTIVE} (the host
 * answered {@code TASK_RUN_ACTIVE}: it still ends the conversation's previous turn) and {@code RUNNER_HOST_REJECTED}:
 * the host rejected it, and it waits until {@code retryAt}. {@code OTHER}: anything else, in the message.</li>
 * </ol>
 * Package 4 adds {@code TRIGGER_PAUSED}, the conversation's step: a paused trigger's conversations take no turn until
 * it is resumed (the package 4 contract, §5.11 and §11). The skills library (ADR-0064) adds {@code NO_HOST_FOR_SKILLS},
 * a host's step: the agent has skills and no connected host that could take the run lists {@code run.skills}.
 * Automatic retries (ADR-0073) add {@code RETRY_SCHEDULED}, before every check: an automatic retry of a turn that
 * failed for a reason that may pass waits until {@code retryAt}, its number of how many in {@code used} and
 * {@code limit}; the dispatcher does not look at it before.
 */
public enum QueueReasonCode {
    COMPANY_STOPPED, SYSTEM_PAUSED, AGENT_PAUSED, ACCOUNT_REQUIRED, BUDGET_SPENT, KEY_ERROR, HOST_OFFLINE, MODEL_ACCESS_UNAVAILABLE,
    NO_HOST_FOR_CREDENTIALS, NO_RUNNER_HOST, RUNNER_HOST_OFFLINE, RUNNER_HOST_DRAINING, CREDENTIAL_POOL_FULL, RUNNER_HOST_FULL,
    PREVIOUS_RUN_ACTIVE, RUNNER_HOST_REJECTED, ASSIGNED, TRIGGER_PAUSED, NO_HOST_FOR_SKILLS, RETRY_SCHEDULED,
    OTHER
}
