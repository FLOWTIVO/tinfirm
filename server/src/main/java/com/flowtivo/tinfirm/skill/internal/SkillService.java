package com.flowtivo.tinfirm.skill.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.CredentialMatch;
import com.flowtivo.tinfirm.shared.api.model.Skill;
import com.flowtivo.tinfirm.shared.api.model.SkillPage;
import com.flowtivo.tinfirm.shared.api.model.SkillVersion;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.sql.Constraints;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.skill.SkillEvents;
import com.flowtivo.tinfirm.skill.SkillUsage;
import com.flowtivo.tinfirm.vault.ScanReport;
import com.flowtivo.tinfirm.vault.VaultService;

/**
 * The library of skills (the skills contract, §4 and §6): every member creates skills and owns those they create; a
 * skill is seen by its owner, the members of the groups it is shared with, the members it is shared with on their own
 * and admins, and edited (a new version, its shares, its delete) by its owner and admins ({@link SkillCallers#access});
 * anything else is 404. An upload is read and checked ({@link SkillBundle}) and scanned for credentials before the
 * skill's row is locked, so a large upload holds up no other request of the skill. Every change is audited by names,
 * versions and hashes, never the content, and announced ({@link SkillEvents}) in its transaction.
 */
@Service
class SkillService {

    static final String TARGET = "skill";

    static final int MAX_VERSIONS = 100;

    static final int MAX_NOTE = 500;

    static final int MAX_SHARES = 50;

    static final int MAX_MEMBER_SHARES = 100;

    private static final String NAME_INDEX = "skill_name_uq";

    private final SkillStore store;

    private final SkillCallers callers;

    private final SkillViews views;

    private final CompanyAccessGuard companyAccess;

    private final GroupMembership groups;

    private final Principals principals;

    private final VaultService vault;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    private final ObjectProvider<SkillUsage> usage;

    SkillService(SkillStore store, SkillCallers callers, SkillViews views, CompanyAccessGuard companyAccess,
            GroupMembership groups, Principals principals, VaultService vault, AuditLog auditLog,
            ApplicationEventPublisher events, TimeSource time, ObjectProvider<SkillUsage> usage) {
        this.usage = usage;
        this.store = store;
        this.callers = callers;
        this.views = views;
        this.companyAccess = companyAccess;
        this.groups = groups;
        this.principals = principals;
        this.vault = vault;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    // ---- reads -------------------------------------------------------------------------------------------------

    /** The live skills the caller sees, by name; for admins with {@code deleted}, the deleted skills instead. */
    @Transactional(readOnly = true)
    public SkillPage list(UUID companyId, @Nullable String query, @Nullable Boolean deleted, @Nullable Integer limit,
            @Nullable String cursor) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AGENTS);
        SkillCallers.Caller caller = callers.current(access);
        SkillStore.Scope scope;
        if (Boolean.TRUE.equals(deleted)) {
            if (!caller.admin()) {
                throw ApiException.forbidden("Only owners and admins list the deleted skills");
            }
            scope = SkillStore.Scope.DELETED;
        }
        else {
            scope = caller.admin() ? SkillStore.Scope.ALL : SkillStore.Scope.VISIBLE;
        }
        if (scope == SkillStore.Scope.VISIBLE && caller.principalId() == null) {
            return new SkillPage(new ArrayList<>());
        }
        PageRequest request = PageRequest.of(limit, cursor);
        List<SkillStore.SkillRow> rows = request.firstPage()
                ? store.page(companyId, scope, caller.principalId(), caller.groupIds(), query, null, null,
                        request.fetchSize())
                : store.page(companyId, scope, caller.principalId(), caller.groupIds(), query, request.key(0, 2),
                        request.uuidKey(1, 2), request.fetchSize());
        Page<SkillStore.SkillRow> page = Page.of(rows, request, row -> List.of(row.name(), row.id().toString()));
        return new SkillPage(new ArrayList<>(views.summaries(caller, companyId, page.items())))
            .nextCursor(page.nextCursor());
    }

    /** One skill the caller sees. */
    @Transactional(readOnly = true)
    public Skill get(UUID companyId, UUID skillId) {
        Seen seen = requireSeen(companyId, skillId);
        return views.skill(seen.caller(), companyId, seen.row());
    }

    /** A version of a skill the caller sees, with its files and the text of its {@code SKILL.md}. */
    @Transactional(readOnly = true)
    public SkillVersion version(UUID companyId, UUID skillId, int version) {
        requireSeen(companyId, skillId);
        SkillStore.VersionRow row = store.version(companyId, skillId, version).orElseThrow(SkillService::notFound);
        byte[] content = store.content(companyId, skillId, version).orElseThrow(SkillService::notFound);
        byte[] skillMd = SkillBundle.unzip(content).getOrDefault(SkillBundle.SKILL_MD, new byte[0]);
        return views.version(row, new String(skillMd, StandardCharsets.UTF_8));
    }

    /** The canonical zip of a version of a skill the caller sees, with the name its download takes. */
    @Transactional(readOnly = true)
    public Download content(UUID companyId, UUID skillId, int version) {
        Seen seen = requireSeen(companyId, skillId);
        byte[] content = store.content(companyId, skillId, version).orElseThrow(SkillService::notFound);
        return new Download(seen.row().name() + "-v" + version + ".zip", content);
    }

    /** A zip to download, with its file name. */
    record Download(String fileName, byte[] content) {
    }

    // ---- create and change -------------------------------------------------------------------------------------

    /** Creates a skill from an upload, owned by the caller, as version 1 (§4.2). */
    @Transactional
    public Skill create(UUID companyId, @Nullable MultipartFile file, @Nullable String skillMd, @Nullable String note) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AGENTS);
        SkillCallers.Caller caller = callers.current(access);
        UUID owner = caller.requirePrincipal();
        String noteText = note(note);
        SkillBundle.Parsed parsed = parse(file, skillMd);
        if (store.nameTaken(companyId, parsed.name())) {
            throw duplicateName();
        }
        scan(companyId, parsed);
        Instant now = time.now();
        SkillStore.SkillRow row = new SkillStore.SkillRow(Ids.newId(), companyId, parsed.name(), owner, 1, null, now,
                now, caller.userId());
        try {
            store.insert(row);
        }
        catch (DataIntegrityViolationException ex) {
            throw Constraints.violated(ex, NAME_INDEX) ? duplicateName() : ex;
        }
        insertVersion(companyId, row.id(), 1, parsed, noteText, caller.userId(), now);
        auditLog.record(versionAudit(AuditAction.SKILL_CREATED, companyId, row.id(), parsed, 1)
            .detail("ownerPrincipalId", owner.toString())
            .build());
        events.publishEvent(new SkillEvents.SkillCreated(companyId, row.id()));
        return views.skill(caller, companyId, row);
    }

    /**
     * A new version of a skill the caller edits (§4.2). The same files as the latest version store nothing: the answer
     * says so ({@link Added#stored()} false).
     */
    @Transactional
    public Added addVersion(UUID companyId, UUID skillId, @Nullable MultipartFile file, @Nullable String skillMd,
            @Nullable String note) {
        Seen seen = requireEditable(companyId, skillId, Lock.NONE);
        String noteText = note(note);
        SkillBundle.Parsed parsed = parse(file, skillMd);
        if (!parsed.name().equals(seen.row().name())) {
            throw ApiException.badRequest(ProblemCode.SKILL_NAME_CHANGED, "The SKILL.md names the skill \""
                    + parsed.name() + "\"; a new version must keep its name \"" + seen.row().name() + "\"");
        }
        scan(companyId, parsed);
        Seen locked = requireEditable(companyId, skillId, Lock.EDIT);
        SkillStore.SkillRow row = locked.row();
        Optional<SkillStore.VersionRow> latest = store.version(companyId, skillId, row.latestVersion());
        if (latest.isPresent() && latest.get().files().equals(parsed.files())) {
            return new Added(views.skill(locked.caller(), companyId, row), false);
        }
        if (row.latestVersion() >= MAX_VERSIONS) {
            throw ApiException.conflict(ProblemCode.SKILL_VERSION_LIMIT, "A skill keeps at most " + MAX_VERSIONS
                    + " versions; create a new skill for further versions");
        }
        int version = row.latestVersion() + 1;
        Instant now = time.now();
        insertVersion(companyId, skillId, version, parsed, noteText, locked.caller().userId(), now);
        store.setLatest(companyId, skillId, version, now);
        auditLog.record(versionAudit(AuditAction.SKILL_VERSION_ADDED, companyId, skillId, parsed, version)
            .detail("previousVersion", row.latestVersion())
            .build());
        events.publishEvent(new SkillEvents.SkillVersionAdded(companyId, skillId, version));
        SkillStore.SkillRow after = store.find(companyId, skillId).orElseThrow();
        return new Added(views.skill(locked.caller(), companyId, after), true);
    }

    /** The answer of {@link #addVersion}: the skill, and whether a version was stored. */
    record Added(Skill skill, boolean stored) {
    }

    /** Replaces the groups and the members that see and use the skill (§4.3). */
    @Transactional
    public Skill replaceShares(UUID companyId, UUID skillId, List<UUID> groupIds, @Nullable List<UUID> principalIds) {
        Seen edit = requireEditable(companyId, skillId, Lock.EDIT);
        SkillStore.Shares previous = store.shares(companyId, skillId);
        SkillStore.Shares requested = new SkillStore.Shares(groupsInput(companyId, groupIds),
                principalIds != null ? membersInput(companyId, principalIds) : previous.principalIds());
        if (requested.equals(previous)) {
            return views.skill(edit.caller(), companyId, edit.row());
        }
        Instant now = time.now();
        store.removeShares(companyId, skillId, minus(previous.groupIds(), requested.groupIds()));
        store.addShares(companyId, skillId, minus(requested.groupIds(), previous.groupIds()), edit.caller().userId(),
                now);
        store.removeMemberShares(companyId, skillId, minus(previous.principalIds(), requested.principalIds()));
        store.addMemberShares(companyId, skillId, minus(requested.principalIds(), previous.principalIds()),
                edit.caller().userId(), now);
        store.touch(companyId, skillId, now);
        sharesChanged(companyId, skillId, CurrentActor.actor(), previous, requested, null);
        return views.skill(edit.caller(), companyId, store.find(companyId, skillId).orElseThrow());
    }

    /**
     * Deletes the skill (soft): its shares go and its name is free (§4.5). The agents that pin it keep it, unless
     * {@code removeFromAgents} takes it from every live one ({@link SkillUsage#removeFromAgents}), the skill's row
     * locked first, {@code FOR UPDATE}: a change of an agent's pins that names the skill holds it
     * {@code FOR KEY SHARE} ({@link com.flowtivo.tinfirm.skill.Skills#lockForPins}), so the count and the agents
     * enumerated include every pin committed before, and a change after the delete reads the skill deleted.
     */
    @Transactional
    public void delete(UUID companyId, UUID skillId, boolean removeFromAgents) {
        Seen edit = requireEditable(companyId, skillId, Lock.DELETE);
        int agentCount = views.agentCount(companyId, skillId);
        if (removeFromAgents) {
            SkillUsage agents = usage.getIfAvailable();
            if (agents != null) {
                agentCount = agents.removeFromAgents(companyId, skillId);
            }
        }
        store.removeAllShares(companyId, skillId);
        store.softDelete(companyId, skillId, edit.caller().userId(), time.now());
        auditLog.record(AuditRecord.company(companyId, AuditAction.SKILL_DELETED, CurrentActor.actor())
            .target(TARGET, skillId)
            .detail("name", edit.row().name())
            .detail("agentCount", agentCount)
            .detail("removedFromAgents", removeFromAgents)
            .build());
        events.publishEvent(new SkillEvents.SkillDeleted(companyId, skillId));
    }

    /**
     * Audits and announces a change of whom the skill is shared with: the groups ({@code groupsAdded},
     * {@code groupsRemoved}) and the members ({@code principalsAdded}, {@code principalsRemoved}).
     */
    void sharesChanged(UUID companyId, UUID skillId, Actor actor, SkillStore.Shares before, SkillStore.Shares after,
            @Nullable String cause) {
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.SKILL_SHARES_CHANGED, actor)
            .target(TARGET, skillId)
            .detail("groupsAdded", sorted(minus(after.groupIds(), before.groupIds())))
            .detail("groupsRemoved", sorted(minus(before.groupIds(), after.groupIds())))
            .detail("principalsAdded", sorted(minus(after.principalIds(), before.principalIds())))
            .detail("principalsRemoved", sorted(minus(before.principalIds(), after.principalIds())));
        if (cause != null) {
            audit.detail("cause", cause);
        }
        auditLog.record(audit.build());
        events.publishEvent(new SkillEvents.SkillShared(companyId, skillId));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private record Seen(SkillCallers.Caller caller, SkillStore.SkillRow row) {
    }

    /** How {@link #requireEditable} reads the skill's row: unlocked, for a change, or for its delete. */
    private enum Lock {
        NONE, EDIT, DELETE
    }

    private Seen requireSeen(UUID companyId, UUID skillId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AGENTS);
        SkillCallers.Caller caller = callers.current(access);
        SkillStore.SkillRow row = store.findLive(companyId, skillId).orElseThrow(SkillService::notFound);
        if (!SkillCallers.access(caller, row, store.shares(companyId, skillId)).sees()) {
            throw notFound();
        }
        return new Seen(caller, row);
    }

    /** The live skill for a caller who edits it, locked as asked; 404 when they do not see it, 403 when they do. */
    private Seen requireEditable(UUID companyId, UUID skillId, Lock lock) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AGENTS);
        SkillCallers.Caller caller = callers.current(access);
        SkillStore.SkillRow row = (switch (lock) {
            case NONE -> store.findLive(companyId, skillId);
            case EDIT -> store.lockLive(companyId, skillId);
            case DELETE -> store.lockLiveForDelete(companyId, skillId);
        }).orElseThrow(SkillService::notFound);
        SkillCallers.Access may = SkillCallers.access(caller, row, store.shares(companyId, skillId));
        if (!may.sees()) {
            throw notFound();
        }
        if (!may.edits()) {
            throw ApiException.forbidden(ProblemCode.SKILL_NOT_EDITABLE, "Only the skill's owner, owners and admins "
                    + "change it");
        }
        return new Seen(caller, row);
    }

    /** Exactly one of a zip and a {@code SKILL.md}'s text, read and checked (§4.2 steps 1, 2 and 4). */
    private static SkillBundle.Parsed parse(@Nullable MultipartFile file, @Nullable String skillMd) {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasText = skillMd != null && !skillMd.isBlank();
        if (hasFile == hasText) {
            throw ApiException.validation("Send a zip of the skill's folder (file) or the text of its SKILL.md "
                    + "(skillMd), not both");
        }
        try {
            if (hasText) {
                return SkillBundle.fromSkillMd(skillMd);
            }
            if (file.getSize() > SkillBundle.MAX_UPLOAD) {
                throw SkillBundle.Refused.tooLarge(SkillBundle.Refused.Limit.UPLOAD, "A skill's zip may have at most "
                        + (SkillBundle.MAX_UPLOAD / (1024 * 1024)) + " MiB");
            }
            return SkillBundle.fromZip(read(file));
        }
        catch (SkillBundle.Refused refused) {
            throw refusal(refused);
        }
    }

    private static byte[] read(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(SkillBundle.MAX_UPLOAD + 1);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static ApiException refusal(SkillBundle.Refused refused) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (refused.limit() != null) {
            details.put("limit", refused.limit().name());
            return ApiException.badRequest(ProblemCode.SKILL_TOO_LARGE, refused.getMessage(),
                    Map.of("details", details));
        }
        details.put("rule", refused.rule() != null ? refused.rule().name() : "PATH");
        if (refused.path() != null) {
            details.put("path", refused.path());
        }
        return ApiException.badRequest(ProblemCode.SKILL_INVALID, refused.getMessage(), Map.of("details", details));
    }

    /**
     * The canonical zip scanned for credentials (§4.2 step 5): any match, or content that cannot be scanned completely,
     * refuses the upload; nothing can be acknowledged.
     */
    private void scan(UUID companyId, SkillBundle.Parsed parsed) {
        ScanReport scan = vault.scan(companyId, parsed.zip());
        if (scan.clean()) {
            return;
        }
        List<CredentialMatch> matches = new ArrayList<>();
        scan.vaultSecrets().forEach(name -> matches.add(new CredentialMatch(CredentialMatch.KindEnum.VAULT_SECRET,
                name)));
        scan.formats().forEach(name -> matches.add(new CredentialMatch(CredentialMatch.KindEnum.FORMAT, name)));
        String what = scan.unscannable() != null && matches.isEmpty()
                ? "content that cannot be scanned for credentials (" + scan.unscannable() + ")"
                : "a credential (" + String.join(", ", matches.stream().map(CredentialMatch::getName).toList()) + ")";
        throw ApiException.unprocessable(ProblemCode.SKILL_CONTAINS_CREDENTIAL, "The skill's files contain " + what
                + ". Every agent that uses the skill would get it, so Tinfirm does not store it. Remove it and upload "
                + "again; nothing was stored.", Map.of("credentialMatches", matches));
    }

    private void insertVersion(UUID companyId, UUID skillId, int version, SkillBundle.Parsed parsed,
            @Nullable String note, UUID by, Instant at) {
        store.insertVersion(companyId, new SkillStore.VersionRow(skillId, version, parsed.name(),
                parsed.description(), parsed.sha256(), parsed.zip().length, parsed.files().size(), parsed.files(),
                note, at, by), parsed.zip());
    }

    private static AuditRecord.Builder versionAudit(AuditAction action, UUID companyId, UUID skillId,
            SkillBundle.Parsed parsed, int version) {
        return AuditRecord.company(companyId, action, CurrentActor.actor())
            .target(TARGET, skillId)
            .detail("name", parsed.name())
            .detail("version", version)
            .detail("sha256", parsed.sha256())
            .detail("sizeBytes", parsed.zip().length)
            .detail("fileCount", parsed.files().size());
    }

    private static @Nullable String note(@Nullable String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.strip();
        if (trimmed.length() > MAX_NOTE) {
            throw ApiException.invalid("A version's note has at most " + MAX_NOTE + " characters", "note");
        }
        return trimmed;
    }

    private Set<UUID> groupsInput(UUID companyId, List<UUID> groupIds) {
        Set<UUID> requested = new LinkedHashSet<>(groupIds);
        if (requested.size() > MAX_SHARES) {
            throw ApiException.validation("A skill is shared with at most " + MAX_SHARES + " groups");
        }
        Map<UUID, String> known = groups.groupNames(companyId, requested);
        for (UUID groupId : requested) {
            if (!known.containsKey(groupId)) {
                throw ApiException.validation("The group " + groupId + " is not a group of this company");
            }
        }
        return requested;
    }

    /** The members a request names by principal: each a member of the company who has not left. */
    private Set<UUID> membersInput(UUID companyId, List<UUID> principalIds) {
        Set<UUID> requested = new LinkedHashSet<>(principalIds);
        if (requested.size() > MAX_MEMBER_SHARES) {
            throw ApiException.validation("A skill is shared with at most " + MAX_MEMBER_SHARES + " members");
        }
        Map<UUID, PrincipalInfo> known = principals.findAll(companyId, requested);
        for (UUID principalId : requested) {
            PrincipalInfo principal = known.get(principalId);
            if (principal == null || !principal.member() || principal.status() == PrincipalStatus.LEFT) {
                throw ApiException.validation("The principal " + principalId + " is not a member of this company");
            }
        }
        return requested;
    }

    private static Set<UUID> minus(Set<UUID> from, Set<UUID> taken) {
        return from.stream().filter(id -> !taken.contains(id)).collect(Collectors.toSet());
    }

    private static List<String> sorted(Set<UUID> ids) {
        return ids.stream().sorted().map(UUID::toString).toList();
    }

    static ApiException notFound() {
        return ApiException.notFound("Skill not found");
    }

    private static ApiException duplicateName() {
        return ApiException.conflict(ProblemCode.DUPLICATE_SKILL_NAME, "A skill with this name already exists");
    }
}
