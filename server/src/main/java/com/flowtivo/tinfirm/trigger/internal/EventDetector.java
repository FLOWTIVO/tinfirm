package com.flowtivo.tinfirm.trigger.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.trigger.TriggerEvent;

/**
 * The event detector (the package 4 contract, §5.5), pure: which events happened to a subject between the poll's
 * snapshot of it and the new read. Events are edges, never levels.
 * <ul>
 * <li><b>Tinfirm's own writes</b> raise nothing, by record and never by identity (the security review's row 10): a
 * comment whose id is in {@code system_write} is not new; a status, a label, a close and a pushed head commit that
 * Tinfirm recorded since the snapshot was read are no edge. A comment by the poll's own account that Tinfirm did not
 * write (Alice's own on her issue) is a person's.</li>
 * <li><b>Assigned and mentioned</b> mean the poll's account (T8): its identity at the system newly among the assignees,
 * its identity mentioned in the system's syntax.</li>
 * <li><b>Without a snapshot</b>: {@code CREATED} when created at or after the bound, then
 * {@code CHANGE_REQUEST_OPENED}, {@code LABEL_ADDED} per label and {@code ASSIGNED} when it was created assigned to the
 * account; never an edge that needs a snapshot.</li>
 * <li><b>With a snapshot</b>: {@code UPDATED} (the hash of title, description and type), {@code STATUS_ENTERED},
 * {@code LABEL_ADDED}, {@code LABEL_REMOVED}, {@code ASSIGNED}, {@code CHANGE_REQUEST_UPDATED} (a new head commit), and
 * {@code CLOSED} for a subject that was open; a closed subject fires nothing else.</li>
 * <li><b>New comments of people</b>, above the snapshot's last comment, or written at or after the bound without one:
 * {@code MENTIONED} when it mentions the account, else {@code COMMENTED}.</li>
 * </ul>
 */
final class EventDetector {

    private EventDetector() {
    }

    /**
     * The poll's account at the system (T8).
     *
     * @param externalId    its stable id there
     * @param externalLogin its login there, where it has one
     */
    record Identity(@Nullable String externalId, @Nullable String externalLogin) {

        boolean is(SystemPolls.@Nullable User user) {
            return user != null && user.is(externalId, externalLogin);
        }

        boolean among(List<SystemPolls.User> users) {
            return users.stream().anyMatch(this::is);
        }
    }

    /** What Tinfirm wrote to the subject, as {@code system_write} records it. */
    record OwnWrites(Set<String> commentIds, Set<String> statuses, boolean statusUnknown, Set<String> labelsAdded,
            Set<String> labelsRemoved, boolean closed, Set<String> pushedShas) {

        static final OwnWrites NONE = new OwnWrites(Set.of(), Set.of(), false, Set.of(), Set.of(), false, Set.of());

        OwnWrites {
            commentIds = Set.copyOf(commentIds);
            statuses = lower(statuses);
            labelsAdded = lower(labelsAdded);
            labelsRemoved = lower(labelsRemoved);
            pushedShas = Set.copyOf(pushedShas);
        }

        private static Set<String> lower(Set<String> names) {
            Set<String> lower = new LinkedHashSet<>();
            names.forEach(name -> lower.add(name.toLowerCase(Locale.ROOT)));
            return Set.copyOf(lower);
        }
    }

    /**
     * One event.
     *
     * @param value     the status entered, the label added or removed
     * @param changedAt when it happened at the system: the comment's time, else the subject's last change
     * @param actor     who did it, where the read says: a comment's author, a creation's author
     * @param comment   the comment of {@code COMMENTED} and {@code MENTIONED}
     */
    record Event(TriggerEvent event, @Nullable String value, Instant changedAt, SystemPolls.@Nullable User actor,
            SystemPolls.@Nullable Comment comment) {
    }

    /**
     * What the detector found.
     *
     * @param events      each once, in the order above
     * @param newComments the new comments of people, oldest first: they fire, and they are stored in the trigger
     *                    conversations open on the subject
     * @param firstRead   whether the poll had no snapshot of the subject
     */
    record Detection(List<Event> events, List<SystemPolls.Comment> newComments, boolean firstRead) {

        Detection {
            events = List.copyOf(events);
            newComments = List.copyOf(newComments);
        }
    }

    static Detection detect(SystemPolls.Subject subject, @Nullable SubjectSnapshot snapshot, Instant bound,
            Identity identity, OwnWrites own) {
        List<SystemPolls.Comment> comments = newComments(subject, snapshot, bound, own);
        List<Event> events = new ArrayList<>();
        if (!subject.open()) {
            if (snapshot != null && snapshot.open() && !own.closed()) {
                events.add(new Event(TriggerEvent.CLOSED, null, subject.updated(), null, null));
            }
            return new Detection(events, comments, snapshot == null);
        }
        if (snapshot == null) {
            if (!subject.created().isBefore(bound)) {
                events.add(new Event(TriggerEvent.CREATED, null, subject.created(), subject.author(), null));
                if (subject.kind() == SystemPolls.SubjectKind.CHANGE_REQUEST) {
                    events.add(new Event(TriggerEvent.CHANGE_REQUEST_OPENED, null, subject.created(), subject.author(),
                            null));
                }
                for (String label : distinct(subject.labels())) {
                    events.add(new Event(TriggerEvent.LABEL_ADDED, label, subject.created(), subject.author(), null));
                }
                if (identity.among(subject.assignees())) {
                    events.add(new Event(TriggerEvent.ASSIGNED, null, subject.created(), subject.author(), null));
                }
            }
        }
        else {
            Instant at = subject.updated();
            String hash = textHash(subject);
            if (snapshot.textHash() != null && !snapshot.textHash().equals(hash)) {
                events.add(new Event(TriggerEvent.UPDATED, null, at, null, null));
            }
            if (subject.status() != null && !subject.status().equalsIgnoreCase(Objects.toString(snapshot.status(), ""))
                    && !own.statusUnknown() && !own.statuses().contains(lower(subject.status()))) {
                events.add(new Event(TriggerEvent.STATUS_ENTERED, subject.status(), at, null, null));
            }
            Set<String> before = lowerSet(snapshot.labels());
            for (String label : distinct(subject.labels())) {
                if (!before.contains(lower(label)) && !own.labelsAdded().contains(lower(label))) {
                    events.add(new Event(TriggerEvent.LABEL_ADDED, label, at, null, null));
                }
            }
            Set<String> now = lowerSet(subject.labels());
            for (String label : distinct(snapshot.labels())) {
                if (!now.contains(lower(label)) && !own.labelsRemoved().contains(lower(label))) {
                    events.add(new Event(TriggerEvent.LABEL_REMOVED, label, at, null, null));
                }
            }
            if (identity.among(subject.assignees()) && !identity.among(snapshot.assignees())) {
                events.add(new Event(TriggerEvent.ASSIGNED, null, at, null, null));
            }
            if (subject.headSha() != null && snapshot.headSha() != null
                    && !subject.headSha().equals(snapshot.headSha())
                    && !own.pushedShas().contains(subject.headSha())) {
                events.add(new Event(TriggerEvent.CHANGE_REQUEST_UPDATED, null, at, null, null));
            }
        }
        for (SystemPolls.Comment comment : comments) {
            TriggerEvent event = comment.mentions(identity.externalId(), identity.externalLogin())
                    ? TriggerEvent.MENTIONED : TriggerEvent.COMMENTED;
            events.add(new Event(event, null, comment.created(), comment.author(), comment));
        }
        return new Detection(events, comments, snapshot == null);
    }

    /**
     * The new comments of people, oldest first: not written by Tinfirm, and above the snapshot's last comment, or,
     * without a snapshot, written at or after the bound (the earlier ones are history).
     */
    static List<SystemPolls.Comment> newComments(SystemPolls.Subject subject, @Nullable SubjectSnapshot snapshot,
            Instant bound, OwnWrites own) {
        List<SystemPolls.Comment> comments = new ArrayList<>();
        Long last = snapshot != null ? snapshot.lastCommentOrder() : null;
        for (SystemPolls.Comment comment : subject.comments()) {
            if (own.commentIds().contains(comment.id())) {
                continue;
            }
            boolean fresh = snapshot != null
                    ? last != null ? comment.order() > last : !comment.created().isBefore(snapshot.commentsFrom())
                    : !comment.created().isBefore(bound);
            if (fresh) {
                comments.add(comment);
            }
        }
        comments.sort((a, b) -> Long.compare(a.order(), b.order()));
        return comments;
    }

    /** SHA-256 of the title, the description and the type, separated by NUL. */
    static String textHash(SystemPolls.Subject subject) {
        return textHash(subject.title(), subject.description(), subject.type());
    }

    static String textHash(String title, @Nullable String body, @Nullable String type) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String text = title + "\u0000" + Objects.toString(body, "") + "\u0000" + Objects.toString(type, "");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** The names, each once ignoring case, in their order. */
    static List<String> distinct(List<String> names) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> distinct = new ArrayList<>();
        for (String name : names) {
            if (seen.add(lower(name))) {
                distinct.add(name);
            }
        }
        return distinct;
    }

    private static Set<String> lowerSet(List<String> names) {
        Set<String> lower = new LinkedHashSet<>();
        names.forEach(name -> lower.add(lower(name)));
        return lower;
    }

    static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
