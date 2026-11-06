package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

/**
 * Whom a conversation's work names (the package 3 contract, §5.6): its commits' author, {@code {Person} via Tinfirm
 * agent {agent}}, whose address is the agent's ({@code agent+{agentId}@tinfirm.invalid}, which no mail reaches), and
 * the line of a change request the server opens for it. For {@code TurnSource.Workspace}'s author (part 1) and
 * {@link ChangeRequestBodies}.
 */
public final class CommitAuthor {

    private CommitAuthor() {
    }

    /** {@code {Person} via Tinfirm agent {agent}}. */
    public static String name(String personName, String agentName) {
        return personName + " via Tinfirm agent " + agentName;
    }

    /** A trigger's conversation's commits: {@code Tinfirm agent {agent} for {trigger}} (the package 4 contract, §5.7). */
    public static String forTrigger(String agentName, String triggerName) {
        return "Tinfirm agent " + agentName + " for " + triggerName;
    }

    /** The agent's address, which no mail reaches. */
    public static String email(UUID agentId) {
        return "agent+" + agentId + "@tinfirm.invalid";
    }
}
