package com.flowtivo.tinfirm.shared.licence;

/**
 * A paid feature (ADR-0077, decision 4), with the lowest edition that holds it. Which features are in force is the
 * licence's ({@link LicenceStatus#features()}); every gate is checked by the server ({@link Licensing#require}).
 */
public enum Feature {
    /** The Outlook add-in (ADR-0074). */
    OUTLOOK_ADDIN(Edition.PRO),
    /** The Gmail add-on (ADR-0083), as the Outlook add-in. */
    GMAIL_ADDON(Edition.PRO),
    /** The audit's filters, export, chain verification and daily checks. */
    AUDIT_TOOLS(Edition.PRO),
    /** The audit shown in the app beyond its last 90 days. */
    AUDIT_FULL_HISTORY(Edition.PRO),
    /**
     * The audit chains' heads anchored outside the database (ADR-0111): signed checkpoints written to a sink, their
     * comparison in the daily check, the anchored status, and the offline verification tool.
     */
    AUDIT_ANCHOR(Edition.PRO),
    /** The OWNER's retention periods; without them retention keeps everything (decision 8). */
    RETENTION_SETTINGS(Edition.PRO),
    /**
     * Elastic runners (ADR-0100): runner hosts with the sandbox bridge or a VM per run, their ready sandboxes on
     * Tinfirm's schedule, and session bundles (ADR-0095 to ADR-0099).
     */
    ELASTIC_RUNNERS(Edition.PRO),
    LEGAL_HOLDS(Edition.ENTERPRISE),
    SAML(Edition.ENTERPRISE),
    SCIM(Edition.ENTERPRISE),
    SIEM(Edition.ENTERPRISE),
    /** Declared companies beyond the first (ADR-0075). */
    MULTI_COMPANY(Edition.ENTERPRISE),
    HA(Edition.ENTERPRISE),
    AIR_GAPPED(Edition.ENTERPRISE);

    private final Edition edition;

    Feature(Edition edition) {
        this.edition = edition;
    }

    /** The lowest edition that holds the feature. */
    public Edition edition() {
        return edition;
    }
}
