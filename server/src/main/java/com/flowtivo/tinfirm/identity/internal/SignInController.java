package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.SignInApi;
import com.flowtivo.tinfirm.shared.api.model.CompanySignIn;

/** A company's sign-in providers, read-only (ADR-0086, decision 15). */
@RestController
class SignInController implements SignInApi {

    private final CompanySignInService signIn;

    SignInController(CompanySignInService signIn) {
        this.signIn = signIn;
    }

    @Override
    public ResponseEntity<CompanySignIn> getCompanySignIn(UUID companyId) {
        return ResponseEntity.ok(signIn.describe(companyId));
    }
}
