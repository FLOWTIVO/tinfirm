package com.flowtivo.tinfirm.system.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.vault.SecretKeeper;

/**
 * A company system's app keeps its client secret in a managed company secret (the package 1 contract, §3.3): the
 * Secrets API does not delete it, since that would leave the app without its secret and unaudited; the app is removed
 * on the system's page instead ({@code DELETE /systems/{id}/app}). A Slack system keeps its signing secret and its
 * app-level token the same way (the package 5 contract, §3.1): they change on the system's page
 * ({@code PUT /systems/{id}/chat/secrets}) and go with the system; a webhook system keeps its generated secret, which it
 * rotates ({@code POST /systems/{id}/webhook/rotate}).
 */
@Component
class SystemSecretKeeper implements SecretKeeper {

    private final SystemStore store;

    private final ChatSystemsImpl chatSystems;

    private final WebhookSystemsImpl webhookSystems;

    SystemSecretKeeper(SystemStore store, ChatSystemsImpl chatSystems, WebhookSystemsImpl webhookSystems) {
        this.store = store;
        this.chatSystems = chatSystems;
        this.webhookSystems = webhookSystems;
    }

    @Override
    public Optional<String> keeperOf(UUID companyId, UUID secretId) {
        return store.withClientSecret(companyId, secretId)
            .map(system -> "the app of the system `" + system.name() + "`: remove the app on the system's page")
            .or(() -> chatSystems.keeperOf(companyId, secretId)
                .map(name -> "the chat system `" + name + "`: store a new one on the system's page, or remove "
                        + "the system"))
            .or(() -> webhookSystems.keeperOf(companyId, secretId)
                .map(name -> "the webhook system `" + name + "`: rotate it on the system's page, or remove the "
                        + "system"));
    }
}
