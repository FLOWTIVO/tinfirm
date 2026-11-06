package com.flowtivo.tinfirm.account.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.vault.SecretKeeper;

/**
 * A system's credential keeps its token or OAuth grant in a managed company secret (the package 1 contract, §3.6;
 * ADR-0072): the Secrets API does not delete it, since that would take the credential, its grants and lendings without
 * their audit and events; the credential is removed on its system's page instead.
 */
@Component
class AccountSecretKeeper implements SecretKeeper {

    private final AccountStore store;

    private final Systems systems;

    AccountSecretKeeper(AccountStore store, Systems systems) {
        this.store = store;
        this.systems = systems;
    }

    @Override
    public Optional<String> keeperOf(UUID companyId, UUID secretId) {
        return store.withSecret(companyId, secretId).filter(AccountStore.AccountRow::credential).map(credential -> {
            String system = systems.find(companyId, credential.systemId())
                .map(SystemInfo::name)
                .orElse("its system");
            return "the credential `" + credential.name() + "` of `" + system + "`: remove it on the system's page";
        });
    }
}
