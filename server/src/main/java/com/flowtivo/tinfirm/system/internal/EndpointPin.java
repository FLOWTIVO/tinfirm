package com.flowtivo.tinfirm.system.internal;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemKind;

/**
 * A call goes only to the endpoint it was prepared for (ADR-0079): the system's row, read again in the call's own
 * short transaction, must have the kind and the base URL the caller's snapshot had, or the credential's
 * ({@link CallCredential#endpoint()}, the system's when the token was revealed). An MCP server moved meanwhile is as
 * gone: {@code INVALID}, "the endpoint changed meanwhile", and nothing is sent to the new one.
 */
final class EndpointPin {

    private EndpointPin() {
    }

    /** The failure of a call prepared for another endpoint than the row's; null when the row still has it. */
    static CallResult.@Nullable Failure moved(SystemStore.SystemRow row, SystemKind kind, @Nullable String baseUrl) {
        if (row.kind() == kind && Objects.equals(row.baseUrl(), baseUrl)) {
            return null;
        }
        return CallResult.Failure.of(CallResult.Kind.INVALID, "The endpoint of " + row.name()
                + " changed meanwhile: nothing was sent to the new one");
    }

    /**
     * The failure of a call with a credential pinned to another endpoint than the row's; null when the row still has
     * it, or the credential is pinned to none.
     */
    static CallResult.@Nullable Failure moved(SystemStore.SystemRow row, CallCredential credential) {
        CallCredential.Endpoint endpoint = credential.endpoint();
        return endpoint == null ? null : moved(row, endpoint.kind(), endpoint.baseUrl());
    }
}
