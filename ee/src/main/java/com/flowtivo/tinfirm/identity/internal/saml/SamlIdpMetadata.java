package com.flowtivo.tinfirm.identity.internal.saml;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.metadata.EntitiesDescriptor;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.IDPSSODescriptor;
import org.opensaml.saml.saml2.metadata.KeyDescriptor;
import org.opensaml.saml.saml2.metadata.SingleSignOnService;
import org.opensaml.security.credential.UsageType;
import org.opensaml.xmlsec.keyinfo.KeyInfoSupport;
import org.springframework.security.saml2.core.OpenSamlInitializationService;

/**
 * An IdP's metadata as Tinfirm accepts it (ADR-0086, decision 3): an {@code EntityDescriptor}, or an
 * {@code EntitiesDescriptor} from which the provider's {@code _ENTITY_ID} picks one, parsed by OpenSAML's hardened
 * parser pool (no DTD, no external entity, no XInclude, secure processing). The IdP must speak SAML 2.0 and offer an
 * HTTP-Redirect {@code SingleSignOnService} and at least one signing certificate. Every {@code use="signing"} or
 * unmarked key is trusted, so that an IdP rolls its certificate over by publishing the next one beside the current;
 * certificates are keys, their issuer, chain and dates are not checked. The IdP's entity ID is pinned: by
 * {@code _ENTITY_ID}, or by the provider's first good document ({@link #requirePinned}).
 */
final class SamlIdpMetadata {

    /** The largest document read. */
    static final int MAX_BYTES = 1024 * 1024;

    static {
        OpenSamlInitializationService.initialize();
    }

    /** The reasons a document is refused, as audited and stored. */
    enum Problem {
        UNREACHABLE("unreachable"), INVALID("invalid"), NO_IDP("no_idp"), EXPIRED("expired"), TOO_LARGE("too_large"),
        /** The document names another IdP than the one the provider's first good document named (H4). */
        ENTITY_ID_CHANGED("entity_id_changed"),
        /** The document names the IdP of another provider that reads other metadata, or an OIDC issuer (H4). */
        ENTITY_ID_IN_USE("entity_id_in_use");

        private final String reason;

        Problem(String reason) {
            this.reason = reason;
        }

        String reason() {
            return reason;
        }

        static Problem of(String reason) {
            for (Problem problem : values()) {
                if (problem.reason.equals(reason)) {
                    return problem;
                }
            }
            throw new IllegalArgumentException(reason);
        }
    }

    /** A document refused, with its reason and what was wrong, for the log. */
    static final class RefusedException extends Exception {

        private static final long serialVersionUID = 1L;

        private final Problem problem;

        private final @Nullable String entityId;

        RefusedException(Problem problem, String message) {
            super(message);
            this.problem = problem;
            this.entityId = null;
        }

        RefusedException(Problem problem, String message, Throwable cause) {
            super(message, cause);
            this.problem = problem;
            this.entityId = null;
        }

        /** A document refused for the IdP it names. */
        RefusedException(Problem problem, String message, String entityId) {
            super(message);
            this.problem = problem;
            this.entityId = entityId;
        }

        Problem problem() {
            return problem;
        }

        /** The entity ID the refused document names, where the refusal is about it; else null. */
        @Nullable String entityId() {
            return entityId;
        }
    }

    /** A signing certificate as stored and shown: its SHA-256 fingerprint, its subject and its end. */
    record Certificate(String sha256, String subject, Instant notAfter) {

        static Certificate of(X509Certificate certificate) {
            return new Certificate(fingerprint(certificate), certificate.getSubjectX500Principal().getName(),
                    certificate.getNotAfter().toInstant());
        }
    }

    /**
     * An accepted IdP.
     *
     * @param entityId            the IdP's entity ID
     * @param ssoLocation         the HTTP-Redirect {@code SingleSignOnService}'s location
     * @param signing             the keys a response may be signed with
     * @param encryption          the IdP's encryption keys, unused by Tinfirm, which sends nothing encrypted
     * @param wantRequestsSigned  the IdP's {@code WantAuthnRequestsSigned}; Tinfirm signs every request anyway
     * @param validUntil          the document's {@code validUntil}, or null
     * @param cacheDuration       the document's {@code cacheDuration}, or null
     */
    record Idp(String entityId, String ssoLocation, List<X509Certificate> signing, List<X509Certificate> encryption,
            boolean wantRequestsSigned, @Nullable Instant validUntil, @Nullable Duration cacheDuration) {

        List<Certificate> certificates() {
            return signing.stream().map(Certificate::of).toList();
        }
    }

    private SamlIdpMetadata() {
    }

    /**
     * Parses and checks a document.
     *
     * @param entityId the IdP to take from a document of several, or null for the only one
     * @param now      the time the document must still be valid at
     */
    static Idp parse(byte[] document, @Nullable String entityId, Instant now) throws RefusedException {
        if (document.length > MAX_BYTES) {
            throw new RefusedException(Problem.TOO_LARGE, "the document is over " + MAX_BYTES + " bytes");
        }
        XMLObject root;
        try {
            root = XMLObjectSupport.unmarshallFromInputStream(XMLObjectProviderRegistrySupport.getParserPool(),
                    new ByteArrayInputStream(document));
        }
        catch (Exception ex) {
            throw new RefusedException(Problem.INVALID, "the document is not SAML metadata: " + ex.getMessage(), ex);
        }
        Instant rootValidUntil = null;
        List<Found> entities = new ArrayList<>();
        if (root instanceof EntityDescriptor entity) {
            entities.add(new Found(entity, null, null));
        }
        else if (root instanceof EntitiesDescriptor group) {
            rootValidUntil = group.getValidUntil();
            collect(group, null, null, entities);
        }
        else {
            throw new RefusedException(Problem.INVALID, "the document is neither an EntityDescriptor nor an "
                    + "EntitiesDescriptor");
        }
        if (rootValidUntil != null && !rootValidUntil.isAfter(now)) {
            throw new RefusedException(Problem.EXPIRED, "the document's validUntil " + rootValidUntil + " passed");
        }
        List<Found> idps = entities.stream()
            .filter(found -> found.entity().getIDPSSODescriptor(SAMLConstants.SAML20P_NS) != null)
            .filter(found -> entityId == null || entityId.equals(found.entity().getEntityID()))
            .toList();
        if (idps.isEmpty()) {
            throw new RefusedException(Problem.NO_IDP, entityId == null ? "the document holds no SAML 2.0 IdP"
                    : "the document holds no SAML 2.0 IdP " + entityId);
        }
        if (idps.size() > 1) {
            throw new RefusedException(Problem.NO_IDP, "the document holds " + idps.size()
                    + " SAML 2.0 IdPs: set _ENTITY_ID to the one to use");
        }
        EntityDescriptor entity = idps.get(0).entity();
        Instant validUntil = earliest(idps.get(0).validUntil(), entity.getValidUntil());
        IDPSSODescriptor idp = entity.getIDPSSODescriptor(SAMLConstants.SAML20P_NS);
        validUntil = earliest(validUntil, idp.getValidUntil());
        if (validUntil != null && !validUntil.isAfter(now)) {
            throw new RefusedException(Problem.EXPIRED, "the IdP's validUntil " + validUntil + " passed");
        }
        String ssoLocation = idp.getSingleSignOnServices()
            .stream()
            .filter(service -> SAMLConstants.SAML2_REDIRECT_BINDING_URI.equals(service.getBinding()))
            .map(SingleSignOnService::getLocation)
            .filter(location -> location != null && (location.startsWith("https://") || isLocalHttp(location)))
            .findFirst()
            .orElseThrow(() -> new RefusedException(Problem.NO_IDP, "the IdP has no https HTTP-Redirect "
                    + "SingleSignOnService"));
        List<X509Certificate> signing = new ArrayList<>();
        List<X509Certificate> encryption = new ArrayList<>();
        for (KeyDescriptor key : idp.getKeyDescriptors()) {
            List<X509Certificate> certificates;
            try {
                certificates = key.getKeyInfo() == null ? List.of() : KeyInfoSupport.getCertificates(key.getKeyInfo());
            }
            catch (CertificateException ex) {
                throw new RefusedException(Problem.INVALID, "a certificate of the IdP cannot be read", ex);
            }
            UsageType use = key.getUse();
            if (use == null || use == UsageType.UNSPECIFIED || use == UsageType.SIGNING) {
                signing.addAll(certificates);
            }
            if (use == null || use == UsageType.UNSPECIFIED || use == UsageType.ENCRYPTION) {
                encryption.addAll(certificates);
            }
        }
        if (signing.isEmpty()) {
            throw new RefusedException(Problem.NO_IDP, "the IdP publishes no signing certificate");
        }
        Duration cache = shortest(idps.get(0).cacheDuration(), entity.getCacheDuration(), idp.getCacheDuration());
        return new Idp(entity.getEntityID(), ssoLocation, distinct(signing), distinct(encryption),
                Boolean.TRUE.equals(idp.getWantAuthnRequestsSigned()), validUntil, cache);
    }

    /**
     * Refuses a document that names another IdP than the provider is pinned to (H4 of the security review of
     * 2026-10-27): with {@code _ENTITY_ID} set, {@link #parse} took that IdP and the setting is the pin; without, the
     * entity ID of the provider's last good document is, from its first one on. An IdP that really changes its entity
     * ID is accepted once the deployment sets {@code _ENTITY_ID} to the new one.
     *
     * @param configured the provider's {@code _ENTITY_ID}, or null
     * @param pinned     the entity ID of the provider's last good document, or null before one
     */
    static void requirePinned(Idp idp, @Nullable String configured, @Nullable String pinned) throws RefusedException {
        if (configured == null && pinned != null && !pinned.equals(idp.entityId())) {
            throw new RefusedException(Problem.ENTITY_ID_CHANGED, "the document names the IdP " + idp.entityId()
                    + ", not " + pinned + " which the provider's first document named: set _ENTITY_ID to accept "
                    + "another", idp.entityId());
        }
    }

    /**
     * The document serialised again as UTF-8 by the hardened parser, so that a replica reads back what was checked
     * whatever encoding the IdP declared; only called on a document {@link #parse} accepted.
     */
    static String utf8(byte[] document) throws RefusedException {
        try {
            XMLObject root = XMLObjectSupport.unmarshallFromInputStream(XMLObjectProviderRegistrySupport.getParserPool(),
                    new ByteArrayInputStream(document));
            return net.shibboleth.shared.xml.SerializeSupport.nodeToString(root.getDOM());
        }
        catch (Exception ex) {
            throw new RefusedException(Problem.INVALID, "the document cannot be serialised again", ex);
        }
    }

    /**
     * An http location on the loopback names only, {@code localhost} and {@code *.localhost} (RFC 6761): Keycloak in
     * development and in the tests (ADR-0086, decision 16).
     */
    static boolean isLocalHttp(@Nullable String location) {
        if (location == null || !location.startsWith("http://")) {
            return false;
        }
        try {
            String host = java.net.URI.create(location).getHost();
            return host != null && (host.equals("localhost") || host.endsWith(".localhost"));
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * An entity of the document, with the earliest {@code validUntil} and the shortest {@code cacheDuration} of the
     * groups that hold it: a nested group that expired takes its IdPs with it.
     */
    private record Found(EntityDescriptor entity, @Nullable Instant validUntil, @Nullable Duration cacheDuration) {
    }

    private static void collect(EntitiesDescriptor group, @Nullable Instant validUntil,
            @Nullable Duration cacheDuration, List<Found> into) {
        Instant groupValidUntil = earliest(validUntil, group.getValidUntil());
        Duration groupCache = shortest(cacheDuration, group.getCacheDuration());
        for (EntityDescriptor entity : group.getEntityDescriptors()) {
            into.add(new Found(entity, groupValidUntil, groupCache));
        }
        for (EntitiesDescriptor nested : group.getEntitiesDescriptors()) {
            collect(nested, groupValidUntil, groupCache, into);
        }
    }

    private static List<X509Certificate> distinct(List<X509Certificate> certificates) {
        Map<String, X509Certificate> byFingerprint = new LinkedHashMap<>();
        certificates.forEach(certificate -> byFingerprint.putIfAbsent(fingerprint(certificate), certificate));
        return List.copyOf(byFingerprint.values());
    }

    private static @Nullable Instant earliest(@Nullable Instant a, @Nullable Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isBefore(b) ? a : b;
    }

    private static @Nullable Duration shortest(@Nullable Duration... durations) {
        Duration shortest = null;
        for (Duration duration : durations) {
            if (duration != null && (shortest == null || duration.compareTo(shortest) < 0)) {
                shortest = duration;
            }
        }
        return shortest;
    }

    static String fingerprint(X509Certificate certificate) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        }
        catch (NoSuchAlgorithmException | java.security.cert.CertificateEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
