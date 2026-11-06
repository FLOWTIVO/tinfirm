package com.flowtivo.tinfirm.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The base of the tests of {@link SystemTools} (package 3d): a company with a system registered over the API, and
 * calls of its tools in the company's tenant context, outside any transaction, as the conversations' tool executor
 * makes them. TEST FIXTURE ONLY: every token is a test value.
 */
abstract class ToolsTestSupport extends IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    SystemTools tools;

    /** A company of a test, its admin's client, and one system. */
    record Setup(TestCompany company, ApiClient admin, UUID systemId) {
    }

    Setup setup(String kind, @Nullable String baseUrl) {
        Map<String, Object> system = new HashMap<>();
        system.put("name", "Support " + kind);
        system.put("kind", kind);
        if (baseUrl != null) {
            system.put("baseUrl", baseUrl);
        }
        return setup(system);
    }

    Setup setup(Map<String, Object> system) {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        return new Setup(company, admin, createSystem(admin, company, system));
    }

    ToolOutcome call(Setup setup, CallCredential credential, String tool, String arguments) {
        return call(setup, credential, tool, arguments, null);
    }

    ToolOutcome call(Setup setup, CallCredential credential, String tool, String arguments,
            @Nullable String label) {
        return SystemContext.callInCompany(setup.company().id(), () -> tools.call(setup.company().id(),
                setup.systemId(), credential, tool, JSON.readTree(arguments), label));
    }

    /** Adds a repository to the code host over the API and answers its id. */
    UUID addRepository(Setup setup, String url, @Nullable String defaultBranch) {
        Map<String, Object> body = new HashMap<>();
        body.put("url", url);
        if (defaultBranch != null) {
            body.put("defaultBranch", defaultBranch);
        }
        ApiResponse response = setup.admin().post(setup.company().path("/systems/" + setup.systemId()
                + "/repositories"), body);
        assertThat(response.status()).as("repository: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    static CallCredential token(String token) {
        return new CallCredential(token, false, null, null);
    }

    static CallCredential oauth(String token) {
        return new CallCredential(token, true, null, null);
    }

    /** The outcome is {@code Done}: its result. */
    static ToolOutcome.Done done(ToolOutcome outcome) {
        assertThat(outcome).as("%s", outcome).isInstanceOf(ToolOutcome.Done.class);
        return (ToolOutcome.Done) outcome;
    }

    static String text(ToolOutcome outcome) {
        return done(outcome).result().text();
    }

    static JsonNode structured(ToolOutcome outcome) {
        JsonNode structured = done(outcome).result().structuredContent();
        assertThat(structured).isNotNull();
        return structured;
    }
}
