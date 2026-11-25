package com.flowtivo.tinfirm.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.RawHttp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parameters that Tomcat cannot parse (Servlet 6.1: {@code getParameter} throws {@code InvalidParameterException}):
 * {@code 400 VALIDATION_FAILED} as a problem, or 413 where Tomcat says the parameters are too large, whether a
 * controller, a filter of Spring Security or the authorization server's filters read them first; never an unhandled
 * error, and never the input in the answer.
 */
@ExtendWith(OutputCaptureExtension.class)
class UnreadableParametersIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Bytes 00 FF 27 (not UTF-8), a malformed escape, and one more parameter than Tomcat's 1000. */
    private static final List<String> UNREADABLE = List.of("cursor=%00%ff%27", "cursor=%zz",
            "a=1" + "&a=1".repeat(1000));

    @Test
    void aSignedInRequestWhoseQueryCannotBeParsedIsRefusedAsAProblem(CapturedOutput output) {
        TestCompany company = companyWithRoles();
        Map<String, String> member = Map.of("Cookie", session(loginAs(company.email("MEMBER"))));
        Map<String, String> admin = Map.of("Cookie", session(admin()));
        Map<String, Map<String, String>> targets = Map.of(company.path("/members"), member,
                company.path("/skills"), member, "/api/companies", member, "/api/oauth/callback", member,
                "/api/admin/users", admin, "/api/admin/audit-events", admin);
        int before = output.getOut().length();
        targets.forEach((path, headers) -> UNREADABLE.forEach(query -> {
            RawHttp.Answer answer = get(path + "?" + query, headers);
            assertRefused(answer, path + "?" + query.substring(0, Math.min(20, query.length())));
        }));
        assertThat(output.getOut().substring(before)).as("nothing logged as an unhandled error")
            .doesNotContain("Unhandled exception")
            .doesNotContain("InvalidParameterException");
    }

    @Test
    void anAnonymousRequestGetsTheSameProblemWhicheverFilterReadsTheParameters(CapturedOutput output) {
        int before = output.getOut().length();
        // Spring Security's CSRF filter reads _csrf; the authorization server's and the OIDC login's filters read
        // their parameters before any controller.
        assertRefused(send("POST", "/api/auth/login?cursor=%00%ff%27", "application/json", "{}"), "login");
        assertRefused(get("/oauth2/authorize?client_id=%ff", Map.of()), "authorize");
        assertRefused(send("POST", "/oauth2/token?grant_type=%zz", "application/json", "{}"), "token");
        assertRefused(get("/login/oauth2/code/test-idp?code=%ff&state=%ff", Map.of()), "OIDC callback");
        // A form body that cannot be parsed.
        assertRefused(send("POST", "/oauth2/token", "application/x-www-form-urlencoded", "grant_type=%ff%00"),
                "token form");
        assertRefused(send("POST", "/oauth2/revoke", "application/x-www-form-urlencoded", "token=%zz"),
                "revoke form");
        assertThat(output.getOut().substring(before)).doesNotContain("InvalidParameterException");
    }

    @Test
    void aFormBodyLargerThanTomcatAllowsIs413() {
        // Tomcat's maxPostSize is 2 MB: it refuses the body by its declared length, before it reads it.
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Content-Length", Integer.toString(3 * 1024 * 1024));
        headers.put("Accept", "application/json");
        RawHttp.Answer answer = RawHttp.send(port, "POST", "/oauth2/token", headers, null);
        assertThat(answer.status()).as("%s", answer).isEqualTo(413);
        assertThat(answer.header("Content-Type")).startsWith("application/problem+json");
        assertThat(JSON.readTree(answer.body()).get("detail").asString())
            .isEqualTo("The request's parameters are larger than the server allows");
    }

    private void assertRefused(RawHttp.Answer answer, String what) {
        assertThat(answer.status()).as("%s: %s", what, answer).isEqualTo(400);
        assertThat(answer.header("Content-Type")).as(what).startsWith("application/problem+json");
        JsonNode problem = JSON.readTree(answer.body());
        assertThat(problem.get("code").asString()).as(what).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("detail").asString()).as(what).isEqualTo(
                "The request's parameters cannot be read: a value is not valid UTF-8, an escape is malformed, "
                        + "or there are too many");
        // Tomcat's message names the parameter and quotes its value; the answer holds neither.
        assertThat(answer.body()).as(what).doesNotContain("cursor").doesNotContain("Parameter [")
            .doesNotContain("\u0000").doesNotContain("�");
    }

    private RawHttp.Answer get(String target, Map<String, String> headers) {
        Map<String, String> all = new HashMap<>(headers);
        all.put("Accept", "application/json, application/problem+json");
        return RawHttp.send(port, "GET", target, all, null);
    }

    private RawHttp.Answer send(String method, String target, String contentType, String body) {
        return RawHttp.send(port, method, target, Map.of("Content-Type", contentType, "Accept",
                "application/json, application/problem+json"), body.getBytes(StandardCharsets.US_ASCII));
    }

    private static String session(ApiClient client) {
        return "TINFIRM_SESSION=" + client.cookie("TINFIRM_SESSION").orElseThrow().getValue();
    }
}
