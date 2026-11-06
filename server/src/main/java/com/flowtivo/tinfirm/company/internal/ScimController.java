package com.flowtivo.tinfirm.company.internal;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.company.scim.ScimException;
import com.flowtivo.tinfirm.company.scim.ScimDirectory;
import com.flowtivo.tinfirm.shared.api.ScimApi;
import com.flowtivo.tinfirm.shared.security.ScimAuthentication;

/**
 * The SCIM endpoint, {@code /scim/v2} (ADR-0087, decisions 1 and 12): the core's controller behind its own chain
 * ({@link ScimSecurityConfiguration}), which authenticated the company's token. The directory is Enterprise's
 * ({@link ScimDirectory}, under {@code ee/}), found when used: without it, or while the licence does not serve
 * {@code SCIM}, every request answers {@code 403} with the detail {@code EDITION_REQUIRED}. Each operation is one
 * transaction in the token's company's tenant context; its refusals are SCIM's errors
 * ({@link ScimExceptionHandler}).
 */
@RestController
class ScimController implements ScimApi {

    private final ObjectProvider<ScimDirectory> directories;

    ScimController(ObjectProvider<ScimDirectory> directories) {
        this.directories = directories;
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> listScimUsers(@Nullable String filter, @Nullable Integer startIndex,
            @Nullable Integer count) {
        return ok(directory().list(companyId(), filter, startIndex, count));
    }

    @Override
    @Transactional
    public ResponseEntity<Map<String, Object>> createScimUser(Map<String, Object> body) {
        Map<String, Object> created = directory().create(companyId(), body);
        return ResponseEntity.status(201).body(created);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getScimUser(String id) {
        return ok(directory().get(companyId(), id));
    }

    @Override
    @Transactional
    public ResponseEntity<Map<String, Object>> replaceScimUser(String id, Map<String, Object> body) {
        return ok(directory().replace(companyId(), id, body));
    }

    @Override
    @Transactional
    public ResponseEntity<Map<String, Object>> patchScimUser(String id, Map<String, Object> body) {
        return ok(directory().patch(companyId(), id, body));
    }

    @Override
    @Transactional
    public ResponseEntity<Void> deleteScimUser(String id) {
        directory().delete(companyId(), id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Map<String, Object>> getScimServiceProviderConfig() {
        return ok(directory().serviceProviderConfig());
    }

    @Override
    public ResponseEntity<Map<String, Object>> listScimResourceTypes() {
        return ok(directory().resourceTypes());
    }

    @Override
    public ResponseEntity<Map<String, Object>> getScimResourceType(String name) {
        return ok(directory().resourceType(name));
    }

    @Override
    public ResponseEntity<Map<String, Object>> listScimSchemas() {
        return ok(directory().schemas());
    }

    @Override
    public ResponseEntity<Map<String, Object>> getScimSchema(String id) {
        return ok(directory().schema(id));
    }

    /** The directory, while the licence serves SCIM; {@code 403 EDITION_REQUIRED} otherwise (decision 10). */
    private ScimDirectory directory() {
        ScimDirectory directory = directories.getIfAvailable();
        if (directory == null) {
            throw ScimException.editionRequired();
        }
        directory.requireServed();
        return directory;
    }

    /** The company the request's token names; the chain admitted nothing else. */
    private static UUID companyId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ScimAuthentication scim) {
            return scim.companyId();
        }
        throw new IllegalStateException("A SCIM request without its token");
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        // The media type follows the request's Accept: application/scim+json first, application/json for Okta.
        return ResponseEntity.ok(body);
    }
}
