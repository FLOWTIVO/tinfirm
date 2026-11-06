package com.flowtivo.tinfirm.conversation.internal;

import java.util.List;

import com.flowtivo.tinfirm.runner.RunnerMessages;

/**
 * Seam for part 3: the conversation's files a turn mounts under {@code /attachments} ({@code run.assign.attachments}).
 * Called outside any transaction (from {@code TurnSource.content}). Part 3 implements it as a bean; without one,
 * {@link #NONE} gives none.
 */
public interface TurnAttachments {

    TurnAttachments NONE = turn -> List.of();

    List<RunnerMessages.AssignedAttachment> attachments(TurnContext turn);
}
