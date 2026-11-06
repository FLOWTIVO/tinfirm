package com.flowtivo.tinfirm.system;

import java.util.List;
import java.util.UUID;

/**
 * Port: what a system's pause and resume do in the modules above the system module (the package 6 contract, §5.4, open
 * point 6), implemented by them: the conversation module cancels the running turns of the conversations bound to the
 * system ({@code stopRunning}); package 4's trigger module pauses every trigger whose source is the system or whose
 * table has a row on it ({@code paused_reason SYSTEM_PAUSED}), resumes those the resume's dialog names through its gate
 * with {@code armed_at} moved to the resume, holds and releases their write-back, and moves its polls' windows to the
 * resume ({@code TriggerRunSwitches}). Called in the transaction of the pause or the resume, in the company's tenant
 * context, as the admin who pulled the switch, after the system's row changed and before the switch's own audit
 * record.
 */
public interface SystemSwitchDependents {

    /** The system is being paused: answers what was cancelled and paused here. */
    default Pausing systemPausing(UUID companyId, UUID systemId, boolean stopRunning) {
        return Pausing.NONE;
    }

    /** The system is being resumed: answers how many triggers were resumed, and how many stayed paused. */
    default Resuming systemResuming(UUID companyId, UUID systemId, boolean resumeTriggers) {
        return Resuming.NONE;
    }

    /** What a pause did in one module: the runs it cancelled, the triggers it paused. */
    record Pausing(List<UUID> runsCancelled, int triggersPaused) {

        public static final Pausing NONE = new Pausing(List.of(), 0);

        public Pausing {
            runsCancelled = List.copyOf(runsCancelled);
        }
    }

    /** What a resume did in one module. */
    record Resuming(int triggersResumed, int triggersStillPaused) {

        public static final Resuming NONE = new Resuming(0, 0);
    }
}
