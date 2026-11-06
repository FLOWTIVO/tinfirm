package com.flowtivo.tinfirm.identity.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.UserAccounts;
import com.flowtivo.tinfirm.identity.UserEvents;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.api.model.User;
import com.flowtivo.tinfirm.shared.api.model.UserCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.UserPage;
import com.flowtivo.tinfirm.shared.api.model.UserUpdateRequest;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/** User administration for instance admins. The last active instance admin cannot be demoted or deactivated. */
@Service
class UserAdminService {

    private final UserRepository users;

    private final UserAccounts accounts;

    private final UserSessions sessions;

    private final AuditLog auditLog;

    private final UserMapper mapper;

    private final TimeSource time;

    private final ApplicationEventPublisher events;

    private final Licensing licensing;

    UserAdminService(UserRepository users, UserAccounts accounts, UserSessions sessions, AuditLog auditLog,
            UserMapper mapper, TimeSource time, ApplicationEventPublisher events, Licensing licensing) {
        this.licensing = licensing;
        this.events = events;
        this.users = users;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public UserPage list(@Nullable Integer limit, @Nullable String cursor) {
        CurrentActor.requireInstanceAdmin();
        PageRequest request = PageRequest.of(limit, cursor);
        List<UserEntity> rows = request.firstPage() ? users.findAllByOrderByEmailAsc(Limit.of(request.fetchSize()))
                : users.findByEmailGreaterThanOrderByEmailAsc(request.key(0, 1), Limit.of(request.fetchSize()));
        Page<User> page = Page.of(rows, request, user -> List.of(user.getEmail())).map(mapper::toDto);
        return new UserPage(page.items()).nextCursor(page.nextCursor());
    }

    @Transactional
    public User create(UserCreateRequest request) {
        CurrentActor.requireInstanceAdmin();
        UserSummary created = accounts.createLocalUser(new UserAccounts.NewLocalUser(request.getEmail(),
                request.getDisplayName(), request.getPassword(), Boolean.TRUE.equals(request.getInstanceAdmin())));
        return mapper.toDto(users.findById(created.id()).orElseThrow());
    }

    @Transactional
    public User update(UUID userId, UserUpdateRequest request) {
        CurrentActor.requireInstanceAdmin();
        boolean mayLoseAdmin = Boolean.FALSE.equals(request.getInstanceAdmin())
                || Boolean.FALSE.equals(request.getActive());
        // Lock all active admins first, so concurrent demotions cannot remove the last one.
        List<UUID> activeAdmins = mayLoseAdmin ? users.lockActiveInstanceAdmins() : List.of();
        UserEntity user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));

        boolean losesAdmin = user.isInstanceAdmin() && user.isActive() && mayLoseAdmin;
        if (losesAdmin && activeAdmins.stream().noneMatch(id -> !id.equals(userId))) {
            throw ApiException.conflict(ProblemCode.LAST_INSTANCE_ADMIN,
                    "The last active instance admin cannot be demoted or deactivated");
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        List<String> changedFields = new ArrayList<>();
        if (request.getDisplayName() != null && !request.getDisplayName().trim().equals(user.getDisplayName())) {
            user.rename(request.getDisplayName().trim(), time.now());
            // The name itself is personal data and stays out of the audit log.
            changedFields.add("displayName");
        }
        if (request.getInstanceAdmin() != null && request.getInstanceAdmin() != user.isInstanceAdmin()) {
            user.grantInstanceAdmin(request.getInstanceAdmin(), time.now());
            changedFields.add("instanceAdmin");
            changes.put("instanceAdmin", request.getInstanceAdmin());
        }
        boolean deactivated = false;
        boolean reactivated = false;
        // The admin's own deactivation; SCIM's (ADR-0087, decision 7) stays as it is, and the user stays inactive
        // while it holds.
        if (request.getActive() != null && request.getActive() != user.isAdminActive()) {
            boolean wasActive = user.isActive();
            if (request.getActive() && user.getScimDeactivatedAt() == null) {
                // ADR-0077, decision 5: a user who becomes active again takes back the seat their roles hold.
                licensing.requireSeatForReactivation(userId);
            }
            user.activate(request.getActive(), time.now());
            changedFields.add("active");
            changes.put("active", request.getActive());
            deactivated = wasActive && !user.isActive();
            reactivated = !wasActive && user.isActive();
        }

        if (!changedFields.isEmpty()) {
            users.flush();
            AuditRecord.Builder event = AuditRecord.instance(AuditAction.USER_UPDATED, CurrentActor.actor())
                .target("user", userId)
                .detail("fields", changedFields);
            changes.forEach(event::detail);
            auditLog.record(event.build());
        }
        if (deactivated) {
            sessions.invalidateAllAfterCommit(userId);
        }
        if (deactivated || reactivated) {
            // Package 6, §5.4: a deactivated user's principal is suspended in every company, and reactivating lifts it.
            events.publishEvent(deactivated ? new UserEvents.UserDeactivated(userId)
                    : new UserEvents.UserReactivated(userId));
        }
        return mapper.toDto(user);
    }
}
