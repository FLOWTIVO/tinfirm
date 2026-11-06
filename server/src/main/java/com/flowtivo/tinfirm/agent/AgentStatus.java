package com.flowtivo.tinfirm.agent;

/**
 * Whether an agent works (the package 3 contract, §3.1): {@code PAUSED} is the kill switch, under which its
 * conversations start no run and run no approved call (I18).
 */
public enum AgentStatus {
    ACTIVE, PAUSED
}
