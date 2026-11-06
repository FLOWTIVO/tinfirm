package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.trigger.ConditionGroup;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerRepeat;
import com.flowtivo.tinfirm.trigger.TriggerResults;
import com.flowtivo.tinfirm.trigger.TriggerSource;
import com.flowtivo.tinfirm.trigger.TriggerStatus;
import com.flowtivo.tinfirm.trigger.TriggerSubject;

/**
 * The event detector and the trigger evaluator as tables (the package 4 contract, §5.5, T8, T9, T16): per account,
 * assigned and mentioned to the poll's account and to someone else; a comment Tinfirm wrote, one by the account's own
 * person; Tinfirm's own status, labels, close and push; {@code authors TINFIRM}; {@code armed_at}; the catch-up;
 * {@code ONCE}; the scope and the subject kind; condition groups.
 */
class EventDetectorTest {

    static final Instant BOUND = Instant.parse("2026-10-12T09:00:00Z");

    static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    static final EventDetector.Identity ALICE = new EventDetector.Identity("key-alice", "alice");

    static final SystemPolls.User ALICE_USER = new SystemPolls.User("key-alice", "alice", "Alice");

    static final SystemPolls.User BOB_USER = new SystemPolls.User("key-bob", "bob", "Bob");

    static SystemPolls.Subject issue(String status, List<String> labels, List<SystemPolls.User> assignees,
            Instant created, Instant updated, List<SystemPolls.Comment> comments, boolean open) {
        return new SystemPolls.Subject("10001", "SHOP-1", SystemPolls.SubjectKind.ISSUE, "SHOP", null, "Checkout",
                "It fails.", status, "Bug", open, assignees, labels, created, updated, comments, comments.size(),
                BOB_USER, null);
    }

    static SystemPolls.Comment comment(long id, SystemPolls.User author, String body, Instant at, String... mentions) {
        return new SystemPolls.Comment(Long.toString(id), id, author, body, at, Set.of(mentions));
    }

    static SubjectSnapshot snapshot(SystemPolls.Subject subject, @Nullable Long lastComment) {
        return new SubjectSnapshot(subject.id(), subject.key(), subject.kind().name(), subject.open(),
                subject.status(), subject.type(), subject.labels(), subject.assignees(),
                EventDetector.textHash(subject), subject.headSha(), null,
                lastComment != null ? lastComment.toString() : null, null, subject.updated(), BOUND.plusSeconds(60));
    }

    static List<TriggerEvent> events(EventDetector.Detection detection) {
        return detection.events().stream().map(EventDetector.Event::event).toList();
    }

    @Test
    void aNewSubjectFiresWhatItWasCreatedWithAndAssignedMeansThePollsAccount() {
        Instant created = BOUND.plusSeconds(300);
        SystemPolls.Subject toAlice = issue("Open", List.of("ai", "AI"), List.of(ALICE_USER), created, created,
                List.of(), true);

        EventDetector.Detection detected = EventDetector.detect(toAlice, null, BOUND, ALICE,
                EventDetector.OwnWrites.NONE);
        assertThat(events(detected)).containsExactly(TriggerEvent.CREATED, TriggerEvent.LABEL_ADDED,
                TriggerEvent.ASSIGNED);
        assertThat(detected.firstRead()).isTrue();

        SystemPolls.Subject toBob = issue("Open", List.of(), List.of(BOB_USER), created, created, List.of(), true);
        assertThat(events(EventDetector.detect(toBob, null, BOUND, ALICE, EventDetector.OwnWrites.NONE)))
            .containsExactly(TriggerEvent.CREATED);
        // Created before the bound: no snapshot, no edge.
        SystemPolls.Subject old = issue("Open", List.of("ai"), List.of(ALICE_USER), BOUND.minusSeconds(60),
                created, List.of(), true);
        assertThat(events(EventDetector.detect(old, null, BOUND, ALICE, EventDetector.OwnWrites.NONE))).isEmpty();
    }

    @Test
    void edgesAgainstTheSnapshotAndTinfirmsOwnWritesAreNone() {
        Instant created = BOUND.minusSeconds(3600);
        SystemPolls.Subject before = issue("Open", List.of("ai", "blocked"), List.of(BOB_USER), created,
                BOUND.plusSeconds(10), List.of(), true);
        SubjectSnapshot snapshot = snapshot(before, null);
        SystemPolls.Subject after = new SystemPolls.Subject("10001", "SHOP-1", SystemPolls.SubjectKind.ISSUE, "SHOP",
                null, "Checkout fails", "It fails.", "In Progress", "Bug", true, List.of(BOB_USER, ALICE_USER),
                List.of("ai", "ready"), created, BOUND.plusSeconds(600), List.of(), 0, BOB_USER, null);

        assertThat(events(EventDetector.detect(after, snapshot, BOUND, ALICE, EventDetector.OwnWrites.NONE)))
            .containsExactly(TriggerEvent.UPDATED, TriggerEvent.STATUS_ENTERED, TriggerEvent.LABEL_ADDED,
                    TriggerEvent.LABEL_REMOVED, TriggerEvent.ASSIGNED);
        // The same changes written by Tinfirm (recorded in system_write) are no edge (T9).
        EventDetector.OwnWrites own = new EventDetector.OwnWrites(Set.of(), Set.of("in progress"), false,
                Set.of("READY"), Set.of("blocked"), false, Set.of());
        assertThat(events(EventDetector.detect(after, snapshot, BOUND, ALICE, own)))
            .containsExactly(TriggerEvent.UPDATED, TriggerEvent.ASSIGNED);
        // Assigned to someone else: no ASSIGNED for Alice's poll (T8).
        SystemPolls.Subject toCarol = new SystemPolls.Subject("10001", "SHOP-1", SystemPolls.SubjectKind.ISSUE,
                "SHOP", null, "Checkout", "It fails.", "Open", "Bug", true, List.of(BOB_USER,
                        new SystemPolls.User("key-carol", "carol", null)), List.of("ai", "blocked"), created,
                BOUND.plusSeconds(600), List.of(), 0, BOB_USER, null);
        assertThat(events(EventDetector.detect(toCarol, snapshot, BOUND, ALICE, EventDetector.OwnWrites.NONE)))
            .isEmpty();
    }

    @Test
    void commentsOfPeopleFireMentionedOrCommentedAndTinfirmsOwnCommentsNothing() {
        Instant created = BOUND.minusSeconds(3600);
        SystemPolls.Subject before = issue("Open", List.of(), List.of(), created, BOUND.plusSeconds(10),
                List.of(comment(100, BOB_USER, "old", BOUND.minusSeconds(60))), true);
        SubjectSnapshot snapshot = snapshot(before, 100L);
        List<SystemPolls.Comment> comments = List.of(
                comment(100, BOB_USER, "old", BOUND.minusSeconds(60)),
                comment(101, ALICE_USER, "Written by Tinfirm through Alice's account", BOUND.plusSeconds(100)),
                comment(102, ALICE_USER, "Alice's own words", BOUND.plusSeconds(200)),
                comment(103, BOB_USER, "@alice please look", BOUND.plusSeconds(300), "alice"),
                comment(104, BOB_USER, "@carol please look", BOUND.plusSeconds(400), "carol"));
        SystemPolls.Subject after = issue("Open", List.of(), List.of(), created, BOUND.plusSeconds(400), comments,
                true);
        EventDetector.OwnWrites own = new EventDetector.OwnWrites(Set.of("101"), Set.of(), false, Set.of(), Set.of(),
                false, Set.of());

        EventDetector.Detection detected = EventDetector.detect(after, snapshot, BOUND, ALICE, own);
        assertThat(events(detected)).containsExactly(TriggerEvent.COMMENTED, TriggerEvent.MENTIONED,
                TriggerEvent.COMMENTED);
        assertThat(detected.newComments()).extracting(SystemPolls.Comment::id).containsExactly("102", "103", "104");
    }

    @Test
    void aClosedSubjectFiresOnlyClosedAndAPushOfTinfirmsIsNoUpdate() {
        Instant created = BOUND.minusSeconds(3600);
        SystemPolls.Subject open = issue("Open", List.of(), List.of(), created, BOUND.plusSeconds(10), List.of(),
                true);
        SystemPolls.Subject closed = issue("Done", List.of("x"), List.of(), created, BOUND.plusSeconds(60), List.of(),
                false);
        assertThat(events(EventDetector.detect(closed, snapshot(open, null), BOUND, ALICE,
                EventDetector.OwnWrites.NONE))).containsExactly(TriggerEvent.CLOSED);
        EventDetector.OwnWrites closedByTinfirm = new EventDetector.OwnWrites(Set.of(), Set.of(), false, Set.of(),
                Set.of(), true, Set.of());
        assertThat(events(EventDetector.detect(closed, snapshot(open, null), BOUND, ALICE, closedByTinfirm)))
            .isEmpty();

        SystemPolls.Subject pull = new SystemPolls.Subject("9", "acme/shop#7", SystemPolls.SubjectKind.CHANGE_REQUEST,
                "acme/shop", null, "A change", null, null, null, true, List.of(), List.of(), created,
                BOUND.plusSeconds(10), List.of(), 0, BOB_USER, "aaa");
        SystemPolls.Subject pushed = pull.withHeadSha("bbb");
        assertThat(events(EventDetector.detect(pushed, snapshot(pull, null), BOUND, ALICE,
                EventDetector.OwnWrites.NONE))).containsExactly(TriggerEvent.CHANGE_REQUEST_UPDATED);
        EventDetector.OwnWrites push = new EventDetector.OwnWrites(Set.of(), Set.of(), false, Set.of(), Set.of(),
                false, Set.of("bbb"));
        assertThat(events(EventDetector.detect(pushed, snapshot(pull, null), BOUND, ALICE, push))).isEmpty();
        // A new pull request: opened.
        SystemPolls.Subject fresh = new SystemPolls.Subject("10", "acme/shop#8", SystemPolls.SubjectKind.CHANGE_REQUEST,
                "acme/shop", null, "New", null, null, null, true, List.of(), List.of(), BOUND.plusSeconds(5),
                BOUND.plusSeconds(5), List.of(), 0, BOB_USER, "ccc");
        assertThat(events(EventDetector.detect(fresh, null, BOUND, ALICE, EventDetector.OwnWrites.NONE)))
            .containsExactly(TriggerEvent.CREATED, TriggerEvent.CHANGE_REQUEST_OPENED);
    }

    // ---- the evaluator -----------------------------------------------------------------------------------------------

    static TriggerInfo trigger(String name, List<TriggerEventSpec> events, TriggerSubject subject,
            @Nullable String scope, List<ConditionGroup> conditions, TriggerRepeat repeat, Instant armedAt) {
        return new TriggerInfo(UUID.randomUUID(), UUID.randomUUID(), name, TriggerSource.TRACKER, UUID.randomUUID(),
                events, subject, scope, conditions, repeat, null, UUID.randomUUID(), "Do it.", UUID.randomUUID(), null,
                UUID.randomUUID(), TriggerResults.DEFAULT, 30, TriggerStatus.ACTIVE, (PausedReason) null, null, null,
                null, armedAt, 0, 0, null, null, null, BOUND, BOUND);
    }

    static ConditionGroup group(List<String> labels, List<String> notLabels, ConditionGroup.@Nullable Authors authors) {
        return new ConditionGroup(List.of(), List.of(), labels, notLabels, List.of(), List.of(), authors);
    }

    @Test
    void theEvaluatorTakesEachTriggersFirstEventUnderItsConditionsScopeKindArmingAndCatchUp() {
        Instant created = BOUND.plusSeconds(300);
        SystemPolls.Subject subject = issue("Open", List.of("ai"), List.of(ALICE_USER), created, created, List.of(),
                true);
        List<EventDetector.Event> detected = EventDetector.detect(subject, null, BOUND, ALICE,
                EventDetector.OwnWrites.NONE).events();
        TriggerEvaluator.Facts facts = TriggerEvaluator.Facts.of(subject, false);
        TriggerInfo assigned = trigger("a assigned", List.of(new TriggerEventSpec(TriggerEvent.ASSIGNED, null),
                new TriggerEventSpec(TriggerEvent.CREATED, null)), TriggerSubject.ISSUE, "SHOP", List.of(),
                TriggerRepeat.EVERY_TIME, BOUND);
        TriggerInfo labelled = trigger("b label", List.of(new TriggerEventSpec(TriggerEvent.LABEL_ADDED, "AI")),
                TriggerSubject.ANY, null, List.of(group(List.of(), List.of("blocked"), null)), TriggerRepeat.ONCE,
                BOUND);
        TriggerInfo otherScope = trigger("c other", List.of(new TriggerEventSpec(TriggerEvent.CREATED, null)),
                TriggerSubject.ANY, "WEB", List.of(), TriggerRepeat.EVERY_TIME, BOUND);
        TriggerInfo changeRequests = trigger("d prs", List.of(new TriggerEventSpec(TriggerEvent.CREATED, null)),
                TriggerSubject.CHANGE_REQUEST, null, List.of(), TriggerRepeat.EVERY_TIME, BOUND);
        TriggerInfo armedLater = trigger("e armed", List.of(new TriggerEventSpec(TriggerEvent.CREATED, null)),
                TriggerSubject.ANY, null, List.of(), TriggerRepeat.EVERY_TIME, created.plusSeconds(1));
        TriggerInfo tinfirmOnly = trigger("f tinfirm", List.of(new TriggerEventSpec(TriggerEvent.CREATED, null)),
                TriggerSubject.ANY, null, List.of(group(List.of(), List.of(), ConditionGroup.Authors.TINFIRM)),
                TriggerRepeat.EVERY_TIME, BOUND);

        TriggerEvaluator.Outcome outcome = TriggerEvaluator.evaluate(List.of(assigned, labelled, otherScope,
                changeRequests, armedLater, tinfirmOnly), subject, detected, facts, Set.of(), NOW,
                Duration.ofHours(24));
        assertThat(outcome.fires()).extracting(match -> match.trigger().name() + ":" + match.event().event())
            .containsExactly("a assigned:ASSIGNED", "b label:LABEL_ADDED");
        // ONCE fired before: no second firing on the subject (T10).
        assertThat(TriggerEvaluator.evaluate(List.of(labelled), subject, detected, facts, Set.of(labelled.id()), NOW,
                Duration.ofHours(24)).fires()).isEmpty();
        // A subject Tinfirm created: authors TINFIRM holds for its events, never for a comment's.
        assertThat(TriggerEvaluator.evaluate(List.of(tinfirmOnly), subject, detected,
                TriggerEvaluator.Facts.of(subject, true), Set.of(), NOW, Duration.ofHours(24)).fires()).hasSize(1);
        // Read later than the catch-up: missed, not fired.
        TriggerEvaluator.Outcome late = TriggerEvaluator.evaluate(List.of(assigned), subject, detected, facts,
                Set.of(), created.plus(Duration.ofHours(25)), Duration.ofHours(24));
        assertThat(late.fires()).isEmpty();
        assertThat(late.missed()).containsExactly(assigned);
        assertThat(TriggerEvaluator.conditionsHold(List.of(group(List.of(), List.of(), ConditionGroup.Authors
                .TINFIRM)), TriggerEvent.COMMENTED, TriggerEvaluator.Facts.of(subject, true))).isFalse();
    }
}
