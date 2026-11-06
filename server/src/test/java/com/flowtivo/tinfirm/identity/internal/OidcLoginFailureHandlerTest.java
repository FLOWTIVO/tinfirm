package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class OidcLoginFailureHandlerTest {

    private final LoginAudit loginAudit = mock(LoginAudit.class);

    private final OidcLoginFailureHandler handler = new OidcLoginFailureHandler("https://tinfirm.example.com",
            loginAudit);

    @Test
    void emailInUseGoesBackToTheSpaWithItsCode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(callback(), response,
                new OidcLoginDeniedException(OidcLoginDeniedException.EMAIL_IN_USE, "email_in_use"));

        assertThat(response.getRedirectedUrl()).isEqualTo("https://tinfirm.example.com/login?error=EMAIL_IN_USE");
        // Provisioning already audited the refusal.
        verify(loginAudit, never()).failed(any(), any(), any());
    }

    @Test
    void aMissingEmailGoesBackToTheSpaWithItsOwnCode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(callback(), response,
                new OidcLoginDeniedException(OidcLoginDeniedException.EMAIL_MISSING, "email_missing"));

        assertThat(response.getRedirectedUrl()).isEqualTo("https://tinfirm.example.com/login?error=EMAIL_MISSING");
        verify(loginAudit, never()).failed(any(), any(), any());
    }

    @Test
    void anUnverifiedEmailGoesBackToTheSpaWithItsOwnCode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(callback(), response,
                new OidcLoginDeniedException(OidcLoginDeniedException.EMAIL_NOT_VERIFIED, "email_not_verified"));

        assertThat(response.getRedirectedUrl())
            .isEqualTo("https://tinfirm.example.com/login?error=EMAIL_NOT_VERIFIED");
        verify(loginAudit, never()).failed(any(), any(), any());
    }

    @Test
    void identityProviderErrorsAreAuditedAndReportedGenerically() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(callback(), response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_state_parameter")));

        assertThat(response.getRedirectedUrl()).isEqualTo("https://tinfirm.example.com/login?error=OIDC_LOGIN_FAILED");
        verify(loginAudit).failed(eq(null), eq("oidc_error"), eq("keycloak"));
    }

    private static MockHttpServletRequest callback() {
        return new MockHttpServletRequest("GET", "/login/oauth2/code/keycloak");
    }
}
