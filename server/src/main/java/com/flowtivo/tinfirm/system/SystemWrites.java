package com.flowtivo.tinfirm.system;

import java.util.List;
import java.util.UUID;

/**
 * The writes of a tracker that are not tools of the agent (the package 4 contract, §2.3 and §5.9): an attachment and a
 * remote link, which the write-back's outbox writes to a trigger's conversation's issue, and the reads a repeated
 * attempt needs to know whether its first attempt wrote already. Jira Data Center and Jira Cloud have both; GitHub and
 * GitLab neither ({@code INVALID}).
 * <p>
 * Like {@link SystemTools}, every call runs <b>outside any database transaction</b> (CONVENTIONS §10;
 * {@link IllegalStateException} inside one): the system's row is read in a short transaction of its own in the
 * caller's tenant context, then the system is called through the outbound client and its guard, only at the system's
 * origin (a Jira Cloud OAuth grant at Atlassian's gateway for the site), within the tool timeout, and only at the
 * endpoint the credential was revealed for ({@link CallCredential#endpoint()}, ADR-0079: a changed one is
 * {@code INVALID}, with nothing sent). Failures are values, never exceptions, and never hold a token: a refused
 * credential is {@code UNAUTHORIZED}.
 */
public interface SystemWrites {

    /**
     * Attaches one file to the issue ({@code POST …/issue/{key}/attachments}, {@code multipart/form-data}); answers
     * the attachment's id.
     *
     * @param fileName the name the issue shows: one segment (a path's {@code /} replaced by the caller)
     */
    CallResult<String> attach(UUID companyId, UUID systemId, CallCredential credential, String key, String fileName,
            String contentType, byte[] content);

    /** The issue's attachments, by name and size: for a repeated attempt of an attachment. */
    CallResult<List<Attachment>> attachments(UUID companyId, UUID systemId, CallCredential credential, String key);

    /**
     * Links a URL to the issue ({@code POST …/issue/{key}/remotelink}), idempotent by the URL: Jira updates the link
     * with the same global id ({@code tinfirm:{url}}) instead of adding another. Answers the link's id.
     */
    CallResult<String> remoteLink(UUID companyId, UUID systemId, CallCredential credential, String key, String url,
            String title);

    /** An attachment of an issue. */
    record Attachment(String id, String fileName, long size) {
    }
}
