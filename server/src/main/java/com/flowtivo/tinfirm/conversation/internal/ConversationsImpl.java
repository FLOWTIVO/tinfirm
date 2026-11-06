package com.flowtivo.tinfirm.conversation.internal;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.conversation.TriggerAudience;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunVisibility;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * {@link Conversations} and the run module's {@link RunVisibility} (the package 3 contract, §5.10, I15, I17): a
 * personal conversation, with its messages, runs, run events, decisions, outputs, attachments and bindings, is seen by
 * its person and by OWNER in full (an instance admin acting in the company too, audited by the access guard); an ADMIN
 * who is not OWNER does not see it, nor does anyone else, and to them it is {@code 404}. A run follows its
 * conversation: its person and OWNER see and cancel it. A read of the Runs API by OWNER of another person's run is
 * audited ({@link #read}, {@link OwnerReads}).
 * <p>
 * A trigger's conversation (the package 4 contract, §5.13, T12, T15) is seen by its trigger's audience (its author, its
 * team, OWNER and ADMIN) and by the author it started with; its people, who answer, cancel, retry and end it, are its
 * trigger's author and team ({@link TriggerAudience#acts}), OWNER and ADMIN only as one of them. It has no person, so
 * no read of it is OWNER's read of another person's conversation: nothing of it is recorded ({@link OwnerReads}).
 */
@Component
class ConversationsImpl implements Conversations, RunVisibility {

    private final ConversationStore store;

    private final Principals principals;

    private final OwnerReads ownerReads;

    private final TriggerPorts triggers;

    ConversationsImpl(ConversationStore store, Principals principals, OwnerReads ownerReads, TriggerPorts triggers) {
        this.store = store;
        this.principals = principals;
        this.ownerReads = ownerReads;
        this.triggers = triggers;
    }

    /**
     * Who asks about conversations.
     *
     * @param principalId their member's principal; null for an instance admin who is not a member
     * @param owner       whether they see every conversation in full: OWNER, or an instance admin
     * @param triggers    the triggers whose conversations they see (their audience, §5.13)
     * @param acting      the triggers in whose conversations they act (their author or team, T12)
     */
    record Caller(UUID userId, @Nullable UUID principalId, boolean owner, TriggerAudience.Seen triggers,
            TriggerAudience.Seen acting) {

        Caller(UUID userId, @Nullable UUID principalId, boolean owner) {
            this(userId, principalId, owner, TriggerAudience.Seen.NONE, TriggerAudience.Seen.NONE);
        }

        /** The caller's principal; 404 for a caller without one (an instance admin who is not a member). */
        UUID requirePrincipal() {
            if (principalId == null) {
                throw ApiException.notFound("You are not a member of this company");
            }
            return principalId;
        }

        /** Whether they act as the conversation's people: its person, or its trigger's author or team (T12). */
        boolean isPerson(ConversationInfo conversation) {
            if (conversation.triggered()) {
                return acting.sees(conversation.triggerId());
            }
            return principalId != null && principalId.equals(conversation.personPrincipalId());
        }

        boolean sees(ConversationInfo conversation) {
            if (conversation.triggered()) {
                return triggers.sees(conversation.triggerId())
                        || principalId != null && principalId.equals(conversation.authorPrincipalId());
            }
            return owner || isPerson(conversation);
        }

        /**
         * Whether they read the person's conversation as OWNER (or an instance admin), not as its person; never for a
         * trigger's conversation (no person, null), which its audience reads by right (the package 4 contract, §5.13).
         */
        boolean readsAsOwner(@Nullable UUID personPrincipalId) {
            return owner && personPrincipalId != null && !personPrincipalId.equals(principalId);
        }

        /** In the app as {@link #sees(ConversationInfo)}; through a client only as its person (G9). */
        boolean sees(ConversationInfo conversation, ConversationView view) {
            return view == ConversationView.CLIENT ? isPerson(conversation) : sees(conversation);
        }
    }

    /**
     * Whether the caller sees every conversation in full: OWNER only (Part 8). An instance admin who is not a member has
     * ADMIN's view under break-glass, and another person's personal conversation is 404 to them (package 6, §5.10).
     */
    static boolean seesEvery(CompanyAccess access) {
        return access.role() == CompanyRole.OWNER;
    }

    static Caller caller(CompanyAccess access, UUID userId, Principals principals) {
        UUID principalId = principals.ofMember(access.companyId(), userId).map(PrincipalInfo::id).orElse(null);
        return new Caller(userId, principalId, seesEvery(access));
    }

    /** The caller of the current request, authorised for the company. */
    Caller current(CompanyAccess access) {
        return caller(access, CurrentActor.requireUser().id());
    }

    Caller caller(CompanyAccess access, UUID userId) {
        UUID principalId = principals.ofMember(access.companyId(), userId).map(PrincipalInfo::id).orElse(null);
        return new Caller(userId, principalId, seesEvery(access), triggers.seenBy(access, userId),
                triggers.actsOn(access, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationInfo> find(UUID companyId, UUID conversationId) {
        return store.find(companyId, conversationId).map(ConversationStore.ConversationRow::info);
    }

    @Override
    public Optional<UUID> idOfKey(UUID companyId, String key) {
        return store.idOfKey(companyId, key.strip());
    }

    @Override
    public boolean maySee(CompanyAccess access, UUID userId, ConversationInfo conversation) {
        return caller(access, userId).sees(conversation);
    }

    @Override
    public boolean maySee(CompanyAccess access, UUID userId, ConversationInfo conversation, ConversationView view) {
        return caller(access, userId).sees(conversation, view);
    }

    @Override
    public boolean isPerson(CompanyAccess access, UUID userId, ConversationInfo conversation) {
        return caller(access, userId).isPerson(conversation);
    }

    // ---- RunVisibility ---------------------------------------------------------------------------------------------

    @Override
    public boolean maySee(CompanyAccess access, UUID userId, RunInfo run) {
        return store.find(access.companyId(), run.conversationId())
            .map(row -> maySee(access, userId, row.info()))
            .orElse(false);
    }

    @Override
    public boolean read(CompanyAccess access, UUID userId, RunInfo run, Read what) {
        Optional<ConversationInfo> conversation = store.find(access.companyId(), run.conversationId())
            .map(ConversationStore.ConversationRow::info);
        if (conversation.isEmpty()) {
            return false;
        }
        Caller caller = caller(access, userId);
        if (!caller.sees(conversation.get())) {
            return false;
        }
        ownerReads.read(access.companyId(), caller, conversation.get(), switch (what) {
            case RUN -> OwnerReads.What.RUNS;
            case EVENTS -> OwnerReads.What.RUN_EVENTS;
            case STREAM -> OwnerReads.What.RUN_STREAM;
        });
        return true;
    }

    @Override
    public boolean mayCancel(CompanyAccess access, UUID userId, RunInfo run) {
        return maySee(access, userId, run);
    }
}
