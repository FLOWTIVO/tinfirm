package com.flowtivo.tinfirm.company.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.scim.ScimSettings;

/** The company's SCIM from its declaration ({@link ScimSettings}). */
@Component
class ScimSettingsImpl implements ScimSettings {

    private final DeclaredCompaniesProperties declarations;

    private final CompanyQueries queries;

    ScimSettingsImpl(DeclaredCompaniesProperties declarations, CompanyQueries queries) {
        this.declarations = declarations;
        this.queries = queries;
    }

    /** The company's SCIM, off for a company no declaration names. */
    @Override
    @Transactional(readOnly = true)
    public Settings of(UUID companyId) {
        return queries.declaredRow(companyId)
            .map(CompanyQueries.DeclaredRow::key)
            .flatMap(this::ofKey)
            .orElse(Settings.OFF);
    }

    /** The SCIM of the declaration with this key. */
    Optional<Settings> ofKey(String key) {
        DeclaredCompaniesProperties.Declaration declaration = key != null ? declarations.companies().get(key) : null;
        if (declaration == null) {
            return Optional.empty();
        }
        DeclaredCompaniesProperties.Scim scim = declaration.scim();
        return Optional.of(new Settings(scim.enabled(), scim.role(), scim.roleSource()));
    }
}
