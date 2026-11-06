package com.flowtivo.tinfirm.vault;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Secret metadata for other modules (never values). Runs in the caller's transaction and tenant context. */
public interface SecretCatalog {

    /**
     * Names of those of {@code secretIds} that are secrets of the company; unknown ids are absent, and so are the
     * secrets a person owns (ADR-0049 §5), which no connection, credential or provider may name.
     */
    Map<UUID, String> names(UUID companyId, Collection<UUID> secretIds);
}
