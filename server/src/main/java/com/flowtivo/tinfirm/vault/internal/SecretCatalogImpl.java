package com.flowtivo.tinfirm.vault.internal;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.vault.SecretCatalog;

@Component
class SecretCatalogImpl implements SecretCatalog {

    private final SecretRepository secrets;

    SecretCatalogImpl(SecretRepository secrets) {
        this.secrets = secrets;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> names(UUID companyId, Collection<UUID> secretIds) {
        if (secretIds.isEmpty()) {
            return Map.of();
        }
        return secrets.findAllById(secretIds)
            .stream()
            .filter(secret -> secret.getCompanyId().equals(companyId) && !secret.owned() && !secret.isInternal())
            .collect(Collectors.toMap(SecretEntity::getId, SecretEntity::getName));
    }
}
