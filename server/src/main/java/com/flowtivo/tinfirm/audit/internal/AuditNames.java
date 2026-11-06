package com.flowtivo.tinfirm.audit.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditPrincipals;
import com.flowtivo.tinfirm.audit.AuditTargetNames;
import com.flowtivo.tinfirm.shared.directory.AgentDisplayNames;
import com.flowtivo.tinfirm.shared.directory.ScimTokenDisplayNames;
import com.flowtivo.tinfirm.shared.directory.UserDisplayNames;
import com.flowtivo.tinfirm.shared.security.ActorType;

/**
 * The names of a page's actors and targets, resolved when it is read and never stored in the chain (CONVENTIONS §8),
 * each kind once per page: the users' display names and the agents' names (the actors, and the {@code user} targets);
 * the principals of the {@code principal} and {@code principal_model_key} targets, named by their member's user or
 * their service identity's name, {@code "{name} (left)"} for one who left; every other type by the module that keeps
 * it ({@link AuditTargetNames}), as the reader may see it.
 * <p>
 * <b>An erased person is named nowhere in the company's chain.</b> A member whose content the company erased after
 * they left ({@code principal.erased} with {@code left} true) and who has not joined again is no name as a target
 * (their principal, their model key, their user) and {@value #ERASED_PERSON} as the actor: the chain keeps their ids
 * only, so erasing them in the company also takes their name from its audit, though their user account keeps it at the
 * instance until an instance admin deletes it (§15.11), and the instance's chain still names them.
 */
@Component
class AuditNames {

    static final String ERASED_PERSON = "erased person";

    static final String USER_TARGET = "user";

    /** The targets whose id is a principal's: a model key is its principal's. */
    static final Set<String> PRINCIPAL_TARGETS = Set.of("principal", "principal_model_key");

    private final UserDisplayNames users;

    private final ObjectProvider<AgentDisplayNames> agents;

    private final ObjectProvider<AuditPrincipals> principals;

    private final ObjectProvider<AuditTargetNames> targets;

    private final ObjectProvider<ScimTokenDisplayNames> scimTokens;

    private final AuditEventStore store;

    AuditNames(UserDisplayNames users, ObjectProvider<AgentDisplayNames> agents,
            ObjectProvider<AuditPrincipals> principals, ObjectProvider<AuditTargetNames> targets,
            AuditEventStore store, ObjectProvider<ScimTokenDisplayNames> scimTokens) {
        this.scimTokens = scimTokens;
        this.users = users;
        this.agents = agents;
        this.principals = principals;
        this.targets = targets;
        this.store = store;
    }

    /**
     * The names a page needs.
     *
     * @param actors       the display names of the users and agents that acted, by id; an erased person's is absent
     * @param erasedUsers  the users who are erased persons in the company
     * @param targetNames  the targets' names by type, then by id
     */
    record Names(Map<UUID, String> actors, Set<UUID> erasedUsers, Map<String, Map<String, String>> targetNames) {

        static final Names NONE = new Names(Map.of(), Set.of(), Map.of());

        @Nullable String target(StoredAuditEvent event) {
            if (event.targetType() == null || event.targetId() == null) {
                return null;
            }
            return targetNames.getOrDefault(event.targetType(), Map.of()).get(event.targetId());
        }
    }

    /** The names of the events' actors and targets, for the reader. */
    Names of(AuditTargetNames.Reader reader, List<StoredAuditEvent> events) {
        if (events.isEmpty()) {
            return Names.NONE;
        }
        UUID companyId = reader.companyId();
        Map<String, Set<String>> byType = new LinkedHashMap<>();
        Set<UUID> userIds = new LinkedHashSet<>();
        Set<UUID> agentIds = new LinkedHashSet<>();
        Set<UUID> scimTokenIds = new LinkedHashSet<>();
        for (StoredAuditEvent event : events) {
            if (event.actorId() != null && ActorType.USER.name().equals(event.actorType())) {
                userIds.add(event.actorId());
            }
            if (event.actorId() != null && ActorType.AGENT.name().equals(event.actorType())) {
                agentIds.add(event.actorId());
            }
            if (event.actorId() != null && ActorType.SCIM.name().equals(event.actorType())) {
                scimTokenIds.add(event.actorId());
            }
            if (event.targetType() != null && event.targetId() != null) {
                byType.computeIfAbsent(event.targetType(), type -> new LinkedHashSet<>()).add(event.targetId());
            }
        }
        userIds.addAll(AuditTargetNames.uuids(byType.getOrDefault(USER_TARGET, Set.of())));

        Map<UUID, AuditPrincipals.Principal> targetPrincipals = new HashMap<>();
        Set<UUID> principalIds = new LinkedHashSet<>();
        PRINCIPAL_TARGETS.forEach(type -> principalIds.addAll(AuditTargetNames.uuids(byType.getOrDefault(type,
                Set.of()))));
        AuditPrincipals directory = principals.getIfAvailable();
        if (companyId != null && directory != null && !principalIds.isEmpty()) {
            targetPrincipals.putAll(directory.find(companyId, principalIds));
            targetPrincipals.values().stream().map(AuditPrincipals.Principal::userId).filter(Objects::nonNull)
                .forEach(userIds::add);
        }
        Set<UUID> erased = companyId != null && directory != null ? erasedUsers(directory, companyId, userIds)
                : Set.of();

        Map<UUID, String> userNames = new HashMap<>(userIds.isEmpty() ? Map.of() : users.displayNames(userIds));
        userNames.keySet().removeAll(erased);
        Map<UUID, String> actors = new HashMap<>(userNames);
        AgentDisplayNames agentNames = agents.getIfAvailable();
        if (!agentIds.isEmpty() && agentNames != null) {
            // Agent actors appear only in company chains, read in that company's tenant context.
            actors.putAll(agentNames.names(agentIds));
        }
        ScimTokenDisplayNames tokenNames = scimTokens.getIfAvailable();
        if (!scimTokenIds.isEmpty() && tokenNames != null && companyId != null) {
            // A SCIM token acts in its company's chain only (ADR-0087, decision 3).
            tokenNames.names(scimTokenIds).forEach((id, name) -> actors.put(id, "SCIM token " + name));
        }

        Map<String, Map<String, String>> names = new HashMap<>();
        Map<String, String> userTargets = new HashMap<>();
        for (String id : byType.getOrDefault(USER_TARGET, Set.of())) {
            String name = AuditTargetNames.uuids(List.of(id)).stream().map(userNames::get).filter(Objects::nonNull)
                .findFirst().orElse(null);
            if (name != null) {
                userTargets.put(id, name);
            }
        }
        names.put(USER_TARGET, userTargets);
        for (String type : PRINCIPAL_TARGETS) {
            names.put(type, principalNames(byType.getOrDefault(type, Set.of()), targetPrincipals, userNames));
        }
        Set<String> named = new HashSet<>(names.keySet());
        for (AuditTargetNames module : targets.orderedStream().toList()) {
            for (String type : module.targetTypes()) {
                Set<String> ids = byType.get(type);
                if (ids != null && !ids.isEmpty() && named.add(type)) {
                    names.put(type, Map.copyOf(module.names(reader, type, Set.copyOf(ids))));
                }
            }
        }
        return new Names(actors, erased, names);
    }

    /** A principal by its member's display name or its service identity's name; "{name} (left)" for one who left. */
    private static Map<String, String> principalNames(Collection<String> ids,
            Map<UUID, AuditPrincipals.Principal> principals, Map<UUID, String> userNames) {
        Map<String, String> names = new HashMap<>();
        for (String id : ids) {
            AuditPrincipals.Principal principal = AuditTargetNames.uuids(List.of(id)).stream().map(principals::get)
                .filter(Objects::nonNull).findFirst().orElse(null);
            if (principal == null) {
                continue;
            }
            String name = principal.userId() != null ? userNames.get(principal.userId()) : principal.name();
            if (name != null) {
                names.put(id, principal.left() ? name + " (left)" : name);
            }
        }
        return names;
    }

    /**
     * The users among these who are erased persons in the company: every principal they had there left, and the
     * company erased one of them after they left. A member who joined again is named again.
     */
    private Set<UUID> erasedUsers(AuditPrincipals directory, UUID companyId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        List<AuditPrincipals.Principal> all = directory.ofUsers(companyId, userIds);
        Set<UUID> current = new HashSet<>();
        Set<UUID> left = new HashSet<>();
        for (AuditPrincipals.Principal principal : all) {
            if (principal.left()) {
                left.add(principal.id());
            }
            else {
                current.add(principal.userId());
            }
        }
        Set<UUID> erasedPrincipals = store.erasedAfterLeaving(companyId, left);
        Set<UUID> erased = new HashSet<>();
        for (AuditPrincipals.Principal principal : all) {
            if (erasedPrincipals.contains(principal.id()) && !current.contains(principal.userId())) {
                erased.add(principal.userId());
            }
        }
        return erased;
    }
}
