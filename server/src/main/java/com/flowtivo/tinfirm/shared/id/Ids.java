package com.flowtivo.tinfirm.shared.id;

import java.util.UUID;

import com.github.f4b6a3.uuid.UuidCreator;

/** Identifiers are UUIDv7 (time-ordered), generated in the application. */
public final class Ids {

    private Ids() {
    }

    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
