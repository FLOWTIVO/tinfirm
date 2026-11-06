package com.flowtivo.tinfirm.identity;

import java.util.UUID;

/** Public view of a user for other modules. */
public record UserSummary(UUID id, String email, String displayName, boolean instanceAdmin, boolean active) {
}
