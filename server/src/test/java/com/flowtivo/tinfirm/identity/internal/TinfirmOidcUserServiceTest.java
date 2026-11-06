package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** The claims of an Entra-like ID token reach provisioning unchanged, and refusals become the SPA's codes. */
class TinfirmOidcUserServiceTest {

    private static final String ISSUER = "https://login.microsoftonline.com/00000000-0000-0000-0000-000000000000/v2.0";

    private final ExternalProvisioning provisioning = mock(ExternalProvisioning.class);

    private final TinfirmOidcUserService service = new TinfirmOidcUserService(provisioning);

    @Test
    void passesTheRegistrationIssuerSubjectAndAllClaims() {
        UUID userId = UUID.randomUUID();
        when(provisioning.provision(any())).thenReturn(new ExternalProvisioning.Granted(userId));

        OidcUser user = service.loadUser(request(Map.of("name", "Ada Lovelace", "preferred_username",
                "ada@contoso.com", "verified_primary_email", List.of("ada.lovelace@contoso.com"))));

        ArgumentCaptor<ExternalProvisioning.OidcLogin> login = ArgumentCaptor.forClass(ExternalProvisioning.OidcLogin.class);
        verify(provisioning).provision(login.capture());
        assertThat(login.getValue().provider()).isEqualTo("entra");
        assertThat(login.getValue().issuer()).isEqualTo(ISSUER);
        assertThat(login.getValue().subject()).isEqualTo("entra-sub-1");
        assertThat(login.getValue().claims()).containsEntry("preferred_username", "ada@contoso.com")
            .containsEntry("verified_primary_email", List.of("ada.lovelace@contoso.com"))
            .doesNotContainKeys("email", "email_verified");
        assertThat(user).isInstanceOf(TinfirmOidcUser.class);
        assertThat(((SessionUser) user).userId()).isEqualTo(userId);
        assertThat(user.getName()).isEqualTo(userId.toString());
    }

    @Test
    void aLoginWithoutAUsableEmailIsDeniedWithEmailMissing() {
        when(provisioning.provision(any()))
            .thenReturn(new ExternalProvisioning.Denied("email_missing", OidcLoginDeniedException.EMAIL_MISSING));

        assertThatThrownBy(() -> service.loadUser(request(Map.of("name", "No Mail"))))
            .isInstanceOfSatisfying(OidcLoginDeniedException.class,
                    denied -> assertThat(denied.getError().getErrorCode()).isEqualTo("EMAIL_MISSING"))
            .hasMessageContaining("email_missing");
    }

    @Test
    void anEmailInUseIsDeniedWithItsCode() {
        when(provisioning.provision(any()))
            .thenReturn(new ExternalProvisioning.Denied("email_in_use", OidcLoginDeniedException.EMAIL_IN_USE));

        assertThatThrownBy(() -> service.loadUser(request(Map.of("email", "taken@contoso.com"))))
            .isInstanceOfSatisfying(OidcLoginDeniedException.class,
                    denied -> assertThat(denied.getError().getErrorCode()).isEqualTo("EMAIL_IN_USE"));
    }

    /** No userinfo endpoint, so the ID token is the only source of claims. */
    private static OidcUserRequest request(Map<String, Object> claims) {
        Instant now = Instant.now();
        ClientRegistration registration = ClientRegistration.withRegistrationId("entra")
            .clientId("tinfirm")
            .clientSecret("test-only-client-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "profile", "email")
            .authorizationUri(ISSUER.replace("/v2.0", "/oauth2/v2.0/authorize"))
            .tokenUri(ISSUER.replace("/v2.0", "/oauth2/v2.0/token"))
            .jwkSetUri(ISSUER.replace("/v2.0", "/discovery/v2.0/keys"))
            .userNameAttributeName("sub")
            .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "test-access-token",
                now, now.plusSeconds(300), Set.of("openid", "profile", "email"));
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-id-token")
            .issuer(ISSUER)
            .subject("entra-sub-1")
            .audience(List.of("tinfirm"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claims(all -> all.putAll(claims))
            .build();
        return new OidcUserRequest(registration, accessToken, idToken);
    }
}
