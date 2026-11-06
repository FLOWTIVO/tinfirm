package com.flowtivo.tinfirm.conversation.internal;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditReads;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ConversationReader;
import com.flowtivo.tinfirm.shared.directory.UserDisplayNames;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * {@code GET /conversations/{id}/reads} (the package 6 contract, §5.6; open point 10): who read a personal
 * conversation other than its person, and when, shown to its person, as the notice promises. Every read by an OWNER
 * who is not its person is a {@code conversation.read} event of the company's chain (§11: no throttle), so the list is
 * read from the chain ({@link AuditReads}): one entry per reader, first and last read, how many, and what was read
 * (the events' {@code what}). Its
 * person and OWNER see it, as they see the conversation; anyone else gets {@code 404}. Listing the reads is not a read
 * of the conversation.
 */
@Service
class ConversationReaders {

    private final ConversationStore store;

    private final ConversationsImpl conversations;

    private final AuditReads reads;

    private final UserDisplayNames users;

    private final CompanyAccessGuard companyAccess;

    ConversationReaders(ConversationStore store, ConversationsImpl conversations, AuditReads reads,
            UserDisplayNames users, CompanyAccessGuard companyAccess) {
        this.store = store;
        this.conversations = conversations;
        this.reads = reads;
        this.users = users;
        this.companyAccess = companyAccess;
    }

    @Transactional(readOnly = true)
    public List<ConversationReader> readers(UUID companyId, UUID conversationId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        ConversationStore.ConversationRow row = store.find(companyId, conversationId)
            .filter(found -> conversations.current(access).sees(found.info()))
            .orElseThrow(() -> ApiException.notFound("Conversation not found"));
        List<AuditReads.ConversationReader> readers = reads.conversationReads(companyId, row.id());
        Map<UUID, String> names = readers.isEmpty() ? Map.of()
                : users.displayNames(readers.stream().map(AuditReads.ConversationReader::userId).toList());
        return readers.stream()
            .map(reader -> new ConversationReader(reader.userId(), reader.firstReadAt().atOffset(ZoneOffset.UTC),
                    reader.lastReadAt().atOffset(ZoneOffset.UTC), reader.reads(), reader.kinds())
                .name(names.getOrDefault(reader.userId(), "deleted user")))
            .toList();
    }
}
