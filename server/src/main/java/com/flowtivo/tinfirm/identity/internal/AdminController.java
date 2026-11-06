package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.audit.AuditTrail;
import com.flowtivo.tinfirm.shared.api.AdminApi;
import com.flowtivo.tinfirm.shared.api.model.AuditCheck;
import com.flowtivo.tinfirm.shared.api.model.AuditCheckAcknowledgement;
import com.flowtivo.tinfirm.shared.api.model.AuditCheckPage;
import com.flowtivo.tinfirm.shared.api.model.AuditEventPage;
import com.flowtivo.tinfirm.shared.api.model.AuditVerification;
import com.flowtivo.tinfirm.shared.api.model.PasswordSetRequest;
import com.flowtivo.tinfirm.shared.api.model.User;
import com.flowtivo.tinfirm.shared.api.model.UserCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.UserPage;
import com.flowtivo.tinfirm.shared.api.model.UserUpdateRequest;
import com.flowtivo.tinfirm.shared.api.model.VaultRewrapResult;
import com.flowtivo.tinfirm.vault.VaultService;

/**
 * {@code /api/admin/**}, instance admins only (enforced by the security filter chain and again by each service).
 * The generated {@link AdminApi} spans users, the instance audit chain and the vault; this controller delegates to
 * the owning modules.
 */
@RestController
class AdminController implements AdminApi {

    private final UserAdminService userAdmin;

    private final PasswordService passwords;

    private final AuditTrail auditTrail;

    private final VaultService vault;

    private final HttpServletRequest request;

    AdminController(UserAdminService userAdmin, PasswordService passwords, AuditTrail auditTrail, VaultService vault,
            HttpServletRequest request) {
        this.userAdmin = userAdmin;
        this.passwords = passwords;
        this.auditTrail = auditTrail;
        this.vault = vault;
        this.request = request;
    }

    @Override
    public ResponseEntity<UserPage> listUsers(Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(userAdmin.list(limit, cursor));
    }

    @Override
    public ResponseEntity<User> createUser(UserCreateRequest create) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userAdmin.create(create));
    }

    @Override
    public ResponseEntity<User> updateUser(UUID userId, UserUpdateRequest update) {
        return ResponseEntity.ok(userAdmin.update(userId, update));
    }

    @Override
    public ResponseEntity<Void> setUserPassword(UUID userId, PasswordSetRequest password) {
        HttpSession session = request.getSession(false);
        passwords.set(userId, password.getNewPassword(), session != null ? session.getId() : null);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AuditEventPage> listInstanceAuditEvents(Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(auditTrail.instanceEvents(limit, cursor));
    }

    @Override
    public ResponseEntity<AuditVerification> verifyInstanceAuditChain() {
        return ResponseEntity.ok(auditTrail.verifyInstanceChain());
    }

    @Override
    public ResponseEntity<AuditCheckPage> listInstanceAuditChecks(Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(auditTrail.instanceChecks(limit, cursor));
    }

    @Override
    public ResponseEntity<AuditCheck> acknowledgeInstanceAuditCheck(UUID checkId,
            AuditCheckAcknowledgement request) {
        return ResponseEntity.ok(auditTrail.acknowledgeInstanceCheck(checkId, request.getNote()));
    }

    @Override
    public ResponseEntity<VaultRewrapResult> rewrapVaultKeys() {
        VaultService.RewrapResult result = vault.rewrapAll();
        return ResponseEntity.ok(new VaultRewrapResult(result.activeKeyId(), result.rewrapped()));
    }
}
