package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.transaction.PlatformTransactionManager;

import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * The consent's audit (ADR-0045): a code is sent only once {@code oauth.consent.granted} is recorded; when the audit
 * log fails, the authorization Spring already saved is removed and the client gets {@code server_error}.
 */
class AuthorizationResponsesTest {

    private static final String REDIRECT_URI = "http://127.0.0.1:7000/callback";

    private final OAuthResources resources = new OAuthResources(
            new TinfirmProperties(URI.create("https://tinfirm.example/"), Set.of("api")));

    private final UUID companyId = UUID.fromString("0190f5a4-0000-7000-8000-000000000001");

    private final UUID userId = UUID.fromString("0190f5a4-0000-7000-8000-000000000002");

    private final RegisteredClient client = RegisteredClient.withId("client-row")
        .clientId("https://client.example/metadata.json")
        .clientName("Client")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri(REDIRECT_URI)
        .scope("tinfirm:read")
        .build();

    private final InMemoryOAuth2AuthorizationService authorizations = new InMemoryOAuth2AuthorizationService();

    private final List<AuditRecord> recorded = new ArrayList<>();

    private AuthorizationResponses responses(AuditLog auditLog) {
        OAuthClients clients = mock(OAuthClients.class);
        when(clients.repository()).thenReturn(new InMemoryRegisteredClientRepository(client));
        when(clients.describe(client)).thenReturn(new OAuthClients.ClientInfo(client.getClientId(), "Client",
                "client.example"));
        OAuthGrantService grants = new OAuthGrantService(authorizations, mock(OAuth2AuthorizationConsentService.class),
                clients, resources, mock(CompanyNames.class), auditLog, null, mock(PlatformTransactionManager.class));
        return new AuthorizationResponses(resources, authorizations, grants);
    }

    /** The authorization Spring's provider saved for a consent, holding the code the handler is about to send. */
    private OAuth2AuthorizationCodeRequestAuthenticationToken issued(String code) {
        Instant now = Instant.now();
        OAuth2AuthorizationCode authorizationCode = new OAuth2AuthorizationCode(code, now,
                now.plus(5, ChronoUnit.MINUTES));
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://tinfirm.example/oauth2/authorize")
            .clientId(client.getClientId())
            .redirectUri(REDIRECT_URI)
            .scopes(Set.of("tinfirm:read"))
            .state("client-state")
            .additionalParameters(Map.of(OAuthAccessTokenCustomizer.RESOURCE, resources.resource(companyId)))
            .build();
        authorizations.save(OAuth2Authorization.withRegisteredClient(client)
            .id("authorization-1")
            .principalName(userId.toString())
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizedScopes(Set.of("tinfirm:read"))
            .attribute(OAuth2AuthorizationRequest.class.getName(), request)
            .token(authorizationCode)
            .build());
        return new OAuth2AuthorizationCodeRequestAuthenticationToken("https://tinfirm.example/oauth2/authorize",
                client.getClientId(), new TestingAuthenticationToken(userId.toString(), null), authorizationCode,
                REDIRECT_URI, "client-state", Set.of("tinfirm:read"));
    }

    private static MockHttpServletRequest consentPost() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/authorize");
        request.addHeader("Accept", "application/json");
        return request;
    }

    private static String redirectUri(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        return JsonMapper.builder().build().readTree(response.getContentAsString()).get("redirectUri").asString();
    }

    @Test
    void anAuditedConsentSendsTheCode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        responses(recorded::add).onAuthenticationSuccess(consentPost(), response, issued("the-code"));

        assertThat(redirectUri(response)).startsWith(REDIRECT_URI + "?code=the-code&state=client-state&iss=");
        assertThat(recorded).hasSize(1);
        assertThat(authorizations.findById("authorization-1")).isNotNull();
    }

    @Test
    void aFailedAuditWithdrawsTheCodeAndAnswersServerError() throws Exception {
        AuditLog failing = record -> {
            throw new IllegalStateException("audit chain unavailable");
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        responses(failing).onAuthenticationSuccess(consentPost(), response, issued("the-code"));

        String redirect = redirectUri(response);
        assertThat(redirect).startsWith(REDIRECT_URI + "?error=server_error&state=client-state&iss=")
            .doesNotContain("the-code");
        assertThat(authorizations.findById("authorization-1")).as("the code cannot be redeemed").isNull();
    }
}
