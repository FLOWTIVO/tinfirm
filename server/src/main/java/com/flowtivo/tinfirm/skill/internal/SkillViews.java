package com.flowtivo.tinfirm.skill.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.api.model.AgentMemberShare;
import com.flowtivo.tinfirm.shared.api.model.Skill;
import com.flowtivo.tinfirm.shared.api.model.SkillFile;
import com.flowtivo.tinfirm.shared.api.model.SkillSummary;
import com.flowtivo.tinfirm.shared.api.model.SkillVersion;
import com.flowtivo.tinfirm.shared.api.model.SkillVersionSummary;
import com.flowtivo.tinfirm.shared.directory.UserDisplayNames;
import com.flowtivo.tinfirm.skill.SkillUsage;

/** The API's views of skills (the skills contract, §6), as the caller may see them. */
@Component
class SkillViews {

    private final SkillStore store;

    private final Principals principals;

    private final UserDisplayNames userNames;

    private final ObjectProvider<SkillUsage> usage;

    SkillViews(SkillStore store, Principals principals, UserDisplayNames userNames, ObjectProvider<SkillUsage> usage) {
        this.store = store;
        this.principals = principals;
        this.userNames = userNames;
        this.usage = usage;
    }

    /** The summaries of skills, in the order given. */
    List<SkillSummary> summaries(SkillCallers.Caller caller, UUID companyId, List<SkillStore.SkillRow> rows) {
        List<UUID> ids = rows.stream().map(SkillStore.SkillRow::id).toList();
        Map<UUID, SkillStore.Shares> shares = store.shares(companyId, ids);
        Map<UUID, SkillStore.VersionRow> latest = store.latest(companyId, ids);
        Map<UUID, String> owners = principals.displayNames(companyId, rows.stream()
            .map(SkillStore.SkillRow::ownerPrincipalId)
            .filter(Objects::nonNull)
            .toList());
        List<UUID> edited = rows.stream()
            .filter(row -> edits(caller, row))
            .map(SkillStore.SkillRow::id)
            .toList();
        Map<UUID, Integer> counts = agentCounts(companyId, edited);
        List<SkillSummary> summaries = new ArrayList<>();
        for (SkillStore.SkillRow row : rows) {
            SkillStore.Shares shared = shares.getOrDefault(row.id(), SkillStore.Shares.NONE);
            SkillStore.VersionRow version = latest.get(row.id());
            boolean editable = edited.contains(row.id());
            summaries.add(new SkillSummary()
                .id(row.id())
                .name(row.name())
                .description(version != null ? version.description() : "")
                .latestVersion(row.latestVersion())
                .ownerPrincipalId(row.ownerPrincipalId())
                .ownerName(row.ownerPrincipalId() != null ? owners.get(row.ownerPrincipalId()) : null)
                .editableByMe(editable)
                .shareGroupIds(sortedIds(shared.groupIds()))
                .sharePrincipalIds(sortedIds(shared.principalIds()))
                .agentCount(editable ? counts.getOrDefault(row.id(), 0) : null)
                .deleted(!row.live())
                .createdAt(ts(row.createdAt()))
                .updatedAt(ts(row.updatedAt())));
        }
        return summaries;
    }

    /** One skill, with its member shares and its versions, newest first. */
    Skill skill(SkillCallers.Caller caller, UUID companyId, SkillStore.SkillRow row) {
        SkillSummary summary = summaries(caller, companyId, List.of(row)).getFirst();
        SkillStore.Shares shared = store.shares(companyId, row.id());
        Map<UUID, PrincipalInfo> members = principals.findAll(companyId, shared.principalIds());
        Map<UUID, String> memberNames = principals.displayNames(companyId, members.keySet());
        List<AgentMemberShare> memberShares = members.values()
            .stream()
            .map(member -> new AgentMemberShare(member.id(), memberNames.getOrDefault(member.id(), ""))
                .userId(member.userId()))
            .sorted(Comparator.comparing(AgentMemberShare::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AgentMemberShare::getPrincipalId))
            .toList();
        List<SkillStore.VersionRow> versions = store.versions(companyId, row.id());
        Map<UUID, String> creators = creators(versions);
        return new Skill()
            .id(summary.getId())
            .name(summary.getName())
            .description(summary.getDescription())
            .latestVersion(summary.getLatestVersion())
            .ownerPrincipalId(summary.getOwnerPrincipalId())
            .ownerName(summary.getOwnerName())
            .editableByMe(summary.getEditableByMe())
            .shareGroupIds(summary.getShareGroupIds())
            .sharePrincipalIds(summary.getSharePrincipalIds())
            .agentCount(summary.getAgentCount())
            .deleted(summary.getDeleted())
            .createdAt(summary.getCreatedAt())
            .updatedAt(summary.getUpdatedAt())
            .memberShares(new ArrayList<>(memberShares))
            .versions(new ArrayList<>(versions.stream().map(version -> summary(version, creators)).toList()));
    }

    /** A version, with its files and the text of its {@code SKILL.md}. */
    SkillVersion version(SkillStore.VersionRow row, String skillMd) {
        Map<UUID, String> creators = creators(List.of(row));
        SkillVersionSummary summary = summary(row, creators);
        return new SkillVersion()
            .version(summary.getVersion())
            .description(summary.getDescription())
            .sha256(summary.getSha256())
            .sizeBytes(summary.getSizeBytes())
            .fileCount(summary.getFileCount())
            .note(summary.getNote())
            .createdAt(summary.getCreatedAt())
            .createdByName(summary.getCreatedByName())
            .skillId(row.skillId())
            .name(row.name())
            .files(new ArrayList<>(row.files()
                .stream()
                .map(file -> new SkillFile(file.path(), file.sizeBytes()))
                .toList()))
            .skillMd(skillMd);
    }

    private static SkillVersionSummary summary(SkillStore.VersionRow row, Map<UUID, String> creators) {
        return new SkillVersionSummary()
            .version(row.version())
            .description(row.description())
            .sha256(row.sha256())
            .sizeBytes((long) row.sizeBytes())
            .fileCount(row.fileCount())
            .note(row.note())
            .createdAt(ts(row.createdAt()))
            .createdByName(row.createdBy() != null ? creators.getOrDefault(row.createdBy(), "deleted user") : null);
    }

    private Map<UUID, String> creators(List<SkillStore.VersionRow> versions) {
        Set<UUID> users = new HashSet<>();
        versions.forEach(version -> {
            if (version.createdBy() != null) {
                users.add(version.createdBy());
            }
        });
        return users.isEmpty() ? Map.of() : userNames.displayNames(users);
    }

    private Map<UUID, Integer> agentCounts(UUID companyId, List<UUID> skillIds) {
        SkillUsage counter = usage.getIfAvailable();
        if (counter == null || skillIds.isEmpty()) {
            return new HashMap<>();
        }
        return counter.agentCounts(companyId, skillIds);
    }

    /** The live agents that pin a version of the skill. */
    int agentCount(UUID companyId, UUID skillId) {
        return agentCounts(companyId, List.of(skillId)).getOrDefault(skillId, 0);
    }

    private static boolean edits(SkillCallers.Caller caller, SkillStore.SkillRow row) {
        if (!row.live()) {
            return caller.admin();
        }
        return caller.admin() || (caller.principalId() != null && caller.principalId().equals(row.ownerPrincipalId()));
    }

    private static List<UUID> sortedIds(Set<UUID> ids) {
        return new ArrayList<>(ids.stream().sorted().toList());
    }

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
