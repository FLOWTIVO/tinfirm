package com.flowtivo.tinfirm.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * The answer of {@link ModelAccess#forRun} (the package 2 contract, §5.1): the run is paid by exactly one model
 * access, or it waits, or it cannot be paid at all. Tinfirm never switches payer (M3): a spent budget, a refused key
 * or an offline subscription host makes the run wait, with the reason.
 */
public sealed interface RunModelAccess {

    /**
     * The principal's budget: their key is usable. {@code models} are the models it may call (empty: every model
     * the gateway serves).
     */
    record Budget(UUID principalId, List<String> models) implements RunModelAccess {

        public Budget {
            models = List.copyOf(models);
        }

        /** Whether the key may call the model ({@link RunModelAccess#allows}). */
        public boolean allows(String model) {
            return RunModelAccess.allows(models, model);
        }

        /**
         * Why a run of {@code model} cannot go on this budget; null when it can. A run paid by a budget names its
         * model ({@code MODEL_REQUIRED}): the gateway serves the models it lists, and a runtime's own default need
         * not be one of them. And its key allows the model ({@code MODEL_NOT_ALLOWED}).
         */
        public @Nullable Refuse refusal(@Nullable String model) {
            if (model == null) {
                return new Refuse(RefuseReason.MODEL_REQUIRED, "No model is chosen: a run paid by a budget goes "
                        + "through the company's model gateway, which serves only the models it lists; choose one "
                        + "in the settings");
            }
            if (!allows(model)) {
                return new Refuse(RefuseReason.MODEL_NOT_ALLOWED, "The model " + model + " is not one the paying "
                        + "principal's model key allows");
            }
            return null;
        }
    }

    /**
     * Whether a key's models allow the model: every model when the list is empty or names LiteLLM's
     * {@code all-proxy-models} or {@code all-team-models}; else the name itself or a pattern ending in {@code *} whose
     * prefix it has ({@code claude-*}; a lone {@code *} allows every model). The one matcher of a key's models: the
     * relay refuses a request for any other model, a person's settings refuse to name one, and package 3 fails a turn
     * whose settings name one ({@code MODEL_NOT_ALLOWED}).
     */
    static boolean allows(List<String> models, String model) {
        if (models.isEmpty()) {
            return true;
        }
        for (String allowed : models) {
            if (allowed.equals(model) || allowed.equals("all-proxy-models") || allowed.equals("all-team-models")
                    || allowed.endsWith("*") && model.startsWith(allowed.substring(0, allowed.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /** The member's subscription on their personal host, by label. */
    record Subscription(UUID runnerHostId, String label) implements RunModelAccess {
    }

    /**
     * The run waits: {@code BUDGET_SPENT} until {@code until} (the budget's reset), {@code KEY_ERROR} (LiteLLM refused
     * the key or is unreachable, or the key is being created), {@code NO_MODEL_KEY} (the company's gateway team
     * provides the keys and the payer has none yet, ADR-0094), {@code HOST_OFFLINE} (the subscription's host is not
     * connected). {@code detail} is for people and holds no secret.
     */
    record Wait(WaitReason reason, @Nullable Instant until, String detail) implements RunModelAccess {
    }

    /** The run cannot be paid; {@link #problem()} is the API's answer. */
    record Refuse(RefuseReason reason, String detail) implements RunModelAccess {

        /** The problem the API answers for this refusal (contract §9). */
        public ApiException problem() {
            return switch (reason) {
                case GATEWAY_NOT_CONFIGURED -> ApiException.conflict(ProblemCode.GATEWAY_NOT_CONFIGURED, detail);
                case PRINCIPAL_INACTIVE -> ApiException.conflict(ProblemCode.PRINCIPAL_SUSPENDED, detail);
                case SUBSCRIPTION_GONE -> ApiException.conflict(ProblemCode.SUBSCRIPTION_GONE, detail);
                case CANNOT_RUN_ANYWHERE -> ApiException.conflict(ProblemCode.CANNOT_RUN_ANYWHERE, detail);
                case PERSONAL_HOST_CREDENTIAL_ONLY -> new ApiException(HttpStatus.BAD_REQUEST,
                        ProblemCode.PERSONAL_HOST_CREDENTIAL_ONLY, detail);
                case MODEL_REQUIRED -> ApiException.badRequest(ProblemCode.MODEL_REQUIRED, detail);
                case MODEL_NOT_ALLOWED -> ApiException.badRequest(ProblemCode.MODEL_NOT_ALLOWED, detail);
            };
        }
    }

    /** Why a run waits; also its queue reason (contract §5.2). */
    enum WaitReason {
        BUDGET_SPENT, KEY_ERROR, NO_MODEL_KEY, HOST_OFFLINE
    }

    /** Why a run cannot be paid. */
    enum RefuseReason {
        /** The company has no LiteLLM that passed its admin test. */
        GATEWAY_NOT_CONFIGURED,
        /** The paying principal is suspended or left (or a subscription's owner is not the paying principal). */
        PRINCIPAL_INACTIVE,
        /** The subscription no longer exists on its owner's personal host. */
        SUBSCRIPTION_GONE,
        /** The subscription names a company host, which holds no credentials (M5). */
        PERSONAL_HOST_CREDENTIAL_ONLY,
        /** No host may run it ({@link Hosts#eligible}). */
        CANNOT_RUN_ANYWHERE,
        /** A budget's run whose settings name no model ({@link Budget#refusal}). */
        MODEL_REQUIRED,
        /** A budget's run of a model its key does not allow ({@link Budget#refusal}). */
        MODEL_NOT_ALLOWED
    }
}
