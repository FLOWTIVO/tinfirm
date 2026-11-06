package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.conversation.ConversationCommands;
import com.flowtivo.tinfirm.conversation.ConversationReads;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.shared.api.DecisionsApi;
import com.flowtivo.tinfirm.shared.api.model.DecideRequest;
import com.flowtivo.tinfirm.shared.api.model.Decision;
import com.flowtivo.tinfirm.shared.api.model.DecisionPage;

/** The Decisions API, through the module's public API in the {@code APP} view (the package 6 contract, §5.7). */
@RestController
class DecisionsController implements DecisionsApi {

    private final ConversationReads reads;

    private final ConversationCommands commands;

    DecisionsController(ConversationReads reads, ConversationCommands commands) {
        this.reads = reads;
        this.commands = commands;
    }

    @Override
    public ResponseEntity<DecisionPage> listDecisions(UUID companyId, Boolean open, Boolean mine, Integer limit,
            @Nullable String cursor) {
        return ResponseEntity.ok(reads.decisions(companyId, ConversationView.APP, Boolean.TRUE.equals(open),
                Boolean.TRUE.equals(mine), limit, cursor));
    }

    @Override
    public ResponseEntity<Decision> getDecision(UUID companyId, UUID decisionId) {
        return ResponseEntity.ok(reads.decision(companyId, ConversationView.APP, decisionId));
    }

    @Override
    public ResponseEntity<Decision> decideDecision(UUID companyId, UUID decisionId, DecideRequest decideRequest,
            @Nullable String idempotencyKey) {
        return ConversationsController.submitted(HttpStatus.OK, commands.decide(companyId, ConversationView.APP,
                decisionId, decideRequest, idempotencyKey));
    }
}
