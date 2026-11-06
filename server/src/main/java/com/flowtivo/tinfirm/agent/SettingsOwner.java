package com.flowtivo.tinfirm.agent;

import java.util.UUID;

/**
 * Whose settings of an agent (the package 3 contract, §3.7): a person's, or, from package 4, a trigger's, which
 * records the version its author accepted (contract §11). {@code agent_settings.trigger_id} is the other owner (V011),
 * and {@link Settings#effective} answers {@link Trigger} from the trigger's row ({@link TriggerSettings}).
 */
public sealed interface SettingsOwner {

    /** A member, by their principal. */
    record Person(UUID principalId) implements SettingsOwner {
    }

    /** A trigger (package 4). */
    record Trigger(UUID triggerId) implements SettingsOwner {
    }
}
