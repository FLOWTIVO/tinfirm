package com.flowtivo.tinfirm.account.internal;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.CredentialsApi;
import com.flowtivo.tinfirm.shared.api.model.AccountClientRequest;
import com.flowtivo.tinfirm.shared.api.model.AccountTokenRequest;
import com.flowtivo.tinfirm.shared.api.model.BorrowedCredentialList;
import com.flowtivo.tinfirm.shared.api.model.CredentialLendRequest;
import com.flowtivo.tinfirm.shared.api.model.SignInRequest;
import com.flowtivo.tinfirm.shared.api.model.SignInStart;
import com.flowtivo.tinfirm.shared.api.model.SystemCredential;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialClientCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialList;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialSignInRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialUpdateRequest;
import com.flowtivo.tinfirm.shared.web.ApiException;

@RestController
class CredentialsController implements CredentialsApi {

    private final Credentials credentials;

    /** The current request (a proxy), whose session a sign-in is bound to. */
    private final HttpServletRequest request;

    CredentialsController(Credentials credentials, HttpServletRequest request) {
        this.credentials = credentials;
        this.request = request;
    }

    @Override
    public ResponseEntity<SystemCredentialList> listSystemCredentials(UUID companyId, UUID systemId) {
        return ResponseEntity.ok(credentials.list(companyId, systemId));
    }

    @Override
    public ResponseEntity<SystemCredential> addSystemCredential(UUID companyId, UUID systemId,
            SystemCredentialCreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentials.add(companyId, systemId, body));
    }

    @Override
    public ResponseEntity<SystemCredential> storeSystemCredentialToken(UUID companyId, UUID systemId,
            UUID credentialId, AccountTokenRequest body) {
        return ResponseEntity.ok(credentials.storeToken(companyId, systemId, credentialId, body.getToken()));
    }

    @Override
    public ResponseEntity<SystemCredential> addSystemCredentialClient(UUID companyId, UUID systemId,
            SystemCredentialClientCreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentials.addClient(companyId, systemId, body));
    }

    @Override
    public ResponseEntity<SystemCredential> storeSystemCredentialClient(UUID companyId, UUID systemId,
            UUID credentialId, AccountClientRequest body) {
        return ResponseEntity.ok(credentials.storeClient(companyId, systemId, credentialId, body.getClientId(),
                body.getClientSecret()));
    }

    @Override
    public ResponseEntity<SignInStart> startSystemCredentialSignIn(UUID companyId, UUID systemId,
            SystemCredentialSignInRequest body) {
        return ResponseEntity.ok(credentials.startSignIn(companyId, systemId, body, sessionId()));
    }

    @Override
    public ResponseEntity<SignInStart> startSystemCredentialSignInAgain(UUID companyId, UUID systemId,
            UUID credentialId, SignInRequest body) {
        return ResponseEntity.ok(credentials.startSignInAgain(companyId, systemId, credentialId, body.getReturnTo(),
                sessionId()));
    }

    @Override
    public ResponseEntity<SystemCredential> updateSystemCredential(UUID companyId, UUID systemId, UUID credentialId,
            SystemCredentialUpdateRequest body) {
        return ResponseEntity.ok(credentials.update(companyId, systemId, credentialId, body));
    }

    @Override
    public ResponseEntity<Void> removeSystemCredential(UUID companyId, UUID systemId, UUID credentialId) {
        credentials.remove(companyId, systemId, credentialId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<SystemCredential> lendSystemCredential(UUID companyId, UUID systemId, UUID credentialId,
            UUID serviceIdentityId, CredentialLendRequest body) {
        return ResponseEntity.ok(credentials.lend(companyId, systemId, credentialId, serviceIdentityId, body));
    }

    @Override
    public ResponseEntity<Void> withdrawSystemCredential(UUID companyId, UUID systemId, UUID credentialId,
            UUID serviceIdentityId) {
        credentials.withdraw(companyId, systemId, credentialId, serviceIdentityId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<BorrowedCredentialList> listServiceIdentityCredentials(UUID companyId,
            UUID serviceIdentityId) {
        return ResponseEntity.ok(credentials.borrowed(companyId, serviceIdentityId));
    }

    /** The id of the caller's session, which a sign-in is bound to. */
    private String sessionId() {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw ApiException.unauthorized("A sign-in needs a session");
        }
        return session.getId();
    }
}
