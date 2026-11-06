package com.flowtivo.tinfirm.identity.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * The core's port of the SAML sign-in (ADR-0086, decision 11), which {@code ee/} implements: the core's
 * {@link SecurityConfiguration} finds it when it builds the web app's chain, and without {@code ee/} there is no
 * SAML. The provisioning of a user, the audit and the session stay the core's ({@link SamlSignIns}).
 */
public interface SamlLogin {

    /**
     * Adds the SAML sign-in to the web app's chain: the start {@code /saml2/authenticate/{id}}, the assertion
     * consumer service {@code /login/saml2/sso/{id}} and the service provider's metadata
     * {@code /saml2/service-provider-metadata/{id}}.
     */
    void configure(HttpSecurity http, SamlSignIns signIns) throws Exception;

    /**
     * The SAML providers the login page offers now: those the licence serves (decision 14) whose metadata is in
     * force, by id.
     */
    List<LoginProvider> loginProviders();

    /** The SAML providers of a declared company, for its sign-in page (decision 15); every one, served or not. */
    List<ProviderState> providersOf(UUID companyId);

    /** Whether the licence serves SAML now (decision 14). */
    boolean served();

    /**
     * @param id       the registration id
     * @param name     the button's name
     * @param startUrl the path that starts a sign-in
     */
    record LoginProvider(String id, String name, String startUrl) {
    }

    /**
     * What a company's sign-in page shows of a SAML provider (decision 15). Nothing in it is secret.
     *
     * @param metadataSource the host of the metadata's URL, or {@code file}
     * @param lastError      the last failed read's reason, or null
     */
    record ProviderState(String id, String name, String spEntityId, String acsUrl, String spMetadataUrl,
            String startUrl, @Nullable String idpEntityId, String metadataSource, @Nullable Instant metadataReadAt,
            @Nullable String lastError, @Nullable Instant lastErrorAt, @Nullable Instant validUntil,
            List<Certificate> idpCertificates, List<Certificate> spCertificates) {
    }

    /** A certificate as the sign-in page shows it: its subject, SHA-256 fingerprint and end. */
    record Certificate(String subject, String sha256, Instant notAfter) {
    }
}
