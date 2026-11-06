package com.flowtivo.tinfirm.conversation.internal;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.TurnSource;

/**
 * For parts 2 and 3, implemented by part 1: {@code Workspace.of(conversation)} (the contract's §5.6) and what approved
 * requests add to it. In the caller's transaction and tenant context.
 */
public interface ConversationWorkspace {

    /**
     * The workspace of the conversation's next turn, in order: the start's repositories (an {@code AGENT} entry leaves
     * when the agent of the person's turns no longer lists it or its system, or the settings switch the system off),
     * then the approved requests. Each repository with its binding as resolved so far (null before), and whether it is
     * read-only and why: the agent's access {@code READ} ({@code AGENT}), the approval's {@code READ}
     * ({@code REQUEST}), or the binding's read-only grant ({@code GRANT}, read now). A repository of a system whose
     * binding is gone is left out. Resolves nothing and reveals nothing.
     */
    List<Repository> of(ConversationInfo conversation, EffectiveSettings settings);

    /**
     * The repositories the agent may ask for ({@code run.assign.requestableRepositories}, §5.5): the agent's
     * repositories the workspace lacks, then the other repositories of its systems, at most 200.
     */
    List<Requestable> requestable(ConversationInfo conversation, EffectiveSettings settings);

    /**
     * An approved request of a repository for this conversation (§5.8, scope {@code CONVERSATION}): it joins the
     * workspace as {@code REQUESTED}, read-only when {@code access} is {@code READ}. At most 20 repositories; one in the
     * workspace already changes nothing.
     *
     * @return whether it joined
     */
    boolean addRequested(UUID companyId, UUID conversationId, UUID repositoryId, RepositoryAccess access);

    /**
     * An approved request of a network host for this conversation ({@code granted_hosts}), normalized; at most 100.
     * A host that a granted one covers already ({@code NetworkHosts.covers}: itself, or a wildcard over it) adds
     * nothing.
     */
    HostGrant grantHost(UUID companyId, UUID conversationId, String host);

    /** What {@link #grantHost} did. */
    enum HostGrant {
        /** The host was added. */
        ADDED,
        /** A granted host covers it already: nothing to add. */
        COVERED,
        /** The conversation holds 100 hosts already: nothing was added. */
        FULL
    }

    /**
     * A repository of a turn's workspace.
     *
     * @param binding the conversation's binding on its system; null while not resolved
     */
    record Repository(UUID repositoryId, UUID systemId, String name, String url, String defaultBranch,
            ConversationInfo.WorkspaceEntry.Source source, boolean readOnly,
            TurnSource.@Nullable ReadOnlyCause readOnlyCause, @Nullable BindingInfo binding) {
    }

    /**
     * A repository the agent may request.
     *
     * @param onAgent whether the agent lists it
     */
    record Requestable(UUID repositoryId, UUID systemId, String name, String url, boolean onAgent) {
    }
}
