package com.flowtivo.tinfirm.ee.scim;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.flowtivo.tinfirm.support.ApiResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TEST FIXTURE ONLY: replays an identity provider's SCIM requests ({@code src/test/resources/scim/*.json}) in their
 * documented order against a company's endpoint, and checks the answers the provider expects: each step's status and
 * the values its JSON pointers name. {@code {{userName}}}, {@code {{externalId}}} and {@code {{random}}} are new for
 * each run; {@code {{id}}} and any other name are what a step captured.
 */
final class ScimRecordedTraffic {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ScimRecordedTraffic() {
    }

    static void replay(String fixture, ScimTest.Scim scim, String userName) {
        JsonNode recorded;
        try (InputStream in = ScimRecordedTraffic.class.getResourceAsStream("/scim/" + fixture)) {
            assertThat(in).as("fixture %s", fixture).isNotNull();
            recorded = JSON.readTree(in);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        Map<String, String> values = new HashMap<>();
        values.put("userName", userName);
        values.put("externalId", UUID.randomUUID().toString());
        values.put("random", "nobody-" + UUID.randomUUID());
        String query = recorded.path("query").asString("");
        for (JsonNode step : recorded.get("steps")) {
            String name = step.get("name").asString();
            String path = fill(step.get("path").asString(), values);
            if (!query.isEmpty()) {
                path += (path.contains("?") ? "&" : "?") + query;
            }
            Object body = step.has("body") ? fill(JSON.writeValueAsString(step.get("body")), values) : null;
            ApiResponse response = scim.send(step.get("method").asString(), path, body);
            assertThat(response.status()).as("%s: %s", name, response).isEqualTo(step.get("status").asInt());
            JsonNode answer = response.body() == null || response.body().isBlank() ? null : response.json();
            step.path("capture").properties().forEach(capture -> values.put(capture.getKey(),
                    answer.at(capture.getValue().asString()).asString()));
            step.path("expect").properties().forEach(expected -> {
                JsonNode actual = answer.at(expected.getKey());
                JsonNode wanted = expected.getValue();
                assertThat(actual.isMissingNode()).as("%s: %s in %s", name, expected.getKey(), answer).isFalse();
                if (wanted.isString()) {
                    assertThat(actual.asString()).as("%s: %s", name, expected.getKey())
                        .isEqualTo(fill(wanted.asString(), values));
                }
                else {
                    assertThat(actual).as("%s: %s", name, expected.getKey()).isEqualTo(wanted);
                }
            });
        }
    }

    private static String fill(String text, Map<String, String> values) {
        String filled = text;
        for (Map.Entry<String, String> value : values.entrySet()) {
            filled = filled.replace("{{" + value.getKey() + "}}", value.getValue());
        }
        return filled;
    }
}
