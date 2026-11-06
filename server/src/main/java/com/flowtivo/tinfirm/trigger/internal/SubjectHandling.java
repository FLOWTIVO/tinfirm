package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.run.ConversationLock;
import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerRepeat;

/**
 * One subject of a poll's pass, in a transaction of its own (the package 4 contract, §5.4 step 4, §5.5, §5.6 and
 * §5.8): its snapshot row locked (inserted when the poll had none), Tinfirm's own writes looked up, the events detected,
 * the triggers evaluated, their rows locked in the order of their ids, the conversations open on the subject locked by
 * id, the firings fired, the new comments stored as {@code EXTERNAL} messages of the open conversations (the first one
 * answering their open questions, §11 point 4), the conversations of a closed subject ended, and the snapshot updated.
 * A failure rolls back this subject only.
 */
@Component
class SubjectHandling {

    private static final Logger log = LoggerFactory.getLogger(SubjectHandling.class);

    private final PollStore store;

    private final TriggerLocks locks;

    private final Firings firings;

    private final TriggerConversations conversations;

    private final ConversationLock conversationLock;

    SubjectHandling(PollStore store, TriggerLocks locks, Firings firings, TriggerConversations conversations,
            ConversationLock conversationLock) {
        this.store = store;
        this.locks = locks;
        this.firings = firings;
        this.conversations = conversations;
        this.conversationLock = conversationLock;
    }

    /**
     * What a pass knows when it handles a subject.
     *
     * @param triggers  the poll's triggers that may fire, in the order of their names
     * @param bound     the window's lower bound
     * @param readAt    when the page holding the subject was read
     * @param account   the poll's account as people know it (its login), for the requests
     */
    record Pass(UUID companyId, UUID pollId, UUID systemId, List<TriggerInfo> triggers,
            EventDetector.Identity identity, @Nullable String account, Instant bound, Instant readAt, Instant now,
            Duration catchUp, Duration overlap) {

        Pass {
            triggers = List.copyOf(triggers);
        }
    }

    /** What handling the subject did. */
    record Handled(int events, int fired, boolean changed) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Handled handle(Pass pass, SystemPolls.Subject subject) {
        UUID companyId = pass.companyId();
        Optional<PollStore.Creator> creator = store.creator(pass.systemId(), subject.key());
        SubjectSnapshot read = SubjectSnapshot.of(subject, null, creator.map(PollStore.Creator::depth).orElse(null),
                pass.readAt());
        SubjectSnapshot previous = store.lockOrInsert(companyId, pass.pollId(), read).orElse(null);
        if (previous != null && previous.sourceUpdatedAt() != null
                && !subject.updated().isAfter(previous.sourceUpdatedAt())) {
            // Read before a later read handled it, or unchanged: no edge.
            return new Handled(0, 0, false);
        }
        Instant writesSince = (previous != null ? previous.seenAt() : pass.bound()).minus(pass.overlap());
        EventDetector.OwnWrites own = store.ownWrites(pass.systemId(), subject.key(), subject.headSha(), writesSince);
        EventDetector.Detection detection = EventDetector.detect(subject, previous, pass.bound(), pass.identity(), own);
        boolean createdByTinfirm = creator.isPresent()
                || previous != null && previous.createdByTinfirmDepth() != null;
        TriggerEvaluator.Facts facts = TriggerEvaluator.Facts.of(subject, createdByTinfirm);
        Set<UUID> onceIds = new LinkedHashSet<>();
        pass.triggers().stream().filter(trigger -> trigger.repeat() == TriggerRepeat.ONCE)
            .forEach(trigger -> onceIds.add(trigger.id()));
        TriggerEvaluator.Outcome outcome = TriggerEvaluator.evaluate(pass.triggers(), subject, detection.events(),
                facts, store.onceFired(onceIds, subject.id()), pass.now(), pass.catchUp());

        // The lock order: the snapshot (held), the triggers by id, their conversations on the subject by id.
        Map<UUID, TriggerInfo> touched = new LinkedHashMap<>();
        outcome.fires().forEach(match -> touched.put(match.trigger().id(), match.trigger()));
        outcome.missed().forEach(trigger -> touched.put(trigger.id(), trigger));
        boolean closed = !subject.open() && previous != null && previous.open();
        boolean commented = !detection.newComments().isEmpty();
        if (closed || commented) {
            pass.triggers().forEach(trigger -> touched.put(trigger.id(), trigger));
        }
        Map<UUID, TriggerInfo> locked = new LinkedHashMap<>();
        for (UUID triggerId : new TreeSet<>(touched.keySet())) {
            locks.lock(companyId, triggerId).filter(TriggerInfo::live).ifPresent(trigger -> locked.put(triggerId,
                    trigger));
        }
        Set<UUID> conversationIds = new TreeSet<>();
        Map<UUID, UUID> openOf = new LinkedHashMap<>();
        for (TriggerInfo trigger : locked.values()) {
            conversations.openOnSubject(companyId, trigger.id(), subject.id()).ifPresent(conversation -> {
                conversationIds.add(conversation.id());
                openOf.put(trigger.id(), conversation.id());
            });
        }
        int depth = detection.firstRead() && creator.isPresent() ? creator.get().depth() + 1 : 0;
        if (depth > Firings.MAX_CHAIN && creator.get().conversationId() != null && !outcome.fires().isEmpty()) {
            conversationIds.add(creator.get().conversationId());
        }
        conversationIds.forEach(id -> conversationLock.lock(companyId, id));

        for (TriggerInfo trigger : outcome.missed()) {
            if (locked.containsKey(trigger.id())) {
                store.countMissed(trigger.id(), 1);
            }
        }
        int fired = 0;
        boolean chainStopped = false;
        Set<UUID> firedByClose = new LinkedHashSet<>();
        for (TriggerEvaluator.Match match : outcome.fires()) {
            TriggerInfo trigger = locked.get(match.trigger().id());
            if (trigger == null) {
                continue;
            }
            Firings.Fired result = firings.fire(new Firings.SubjectEvent(trigger, pass.systemId(), subject,
                    match.event(), depth, pass.account(), null, null));
            if (result.outcome() != null) {
                fired++;
                chainStopped |= result.outcome() == com.flowtivo.tinfirm.trigger.FiringOutcome.SKIPPED_CHAIN;
                if (match.event().event() == TriggerEvent.CLOSED && result.conversationId() != null) {
                    firedByClose.add(result.conversationId());
                }
            }
            else {
                log.debug("Trigger {} did not fire on {}: {}", trigger.id(), subject.key(), result.stopped());
            }
        }
        if (chainStopped && creator.isPresent() && creator.get().conversationId() != null) {
            conversations.post(companyId, creator.get().conversationId(), subject.key() + " was created by this "
                    + "conversation at the end of a chain of " + Firings.MAX_CHAIN + " triggered conversations, so it "
                    + "started no trigger.");
        }
        if (commented) {
            // The new comments in every trigger conversation open on the subject, oldest first: while questions are
            // open, the first answers them all (§5.8, §11 point 4).
            for (TriggerInfo trigger : locked.values()) {
                Optional<ConversationInfo> open = conversations.openOnSubject(companyId, trigger.id(), subject.id());
                if (open.isEmpty()) {
                    continue;
                }
                List<SystemPolls.Comment> comments = new ArrayList<>(detection.newComments());
                comments.sort(Comparator.comparingLong(SystemPolls.Comment::order));
                for (SystemPolls.Comment comment : comments) {
                    conversations.external(companyId, open.get().id(), Firings.external(comment, true));
                }
            }
        }
        if (closed) {
            // §5.6: the conversations the close did not fire end now; those it fired end once their turn ended.
            for (TriggerInfo trigger : locked.values()) {
                conversations.openOnSubject(companyId, trigger.id(), subject.id())
                    .filter(conversation -> !firedByClose.contains(conversation.id()))
                    .ifPresent(conversation -> conversations.end(companyId, conversation.id(),
                            ConversationInfo.EndedReason.SUBJECT_CLOSED, null));
            }
        }
        if (previous != null) {
            store.updateSnapshot(pass.pollId(), SubjectSnapshot.of(subject, previous,
                    creator.map(PollStore.Creator::depth).orElse(null), pass.readAt()));
        }
        return new Handled(detection.events().size(), fired, true);
    }
}
