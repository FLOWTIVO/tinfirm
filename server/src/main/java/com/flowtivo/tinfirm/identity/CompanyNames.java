package com.flowtivo.tinfirm.identity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: the names of companies, whoever asks (ADR-0045). Implemented by the company module. The OAuth authorization
 * server names a company on its consent page and in "Connected apps" (where the person may no longer be a member),
 * both to a signed-in person; never to an anonymous caller (the protected-resource metadata of a company's MCP
 * endpoint names none). Only names: no members, no settings.
 */
public interface CompanyNames {

    /** The company's name, or empty when there is no such company. */
    Optional<String> nameOf(UUID companyId);

    /** The names of the companies that exist among the ids. */
    Map<UUID, String> namesOf(Collection<UUID> companyIds);
}
