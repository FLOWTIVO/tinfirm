package com.flowtivo.tinfirm.identity.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.ScimUsers;
import com.flowtivo.tinfirm.identity.UserEvents;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/** The users of a company's SCIM client (ADR-0087), behind the company module's SCIM directory. */
@Component
class ScimUsersImpl implements ScimUsers {

    private final UserRepository users;

    private final UserSessions sessions;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final ApplicationEventPublisher events;

    ScimUsersImpl(UserRepository users, UserSessions sessions, AuditLog auditLog, TimeSource time,
            ApplicationEventPublisher events) {
        this.users = users;
        this.sessions = sessions;
        this.auditLog = auditLog;
        this.time = time;
        this.events = events;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<ScimUser> find(UUID userId) {
        return users.findById(userId).map(ScimUsersImpl::view);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<ScimUser> findByEmail(String email) {
        return users.findByEmail(Emails.normalize(email)).map(ScimUsersImpl::view);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ScimUser create(UUID companyId, String email, String displayName) {
        String normalized = Emails.normalize(email);
        if (users.existsByEmail(normalized)) {
            throw duplicateEmail();
        }
        UserEntity user = UserEntity.scim(Ids.newId(), normalized, displayName, companyId, time.now());
        try {
            users.saveAndFlush(user);
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicateEmail();
        }
        auditLog.record(AuditRecord.instance(AuditAction.USER_CREATED, CurrentActor.actor())
            .target("user", user.getId())
            .detail("authSource", AuthSource.SCIM.name())
            .detail("companyId", companyId.toString())
            .detail("instanceAdmin", false)
            .build());
        return view(user);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ScimUser update(UUID userId, @Nullable String email, @Nullable String displayName) {
        UserEntity user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
        List<String> fields = new ArrayList<>();
        if (email != null) {
            String normalized = Emails.normalize(email);
            if (!normalized.equals(user.getEmail())) {
                if (users.findByEmail(normalized).filter(other -> !other.getId().equals(userId)).isPresent()) {
                    throw duplicateEmail();
                }
                user.changeEmail(normalized, time.now());
                fields.add("email");
            }
        }
        if (displayName != null && !displayName.equals(user.getDisplayName())) {
            user.rename(displayName, time.now());
            fields.add("displayName");
        }
        if (!fields.isEmpty()) {
            try {
                users.flush();
            }
            catch (DataIntegrityViolationException ex) {
                throw duplicateEmail();
            }
            // The fields' names, never their values: the audit holds no email or name (CONVENTIONS §8).
            auditLog.record(AuditRecord.instance(AuditAction.USER_UPDATED, CurrentActor.actor())
                .target("user", userId)
                .detail("fields", fields)
                .build());
        }
        return view(user);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ScimUser setScimActive(UUID userId, boolean active) {
        UserEntity user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
        boolean scimActive = user.getScimDeactivatedAt() == null;
        if (scimActive == active) {
            return view(user);
        }
        boolean wasActive = user.isActive();
        user.scimActivate(active, time.now());
        users.flush();
        auditLog.record(AuditRecord.instance(AuditAction.USER_UPDATED, CurrentActor.actor())
            .target("user", userId)
            .detail("fields", List.of("scimActive"))
            .detail("scimActive", active)
            .build());
        if (wasActive && !user.isActive()) {
            // Their sessions end everywhere (CONVENTIONS §5), and their principal is suspended in every company.
            sessions.invalidateAllAfterCommit(userId);
            events.publishEvent(new UserEvents.UserDeactivated(userId));
        }
        else if (!wasActive && user.isActive()) {
            events.publishEvent(new UserEvents.UserReactivated(userId));
        }
        return view(user);
    }

    private static ScimUser view(UserEntity user) {
        return new ScimUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getAuthSource().name(),
                user.isActive(), user.getScimDeactivatedAt() != null, user.getScimCompanyId(), user.getCreatedAt(),
                user.getUpdatedAt());
    }

    private static ApiException duplicateEmail() {
        return ApiException.conflict(ProblemCode.DUPLICATE_EMAIL, "A user with this email already exists");
    }
}
