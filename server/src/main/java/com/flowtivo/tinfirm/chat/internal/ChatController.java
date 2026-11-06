package com.flowtivo.tinfirm.chat.internal;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.ChatApi;
import com.flowtivo.tinfirm.shared.api.model.ChatBot;
import com.flowtivo.tinfirm.shared.api.model.ChatBotRequest;
import com.flowtivo.tinfirm.shared.api.model.ChatChannelList;
import com.flowtivo.tinfirm.shared.api.model.ChatManifest;
import com.flowtivo.tinfirm.shared.api.model.ChatSecretsRequest;
import com.flowtivo.tinfirm.shared.api.model.ChatSettings;
import com.flowtivo.tinfirm.shared.api.model.ExternalSystem;
import com.flowtivo.tinfirm.system.ChatSystems;

@RestController
class ChatController implements ChatApi {

    private final ChatAdmin admin;

    private final ChatManifests manifests;

    private final ChatSystems chatSystems;

    private final ChatChannels channels;

    ChatController(ChatAdmin admin, ChatManifests manifests, ChatSystems chatSystems, ChatChannels channels) {
        this.admin = admin;
        this.manifests = manifests;
        this.chatSystems = chatSystems;
        this.channels = channels;
    }

    @Override
    public ResponseEntity<ChatChannelList> listChatChannels(UUID companyId, UUID systemId, String query,
            UUID authorPrincipalId) {
        return ResponseEntity.ok(channels.list(companyId, systemId, query, authorPrincipalId));
    }

    @Override
    public ResponseEntity<ChatBot> setChatBot(UUID companyId, UUID systemId, ChatBotRequest request) {
        return ResponseEntity.ok(admin.setBot(companyId, systemId, request));
    }

    @Override
    public ResponseEntity<Void> removeChatBot(UUID companyId, UUID systemId) {
        admin.removeBot(companyId, systemId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ExternalSystem> setChatSecrets(UUID companyId, UUID systemId, ChatSecretsRequest request) {
        admin.setSecrets(companyId, systemId, request);
        return ResponseEntity.ok(chatSystems.view(companyId, systemId));
    }

    @Override
    public ResponseEntity<ChatManifest> getChatManifest(UUID companyId, UUID systemId) {
        return ResponseEntity.ok(manifests.manifest(companyId, systemId));
    }

    @Override
    public ResponseEntity<ChatSettings> getChatSettings(UUID companyId) {
        return ResponseEntity.ok(admin.settings(companyId));
    }

    @Override
    public ResponseEntity<ChatSettings> setChatSettings(UUID companyId, ChatSettings settings) {
        return ResponseEntity.ok(admin.setSettings(companyId, settings));
    }
}
