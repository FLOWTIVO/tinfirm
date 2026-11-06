package com.flowtivo.tinfirm.governance.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationPurge;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.RunPurge;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.Erasure;
import com.flowtivo.tinfirm.shared.api.model.ErasureCounts;
import com.flowtivo.tinfirm.shared.api.model.ErasurePreview;
import com.flowtivo.tinfirm.shared.api.model.ErasureRemains;
import com.flowtivo.tinfirm.shared.retention.IdentityContent;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.Systems;

/**
 * Erasing content on request (the package 6 contract, §5.9; open point 17), by OWNER ({@code MANAGE_RETENTION}), at
 * once and in one transaction:
 * <ul>
 * <li>a person's: for a member who left, every personal conversation of theirs and what they said, added and decided
 * in others'; for a member still in the company, their ended personal conversations (or those named), an open one named
 * being {@code 409 ERASURE_NOT_ALLOWED}. Each conversation loses what all four kinds of retention would purge, its
 * runs' texts and events included, and shows that it was erased; their dismissals of inbox notices go too;</li>
 * <li>an outside identity's words, through the content modules that hold them ({@link IdentityContent}), in open
 * conversations too, and the events of the runs whose prompts quoted them.</li>
 * </ul>
 * Refused while an open legal hold covers any of it ({@code 409 ON_HOLD}, naming the holds; G16). The conversations
 * are locked first, in the order of their ids, then the company's hold lock is taken shared and the holds read under
 * it, then the rows are purged, and the audit records last (the lock order). The answer counts what went and says what
 * remains, where, and who can remove it. Audited {@code principal.erased} or {@code identity.erased} with the counts;
 * the chain holds ids only and is never touched (G11).
 * <p>
 * Each erasure has its dry run, the preview: the same selection ({@link #personScope}, {@link #identityScope}), the
 * same holds' check ({@link #covering}), the purge APIs' counts in place of their purges, and the same remains. It
 * changes nothing and is not audited (CONVENTIONS §8: it reads counts and the company's own holds, never content, as
 * the retention page does; the erasure that follows it is audited).
 */
@Service
class ErasureService {

    private final ConversationPurge conversations;

    private final Conversations conversationInfo;

    private final RunPurge runs;

    private final RetentionStore store;

    private final HoldService holds;

    private final Principals principals;

    private final Systems systems;

    private final ObjectProvider<IdentityContent> identityContent;

    private final CompanyAccessGuard companyAccess;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final AgentMemory memory;

    ErasureService(ConversationPurge conversations, Conversations conversationInfo, RunPurge runs,
            RetentionStore store, HoldService holds, Principals principals, Systems systems,
            ObjectProvider<IdentityContent> identityContent, CompanyAccessGuard companyAccess, AuditLog auditLog,
            TimeSource time, AgentMemory memory) {
        this.conversations = conversations;
        this.conversationInfo = conversationInfo;
        this.runs = runs;
        this.store = store;
        this.holds = holds;
        this.principals = principals;
        this.systems = systems;
        this.identityContent = identityContent;
        this.companyAccess = companyAccess;
        this.auditLog = auditLog;
        this.time = time;
        this.memory = memory;
    }

    /**
     * What a person's erasure reaches: whether they left, the personal conversations it erases whole, the conversations
     * of others where it erases what they said (a member who left), and how many open ones it keeps.
     */
    private record PersonScope(UUID principalId, @Nullable UUID userId, boolean left, Set<UUID> targets,
            Set<UUID> authored, long openKept) {

        /** Every conversation it reaches, in the order of their ids: the lock order. */
        Set<UUID> all() {
            Set<UUID> all = new TreeSet<>(targets);
            all.addAll(authored);
            return all;
        }
    }

    @Transactional
    public Erasure erasePrincipal(UUID companyId, UUID principalId, @Nullable List<UUID> conversationIds) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        PersonScope scope = personScope(companyId, principalId, conversationIds);
        // A member who left: no system's last test names them any more. The systems' rows come before any
        // conversation, as a system's pause takes them (CONVENTIONS §10); a refusal below rolls it back.
        long systemTests = scope.left() ? systems.forgetTester(companyId, principalId) : 0;
        conversations.lock(scope.all());
        store.lockHolds(companyId, false);
        refuseHeld(companyId, scope.all(), Set.of(principalId));

        Instant now = time.now();
        ConversationPurge.Purged purged = ConversationPurge.Purged.NONE;
        long runTexts = 0;
        long runEvents = 0;
        for (UUID conversationId : new TreeSet<>(scope.targets())) {
            purged = purged.plus(conversations.erase(conversationId, now));
            runTexts += runs.purgeTextsOf(conversationId, now);
            runEvents += runs.purgeEventsOf(conversationId, now);
        }
        if (scope.left()) {
            purged = purged.plus(conversations.eraseAuthored(principalId, scope.userId(), scope.targets(), now));
        }
        // The notes the agents kept of what they learned in those conversations (ADR-0063).
        long memoryNotes = memory.eraseLearnedIn(companyId, scope.targets());
        // What they put away in their inbox (§15.7) goes with them; a member who left has none left.
        long dismissals = conversations.eraseDismissals(principalId);

        List<ErasureRemains> remains = personRemains(scope);
        ErasureCounts counts = counts(purged, runTexts, runEvents, other(memoryNotes, dismissals, systemTests));
        AuditRecord.Builder record = AuditRecord
            .company(companyId, AuditAction.PRINCIPAL_ERASED, CurrentActor.actor())
            .target("principal", principalId)
            .detail("principalId", principalId.toString())
            .detail("left", scope.left());
        auditCounts(record, counts, remains);
        auditLog.record(record.build());
        return new Erasure(now.atOffset(ZoneOffset.UTC), counts, remains);
    }

    /** The person's erasure's dry run: what it would erase now, what would remain, and the holds that would stop it. */
    @Transactional(readOnly = true)
    public ErasurePreview previewPrincipal(UUID companyId, UUID principalId, @Nullable List<UUID> conversationIds) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        PersonScope scope = personScope(companyId, principalId, conversationIds);
        List<RetentionStore.HoldRow> covering = covering(companyId, scope.all(), Set.of(principalId))
            .orElse(List.of());
        ConversationPurge.Purged purged = conversations.countErase(scope.targets());
        long runTexts = runs.countTextsOf(scope.targets());
        long runEvents = runs.countEventsOf(scope.targets());
        if (scope.left()) {
            purged = purged.plus(conversations.countAuthored(principalId, scope.userId(), scope.targets()));
        }
        long memoryNotes = memory.countLearnedIn(companyId, scope.targets());
        long dismissals = conversations.countDismissals(principalId);
        long systemTests = scope.left() ? systems.countTester(companyId, principalId) : 0;
        return new ErasurePreview(counts(purged, runTexts, runEvents, other(memoryNotes, dismissals, systemTests)),
                personRemains(scope), holds.dtos(companyId, covering));
    }

    /**
     * What a person's erasure reaches, or its refusal: {@code 404} for an unknown principal or a conversation named
     * that is not a personal conversation of theirs, {@code 409 ERASURE_NOT_ALLOWED} for a service identity or an open
     * conversation named for a member still in the company.
     */
    private PersonScope personScope(UUID companyId, UUID principalId, @Nullable List<UUID> conversationIds) {
        PrincipalInfo principal = principals.find(companyId, principalId)
            .orElseThrow(() -> ApiException.notFound("Principal not found"));
        if (!principal.member()) {
            throw ApiException.conflict(ProblemCode.ERASURE_NOT_ALLOWED,
                    "A service identity has no content of its own to erase");
        }
        boolean left = principal.status() == PrincipalStatus.LEFT;
        List<ConversationPurge.Personal> personal = conversations.personal(principalId);
        Set<UUID> targets = new HashSet<>();
        long openKept = 0;
        if (conversationIds != null && !conversationIds.isEmpty()) {
            Map<UUID, ConversationPurge.Personal> byId = new LinkedHashMap<>();
            personal.forEach(conversation -> byId.put(conversation.id(), conversation));
            for (UUID id : new TreeSet<>(conversationIds)) {
                ConversationPurge.Personal conversation = byId.get(id);
                if (conversation == null) {
                    throw ApiException.notFound("Conversation " + id + " is not a personal conversation of theirs");
                }
                if (conversation.open() && !left) {
                    throw ApiException.conflict(ProblemCode.ERASURE_NOT_ALLOWED, "The conversation "
                            + conversation.key() + " is open: its person ends it before it is erased",
                            Map.of("conversationId", id.toString()));
                }
                targets.add(conversation.id());
            }
        }
        else {
            for (ConversationPurge.Personal conversation : personal) {
                if (left || !conversation.open()) {
                    targets.add(conversation.id());
                }
                else {
                    openKept++;
                }
            }
        }
        Set<UUID> authored = left ? conversations.authoredIn(principalId, principal.userId()) : Set.of();
        return new PersonScope(principalId, principal.userId(), left, targets, authored, openKept);
    }

    /** What remains after a person's erasure, and who removes it. */
    private static List<ErasureRemains> personRemains(PersonScope scope) {
        List<ErasureRemains> remains = commonRemains();
        if (scope.openKept() > 0) {
            remains.add(new ErasureRemains("OPEN_CONVERSATIONS", "Their open personal conversations",
                    "They are erased once their person ends them, by erasing again").count(scope.openKept()));
        }
        if (scope.left() && !scope.authored().isEmpty()) {
            remains.add(new ErasureRemains("AGENT_TEXTS",
                    "The agents' messages, outputs and decisions' texts in the other conversations they took part in,"
                            + " which may quote their words",
                    "Retention, once those conversations ended (the content period), or their own erasure")
                .count((long) scope.authored().size()));
        }
        remains.add(new ErasureRemains("USER_ACCOUNT", "Their name and email: the instance's user account",
                "An instance admin, by deleting the user; the audit then names a deleted user"));
        return remains;
    }

    /** What an outside identity's erasure reaches: the identity, the modules that hold its words, their conversations. */
    private record IdentityScope(String identity, List<IdentityContent> modules, Set<UUID> reached) {
    }

    @Transactional
    public Erasure eraseIdentity(UUID companyId, UUID systemId, String externalId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        IdentityScope scope = identityScope(companyId, systemId, externalId);
        conversations.lock(scope.reached());
        store.lockHolds(companyId, false);
        refuseHeld(companyId, scope.reached(), Set.of());

        Instant now = time.now();
        Map<String, Long> other = new LinkedHashMap<>();
        Set<UUID> quoted = new HashSet<>();
        for (IdentityContent module : scope.modules()) {
            IdentityContent.Erased erased = module.erase(companyId, systemId, scope.identity(), now);
            erased.counts().forEach((kind, count) -> other.merge(kind, count, Long::sum));
            quoted.addAll(erased.runIds());
        }
        // The runs' tool calls' journal rows go with their events (package R).
        conversations.purgeToolCalls(quoted);
        long runEvents = runs.purgeEvents(quoted, now);

        List<ErasureRemains> remains = identityRemains();
        ErasureCounts counts = counts(ConversationPurge.Purged.NONE, 0, runEvents, other);
        AuditRecord.Builder record = AuditRecord
            .company(companyId, AuditAction.IDENTITY_ERASED, CurrentActor.actor())
            .target("system", systemId)
            .detail("systemId", systemId.toString())
            .detail("externalId", scope.identity());
        auditCounts(record, counts, remains);
        auditLog.record(record.build());
        return new Erasure(now.atOffset(ZoneOffset.UTC), counts, remains);
    }

    /** The identity's erasure's dry run: what it would erase now, what would remain, the holds that would stop it. */
    @Transactional(readOnly = true)
    public ErasurePreview previewIdentity(UUID companyId, UUID systemId, String externalId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        IdentityScope scope = identityScope(companyId, systemId, externalId);
        List<RetentionStore.HoldRow> covering = covering(companyId, scope.reached(), Set.of()).orElse(List.of());
        Map<String, Long> other = new LinkedHashMap<>();
        Set<UUID> quoted = new HashSet<>();
        for (IdentityContent module : scope.modules()) {
            IdentityContent.Erased counted = module.count(companyId, systemId, scope.identity());
            counted.counts().forEach((kind, count) -> other.merge(kind, count, Long::sum));
            quoted.addAll(counted.runIds());
        }
        return new ErasurePreview(counts(ConversationPurge.Purged.NONE, 0, runs.countEvents(quoted), other),
                identityRemains(), holds.dtos(companyId, covering));
    }

    /** What an outside identity's erasure reaches, or its refusal: {@code 404} for an unknown system. */
    private IdentityScope identityScope(UUID companyId, UUID systemId, String externalId) {
        systems.find(companyId, systemId).orElseThrow(() -> ApiException.notFound("System not found"));
        String identity = externalId.strip();
        if (identity.isEmpty() || identity.length() > 255) {
            throw ApiException.validation("externalId must be 1 to 255 characters");
        }
        List<IdentityContent> modules = identityContent.orderedStream().toList();
        Set<UUID> reached = new TreeSet<>();
        for (IdentityContent module : modules) {
            reached.addAll(module.conversationsOf(companyId, systemId, identity));
        }
        return new IdentityScope(identity, modules, reached);
    }

    /** What remains after an outside identity's erasure, and who removes it. */
    private static List<ErasureRemains> identityRemains() {
        List<ErasureRemains> remains = commonRemains();
        remains.add(new ErasureRemains("AGENT_TEXTS",
                "The agents' messages, outputs and decisions' texts, which may quote the words",
                "Retention, once each conversation ended (the content period), or a conversation's erasure"));
        return remains;
    }

    /** {@code 409 ON_HOLD} when an open hold covers one of the conversations or one of the principals. */
    private void refuseHeld(UUID companyId, Set<UUID> conversationIds, Set<UUID> principalIds) {
        Optional<List<RetentionStore.HoldRow>> covering = covering(companyId, conversationIds, principalIds);
        if (covering.isPresent()) {
            List<String> holdIds = covering.get().stream().map(hold -> hold.id().toString()).toList();
            throw ApiException.conflict(ProblemCode.ON_HOLD,
                    "An open legal hold covers what this erasure would remove; lift the hold first",
                    Map.of("holdIds", holdIds));
        }
    }

    /**
     * The open holds that cover one of the conversations or one of the principals (G16): those on the conversations
     * themselves, and on the persons through whom the others are held (a conversation's person, a trigger's
     * conversation's author). Empty when none covers any of it.
     */
    private Optional<List<RetentionStore.HoldRow>> covering(UUID companyId, Set<UUID> conversationIds,
            Set<UUID> principalIds) {
        ConversationPurge.Held held = store.held();
        Set<UUID> covered = conversations.covered(conversationIds, held);
        Set<UUID> people = new HashSet<>(principalIds);
        people.retainAll(held.principalIds());
        if (covered.isEmpty() && people.isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> onConversations = new HashSet<>(covered);
        onConversations.retainAll(held.conversationIds());
        for (UUID conversationId : covered) {
            if (!onConversations.contains(conversationId)) {
                conversationInfo.find(companyId, conversationId)
                    .ifPresent(conversation -> people.add(conversation.actingPrincipalId()));
            }
        }
        return Optional.of(store.openHoldsOn(onConversations, people));
    }

    /** What remains after any erasure, and who removes it. */
    private static List<ErasureRemains> commonRemains() {
        List<ErasureRemains> remains = new ArrayList<>();
        remains.add(new ErasureRemains("AUDIT", "The audit log, which holds ids only, never content",
                "Nobody: it is kept for the company's life"));
        remains.add(new ErasureRemains("TOMBSTONES",
                "Each conversation's tombstone (key, agent, place, dates, turns) and its runs' status, usage and spend",
                "Nobody: they hold no text"));
        remains.add(new ErasureRemains("RUNNER_SESSIONS",
                "The runtime's session home on the runner hosts that ran the turns, and its session bundle on the"
                        + " server, which a later turn of an open conversation resumes",
                "The hosts' operators, on the hosts; a session bundle goes when its conversation ends or takes no turn"
                        + " for the bundles' retention"));
        remains.add(new ErasureRemains("SOURCE_SYSTEM",
                "What the conversations posted or read at systems: comments, change requests, chat messages",
                "The company, at each system"));
        return remains;
    }

    private static ErasureCounts counts(ConversationPurge.Purged purged, long runTexts, long runEvents,
            Map<String, Long> other) {
        ErasureCounts counts = new ErasureCounts(purged.conversations(), purged.messages(), purged.decisions(),
                purged.outputs(), purged.files(), runTexts, runEvents);
        if (!other.isEmpty()) {
            counts.other(new LinkedHashMap<>(other));
        }
        return counts;
    }

    /** A person's erasure's counts from the other modules, each only when it removed anything. */
    private static Map<String, Long> other(long memoryNotes, long dismissals, long systemTests) {
        Map<String, Long> other = new LinkedHashMap<>();
        if (memoryNotes > 0) {
            other.put("memoryNotes", memoryNotes);
        }
        if (dismissals > 0) {
            other.put("inboxDismissals", dismissals);
        }
        if (systemTests > 0) {
            other.put("systemTests", systemTests);
        }
        return other;
    }

    private static void auditCounts(AuditRecord.Builder record, ErasureCounts counts, List<ErasureRemains> remains) {
        Map<String, Object> erased = new LinkedHashMap<>();
        erased.put("conversations", counts.getConversations());
        erased.put("messages", counts.getMessages());
        erased.put("decisions", counts.getDecisions());
        erased.put("outputs", counts.getOutputs());
        erased.put("files", counts.getFiles());
        erased.put("runs", counts.getRuns());
        erased.put("runEvents", counts.getRunEvents());
        if (counts.getOther() != null) {
            erased.put("other", new LinkedHashMap<>(counts.getOther()));
        }
        record.detail("erased", erased)
            .detail("remains", remains.stream().map(ErasureRemains::getWhat).toList());
    }
}
