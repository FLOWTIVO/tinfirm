package com.flowtivo.tinfirm.chat.internal;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.ChatLinksApi;
import com.flowtivo.tinfirm.shared.api.model.ChatLink;
import com.flowtivo.tinfirm.shared.api.model.ChatLinkTokenRequest;
import com.flowtivo.tinfirm.shared.api.model.ChatPreference;

@RestController
class ChatLinksController implements ChatLinksApi {

    private final ChatLinks links;

    private final ChatMembers members;

    ChatLinksController(ChatLinks links, ChatMembers members) {
        this.links = links;
        this.members = members;
    }

    @Override
    public ResponseEntity<ChatLink> readChatLink(UUID companyId, ChatLinkTokenRequest request) {
        return ResponseEntity.ok(links.read(companyId, request.getToken()));
    }

    @Override
    public ResponseEntity<ChatLink> confirmChatLink(UUID companyId, ChatLinkTokenRequest request) {
        return ResponseEntity.ok(links.confirm(companyId, request.getToken()));
    }

    @Override
    public ResponseEntity<ChatPreference> getMyChatPreference(UUID companyId) {
        return ResponseEntity.ok(members.preference(companyId));
    }

    @Override
    public ResponseEntity<ChatPreference> setMyChatPreference(UUID companyId, ChatPreference preference) {
        return ResponseEntity.ok(members.setPreference(companyId, preference));
    }

    @Override
    public ResponseEntity<Void> removeMemberChatAccount(UUID companyId, UUID userId, UUID systemId) {
        members.removeMemberAccount(companyId, userId, systemId);
        return ResponseEntity.noContent().build();
    }
}
