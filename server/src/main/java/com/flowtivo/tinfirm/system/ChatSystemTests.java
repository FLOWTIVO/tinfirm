package com.flowtivo.tinfirm.system;

import java.util.UUID;

/**
 * Port: the test of a chat system (the package 5 contract, §5.1: {@code POST /systems/{id}/test} also checks the bot,
 * the transport and the stored secrets), implemented by the {@code chat} module, which holds the bot. Runs outside any
 * transaction, in the caller's tenant context, after the system module authorised the caller; the system module
 * stores the outcome as the system's status.
 */
public interface ChatSystemTests {

    /** The outcome: whether everything checked passed, and what was checked or failed, in words. */
    record Outcome(boolean ok, boolean withBot, String message) {
    }

    Outcome test(UUID companyId, UUID systemId);
}
