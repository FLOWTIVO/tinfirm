package com.flowtivo.tinfirm.conversation.internal;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.StagedFiles;

/** {@link StagedFiles}: the attachment service's held files (ADR-0084). */
@Component
class StagedFilesApi implements StagedFiles {

    private final AttachmentService attachments;

    private final AttachmentProperties properties;

    StagedFilesApi(AttachmentService attachments, AttachmentProperties properties) {
        this.attachments = attachments;
        this.properties = properties;
    }

    @Override
    public StagedFile stage(UUID companyId, String fileName, @Nullable String contentType, byte[] content) {
        return attachments.stage(companyId, fileName, contentType, content);
    }

    @Override
    public void release(UUID companyId, List<UUID> ids) {
        attachments.release(companyId, ids);
    }

    @Override
    public long maxFileBytes() {
        return properties.maxFileSize().toBytes();
    }
}
