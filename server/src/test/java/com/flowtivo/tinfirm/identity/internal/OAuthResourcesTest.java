package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.ApiScope;

/** The names of the authorization server and its resources, the scopes, and the 401's challenge (ADR-0045). */
class OAuthResourcesTest {

    private final OAuthResources resources = new OAuthResources(
            new TinfirmProperties(URI.create("https://tinfirm.example/"), Set.of("api")));

    private final UUID company = UUID.fromString("0190f5a4-0000-7000-8000-000000000001");

    @Test
    void theResourceOfACompanyIsItsCanonicalEndpointUri() {
        assertThat(resources.issuer()).isEqualTo("https://tinfirm.example");
        assertThat(resources.resource(company))
            .isEqualTo("https://tinfirm.example/api/companies/0190f5a4-0000-7000-8000-000000000001/mcp");
        assertThat(resources.resourceMetadata(company)).isEqualTo("https://tinfirm.example/.well-known/"
                + "oauth-protected-resource/api/companies/0190f5a4-0000-7000-8000-000000000001/mcp");
        assertThat(resources.companyOfResource(resources.resource(company))).contains(company);
    }

    @Test
    void onlyTheExactCanonicalUriIsAResource() {
        for (String other : List.of("http://tinfirm.example/api/companies/" + company + "/mcp",
                "https://tinfirm.example.evil/api/companies/" + company + "/mcp",
                "https://tinfirm.example/api/companies/" + company + "/mcp/",
                "https://tinfirm.example/api/companies/" + company + "/mcp/x",
                "https://tinfirm.example/api/companies/" + company.toString().toUpperCase() + "/mcp",
                "https://tinfirm.example/api/companies/" + company + "/mcp?x=1",
                "https://tinfirm.example/api/companies/" + company, "https://tinfirm.example", "")) {
            assertThat(resources.companyOfResource(other)).as(other).isEmpty();
        }
        assertThat(resources.companyOfResource(null)).isEmpty();
    }

    @Test
    void theCompanyOfARequestPath() {
        assertThat(OAuthResources.companyOfPath("/api/companies/" + company + "/mcp")).contains(company);
        assertThat(OAuthResources.companyOfPath("/api/companies/" + company + "/mcp/deeper")).contains(company);
        assertThat(OAuthResources.companyOfPath("/api/companies/c/mcp")).isEmpty();
        assertThat(OAuthResources.companyOfPath("/api/me")).isEmpty();
    }

    @Test
    void theConsentExplanationsSayWhatAHigherLevelIncludes() {
        assertThat(OAuthScopes.explanation(ApiScope.WRITE)).startsWith("Everything Read allows,").contains("Never decide");
        assertThat(OAuthScopes.explanation(ApiScope.DECIDE)).startsWith("Everything Read and Write allow,");
    }

    @Test
    void theHighestScopeCountsAndUnknownOnesAreIgnored() {
        assertThat(OAuthScopes.highest(List.of("tinfirm:read"))).contains(ApiScope.READ);
        assertThat(OAuthScopes.highest(List.of("tinfirm:read", "tinfirm:decide", "tinfirm:write")))
            .contains(ApiScope.DECIDE);
        assertThat(OAuthScopes.highest(List.of("openid", "tinfirm:write"))).contains(ApiScope.WRITE);
        assertThat(OAuthScopes.highest(List.of("openid", "tinfirm:admin"))).isEmpty();
        assertThat(OAuthScopes.known(Set.of("tinfirm:decide", "x", "tinfirm:read")))
            .containsExactly("tinfirm:read", "tinfirm:decide");
    }

    @Test
    void theChallengeNamesTheMetadataOfTheRequestedCompany() {
        McpChallenge challenge = new McpChallenge(resources);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/companies/" + company + "/mcp");
        assertThat(challenge.header(request, null)).isEqualTo("Bearer resource_metadata=\""
                + resources.resourceMetadata(company) + "\", scope=\"tinfirm:read\"");
        assertThat(challenge.header(request, "invalid_token")).isEqualTo("Bearer error=\"invalid_token\", "
                + "resource_metadata=\"" + resources.resourceMetadata(company) + "\", scope=\"tinfirm:read\"");
        MockHttpServletRequest noCompany = new MockHttpServletRequest("POST", "/api/companies/x/mcp");
        assertThat(challenge.header(noCompany, null)).isEqualTo("Bearer");
        assertThat(challenge.header(noCompany, "invalid_token")).isEqualTo("Bearer error=\"invalid_token\"");
    }
}
