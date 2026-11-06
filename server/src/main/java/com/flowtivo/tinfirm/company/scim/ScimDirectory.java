package com.flowtivo.tinfirm.company.scim;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Port: the SCIM directory behind {@code /scim/v2} (ADR-0087, decision 12), Enterprise's: the mapping of SCIM's
 * resources and patches onto the company's members, under {@code ee/}, which checks the licence in force
 * ({@code SCIM}). The core's endpoint finds it when used ({@link ScimController}) and answers {@code 403} with the
 * detail {@code EDITION_REQUIRED} without it.
 * <p>
 * Each method runs in the company's tenant context, as the company's SCIM token ({@code Actor} kind {@code SCIM}),
 * after the token was resolved; the token names the company, whose members alone it sees. The bodies are SCIM's JSON
 * as the client sent it, and the answers SCIM's JSON as it goes out. A refusal is a {@link ScimException}.
 */
public interface ScimDirectory {

    /** {@code 403 EDITION_REQUIRED} unless the licence serves SCIM now (decision 10). */
    void requireServed();

    Map<String, Object> list(UUID companyId, @Nullable String filter, @Nullable Integer startIndex,
            @Nullable Integer count);

    Map<String, Object> get(UUID companyId, String id);

    Map<String, Object> create(UUID companyId, Map<String, Object> body);

    Map<String, Object> replace(UUID companyId, String id, Map<String, Object> body);

    Map<String, Object> patch(UUID companyId, String id, Map<String, Object> body);

    void delete(UUID companyId, String id);

    Map<String, Object> serviceProviderConfig();

    Map<String, Object> resourceTypes();

    /** One resource type by its name, as {@link #resourceTypes()} lists it; {@code 404} for any other. */
    Map<String, Object> resourceType(String name);

    Map<String, Object> schemas();

    /** One schema by its URI, as {@link #schemas()} lists it; {@code 404} for any other. */
    Map<String, Object> schema(String id);
}
