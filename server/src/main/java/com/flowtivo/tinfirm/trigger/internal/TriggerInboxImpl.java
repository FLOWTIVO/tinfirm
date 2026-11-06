package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveAgent;
import com.flowtivo.tinfirm.agent.TriggerSettings;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.conversation.InboxItems;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;
import com.flowtivo.tinfirm.shared.api.model.InboxTrigger;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.WaitingReason;

/**
 * The inbox's {@code TRIGGER} items, through the conversation module's port {@link InboxItems} (the package 4
 * contract, §5.13, items 4 and 5, with §11): what a caller's triggers need of them, longest waiting first. Each
 * notice's key is the trigger, the reason and the state that raised it (when it paused or began to wait, the agent's
 * version, the latest write-back dropped or firing skipped, the admin's change, the poll and when it began to wait), so
 * that a dismissed notice comes back when the trigger's state changes again (CONVENTIONS §15.7); one the caller is the
 * one to act on (a widening to accept, a trigger to reassign, a broken account to reconnect) is not dismissible.
 * <ul>
 * <li>Its author: reassigned to them and waiting for their acceptance ({@code TO_ACCEPT}, the item
 * {@code TRIGGER_REASSIGNED_TO_YOU}, which takes the place of its pause and of the admin's change, H2); paused or waiting
 * with the reason (but {@code AUTHOR_LEFT}), a widening of the agent to accept,
 * write-backs dropped in the last 7 days, firings skipped by the chain or the rate in the last hour, an admin's change
 * on their behalf in the last 7 days (a credential lent to its service account in place of the one its row named
 * included, {@link TriggerLendings}).</li>
 * <li>Its team: firings skipped in the last hour, and a pause for a broken account ({@code ACCOUNT_BROKEN}).</li>
 * <li>Its author and its team: notes its conversations proposed for its memory, to review ({@code MEMORY_REVIEW},
 * ADR-0063).</li>
 * <li>Admins: triggers paused {@code AUTHOR_LEFT}, to reassign; triggers paused for a service identity's broken
 * account; polls waiting on an account or a suspended principal.</li>
 * <li>Whoever lent a credential to a service account, or OWNER and ADMIN when that was not recorded or they are no
 * member any more: a trigger on a source anyone may write to that acts with it, the first time, for 30 days
 * ({@code CREDENTIAL_EXPOSED_TO_OPEN_SOURCE}, {@link TriggerExposures}; D2, the owner's decision Q3).</li>
 * </ul>
 */
@Component
class TriggerInboxImpl implements InboxItems {

    private static final Duration WEEK = Duration.ofDays(7);

    private static final Duration HOUR = Duration.ofHours(1);

    private static final Duration MONTH = Duration.ofDays(30);

    private final TriggerStore store;

    private final TriggerConversations conversations;

    private final TriggerSettings triggerSettings;

    private final Agents agents;

    private final Principals principals;

    private final GroupMembership groups;

    private final TimeSource time;

    private final AgentMemory memory;

    private final Accounts accounts;

    private final Systems systems;

    TriggerInboxImpl(TriggerStore store, TriggerConversations conversations, TriggerSettings triggerSettings,
            Agents agents, Principals principals, GroupMembership groups, TimeSource time, AgentMemory memory,
            Accounts accounts, Systems systems) {
        this.accounts = accounts;
        this.systems = systems;
        this.store = store;
        this.conversations = conversations;
        this.triggerSettings = triggerSettings;
        this.agents = agents;
        this.principals = principals;
        this.groups = groups;
        this.time = time;
        this.memory = memory;
    }

    /**
     * A trigger that needs the caller.
     *
     * @param reason    {@code PAUSED} and {@code WAITING} (with the reason as {@code detail}), {@code WIDENING},
     *                  {@code WRITE_BACK_DROPPED}, {@code FIRINGS_SKIPPED}, {@code CHANGED_BY_ADMIN} (the author's);
     *                  {@code MEMORY_REVIEW} (the author's and the team's);
     *                  {@code TO_REASSIGN} and {@code POLL_WAITING} (admins'); {@code TRIGGER_REASSIGNED_TO_YOU} (the new
     *                  author's); {@code CREDENTIAL_EXPOSED_TO_OPEN_SOURCE} (the lender's, or the admins')
     * @param count     how many, where it counts: write-backs dropped, firings skipped, notes to review
     * @param condition the state that raised it: the last parts of its notice's key
     */
    record Need(Instant since, UUID triggerId, String name, String reason, @Nullable String detail,
            @Nullable Integer count, List<@Nullable Object> condition) {

        InboxItems.Item item() {
            List<@Nullable Object> parts = new ArrayList<>(List.of(triggerId, reason));
            parts.addAll(condition);
            return new InboxItems.Item(since, new InboxItem(InboxItemKind.TRIGGER, since.atOffset(ZoneOffset.UTC))
                .trigger(new InboxTrigger(triggerId, name, InboxTrigger.ReasonEnum.fromValue(reason))
                    .detail(detail)
                    .count(count))
                .noticeKey(InboxItems.key(InboxItemKind.TRIGGER, parts.toArray()))
                .dismissible(!callerActs()));
        }

        /**
         * Whether the caller is the one to act (the web's orange): a widening to accept, a trigger to reassign, a
         * broken account to reconnect, memory notes to review. Such a notice leaves once handled and is not dismissed.
         */
        boolean callerActs() {
            return switch (reason) {
                case "WIDENING", "TO_REASSIGN", "MEMORY_REVIEW", "TRIGGER_REASSIGNED_TO_YOU" -> true;
                case "PAUSED" -> PausedReason.ACCOUNT_BROKEN.name().equals(detail);
                case "WAITING" -> WaitingReason.RECONNECT_YOURS.name().equals(detail);
                default -> false;
            };
        }
    }

    @Override
    public List<InboxItems.Item> itemsFor(InboxItems.Caller caller, int limit) {
        return needs(caller, limit).stream().map(Need::item).toList();
    }

    /** The caller's needs, at most {@code limit}, longest waiting first. */
    List<Need> needs(InboxItems.Caller caller, int limit) {
        UUID companyId = caller.companyId();
        UUID userId = caller.userId();
        Instant now = time.now();
        List<Need> items = new ArrayList<>();
        UUID principalId = caller.principalId();
        Set<UUID> authored = new HashSet<>();
        if (principalId != null) {
            List<TriggerStore.AdminChange> changes = store.adminChanges(companyId, principalId, now.minus(WEEK));
            Map<UUID, String> admins = principals.displayNames(companyId, changes.stream()
                .map(TriggerStore.AdminChange::by).filter(by -> by != null).toList());
            Map<UUID, TriggerStore.AdminChange> changeOf = new HashMap<>();
            changes.forEach(change -> changeOf.put(change.triggerId(), change));
            Set<UUID> toAccept = new HashSet<>();
            Map<UUID, TriggerInfo> audience = new LinkedHashMap<>();
            for (TriggerInfo trigger : store.liveOfAuthorInfo(companyId, principalId)) {
                authored.add(trigger.id());
                audience.put(trigger.id(), trigger);
                if (trigger.paused() && trigger.pausedReason() == PausedReason.TO_ACCEPT) {
                    // H2: reassigned to the caller, it waits for them; the admin's note is this item's detail.
                    toAccept.add(trigger.id());
                    TriggerStore.AdminChange change = changeOf.get(trigger.id());
                    String admin = change != null && change.by() != null
                            ? admins.getOrDefault(change.by(), "An admin") : "An admin";
                    items.add(new Need(since(trigger), trigger.id(), trigger.name(), "TRIGGER_REASSIGNED_TO_YOU",
                            admin + " reassigned the trigger " + trigger.name() + " to you: accept its accounts, its "
                                    + "payer and its open events to resume it", null, List.of(since(trigger))));
                    continue;
                }
                author(trigger, now, items);
            }
            for (TriggerInfo trigger : store.liveOfTeams(companyId, groups.groupsOf(companyId, userId))) {
                if (!authored.contains(trigger.id())) {
                    audience.put(trigger.id(), trigger);
                    team(trigger, now, items);
                }
            }
            // Notes its conversations proposed for its memory, which its author and team review (ADR-0063).
            memory.proposalsOf(companyId, audience.keySet()).forEach((triggerId, waiting) -> items.add(new Need(
                    waiting.since(), triggerId, audience.get(triggerId).name(), "MEMORY_REVIEW", null,
                    waiting.count(), List.of(waiting.since()))));
            for (TriggerStore.AdminChange change : changes) {
                if (toAccept.contains(change.triggerId())) {
                    continue;
                }
                String admin = change.by() != null ? admins.getOrDefault(change.by(), "An admin") : "An admin";
                items.add(new Need(change.at(), change.triggerId(), change.name(), "CHANGED_BY_ADMIN",
                        admin + " changed your trigger " + change.name() + ": " + change.what(), null,
                        List.of(change.at())));
            }
        }
        if (caller.admin()) {
            for (TriggerInfo trigger : store.livePaused(companyId, PausedReason.AUTHOR_LEFT)) {
                items.add(new Need(since(trigger), trigger.id(), trigger.name(), "TO_REASSIGN",
                        "Its author left: reassign it to someone who uses the agent", null, List.of(since(trigger))));
            }
            for (TriggerInfo trigger : store.livePaused(companyId, PausedReason.ACCOUNT_BROKEN)) {
                if (!authored.contains(trigger.id()) && serviceRow(trigger)) {
                    items.add(paused(trigger));
                }
            }
            for (TriggerStore.WaitingPoll poll : store.waitingPolls(companyId, limit)) {
                for (TriggerInfo trigger : onPoll(companyId, poll)) {
                    items.add(new Need(poll.since(), trigger.id(), trigger.name(), "POLL_WAITING",
                            "Its poll of the account " + poll.accountId() + " waits", null,
                            List.of(poll.id(), poll.since())));
                }
            }
        }
        exposed(caller, now, limit, items);
        items.sort(Comparator.comparing(Need::since));
        return items.size() > limit ? List.copyOf(items.subList(0, limit)) : items;
    }

    /**
     * D2, Q3: the credentials triggers open to anyone exposed in the last 30 days, for whoever lent each, or for OWNER
     * and ADMIN when that was not recorded or the lender is no member any more. Keyed by the trigger, the credential and
     * when it was recorded, so that a dismissal hides it for good.
     */
    private void exposed(InboxItems.Caller caller, Instant now, int limit, List<Need> items) {
        UUID companyId = caller.companyId();
        // The audience is the query's, so that the limit never hides the caller's own behind others'.
        for (TriggerStore.Exposure exposure : store.exposuresSince(companyId, caller.userId(), caller.admin(),
                now.minus(MONTH), limit)) {
            String credential = accounts.find(companyId, exposure.accountId()).map(AccountInfo::name)
                .orElse("a credential");
            String service = principals.displayNames(companyId, List.of(exposure.servicePrincipalId()))
                .getOrDefault(exposure.servicePrincipalId(), "a service account");
            String source = exposure.systemId() != null ? systems.find(companyId, exposure.systemId())
                .map(SystemInfo::name).orElse("its system") : "its system";
            items.add(new Need(exposure.at(), exposure.triggerId(), exposure.triggerName(),
                    "CREDENTIAL_EXPOSED_TO_OPEN_SOURCE", "The trigger " + exposure.triggerName() + " acts with the "
                            + "credential " + credential + " lent to " + service + ", and anyone who may write to "
                            + source + " (" + exposure.source().name() + ") drives it", null,
                    List.of(exposure.accountId(), exposure.at())));
        }
    }

    private void author(TriggerInfo trigger, Instant now, List<Need> items) {
        UUID companyId = trigger.companyId();
        if (trigger.paused() && trigger.pausedReason() != PausedReason.AUTHOR_LEFT) {
            items.add(paused(trigger));
        }
        else if (trigger.waitingReason() != null) {
            Instant since = trigger.waitingSince() != null ? trigger.waitingSince() : trigger.updatedAt();
            items.add(new Need(since, trigger.id(), trigger.name(), "WAITING", trigger.waitingReason().name(), null,
                    List.of(trigger.waitingReason().name(), since)));
        }
        AgentInfo agent = agents.find(companyId, trigger.agentId()).filter(AgentInfo::live).orElse(null);
        if (agent != null) {
            int accepted = triggerSettings.acceptedVersion(companyId, trigger.id());
            // A newer version that widens nothing (a description, or a narrowing) waits for no acceptance.
            if (agent.version() > accepted && agents.effective(companyId, agent.id(), accepted)
                .filter(EffectiveAgent::wideningPending)
                .isPresent()) {
                items.add(new Need(trigger.updatedAt(), trigger.id(), trigger.name(), "WIDENING",
                        "Version " + agent.version() + " of " + agent.name() + " waits for your acceptance", null,
                        List.of(agent.version())));
            }
        }
        TriggerStore.Recent dropped = store.droppedSince(companyId, conversations.allOf(companyId, trigger.id()),
                now.minus(WEEK));
        if (dropped.count() > 0) {
            // A new drop is another key; one that leaves the week is not.
            items.add(new Need(now.minus(WEEK), trigger.id(), trigger.name(), "WRITE_BACK_DROPPED", null,
                    dropped.count(), latest(dropped)));
        }
        skipped(trigger, now, items);
    }

    private void team(TriggerInfo trigger, Instant now, List<Need> items) {
        if (trigger.paused() && trigger.pausedReason() == PausedReason.ACCOUNT_BROKEN) {
            items.add(paused(trigger));
        }
        skipped(trigger, now, items);
    }

    /** A paused trigger, with its reason: another pause is another key. */
    private static Need paused(TriggerInfo trigger) {
        String reason = trigger.pausedReason() != null ? trigger.pausedReason().name() : null;
        return new Need(since(trigger), trigger.id(), trigger.name(), "PAUSED", reason, null,
                Arrays.asList(reason, since(trigger)));
    }

    /** Firings skipped by the chain or the rate in the last hour: one item an hour (§11, open point 15). */
    private void skipped(TriggerInfo trigger, Instant now, List<Need> items) {
        TriggerStore.Recent skipped = store.skippedSince(trigger.companyId(), trigger.id(), now.minus(HOUR));
        if (skipped.count() > 0) {
            // A firing skipped after the dismissal is another key.
            items.add(new Need(now.minus(HOUR), trigger.id(), trigger.name(), "FIRINGS_SKIPPED", null,
                    skipped.count(), latest(skipped)));
        }
    }

    /** The latest of what was counted, the last part of its notice's key. */
    private static List<@Nullable Object> latest(TriggerStore.Recent recent) {
        return Arrays.asList(recent.latest());
    }

    private boolean serviceRow(TriggerInfo trigger) {
        List<TriggerAccount> rows = store.accounts(trigger.companyId(), trigger.id());
        Map<UUID, PrincipalInfo> holders = principals.findAll(trigger.companyId(),
                rows.stream().map(TriggerAccount::holderPrincipalId).toList());
        return holders.values().stream().anyMatch(holder -> holder.kind() == PrincipalKind.SERVICE);
    }

    private List<TriggerInfo> onPoll(UUID companyId, TriggerStore.WaitingPoll poll) {
        return store.liveWithAccount(companyId, poll.accountId()).stream()
            .map(id -> store.find(companyId, id).orElseThrow())
            .filter(trigger -> poll.systemId().equals(trigger.systemId()))
            .toList();
    }

    private static Instant since(TriggerInfo trigger) {
        return trigger.pausedAt() != null ? trigger.pausedAt() : trigger.updatedAt();
    }
}
