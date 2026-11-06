package com.flowtivo.tinfirm.agent;

/**
 * A tool's mode on an agent (the package 3 contract, §3.3; PLATFORM §10): {@code ALLOW} runs a call, {@code ASK} makes
 * each call an approval. A tool the agent does not list is not available: there is no {@code DENY} (I3).
 */
public enum ToolMode {
    ALLOW, ASK
}
