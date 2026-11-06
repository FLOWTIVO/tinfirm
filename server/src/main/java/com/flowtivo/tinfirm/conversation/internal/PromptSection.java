package com.flowtivo.tinfirm.conversation.internal;

import java.util.Optional;

/**
 * Seams for parts 2 and 3: a section of the prompt (the contract's §5.9) that another part fills. Each answers the
 * section's body without its heading, or empty to leave the section out. Called outside any transaction (from
 * {@code TurnSource.content}); an implementation opens its own short transactions in the turn's company. The same body
 * goes into the full prompt and, for a resumed session, into the resume prompt: what it holds is what the agent needs
 * to know at this turn.
 * <p>
 * Each part implements its own as a bean; without one, its section is left out.
 */
public final class PromptSection {

    private PromptSection() {
    }

    /**
     * {@code # Answers} (part 2): the questions and their answers, the decisions and their outcomes, and the parked
     * calls' results since the previous turn.
     */
    public interface Answers {

        Optional<String> section(TurnContext turn);
    }

    /** {@code # Files} (part 3): the conversation's files under {@code /attachments}; documents go to {@code /output}. */
    public interface Files {

        Optional<String> section(TurnContext turn);
    }

    /** {@code # Outputs} (part 3): what this conversation produced so far. */
    public interface Outputs {

        Optional<String> section(TurnContext turn);
    }
}
