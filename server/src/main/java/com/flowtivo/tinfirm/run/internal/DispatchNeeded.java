package com.flowtivo.tinfirm.run.internal;

import java.util.UUID;

/** A run was queued or a slot was freed: the dispatcher is nudged once the transaction commits. */
record DispatchNeeded(UUID runId) {
}
