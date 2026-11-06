package com.flowtivo.tinfirm.shared.directory;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port for resolving user display names at read time (implemented by the identity module).
 * Other modules store user ids only, never names, so personal data stays in one place and erasing a user
 * never breaks an audit chain.
 */
public interface UserDisplayNames {

    /** Display names of the given users; users that no longer exist are absent from the result. */
    Map<UUID, String> displayNames(Collection<UUID> userIds);
}
