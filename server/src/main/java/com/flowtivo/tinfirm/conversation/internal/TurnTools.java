package com.flowtivo.tinfirm.conversation.internal;

import java.util.List;

import com.flowtivo.tinfirm.runner.RunnerMessages;

/**
 * Seam for part 2: the server-executed tools of a turn ({@code run.assign.tools}, the contract's §5.4,
 * {@code ConversationTools.list}), at most 200, each named {@code ToolNames.of(handle, tool)}. Part 1 renders the
 * prompt's {@code # Tools} section from the same list, one line per tool. Called outside any transaction (from
 * {@code TurnSource.content}); an implementation opens its own short transactions in the turn's company.
 * <p>
 * Part 2 implements it as a bean ({@code ConversationTools}); without one, {@link #NONE} lists none.
 */
public interface TurnTools {

    /** No tools: a run without server-executed tools. */
    TurnTools NONE = turn -> List.of();

    List<RunnerMessages.ToolSpec> tools(TurnContext turn);
}
