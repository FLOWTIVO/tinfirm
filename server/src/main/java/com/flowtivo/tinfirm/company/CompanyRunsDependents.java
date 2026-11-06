package com.flowtivo.tinfirm.company;

import java.util.List;
import java.util.UUID;

/**
 * Port: what the company's stop does in the modules above the company (the package 6 contract, §5.4, open point 7),
 * implemented by them: the run module cancels what runs; package 4's trigger module makes its active triggers wait
 * and holds their write-back, and at the lift arms them again and releases it ({@code TriggerRunSwitches}). Called in the transaction of the stop or
 * the lift, in the company's tenant context, as the actor of the switch, after the company's row is marked and before
 * the switch's own audit record, so that each locks what it changes (conversations before their runs, CONVENTIONS
 * §10) and audits it.
 */
public interface CompanyRunsDependents {

    /** The company's runs are being stopped: what runs is cancelled. Answers the runs it cancelled. */
    default List<UUID> runsStopping(UUID companyId) {
        return List.of();
    }

    /**
     * The company's stop is being lifted: every active trigger is armed again at the lift, without a resume of each
     * (the stated exception to package 4's decision 9), and the held write-back is released.
     */
    default Lifted runsLifted(UUID companyId) {
        return Lifted.NONE;
    }

    /** What a lift released in one module: the triggers armed again, the write-back rows released. */
    record Lifted(int triggersArmed, int heldRowsReleased) {

        public static final Lifted NONE = new Lifted(0, 0);

        public Lifted plus(Lifted other) {
            return new Lifted(triggersArmed + other.triggersArmed, heldRowsReleased + other.heldRowsReleased);
        }
    }
}
