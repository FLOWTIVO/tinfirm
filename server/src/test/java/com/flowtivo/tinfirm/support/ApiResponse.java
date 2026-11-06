package com.flowtivo.tinfirm.support;

import java.net.http.HttpHeaders;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** An HTTP response of the API under test. */
public record ApiResponse(int status, HttpHeaders headers, String body) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public JsonNode json() {
        return JSON.readTree(body);
    }

    /** The problem {@code code} property, or null. */
    public String problemCode() {
        JsonNode code = json().get("code");
        return code == null || code.isNull() ? null : code.asString();
    }

    public String contentType() {
        return headers.firstValue("Content-Type").orElse("");
    }

    public List<String> setCookies() {
        return headers.allValues("Set-Cookie");
    }

    public String header(String name) {
        return headers.firstValue(name).orElse(null);
    }

    @Override
    public String toString() {
        return "ApiResponse[" + status + " " + body + "]";
    }
}
