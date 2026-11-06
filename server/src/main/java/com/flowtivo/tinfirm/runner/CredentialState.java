package com.flowtivo.tinfirm.runner;

/** The last reported state of a runner credential; {@code OFFLINE} is derived from the connection at read time. */
public enum CredentialState {
    AVAILABLE, COOLDOWN, INVALID
}
