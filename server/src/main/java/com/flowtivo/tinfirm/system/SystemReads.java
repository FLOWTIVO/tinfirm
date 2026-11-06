package com.flowtivo.tinfirm.system;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.ExternalSystem;
import com.flowtivo.tinfirm.shared.api.model.ExternalSystemPage;
import com.flowtivo.tinfirm.shared.api.model.SystemTestResult;
import com.flowtivo.tinfirm.shared.api.model.SystemToolList;

/**
 * Systems for the caller's AI client (the MCP endpoint, ADR-0045): the reads of the Systems API and its test, with its
 * inputs, its DTOs and its rules, through the same services. Every member sees the company's systems and their own
 * personal MCP servers, never another member's (404, admins included); what only whoever manages a system sees (its
 * app, who tested it, a webhook's mapping and rate) stays theirs; no secret is ever answered. Nothing here registers,
 * changes or removes a system, its app, its catalogue or its tools.
 */
public interface SystemReads {

    /** {@code listSystems}: the systems the caller sees, by name, a page. */
    ExternalSystemPage list(UUID companyId, @Nullable Integer limit, @Nullable String cursor);

    /** {@code getSystem}: one system, its settings by kind, as the caller sees it. */
    ExternalSystem get(UUID companyId, UUID systemId);

    /** {@code listSystemTools}: an MCP server's stored catalogue, or a tracker's fixed tools, with their effects. */
    SystemToolList tools(UUID companyId, UUID systemId);

    /**
     * {@code testSystem}: reaches the system, with the caller's own {@code OK} account when they hold one, else
     * without a credential, and keeps the outcome as its last test. For whoever manages it (403 otherwise); outside
     * any transaction, since it calls the system.
     */
    SystemTestResult test(UUID companyId, UUID systemId);
}
