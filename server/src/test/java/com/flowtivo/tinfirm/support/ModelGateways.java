package com.flowtivo.tinfirm.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flowtivo.tinfirm.support.IntegrationTest.TestCompany;

/** The model settings of a test company (the package 2 contract, §3.1). TEST FIXTURE ONLY. */
public final class ModelGateways {

    public static final Duration WAIT = Duration.ofSeconds(20);

    /** The default models of {@link #configureBundled}. */
    public static final List<String> DEFAULT_MODELS = List.of("claude-sonnet-4-5", "claude-haiku-4-5");

    private ModelGateways() {
    }

    /**
     * Configures the bundled gateway (the fake) with a default budget of 25 USD per 30 days and
     * {@link #DEFAULT_MODELS}, and tests it, which creates the keys of the company's principals.
     */
    public static void configureBundled(ApiClient admin, TestCompany company) {
        ApiResponse put = admin.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED", "defaultBudgetUsd",
                25, "budgetPeriod", "30d", "defaultModels", DEFAULT_MODELS));
        assertThat(put.status()).as("model settings: %s", put).isEqualTo(200);
        ApiResponse test = admin.post(company.path("/model-settings/test"), null);
        assertThat(test.status()).as("model settings test: %s", test).isEqualTo(200);
    }

    /** The alias of a principal's key in LiteLLM. */
    public static String alias(TestCompany company, UUID principalId) {
        return "tinfirm-" + company.id() + "-" + principalId;
    }

    /** The alias of a principal's key after {@code generation} rotations ({@code -r1}, {@code -r2}, ...). */
    public static String alias(TestCompany company, UUID principalId, int generation) {
        return alias(company, principalId) + (generation > 0 ? "-r" + generation : "");
    }

    /** Every key of the principal in the gateway, whatever its generation. */
    public static List<FakeLiteLlm.Key> keysOf(FakeLiteLlm gateway, TestCompany company, UUID principalId) {
        return gateway.keysOfTeam("tinfirm-" + company.id())
            .stream()
            .filter(key -> key.alias.startsWith(alias(company, principalId)))
            .toList();
    }

    /** Waits for the principal's key in the gateway. */
    public static FakeLiteLlm.Key awaitKey(FakeLiteLlm gateway, TestCompany company, UUID principalId) {
        await().atMost(WAIT).until(() -> gateway.keyOfAlias(alias(company, principalId)).isPresent());
        return gateway.keyOfAlias(alias(company, principalId)).orElseThrow();
    }

    /** Waits until the principal's key status, as the API shows it, is {@code status}. */
    /** The alias of the company's own key (ADR-0065) after {@code generation} rotations. */
    public static String companyKeyAlias(TestCompany company, int generation) {
        return "tinfirm-" + company.id() + "-company" + (generation > 0 ? "-r" + generation : "");
    }

    /** Waits until the company's own key has the status, as {@code getCompanyKeyModelAccess} shows it. */
    public static void awaitCompanyKey(ApiClient admin, TestCompany company, String status) {
        await().atMost(WAIT).untilAsserted(() -> {
            ApiResponse access = admin.get(company.path("/model-access/company-key"));
            assertThat(access.status()).as("%s", access).isEqualTo(200);
            assertThat(access.json().has("keyStatus") ? access.json().get("keyStatus").asString() : null)
                .as("%s", access)
                .isEqualTo(status);
        });
    }

    public static void awaitStatus(ApiClient admin, TestCompany company, UUID principalId, String status) {
        await().atMost(WAIT).untilAsserted(() -> {
            ApiResponse access = admin.get(company.path("/principals/" + principalId + "/model-access"));
            assertThat(access.status()).as("%s", access).isEqualTo(200);
            assertThat(access.json().has("keyStatus") ? access.json().get("keyStatus").asString() : null)
                .as("%s", access)
                .isEqualTo(status);
        });
    }
}
