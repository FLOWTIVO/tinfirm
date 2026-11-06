package com.flowtivo.tinfirm.vault.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.vault.SecretWriter;

@Component
class SecretWriterImpl implements SecretWriter {

    private final SecretService secrets;

    SecretWriterImpl(SecretService secrets) {
        this.secrets = secrets;
    }

    @Override
    public UUID create(UUID companyId, String name, String description, String value) {
        return secrets.createTrusted(companyId, name, description, value);
    }

    @Override
    public boolean replace(UUID companyId, UUID secretId, String value) {
        return secrets.replaceTrusted(companyId, secretId, value);
    }

    @Override
    public void delete(UUID companyId, UUID secretId) {
        secrets.deleteTrusted(companyId, secretId);
    }

    @Override
    public UUID createOwned(UUID companyId, UUID ownerUserId, String name, String description, String value) {
        return secrets.createOwned(companyId, ownerUserId, name, description, value);
    }

    @Override
    public boolean replaceOwned(UUID companyId, UUID ownerUserId, UUID secretId, String value) {
        return secrets.replaceOwned(companyId, ownerUserId, secretId, value);
    }

    @Override
    public void deleteOwned(UUID companyId, UUID ownerUserId, UUID secretId) {
        secrets.deleteOwned(companyId, ownerUserId, secretId);
    }

    @Override
    public void deleteAllOwned(UUID companyId, UUID ownerUserId) {
        secrets.deleteAllOwned(companyId, ownerUserId);
    }

    @Override
    public UUID createInternal(UUID companyId, String name, String description, String value) {
        return secrets.createInternal(companyId, name, description, value);
    }

    @Override
    public void deleteInternal(UUID companyId, UUID secretId) {
        secrets.deleteInternal(companyId, secretId);
    }
}
