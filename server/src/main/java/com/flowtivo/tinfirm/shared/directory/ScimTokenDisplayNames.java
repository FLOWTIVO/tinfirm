package com.flowtivo.tinfirm.shared.directory;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port for resolving the names of a company's SCIM tokens at read time, for the audit log, whose actor can be a SCIM
 * token (ADR-0087, decision 3; CONVENTIONS §8). The company module implements it. Runs in the caller's tenant context,
 * so only the company's own tokens are found.
 */
public interface ScimTokenDisplayNames {

    /** The names of the given tokens, revoked ones included; unknown ids are absent from the result. */
    Map<UUID, String> names(Collection<UUID> tokenIds);
}
