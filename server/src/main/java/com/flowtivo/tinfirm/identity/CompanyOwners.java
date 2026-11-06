package com.flowtivo.tinfirm.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port: the OWNERs of companies, by name, for the notice that they can read a person's personal conversations in full
 * (security review, row 18; the package 6 contract, §5.6): on the consent page of an AI client, in the answer of a
 * personal access token's creation and in the MCP endpoint's answers. Implemented by the company module. Only display
 * names, never emails.
 */
public interface CompanyOwners {

    /** The display names of each company's OWNERs, sorted; a company without one (or unknown) maps to none. */
    Map<UUID, List<String>> ownersOf(Collection<UUID> companyIds);

    /** The display names of the company's OWNERs, sorted. */
    default List<String> ownersOf(UUID companyId) {
        return ownersOf(List.of(companyId)).getOrDefault(companyId, List.of());
    }
}
