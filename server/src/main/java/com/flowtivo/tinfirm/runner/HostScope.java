package com.flowtivo.tinfirm.runner;

/**
 * Whose a runner host is (the package 2 contract, §2, M4): a {@code COMPANY} host, registered by an admin, runs any
 * conversation; a {@code PERSONAL} host, registered by a member, runs only its owner's work and is the only kind that
 * holds credentials of its own (M5). Set at registration, never changed.
 */
public enum HostScope {
    COMPANY, PERSONAL
}
