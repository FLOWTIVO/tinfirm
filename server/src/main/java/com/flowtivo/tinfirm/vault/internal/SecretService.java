package com.flowtivo.tinfirm.vault.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.SecretCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.SecretMeta;
import com.flowtivo.tinfirm.shared.api.model.SecretPage;
import com.flowtivo.tinfirm.shared.api.model.SecretUpdateRequest;
import com.flowtivo.tinfirm.shared.directory.UserDisplayNames;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.vault.SecretKeeper;

/**
 * Write-only secrets: values go in encrypted and never come back out through the API. The Secrets API sees the
 * company's own secrets only: a secret a person owns (ADR-0049 §5) is never listed, and its id is 404 for an update
 * or a delete, as an unknown id is; only {@link com.flowtivo.tinfirm.vault.SecretWriter}'s owned operations write it.
 * A managed secret ({@link ManagedSecrets}: a service identity's account's token or grant, a system's OAuth client
 * secret) is listed, but its value is not replaced through the API, and while its account or app keeps it
 * ({@link SecretKeeper}) it is not deleted through the API either: it goes with the account's disconnect or the app's
 * removal, which audit and announce what went with it. A secret another module keeps under a name that is not managed
 * (the admin key of a company's own LiteLLM, which the model settings keep) is neither replaced nor deleted through
 * the API while it is kept ({@code 409 SECRET_MANAGED}): a value pasted here would bypass what the keeper does with a
 * new one (the model settings turn the relay off until the gateway passes its test again). The trusted writes of the
 * module that keeps it (an OAuth refresh, a disconnect, a new admin key in the model settings) are unaffected. An
 * internal secret (the package 2 contract, §3.2: a principal's LiteLLM key) is neither listed nor reachable by id, as a
 * person's own is; only {@link #createInternal} and {@link #deleteInternal} write it.
 */
@Service
class SecretService {

    private static final String TARGET = "secret";

    private final SecretRepository secrets;

    private final SecretVersionStore versions;

    private final EnvelopeCipher cipher;

    private final CompanyAccessGuard companyAccess;

    private final AuditLog auditLog;

    private final UserDisplayNames userNames;

    private final SecretMapper mapper;

    private final ObjectProvider<SecretKeeper> keepers;

    private final TimeSource time;

    SecretService(SecretRepository secrets, SecretVersionStore versions, EnvelopeCipher cipher,
            CompanyAccessGuard companyAccess, AuditLog auditLog, UserDisplayNames userNames, SecretMapper mapper,
            ObjectProvider<SecretKeeper> keepers, TimeSource time) {
        this.secrets = secrets;
        this.versions = versions;
        this.cipher = cipher;
        this.companyAccess = companyAccess;
        this.auditLog = auditLog;
        this.userNames = userNames;
        this.mapper = mapper;
        this.keepers = keepers;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public SecretPage list(UUID companyId, @Nullable Integer limit, @Nullable String cursor) {
        companyAccess.require(companyId, CompanyPermission.VIEW_SECRETS);
        PageRequest request = PageRequest.of(limit, cursor);
        List<SecretEntity> rows = request.firstPage()
                ? secrets.findByCompanyIdAndOwnerUserIdIsNullAndInternalFalseOrderByNameAsc(companyId,
                        Limit.of(request.fetchSize()))
                : secrets.findByCompanyIdAndOwnerUserIdIsNullAndInternalFalseAndNameGreaterThanOrderByNameAsc(
                        companyId, request.key(0, 1), Limit.of(request.fetchSize()));
        Page<SecretEntity> page = Page.of(rows, request, secret -> List.of(secret.getName()));
        Map<UUID, String> names = creatorNames(page.items());
        return new SecretPage(page.items().stream().map(secret -> mapper.toDto(secret, names)).toList())
            .nextCursor(page.nextCursor());
    }

    @Transactional
    public SecretMeta create(UUID companyId, SecretCreateRequest request) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_SECRETS);
        AuthenticatedUser user = CurrentActor.requireUser();
        String managedBy = ManagedSecrets.managedBy(request.getName());
        if (managedBy != null) {
            // The security review of 2026-10-27, I1: a hand-made secret with a managed name would show as
            // maintained by Tinfirm and could not be edited.
            throw ApiException.validation("The name `" + request.getName() + "` is reserved for the secrets Tinfirm "
                    + "maintains (" + managedBy + "); choose another name");
        }
        if (secrets.existsByCompanyIdAndName(companyId, request.getName())) {
            throw duplicateName();
        }
        SecretEntity secret = new SecretEntity(Ids.newId(), companyId, request.getName(),
                blankToNull(request.getDescription()), user.id(), time.now());
        try {
            secrets.saveAndFlush(secret);
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicateName();
        }
        storeVersion(secret, 1, request.getValue());
        auditLog.record(AuditRecord.company(companyId, AuditAction.SECRET_CREATED, CurrentActor.actor())
            .target(TARGET, secret.getId())
            .detail("name", secret.getName())
            .detail("version", 1)
            .build());
        return toDto(secret);
    }

    @Transactional
    public SecretMeta update(UUID companyId, UUID secretId, SecretUpdateRequest request) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_SECRETS);
        SecretEntity secret = secrets.lockCompanySecret(secretId, companyId).orElseThrow(SecretService::notFound);
        String managedBy = ManagedSecrets.managedBy(secret.getName());
        if (managedBy != null) {
            throw ApiException.validation("The value of `" + secret.getName() + "` is maintained by Tinfirm ("
                    + managedBy + "); connect the account or set the app again on its page");
        }
        Optional<String> keeper = keeperOf(companyId, secretId);
        if (keeper.isPresent()) {
            throw kept(secret, keeper.get());
        }
        int version = secret.nextVersion(time.now());
        boolean descriptionChanged = false;
        if (request.getDescription() != null) {
            String description = blankToNull(request.getDescription());
            descriptionChanged = !Objects.equals(description, secret.getDescription());
            secret.describe(description, time.now());
        }
        storeVersion(secret, version, request.getValue());
        auditLog.record(AuditRecord.company(companyId, AuditAction.SECRET_UPDATED, CurrentActor.actor())
            .target(TARGET, secret.getId())
            .detail("name", secret.getName())
            .detail("version", version)
            .detail("descriptionChanged", descriptionChanged)
            .build());
        return toDto(secret);
    }

    @Transactional
    public void delete(UUID companyId, UUID secretId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_SECRETS);
        SecretEntity secret = secrets.lockCompanySecret(secretId, companyId).orElseThrow(SecretService::notFound);
        Optional<String> keeper = keeperOf(companyId, secretId);
        if (keeper.isPresent()) {
            throw kept(secret, keeper.get());
        }
        versions.deleteAll(secret.getId());
        secrets.delete(secret);
        auditLog.record(AuditRecord.company(companyId, AuditAction.SECRET_DELETED, CurrentActor.actor())
            .target(TARGET, secret.getId())
            .detail("name", secret.getName())
            .build());
    }

    /** {@link com.flowtivo.tinfirm.vault.SecretWriter#create}: no access check; the caller made it. */
    @Transactional
    public UUID createTrusted(UUID companyId, String name, String description, String value) {
        AuthenticatedUser user = CurrentActor.requireUser();
        return createNamed(companyId, name, description, value, user.id(), null);
    }

    /** {@link com.flowtivo.tinfirm.vault.SecretWriter#replace}: no access check; the caller made it. */
    @Transactional
    public boolean replaceTrusted(UUID companyId, UUID secretId, String value) {
        return secrets.lockCompanySecret(secretId, companyId).map(secret -> replaceValue(secret, value)).orElse(false);
    }

    /** {@link com.flowtivo.tinfirm.vault.SecretWriter#delete}: no access check; the caller made it. */
    @Transactional
    public void deleteTrusted(UUID companyId, UUID secretId) {
        secrets.lockCompanySecret(secretId, companyId).ifPresent(this::deleteSecret);
    }

    /** {@link com.flowtivo.tinfirm.vault.SecretWriter#createOwned}: no access check; the caller made it. */
    @Transactional
    public UUID createOwned(UUID companyId, UUID ownerUserId, String name, String description, String value) {
        return createNamed(companyId, name, description, value, ownerUserId, ownerUserId);
    }

    /** {@link com.flowtivo.tinfirm.vault.SecretWriter#replaceOwned}: no access check; the caller made it. */
    @Transactional
    public boolean replaceOwned(UUID companyId, UUID ownerUserId, UUID secretId, String value) {
        return secrets.lockOwned(secretId, companyId, ownerUserId).map(secret -> replaceValue(secret, value))
            .orElse(false);
    }

    /** {@link com.flowtivo.tinfirm.vault.SecretWriter#deleteOwned}: no access check; the caller made it. */
    @Transactional
    public void deleteOwned(UUID companyId, UUID ownerUserId, UUID secretId) {
        secrets.lockOwned(secretId, companyId, ownerUserId).ifPresent(this::deleteSecret);
    }

    /** {@link com.flowtivo.tinfirm.vault.SecretWriter#deleteAllOwned}: no access check; the caller made it. */
    @Transactional
    public void deleteAllOwned(UUID companyId, UUID ownerUserId) {
        secrets.lockAllOwned(companyId, ownerUserId).forEach(this::deleteSecret);
    }

    /**
     * {@link com.flowtivo.tinfirm.vault.SecretWriter#createInternal}: no access check; the caller made it. The actor
     * may be the system (a key created after the commit that created its principal).
     */
    @Transactional
    public UUID createInternal(UUID companyId, String name, String description, String value) {
        Actor actor = CurrentActor.actor();
        UUID createdBy = actor.type() == ActorType.USER ? actor.id() : null;
        return createNamed(companyId, name, description, value, createdBy, null, true);
    }

    /** {@link com.flowtivo.tinfirm.vault.SecretWriter#deleteInternal}: no access check; the caller made it. */
    @Transactional
    public void deleteInternal(UUID companyId, UUID secretId) {
        secrets.lockInternal(secretId, companyId).ifPresent(this::deleteSecret);
    }

    private UUID createNamed(UUID companyId, String name, String description, String value, UUID createdBy,
            @Nullable UUID ownerUserId) {
        return createNamed(companyId, name, description, value, createdBy, ownerUserId, false);
    }

    /**
     * A new secret under {@code name}, or {@code name_2}, {@code name_3}... when the name is taken; owned by
     * {@code ownerUserId} when it is set, Tinfirm's own when {@code internal}. Audited as {@code secret.created}, with
     * {@code userId} for an owned one.
     */
    private UUID createNamed(UUID companyId, String name, String description, String value,
            @Nullable UUID createdBy, @Nullable UUID ownerUserId, boolean internal) {
        String chosen = name;
        for (int suffix = 2; secrets.existsByCompanyIdAndName(companyId, chosen); suffix++) {
            if (suffix > 99) {
                throw duplicateName();
            }
            chosen = name + "_" + suffix;
        }
        SecretEntity secret = new SecretEntity(Ids.newId(), companyId, chosen, blankToNull(description), createdBy,
                ownerUserId, internal, time.now());
        secrets.saveAndFlush(secret);
        storeVersion(secret, 1, value);
        auditLog.record(owner(AuditRecord.company(companyId, AuditAction.SECRET_CREATED, CurrentActor.actor())
            .target(TARGET, secret.getId())
            .detail("name", secret.getName())
            .detail("version", 1), secret)
            .build());
        return secret.getId();
    }

    private boolean replaceValue(SecretEntity secret, String value) {
        int version = secret.nextVersion(time.now());
        storeVersion(secret, version, value);
        auditLog.record(owner(AuditRecord.company(secret.getCompanyId(), AuditAction.SECRET_UPDATED,
                CurrentActor.actor())
            .target(TARGET, secret.getId())
            .detail("name", secret.getName())
            .detail("version", version)
            .detail("descriptionChanged", false), secret)
            .build());
        return true;
    }

    private void deleteSecret(SecretEntity secret) {
        versions.deleteAll(secret.getId());
        secrets.delete(secret);
        auditLog.record(owner(AuditRecord.company(secret.getCompanyId(), AuditAction.SECRET_DELETED,
                CurrentActor.actor())
            .target(TARGET, secret.getId())
            .detail("name", secret.getName()), secret)
            .build());
    }

    /** What keeps the secret, as its keeper names it; empty when nothing does ({@link SecretKeeper}). */
    private Optional<String> keeperOf(UUID companyId, UUID secretId) {
        return keepers.orderedStream()
            .map(candidate -> candidate.keeperOf(companyId, secretId))
            .flatMap(Optional::stream)
            .findFirst();
    }

    private static ApiException kept(SecretEntity secret, String keeper) {
        return ApiException.conflict(ProblemCode.SECRET_MANAGED, "`" + secret.getName() + "` is kept by " + keeper);
    }

    /** An owned secret's events name its owner ({@code userId}, ADR-0049 §5); a company secret's do not. */
    static AuditRecord.Builder owner(AuditRecord.Builder record, SecretEntity secret) {
        UUID ownerUserId = secret.getOwnerUserId();
        return ownerUserId != null ? record.detail("userId", ownerUserId.toString()) : record;
    }

    private void storeVersion(SecretEntity secret, int version, String value) {
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
        try {
            EncryptedValue encrypted = cipher.encrypt(new SecretAad(secret.getCompanyId(), secret.getId(), version),
                    plaintext);
            versions.insert(secret.getId(), version, secret.getCompanyId(), encrypted, time.now());
        }
        finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private SecretMeta toDto(SecretEntity secret) {
        return mapper.toDto(secret, creatorNames(List.of(secret)));
    }

    private Map<UUID, String> creatorNames(List<SecretEntity> page) {
        Set<UUID> creators = page.stream()
            .map(SecretEntity::getCreatedBy)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        return creators.isEmpty() ? Map.of() : userNames.displayNames(creators);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ApiException duplicateName() {
        return ApiException.conflict(ProblemCode.DUPLICATE_SECRET_NAME, "A secret with this name already exists");
    }

    private static ApiException notFound() {
        return ApiException.notFound("Secret not found");
    }
}
