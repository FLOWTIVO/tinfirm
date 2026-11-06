package com.flowtivo.tinfirm.ee.scim;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.scim.ScimDirectory;
import com.flowtivo.tinfirm.company.scim.ScimException;
import com.flowtivo.tinfirm.company.scim.ScimMembers;
import com.flowtivo.tinfirm.company.scim.ScimRoleSource;
import com.flowtivo.tinfirm.company.scim.ScimSettings;
import com.flowtivo.tinfirm.identity.ScimUsers;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.principal.PrincipalSwitches;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * SCIM 2.0's {@code Users} over the company's members (ADR-0087), Enterprise's: create, read, list, replace, patch,
 * deactivate and delete, as decisions 4 to 8 say. Runs in the controller's transaction, in the company's tenant
 * context, as the company's SCIM token.
 * <ul>
 * <li>The token sees the company's members, every role, suspended ones included; anyone else is {@code 404}.</li>
 * <li>Deactivation suspends the member at once with SCIM's own suspension, kept apart from an admin's; the user is
 * deactivated too when this company's SCIM created or bound them and no other company holds them. A user the company
 * adopted by email keeps their standing on the instance. Reactivation lifts SCIM's, and only SCIM's.</li>
 * <li>Delete is a member who leaves, never an erasure; the user is deactivated as at a deactivation.</li>
 * <li>The {@code externalId} names the membership: it binds a first sign-in only to a user this company's SCIM
 * created ({@code ExternalProvisioning}), so that an adopted user of another company is never bound by it.</li>
 * <li>An OWNER: never made, their role and {@code externalId} never changed, deactivated or deleted but never the
 * last active one ({@code 400 mutability}, {@code LAST_OWNER}, and the OWNER told in their inbox).</li>
 * <li>A role that holds a seat falls back to VIEWER when none is free, never {@code SEATS_EXHAUSTED}.</li>
 * <li>A user's email and name change only for a user this company's SCIM created or bound who is a member of no
 * other company, so that one company's IdP never renames a person another company also has.</li>
 * </ul>
 */
@Component
class ScimDirectoryImpl implements ScimDirectory {

    /** The highest {@code startIndex} served: SCIM's offset is bounded (CONVENTIONS §7). */
    static final int MAX_START_INDEX = 10_000;

    private static final String TARGET = "user";

    private final ScimMembers members;

    private final ScimUsers users;

    private final UserDirectory directory;

    private final ScimSettings settings;

    private final PrincipalSwitches principals;

    private final Licensing licensing;

    private final AuditLog auditLog;

    private final TenantScope tenantScope;

    private final TinfirmProperties tinfirm;

    private final TransactionTemplate ownTransaction;

    ScimDirectoryImpl(ScimMembers members, ScimUsers users, UserDirectory directory, ScimSettings settings,
            PrincipalSwitches principals, Licensing licensing, AuditLog auditLog, TenantScope tenantScope,
            TinfirmProperties tinfirm, PlatformTransactionManager transactionManager) {
        this.members = members;
        this.users = users;
        this.directory = directory;
        this.settings = settings;
        this.principals = principals;
        this.licensing = licensing;
        this.auditLog = auditLog;
        this.tenantScope = tenantScope;
        this.tinfirm = tinfirm;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void requireServed() {
        // ADR-0077, decision 7: SCIM serves while the licence holds SCIM (Enterprise in force, EXPIRING and GRACE)
        // and stops after the grace; nothing is undone.
        if (!licensing.has(Feature.SCIM)) {
            throw ScimException.editionRequired();
        }
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    @Override
    public Map<String, Object> list(UUID companyId, @Nullable String filter, @Nullable Integer startIndex,
            @Nullable Integer count) {
        int start = startIndex == null || startIndex < 1 ? 1 : startIndex;
        if (start > MAX_START_INDEX) {
            throw ScimException.invalidValue("startIndex is at most " + MAX_START_INDEX);
        }
        int size = count == null ? ScimSchemas.MAX_RESULTS : Math.clamp(count, 0, ScimSchemas.MAX_RESULTS);
        if (filter != null && !filter.isBlank()) {
            List<ScimMembers.Member> found = filtered(companyId, ScimFilter.parse(filter)).stream().toList();
            List<Map<String, Object>> page = found.stream()
                .skip(start - 1L)
                .limit(size)
                .map(member -> render(member))
                .toList();
            return ScimUsersJson.list(page, found.size(), start);
        }
        int total = members.count(companyId);
        List<Map<String, Object>> page = size == 0 ? List.of()
                : members.page(companyId, start - 1, size).stream().map(this::render).toList();
        return ScimUsersJson.list(page, total, start);
    }

    private Optional<ScimMembers.Member> filtered(UUID companyId, ScimFilter filter) {
        return switch (filter.attribute()) {
            case "userName" -> members.findByEmail(companyId, filter.value());
            case "externalId" -> members.findByExternalId(companyId, filter.value());
            default -> uuid(filter.value()).flatMap(id -> members.find(companyId, id));
        };
    }

    @Override
    public Map<String, Object> get(UUID companyId, String id) {
        return render(require(companyId, id));
    }

    @Override
    public Map<String, Object> serviceProviderConfig() {
        return ScimSchemas.serviceProviderConfig(baseUrl());
    }

    @Override
    public Map<String, Object> resourceTypes() {
        return ScimSchemas.resourceTypes(baseUrl());
    }

    @Override
    public Map<String, Object> resourceType(String name) {
        return ScimSchemas.resourceType(baseUrl(), name);
    }

    @Override
    public Map<String, Object> schemas() {
        return ScimSchemas.schemas(baseUrl());
    }

    @Override
    public Map<String, Object> schema(String id) {
        return ScimSchemas.schema(baseUrl(), id);
    }

    // ---- create ---------------------------------------------------------------------------------------------------

    @Override
    public Map<String, Object> create(UUID companyId, Map<String, Object> body) {
        ScimUserDraft draft = ScimUserDraft.of(body);
        String email = Objects.requireNonNull(draft.userName);
        String externalId = Objects.requireNonNull(draft.externalId);
        ScimSettings.Settings scim = settings.of(companyId);
        CompanyRole asked = draft.roleGiven ? ScimUsersJson.role(draft.role) : scim.role();
        members.lock(companyId);
        if (members.findByEmail(companyId, email).isPresent()) {
            throw ScimException.uniqueness("A member of the company has this userName");
        }
        if (members.findByExternalId(companyId, externalId).isPresent()) {
            throw ScimException.uniqueness("A member of the company has this externalId");
        }
        // A user of this email who is no member, local or of any provider, becomes a member, as when an OWNER adds
        // them by email (CONVENTIONS §6); else a new user, who signs in later.
        ScimUsers.ScimUser user = users.findByEmail(email)
            .orElseGet(() -> createUser(companyId, email, draft.name(email)));
        ScimMembers.Given given;
        try {
            given = members.add(companyId, user.id(), asked, externalId, scim.roleSource() == ScimRoleSource.IDP);
        }
        catch (DataIntegrityViolationException ex) {
            throw ScimException.uniqueness("A member of the company has this userName or externalId");
        }
        auditLog.record(scimEvent(companyId, AuditAction.SCIM_USER_CREATED, user.id())
            .detail("role", given.role().name())
            .detail("seatFallback", given.seatFallback())
            .build());
        if (draft.active && user.scimDeactivated()) {
            // A person SCIM deleted, and so deactivated, comes back: SCIM's own deactivation is lifted, and only it.
            users.setScimActive(user.id(), true);
            tenantScope.enterCompany(companyId);
        }
        if (!draft.active) {
            deactivate(companyId, requireMember(companyId, user.id()));
        }
        return render(requireMember(companyId, user.id()));
    }

    private ScimUsers.ScimUser createUser(UUID companyId, String email, String name) {
        try {
            return users.create(companyId, email, name);
        }
        catch (ApiException ex) {
            throw uniqueness(ex);
        }
    }

    // ---- replace and patch ----------------------------------------------------------------------------------------

    @Override
    public Map<String, Object> replace(UUID companyId, String id, Map<String, Object> body) {
        ScimMembers.Member member = require(companyId, id);
        return apply(companyId, member, ScimUserDraft.of(body));
    }

    @Override
    public Map<String, Object> patch(UUID companyId, String id, Map<String, Object> body) {
        ScimMembers.Member member = require(companyId, id);
        ScimUsers.ScimUser user = users.find(member.userId()).orElseThrow(ScimDirectoryImpl::notFound);
        ScimUserDraft draft = ScimUserDraft.stored(user.email(), member.externalId(), user.displayName(),
                !member.scimSuspended());
        draft.patch(body);
        return apply(companyId, member, draft);
    }

    /** Makes the member what the draft asks, in decision 7's order, and answers them as stored. */
    private Map<String, Object> apply(UUID companyId, ScimMembers.Member found, ScimUserDraft draft) {
        members.lock(companyId);
        UUID userId = found.userId();
        ScimMembers.Member member = requireMember(companyId, userId);
        ScimUsers.ScimUser user = users.find(userId).orElseThrow(ScimDirectoryImpl::notFound);
        boolean owner = member.role() == CompanyRole.OWNER;
        List<String> changed = new ArrayList<>();
        AuditRecord.Builder event = scimEvent(companyId, AuditAction.SCIM_USER_CHANGED, userId);

        // The externalId decides who may bind to the account (decision 6), for a user this company's SCIM created
        // only: never an OWNER's, and an adopted user's names the membership alone.
        String externalId = draft.externalId;
        if (externalId != null && !externalId.equals(member.externalId()) && !owner) {
            Optional<ScimMembers.Member> other = members.findByExternalId(companyId, externalId);
            if (other.isPresent() && !other.get().userId().equals(userId)) {
                throw ScimException.uniqueness("A member of the company has this externalId");
            }
            try {
                members.setExternalId(companyId, userId, externalId);
            }
            catch (DataIntegrityViolationException ex) {
                throw ScimException.uniqueness("A member of the company has this externalId");
            }
            changed.add("externalId");
            if (user.awaitingSignIn()) {
                event.detail("previousExternalIdSha256", member.externalId() != null ? sha256(member.externalId())
                        : null)
                    .detail("externalIdSha256", sha256(externalId));
            }
        }

        // The email and name are the instance's user's: this company's IdP changes them only for its own.
        String email = Objects.requireNonNull(draft.userName);
        boolean ownsUser = companyId.equals(user.scimCompanyId()) && members.elsewhere(companyId, userId)
            .memberships() == 0;
        if (ownsUser) {
            String newEmail = email.equals(user.email()) ? null : email;
            String name = draft.name(email);
            String newName = name.equals(user.displayName()) ? null : name;
            if (newEmail != null || newName != null) {
                try {
                    users.update(userId, newEmail, newName);
                }
                catch (ApiException ex) {
                    throw uniqueness(ex);
                }
                if (newEmail != null) {
                    changed.add("userName");
                }
                if (newName != null) {
                    changed.add("displayName");
                }
            }
        }

        // The role: an OWNER's never; under IDP whenever the request carries it, under APP never after creation
        // (ADR-0088, decision 8).
        if (draft.roleGiven && !owner) {
            CompanyRole asked = ScimUsersJson.role(draft.role);
            if (settings.of(companyId).roleSource() == ScimRoleSource.IDP && asked != member.role()) {
                ScimMembers.Given given = members.changeRole(companyId, userId, asked);
                if (given.role() != member.role()) {
                    changed.add("role");
                }
                if (given.seatFallback()) {
                    event.detail("seatFallback", true);
                }
            }
        }

        if (draft.active != !member.scimSuspended()) {
            if (draft.active) {
                reactivate(companyId, requireMember(companyId, userId));
            }
            else {
                deactivate(companyId, requireMember(companyId, userId));
            }
        }
        if (!changed.isEmpty()) {
            auditLog.record(event.detail("attributes", changed).build());
        }
        return render(requireMember(companyId, userId));
    }

    // ---- deactivate, reactivate, delete ---------------------------------------------------------------------------

    /**
     * Access ends at once and the data stays (decision 7): SCIM's suspension of the member, then the user's
     * deactivation when this company's SCIM created or bound them and no other company holds them in effect.
     */
    private void deactivate(UUID companyId, ScimMembers.Member member) {
        UUID userId = member.userId();
        if (member.role() == CompanyRole.OWNER && !member.suspended() && members.lastActiveOwner(companyId, userId)) {
            refuseLastOwner(companyId, userId);
        }
        members.setScimSuspended(companyId, userId, true);
        principals.scimSuspended(companyId, userId);
        if (ownUser(companyId, userId) && members.elsewhere(companyId, userId).activeMemberships() == 0
                && !instanceAdmin(userId)) {
            users.setScimActive(userId, false);
            tenantScope.enterCompany(companyId);
        }
        auditLog.record(scimEvent(companyId, AuditAction.SCIM_USER_DEACTIVATED, userId).build());
    }

    /**
     * SCIM's suspension and SCIM's deactivation are lifted, and nothing else: an admin's suspension or an instance
     * admin's deactivation stays (decision 7). A seat's role given back takes the seat fallback.
     */
    private void reactivate(UUID companyId, ScimMembers.Member member) {
        UUID userId = member.userId();
        ScimUsers.ScimUser user = users.find(userId).orElseThrow(ScimDirectoryImpl::notFound);
        if (user.scimDeactivated()) {
            if (!licensing.seatAvailableForReactivation(userId)) {
                members.fallBackToViewer(companyId, userId);
            }
            users.setScimActive(userId, true);
            tenantScope.enterCompany(companyId);
        }
        members.setScimSuspended(companyId, userId, false);
        principals.scimLifted(companyId, userId);
        auditLog.record(scimEvent(companyId, AuditAction.SCIM_USER_REACTIVATED, userId).build());
    }

    @Override
    public void delete(UUID companyId, String id) {
        members.lock(companyId);
        ScimMembers.Member member = require(companyId, id);
        UUID userId = member.userId();
        if (member.role() == CompanyRole.OWNER && members.lastActiveOwner(companyId, userId)) {
            refuseLastOwner(companyId, userId);
        }
        // As a member an admin removes (§15.13): no access, their content kept, the checklist for the admins.
        members.remove(companyId, userId);
        if (ownUser(companyId, userId) && members.elsewhere(companyId, userId).memberships() == 0
                && !instanceAdmin(userId)) {
            users.setScimActive(userId, false);
            tenantScope.enterCompany(companyId);
        }
        auditLog.record(scimEvent(companyId, AuditAction.SCIM_USER_DELETED, userId).build());
    }

    /**
     * {@code 400 mutability} with the detail {@code LAST_OWNER}; the refusal is kept for the OWNER's inbox in a
     * transaction of its own, since the request's rolls back.
     */
    private void refuseLastOwner(UUID companyId, UUID userId) {
        ownTransaction.executeWithoutResult(status -> members.lastOwnerRefused(companyId, userId));
        throw ScimException.mutability("LAST_OWNER");
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private Map<String, Object> render(ScimMembers.Member member) {
        ScimUsers.ScimUser user = users.find(member.userId()).orElseThrow(ScimDirectoryImpl::notFound);
        return ScimUsersJson.user(member, user, baseUrl());
    }

    private ScimMembers.Member require(UUID companyId, String id) {
        return uuid(id).flatMap(userId -> members.find(companyId, userId)).orElseThrow(ScimDirectoryImpl::notFound);
    }

    private ScimMembers.Member requireMember(UUID companyId, UUID userId) {
        return members.find(companyId, userId).orElseThrow(ScimDirectoryImpl::notFound);
    }

    /**
     * Whether this company's SCIM created or bound the user ({@code scim_company_id}): only then may its IdP deactivate
     * them on the instance. A user it adopted by email loses this company's membership, never their account.
     */
    private boolean ownUser(UUID companyId, UUID userId) {
        return users.find(userId).map(user -> companyId.equals(user.scimCompanyId())).orElse(false);
    }

    private boolean instanceAdmin(UUID userId) {
        // An instance admin is never deactivated by one company's IdP: the instance would lose its break-glass.
        return directory.findById(userId).map(UserSummary::instanceAdmin).orElse(false);
    }

    private AuditRecord.Builder scimEvent(UUID companyId, AuditAction action, UUID userId) {
        // The user's id, never the userName: the audit holds no email (CONVENTIONS §8).
        return AuditRecord.company(companyId, action, CurrentActor.actor())
            .target(TARGET, userId)
            .detail("userId", userId.toString());
    }

    private String baseUrl() {
        String url = tinfirm.publicUrl().toString();
        return (url.endsWith("/") ? url.substring(0, url.length() - 1) : url) + "/scim/v2";
    }

    private static ScimException uniqueness(ApiException ex) {
        if (ex.code() == ProblemCode.DUPLICATE_EMAIL) {
            return ScimException.uniqueness("Another user has this userName");
        }
        throw ex;
    }

    private static Optional<UUID> uuid(String id) {
        try {
            return Optional.of(UUID.fromString(id.trim()));
        }
        catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ScimException notFound() {
        return ScimException.notFound("No such user in the company");
    }
}
