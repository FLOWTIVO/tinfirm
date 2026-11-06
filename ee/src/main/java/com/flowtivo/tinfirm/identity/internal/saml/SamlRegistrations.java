package com.flowtivo.tinfirm.identity.internal.saml;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.identity.internal.SamlSettings;
import com.flowtivo.tinfirm.identity.internal.SamlSpCredentials;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The SAML relying-party registrations (ADR-0086, decision 2), one per declared provider, each built from the IdP
 * metadata a worker stored ({@link SamlMetadataStore}), never from a fetch of this replica's own, and built again when
 * the stored document's hash changes, so that every replica uses the same keys and the same
 * {@code SingleSignOnService}. A provider without a good document, or whose document's {@code validUntil} passed, has
 * no registration.
 */
@Component
class SamlRegistrations implements RelyingPartyRegistrationRepository, Iterable<RelyingPartyRegistration> {

    private static final Logger log = LoggerFactory.getLogger(SamlRegistrations.class);

    /** How long a registration is used before the stored hash is checked again. */
    static final Duration RECHECK = Duration.ofSeconds(10);

    /** The paths of the service provider, under {@code TINFIRM_PUBLIC_URL}. */
    static final String METADATA_PATH = "/saml2/service-provider-metadata/";

    static final String ACS_PATH = "/login/saml2/sso/";

    static final String START_PATH = "/saml2/authenticate/";

    private final SamlSettings settings;

    private final SamlMetadataStore store;

    private final TimeSource time;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(@Nullable String hash, @Nullable RelyingPartyRegistration registration,
            @Nullable Instant validUntil, Instant checkedAt) {
    }

    SamlRegistrations(SamlSettings settings, SamlMetadataStore store, TimeSource time) {
        this.settings = settings;
        this.store = store;
        this.time = time;
    }

    @Override
    public @Nullable RelyingPartyRegistration findByRegistrationId(String registrationId) {
        if (settings.properties().provider(registrationId) == null) {
            return null;
        }
        Instant now = time.now();
        Cached cached = cache.get(registrationId);
        if (cached == null || cached.checkedAt().plus(RECHECK).isBefore(now)) {
            cached = refresh(registrationId, cached, now);
        }
        if (cached.validUntil() != null && !cached.validUntil().isAfter(now)) {
            return null;
        }
        return cached.registration();
    }

    /** The service provider's own registration, for its metadata: known before the IdP's metadata is. */
    RelyingPartyRegistration serviceProvider(String registrationId) {
        RelyingPartyRegistration full = findByRegistrationId(registrationId);
        if (full != null) {
            return full;
        }
        return builder(registrationId)
            .assertingPartyMetadata(party -> party.entityId("urn:tinfirm:saml:idp-metadata-pending")
                .singleSignOnServiceLocation(settings.publicBaseUrl() + "/login")
                .singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT))
            .build();
    }

    @Override
    public Iterator<RelyingPartyRegistration> iterator() {
        List<RelyingPartyRegistration> registrations = new ArrayList<>();
        new java.util.TreeSet<>(settings.properties().providers().keySet()).forEach(id -> {
            RelyingPartyRegistration registration = findByRegistrationId(id);
            if (registration != null) {
                registrations.add(registration);
            }
        });
        return registrations.iterator();
    }

    /** The stored metadata's state of a provider, for the sign-in page. */
    Optional<SamlMetadataStore.Row> stored(String registrationId) {
        return store.find(registrationId);
    }

    private Cached refresh(String registrationId, @Nullable Cached cached, Instant now) {
        Optional<String> hash = store.hash(registrationId);
        if (cached != null && hash.isPresent() && hash.get().equals(cached.hash())) {
            Cached rechecked = new Cached(cached.hash(), cached.registration(), cached.validUntil(), now);
            cache.put(registrationId, rechecked);
            return rechecked;
        }
        Cached built = build(registrationId, now);
        cache.put(registrationId, built);
        return built;
    }

    private Cached build(String registrationId, Instant now) {
        Optional<SamlMetadataStore.Row> row = store.find(registrationId);
        if (row.isEmpty() || row.get().document() == null) {
            return new Cached(null, null, null, now);
        }
        SamlMetadataStore.Row stored = row.get();
        SamlIdpMetadata.Idp idp;
        try {
            idp = SamlIdpMetadata.parse(stored.document().getBytes(StandardCharsets.UTF_8),
                    settings.properties().provider(registrationId).entityId(), now);
        }
        catch (SamlIdpMetadata.RefusedException ex) {
            // Stored when valid; only its validUntil can have passed since.
            log.warn("The stored IdP metadata of the SAML provider {} is not usable ({}): {}", registrationId,
                    ex.problem().reason(), ex.getMessage());
            return new Cached(stored.documentHash(), null, null, now);
        }
        RelyingPartyRegistration registration = builder(registrationId)
            .assertingPartyMetadata(party -> party.entityId(idp.entityId())
                .singleSignOnServiceLocation(idp.ssoLocation())
                .singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT)
                .wantAuthnRequestsSigned(true)
                .signingAlgorithms(algorithms -> {
                    algorithms.clear();
                    algorithms.add(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
                    algorithms.add(SignatureConstants.ALGO_ID_SIGNATURE_ECDSA_SHA256);
                })
                .verificationX509Credentials(credentials -> idp.signing()
                    .forEach(certificate -> credentials.add(Saml2X509Credential.verification(certificate))))
                .encryptionX509Credentials(credentials -> idp.encryption()
                    .forEach(certificate -> credentials.add(Saml2X509Credential.encryption(certificate)))))
            .build();
        return new Cached(stored.documentHash(), registration, idp.validUntil(), now);
    }

    /** The service provider's side of a registration (decision 2). */
    private RelyingPartyRegistration.Builder builder(String registrationId) {
        String base = settings.publicBaseUrl();
        SamlSpCredentials credentials = settings.credentials();
        return RelyingPartyRegistration.withRegistrationId(registrationId)
            .entityId(base + METADATA_PATH + registrationId)
            .assertionConsumerServiceLocation(base + ACS_PATH + registrationId)
            .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
            .authnRequestsSigned(true)
            // The active credential first: it signs; every one is published and tried to decrypt.
            .signingX509Credentials(signing -> credentials.all()
                .forEach(credential -> signing
                    .add(Saml2X509Credential.signing(credential.privateKey(), credential.certificate()))))
            .decryptionX509Credentials(decryption -> credentials.all()
                .forEach(credential -> decryption
                    .add(Saml2X509Credential.decryption(credential.privateKey(), credential.certificate()))));
    }
}
