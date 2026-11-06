package com.flowtivo.tinfirm.system.internal;

import java.net.http.HttpRequest;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.GmailCalls;
import com.flowtivo.tinfirm.system.internal.http.GmailEndpoints;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;

/** {@link GmailCalls}: Google's keys, at {@link GmailEndpoints}. */
@Component
class GmailCallsImpl implements GmailCalls {

    private final OutboundHttp http;

    private final GmailEndpoints endpoints;

    GmailCallsImpl(OutboundHttp http, GmailEndpoints endpoints) {
        this.http = http;
        this.endpoints = endpoints;
    }

    @Override
    public CallResult<String> googleKeys() {
        NoTransaction.require("A call to Google");
        HttpRequest request = http.request(endpoints.googleKeys()).header("Accept", "application/json").GET().build();
        return body(http.send(endpoints.googleKeys(), request), "Google's keys");
    }

    /** The answer's body, or what failed: the response's status as a {@link CallResult.Kind}. */
    static CallResult<String> body(CallResult<OutboundHttp.Response> answer, String what) {
        if (answer.failure() != null) {
            return answer.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(answer.value());
        if (!response.success()) {
            return CallResult.failed(OutboundHttp.kindOf(response.status()), what + " answered " + response.status());
        }
        return CallResult.ok(response.body());
    }
}
