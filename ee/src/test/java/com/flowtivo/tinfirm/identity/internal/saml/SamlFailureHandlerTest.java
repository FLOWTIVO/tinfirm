package com.flowtivo.tinfirm.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import com.flowtivo.tinfirm.identity.internal.SamlSignIns;

/**
 * ADR-0086, decision 13: every refused response counts against the client address, including one provisioning refused
 * and audited already, such as {@code ROLE_NOT_MAPPED}; that one is not audited a second time.
 */
class SamlFailureHandlerTest {

    private final SamlSignIns signIns = mock(SamlSignIns.class);

    private final AuthenticationFailureHandler failure = SamlLoginImpl.failure(signIns);

    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login/saml2/sso/kc");

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void loginPage() {
        when(signIns.loginError(anyString())).thenAnswer(call -> "/login?error=" + call.getArgument(0));
    }

    @Test
    void aRefusalProvisioningAuditedIsCountedAndNotAuditedAgain() throws Exception {
        failure.onAuthenticationFailure(request, response, SamlRefusal.audited("role_not_mapped", "ROLE_NOT_MAPPED"));

        verify(signIns).failed(request);
        verify(signIns, never()).refused(any(), any(), anyString(), anyString());
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=ROLE_NOT_MAPPED");
    }

    @Test
    void aRefusalOfTheChecksIsAuditedWhichCountsIt() throws Exception {
        failure.onAuthenticationFailure(request, response, SamlRefusal.of("replayed", "the assertion was used"));

        verify(signIns).refused(request, "kc", "replayed", "the assertion was used");
        verify(signIns, never()).failed(any());
    }
}
