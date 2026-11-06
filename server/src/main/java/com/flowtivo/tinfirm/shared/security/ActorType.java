package com.flowtivo.tinfirm.shared.security;

/** Who performed an action. */
public enum ActorType {
    USER, SYSTEM, AGENT,
    /** A company's SCIM token, by its id (ADR-0087, decision 3). */
    SCIM
}
