package com.flowtivo.tinfirm.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.MeApi;
import com.flowtivo.tinfirm.shared.api.model.CurrentUser;
import com.flowtivo.tinfirm.shared.api.model.OwnPasswordChangeRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;

@RestController
class MeController implements MeApi {

    private final CurrentUserService currentUsers;

    private final PasswordService passwords;

    private final HttpServletRequest request;

    MeController(CurrentUserService currentUsers, PasswordService passwords, HttpServletRequest request) {
        this.currentUsers = currentUsers;
        this.passwords = passwords;
        this.request = request;
    }

    @Override
    public ResponseEntity<CurrentUser> getCurrentUser() {
        return ResponseEntity.ok(currentUsers.describe(CurrentActor.requireUser().id()));
    }

    @Override
    public ResponseEntity<Void> changeOwnPassword(OwnPasswordChangeRequest change) {
        HttpSession session = request.getSession(false);
        passwords.changeOwn(change.getCurrentPassword(), change.getNewPassword(),
                session != null ? session.getId() : null);
        return ResponseEntity.noContent().build();
    }
}
