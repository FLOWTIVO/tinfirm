package com.flowtivo.tinfirm.audit.internal;

/** The head of one audit chain: the last sequence number and hash. */
record ChainHead(long lastSeq, String lastHash) {
}
