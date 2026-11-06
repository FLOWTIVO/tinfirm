package com.flowtivo.tinfirm.conversation.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The limits of a conversation's files (ADR-0014; the package 3 contract, §3.13), its person's and the documents its
 * agent saved alike. The multipart limit {@code spring.servlet.multipart.max-file-size} is set from the same variable,
 * so an oversized upload is refused before it is read completely.
 *
 * @param maxFileSize             the largest file ({@code TINFIRM_ATTACHMENT_MAX_FILE_SIZE})
 * @param maxFilesPerConversation the most files of one conversation
 *                                ({@code TINFIRM_ATTACHMENT_MAX_FILES_PER_CONVERSATION})
 * @param maxConversationSize     the most bytes of all files of one conversation
 *                                ({@code TINFIRM_ATTACHMENT_MAX_CONVERSATION_SIZE})
 */
@Validated
@ConfigurationProperties("tinfirm.attachment")
record AttachmentProperties(@NotNull @DefaultValue("25MB") DataSize maxFileSize,
        @Min(1) @DefaultValue("50") int maxFilesPerConversation,
        @NotNull @DefaultValue("250MB") DataSize maxConversationSize) {
}
