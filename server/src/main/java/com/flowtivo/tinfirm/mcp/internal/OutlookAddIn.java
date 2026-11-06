package com.flowtivo.tinfirm.mcp.internal;

import java.util.UUID;

import com.flowtivo.tinfirm.shared.api.model.OutlookManifest;

/**
 * Tinfirm's Outlook add-in (ADR-0074), Pro's and Enterprise's (ADR-0077, decision 4, {@code OUTLOOK_ADDIN}): a paid
 * feature's code, under {@code ee/} (decision 1), which checks the licence in force. The core finds it when used
 * ({@link OutlookController}) and never names its implementation: without {@code ee/} the server answers as
 * Community. The task pane's calls to this module's endpoint are refused in the core ({@link McpRequestFilter}).
 */
interface OutlookAddIn {

    /** The company's manifest, for any member: {@code 403 EDITION_REQUIRED} after the access check, unless licensed. */
    OutlookManifest manifest(UUID companyId);
}
