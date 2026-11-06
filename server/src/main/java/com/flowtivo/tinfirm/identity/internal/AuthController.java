package com.flowtivo.tinfirm.identity.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.api.AuthApi;
import com.flowtivo.tinfirm.shared.api.model.AuthProviders;
import com.flowtivo.tinfirm.shared.api.model.CurrentUser;
import com.flowtivo.tinfirm.shared.api.model.LoginRequest;
import com.flowtivo.tinfirm.shared.api.model.LoginProvider;
import com.flowtivo.tinfirm.shared.api.model.ProviderKind;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;

@RestController
class AuthController implements AuthApi {

    private final LocalLoginService localLogin;

    private final LoginSessions loginSessions;

    private final CurrentUserService currentUsers;

    private final AuditLog auditLog;

    private final AuthProperties properties;

    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

    private final SamlSettings saml;

    private final ObjectProvider<SamlLogin> samlLogin;

    private final HttpServletRequest request;

    private final HttpServletResponse response;

    AuthController(LocalLoginService localLogin, LoginSessions loginSessions, CurrentUserService currentUsers,
            AuditLog auditLog, AuthProperties properties,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations, SamlSettings saml,
            ObjectProvider<SamlLogin> samlLogin, HttpServletRequest request, HttpServletResponse response) {
        this.localLogin = localLogin;
        this.loginSessions = loginSessions;
        this.currentUsers = currentUsers;
        this.auditLog = auditLog;
        this.properties = properties;
        this.clientRegistrations = clientRegistrations;
        this.saml = saml;
        this.samlLogin = samlLogin;
        this.request = request;
        this.response = response;
    }

    /** Public. Also sets the {@code XSRF-TOKEN} cookie that the SPA needs for the login request. */
    @Override
    public ResponseEntity<AuthProviders> getAuthProviders() {
        CsrfTokens.render(request);
        List<LoginProvider> providers = new ArrayList<>(oidcProviders());
        SamlLogin samlProviders = samlLogin.getIfAvailable();
        if (saml.declared() && samlProviders != null) {
            samlProviders.loginProviders()
                .forEach(provider -> providers.add(new LoginProvider(provider.id(), provider.name(), ProviderKind.SAML,
                        provider.startUrl())));
        }
        providers.sort(Comparator.comparing(LoginProvider::getId));
        return ResponseEntity.ok(new AuthProviders(properties.localEnabled(), providers));
    }

    @Override
    public ResponseEntity<CurrentUser> login(LoginRequest login) {
        AuthenticatedUser user = localLogin.authenticate(login.getEmail(), login.getPassword(),
                request.getRemoteAddr());
        loginSessions.establish(user.id(), request, response);
        try (TenantContextHolder.Scope ignored = TenantContextHolder
            .open(TenantContext.forUser(user.id(), user.instanceAdmin()))) {
            return ResponseEntity.ok(currentUsers.describe(user.id()));
        }
    }

    /** Idempotent: without a session it only clears the cookies. */
    @Override
    public ResponseEntity<Void> logout() {
        CurrentActor.user()
            .ifPresent(user -> auditLog.record(AuditRecord.instance(AuditAction.AUTH_LOGOUT, Actor.user(user.id()))
                .target("user", user.id())
                .build()));
        loginSessions.end(request, response);
        return ResponseEntity.noContent().build();
    }

    private List<LoginProvider> oidcProviders() {
        ClientRegistrationRepository repository = clientRegistrations.getIfAvailable();
        if (!(repository instanceof Iterable<?> registrations)) {
            return List.of();
        }
        return StreamSupport.stream(registrations.spliterator(), false)
            .map(ClientRegistration.class::cast)
            .sorted(Comparator.comparing(ClientRegistration::getRegistrationId))
            .map(registration -> new LoginProvider(registration.getRegistrationId(), registration.getClientName(),
                    ProviderKind.OIDC, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
                            + "/" + registration.getRegistrationId()))
            .toList();
    }
}
