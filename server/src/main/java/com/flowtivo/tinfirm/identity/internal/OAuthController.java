package com.flowtivo.tinfirm.identity.internal;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.identity.CompanyOwners;
import com.flowtivo.tinfirm.shared.api.OAuthApi;
import com.flowtivo.tinfirm.shared.api.model.CompanyOwnerNotice;
import com.flowtivo.tinfirm.shared.api.model.OAuthClient;
import com.flowtivo.tinfirm.shared.api.model.OAuthConsentRequest;
import com.flowtivo.tinfirm.shared.api.model.OAuthGrant;
import com.flowtivo.tinfirm.shared.api.model.OAuthScope;
import com.flowtivo.tinfirm.shared.api.model.OAuthScopeName;
import com.flowtivo.tinfirm.shared.security.ApiScope;

/**
 * The logged-in user's side of the OAuth authorization server (ADR-0045): the consent page's data, with the notice of
 * who can read the conversations the client starts (the package 6 contract, §5.6), and "Connected apps".
 */
@RestController
class OAuthController implements OAuthApi {

    private final OAuthGrantService grants;

    private final CompanyOwners owners;

    OAuthController(OAuthGrantService grants, CompanyOwners owners) {
        this.grants = grants;
        this.owners = owners;
    }

    @Override
    public ResponseEntity<OAuthConsentRequest> getOAuthConsent(String clientId, String state, @Nullable String scope) {
        OAuthGrantService.ConsentRequest request = grants.consentRequest(clientId, state);
        return ResponseEntity.ok(new OAuthConsentRequest(client(request.client()), request.companyId(),
                request.companyName(), request.requested().stream().map(OAuthController::name).toList(),
                request.offered()
                    .stream()
                    .map(s -> new OAuthScope(name(s), OAuthScopes.title(s), OAuthScopes.explanation(s)))
                    .toList(),
                new CompanyOwnerNotice(request.companyId(), request.companyName(),
                        owners.ownersOf(request.companyId()))));
    }

    @Override
    public ResponseEntity<List<OAuthGrant>> listOAuthGrants() {
        return ResponseEntity.ok(grants.listOwn().stream().map(OAuthController::toDto).toList());
    }

    @Override
    public ResponseEntity<Void> revokeOAuthGrant(UUID grantId) {
        grants.revokeOwn(grantId);
        return ResponseEntity.noContent().build();
    }

    private static OAuthGrant toDto(OAuthGrantService.Grant grant) {
        return new OAuthGrant(grant.id(), client(grant.client()), grant.companyId(), grant.companyName(),
                grant.scopes().stream().map(OAuthController::name).toList(),
                grant.grantedAt().atOffset(ZoneOffset.UTC))
            .lastUsedAt(grant.lastUsedAt() != null ? grant.lastUsedAt().atOffset(ZoneOffset.UTC) : null);
    }

    private static OAuthClient client(OAuthClients.ClientInfo info) {
        return new OAuthClient(info.clientId(), info.name()).domain(info.domain());
    }

    private static OAuthScopeName name(ApiScope scope) {
        return OAuthScopeName.fromValue(scope.oauthScope());
    }
}
