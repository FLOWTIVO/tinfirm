package com.flowtivo.tinfirm.account.internal;

import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.AccountsApi;
import com.flowtivo.tinfirm.shared.api.model.Account;
import com.flowtivo.tinfirm.shared.api.model.AccountTokenRequest;
import com.flowtivo.tinfirm.shared.api.model.MyAccountList;
import com.flowtivo.tinfirm.shared.api.model.SignInRequest;
import com.flowtivo.tinfirm.shared.api.model.SignInStart;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.web.ApiException;

@RestController
class AccountsController implements AccountsApi {

    private final AccountService accounts;

    private final MyAccounts myAccounts;

    private final SignInCallback callback;

    /** The current request (a proxy), whose session a sign-in is bound to. */
    private final HttpServletRequest request;

    AccountsController(AccountService accounts, MyAccounts myAccounts, SignInCallback callback,
            HttpServletRequest request) {
        this.accounts = accounts;
        this.myAccounts = myAccounts;
        this.callback = callback;
        this.request = request;
    }

    @Override
    public ResponseEntity<MyAccountList> listMyAccounts(UUID companyId) {
        return ResponseEntity.ok(myAccounts.list(companyId));
    }

    @Override
    public ResponseEntity<Account> storeMyAccountToken(UUID companyId, UUID systemId, AccountTokenRequest body) {
        Account stored = accounts.storeMyToken(mine(companyId, systemId), body.getToken());
        return ResponseEntity.ok(stored.binding(myAccounts.bindingOn(companyId, systemId)));
    }

    @Override
    public ResponseEntity<SignInStart> startMyAccountSignIn(UUID companyId, UUID systemId, SignInRequest body) {
        return ResponseEntity.ok(accounts.startMySignIn(mine(companyId, systemId), body.getReturnTo(),
                sessionId()));
    }

    @Override
    public ResponseEntity<Void> disconnectMyAccount(UUID companyId, UUID systemId) {
        accounts.disconnectMine(mine(companyId, systemId));
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> completeSignIn(@Nullable String code, @Nullable String state,
            @Nullable String error) {
        String location = callback.complete(code, state, error, CurrentActor.requireUser().id(), sessionId());
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).build();
    }

    private static AccountService.Target mine(UUID companyId, UUID systemId) {
        return new AccountService.Target(companyId, systemId);
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
