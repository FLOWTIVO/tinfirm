package com.flowtivo.tinfirm.identity.internal;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.UserAccounts;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.directory.UserDisplayNames;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/** User lookups for other modules, and local user creation shared by the admin API and bootstrap. */
@Service
class UserAccountService implements UserDirectory, UserAccounts, UserDisplayNames {

    static final int MIN_PASSWORD_LENGTH = 12;

    static final int MAX_PASSWORD_LENGTH = 1024;

    static final int MAX_DISPLAY_NAME_LENGTH = 100;

    static final int MAX_EMAIL_LENGTH = 320;

    private final UserRepository users;

    private final PasswordHasher hasher;

    private final AuditLog auditLog;

    private final TimeSource time;

    UserAccountService(UserRepository users, PasswordHasher hasher, AuditLog auditLog, TimeSource time) {
        this.users = users;
        this.hasher = hasher;
        this.auditLog = auditLog;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findById(UUID id) {
        return users.findById(id).map(UserEntity::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findByEmail(String email) {
        return users.findByEmail(Emails.normalize(email)).map(UserEntity::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findLocalByEmail(String email) {
        return users.findByEmail(Emails.normalize(email)).filter(UserEntity::isLocal).map(UserEntity::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findByEntraObject(String tenantId, String objectId) {
        return users.findByOidcTenantIdAndOidcObjectId(tenantId.toLowerCase(java.util.Locale.ROOT),
                objectId.toLowerCase(java.util.Locale.ROOT))
            .map(UserEntity::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> findAllById(Collection<UUID> ids) {
        return users.findAllById(ids)
            .stream()
            .map(UserEntity::toSummary)
            .collect(Collectors.toMap(UserSummary::id, Function.identity()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> displayNames(Collection<UUID> userIds) {
        return users.findAllById(userIds)
            .stream()
            .collect(Collectors.toMap(UserEntity::getId, UserEntity::getDisplayName));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean anyUserExists() {
        return users.count() > 0;
    }

    @Override
    @Transactional
    public UserSummary createLocalUser(NewLocalUser newUser) {
        String email = Emails.normalize(newUser.email());
        String displayName = newUser.displayName().trim();
        if (email.isEmpty() || email.length() > MAX_EMAIL_LENGTH || !email.contains("@")) {
            throw ApiException.validation("Invalid email");
        }
        if (displayName.isEmpty() || displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw ApiException.validation("Display name must be 1-" + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        if (newUser.password().length() < MIN_PASSWORD_LENGTH || newUser.password().length() > MAX_PASSWORD_LENGTH) {
            throw ApiException.validation("Password must be " + MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH
                    + " characters");
        }
        if (users.existsByEmail(email)) {
            throw duplicateEmail();
        }
        UserEntity user = UserEntity.local(Ids.newId(), email, displayName, hasher.hash(newUser.password()),
                newUser.instanceAdmin(), time.now());
        try {
            users.saveAndFlush(user);
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicateEmail();
        }
        auditLog.record(AuditRecord.instance(AuditAction.USER_CREATED, CurrentActor.actor())
            .target("user", user.getId())
            .detail("authSource", AuthSource.LOCAL.name())
            .detail("instanceAdmin", user.isInstanceAdmin())
            .build());
        return user.toSummary();
    }

    private static ApiException duplicateEmail() {
        return ApiException.conflict(ProblemCode.DUPLICATE_EMAIL, "A user with this email already exists");
    }
}
