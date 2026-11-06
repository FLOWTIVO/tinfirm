package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.conversation.ConversationCommands;
import com.flowtivo.tinfirm.conversation.ConversationReads;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.shared.api.InboxApi;
import com.flowtivo.tinfirm.shared.api.model.Inbox;

/**
 * The inbox, through the module's public API in the {@code APP} view (the package 6 contract, §5.7), and the caller's
 * dismissals of its notices (CONVENTIONS §15.7).
 */
@RestController
class InboxController implements InboxApi {

    private final ConversationReads reads;

    private final ConversationCommands commands;

    InboxController(ConversationReads reads, ConversationCommands commands) {
        this.reads = reads;
        this.commands = commands;
    }

    @Override
    public ResponseEntity<Inbox> getInbox(UUID companyId) {
        return ResponseEntity.ok(reads.inbox(companyId, ConversationView.APP));
    }

    @Override
    public ResponseEntity<Void> dismissInboxNotice(UUID companyId, String noticeKey) {
        commands.dismissNotice(companyId, noticeKey);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> restoreInboxNotice(UUID companyId, String noticeKey) {
        commands.restoreNotice(companyId, noticeKey);
        return ResponseEntity.noContent().build();
    }
}
