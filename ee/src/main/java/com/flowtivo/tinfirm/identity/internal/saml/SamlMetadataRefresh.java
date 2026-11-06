package com.flowtivo.tinfirm.identity.internal.saml;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.internal.SamlProperties;
import com.flowtivo.tinfirm.identity.internal.SamlSettings;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.net.BoundedBodies;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * Reads each SAML provider's IdP metadata (ADR-0086, decision 3) on a {@code worker} replica, one at a time across
 * the replicas: at the start, then every {@code _METADATA_REFRESH}, sooner when the document's {@code cacheDuration}
 * or {@code validUntil} asks. The result goes to {@code saml_idp_metadata}, from which every replica builds its
 * registration. A URL is fetched over verified TLS, at most 1 MiB and within one timeout from the request to the end
 * of the body ({@link BoundedBodies}: a host that stalls holds back no other provider's read), following a redirect
 * only to the same host; a file is read again too, so that a replaced ConfigMap needs no restart. A failed read keeps
 * the last good document.
 * <p>
 * The IdP's entity ID is pinned (H4 of the security review of 2026-10-27): by {@code _ENTITY_ID}, else by the
 * provider's first good document; a document that names another IdP is refused ({@code entity_id_changed}), and so is
 * one that names the IdP of another provider reading other metadata, or an OIDC provider's issuer
 * ({@code entity_id_in_use}). The last good document stays in use, and the sign-in page shows the refusal.
 * <p>
 * Audited: {@code saml.idp_keys.changed} when the signing certificates change, {@code saml.idp_entity_id_changed}
 * when a document names another IdP than the pinned one, and {@code saml.metadata.failed} at the first other failure
 * after a good read. At the start, on a worker, a provider without any good document whose read fails stops the server
 * under a licence that serves SAML: a deployment never runs with a provider nobody can sign in with. Two providers
 * whose documents, as stored, name one IdP from different metadata, or a provider that names an OIDC issuer, stop it
 * under any licence.
 */
@Component
class SamlMetadataRefresh {

    private static final Logger log = LoggerFactory.getLogger(SamlMetadataRefresh.class);

    private static final String LOCK = "saml.metadata-refresh";

    /** After a failed read, the next try. */
    static final Duration RETRY = Duration.ofMinutes(5);

    /** The connect timeout, and the whole budget of one fetch from its request to the end of the body. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final int MAX_REDIRECTS = 3;

    private final SamlSettings settings;

    private final SamlMetadataStore store;

    private final AuditLog auditLog;

    private final SystemTransactions transactions;

    private final LockProvider locks;

    private final TimeSource time;

    private final TinfirmProperties tinfirm;

    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(TIMEOUT)
        .proxy(ProxySelector.getDefault())
        .build();

    SamlMetadataRefresh(SamlSettings settings, SamlMetadataStore store, AuditLog auditLog,
            SystemTransactions transactions, LockProvider locks, TimeSource time, TinfirmProperties tinfirm,
            Licensing licensing) {
        this.settings = settings;
        this.store = store;
        this.auditLog = auditLog;
        this.transactions = transactions;
        this.locks = locks;
        this.time = time;
        this.tinfirm = tinfirm;
        if (settings.declared() && tinfirm.hasRole(TinfirmProperties.WORKER_ROLE)) {
            List<String> failed = refreshAll(true);
            if (!failed.isEmpty() && licensing.has(Feature.SAML)) {
                throw new IllegalStateException("The IdP metadata of the SAML providers " + failed + " cannot be read, "
                        + "and none was read before: check TINFIRM_AUTH_SAML_PROVIDERS_<ID>_METADATA_LOCATION "
                        + "(ADR-0086, decision 3)");
            }
            List<String> conflicts = settings.properties().entityIdConflicts(entityIds(), settings.oidcIssuers());
            if (!conflicts.isEmpty()) {
                throw new IllegalStateException("The SAML providers name IdPs they may not (H4 of the security review "
                        + "of 2026-10-27): " + String.join("; ", conflicts) + ". Set _ENTITY_ID, or declare one "
                        + "provider for one IdP");
            }
        }
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void tick() {
        if (settings.declared() && tinfirm.hasRole(TinfirmProperties.WORKER_ROLE)) {
            refreshAll(false);
        }
    }

    /**
     * Reads the providers whose next read is due, or every one when {@code all}, under the lock.
     *
     * @return the providers whose read failed while they hold no good document
     */
    List<String> refreshAll(boolean all) {
        Optional<SimpleLock> lock = locks.lock(new LockConfiguration(time.now(), LOCK, Duration.ofMinutes(5),
                Duration.ZERO));
        if (lock.isEmpty()) {
            return List.of();
        }
        try {
            List<String> withoutDocument = new java.util.ArrayList<>();
            for (Map.Entry<String, SamlProperties.Provider> entry : new java.util.TreeMap<>(
                    settings.properties().providers()).entrySet()) {
                Optional<SamlMetadataStore.Row> row = store.find(entry.getKey());
                if (!all && row.isPresent() && row.get().nextReadAt().isAfter(time.now())) {
                    continue;
                }
                if (!refresh(entry.getKey(), entry.getValue(), row.orElse(null))
                        && (row.isEmpty() || row.get().document() == null)) {
                    withoutDocument.add(entry.getKey());
                }
            }
            return withoutDocument;
        }
        finally {
            lock.get().unlock();
        }
    }

    /** Reads one provider's metadata now; whether the read succeeded. */
    boolean refresh(String id, SamlProperties.Provider provider, SamlMetadataStore.@Nullable Row previous) {
        Instant now = time.now();
        byte[] document;
        SamlIdpMetadata.Idp idp;
        String text;
        try {
            document = read(provider.metadataLocation());
            idp = SamlIdpMetadata.parse(document, provider.entityId(), now);
            SamlIdpMetadata.requirePinned(idp, provider.entityId(), previous != null ? previous.entityId() : null);
            requireOwnEntityId(id, idp);
            // Stored as UTF-8 whatever the document's own encoding, as the replicas read it back.
            text = SamlIdpMetadata.utf8(document);
        }
        catch (SamlIdpMetadata.RefusedException ex) {
            failed(id, previous, ex, now);
            return false;
        }
        String hash = sha256(text.getBytes(StandardCharsets.UTF_8));
        Instant next = nextRead(provider, idp, now);
        transactions.instanceWide(() -> {
            store.stored(id, text, hash, idp, now, next);
            List<SamlIdpMetadata.Certificate> before = previous != null && previous.document() != null
                    ? previous.certificates() : null;
            List<SamlIdpMetadata.Certificate> after = idp.certificates();
            if (before != null && !fingerprints(before).equals(fingerprints(after))) {
                auditLog.record(AuditRecord.instance(AuditAction.SAML_IDP_KEYS_CHANGED, Actor.SYSTEM)
                    .detail("provider", id)
                    .detail("before", certificates(before))
                    .detail("after", certificates(after))
                    .build());
            }
            return null;
        });
        if (previous == null || !hash.equals(previous.documentHash())) {
            log.info("Read the IdP metadata of the SAML provider {}: {} with {} signing certificate(s)", id,
                    idp.entityId(), idp.signing().size());
        }
        return true;
    }

    /**
     * Refuses a document that names the IdP of another provider reading other metadata, as configured or as last
     * read, or an OIDC provider's issuer (H4).
     */
    private void requireOwnEntityId(String id, SamlIdpMetadata.Idp idp) throws SamlIdpMetadata.RefusedException {
        Map<String, String> entityIds = new java.util.HashMap<>(entityIds());
        entityIds.put(id, idp.entityId());
        String conflict = settings.properties()
            .entityIdConflict(id, idp.entityId(), entityIds, settings.oidcIssuers());
        if (conflict != null) {
            throw new SamlIdpMetadata.RefusedException(SamlIdpMetadata.Problem.ENTITY_ID_IN_USE, conflict,
                    idp.entityId());
        }
    }

    /** Each declared provider's IdP: its {@code _ENTITY_ID}, else the one its last good document named, if any. */
    private Map<String, String> entityIds() {
        Map<String, String> entityIds = new java.util.TreeMap<>();
        settings.properties().providers().forEach((id, provider) -> {
            String entityId = provider.entityId() != null ? provider.entityId()
                    : store.find(id).map(SamlMetadataStore.Row::entityId).orElse(null);
            if (entityId != null) {
                entityIds.put(id, entityId);
            }
        });
        return entityIds;
    }

    private void failed(String id, SamlMetadataStore.@Nullable Row previous, SamlIdpMetadata.RefusedException refusal,
            Instant now) {
        SamlIdpMetadata.Problem problem = refusal.problem();
        log.warn("The IdP metadata of the SAML provider {} could not be read ({}): {}; the last good one stays in use",
                id, problem.reason(), refusal.getMessage());
        transactions.instanceWide(() -> {
            store.failed(id, problem.reason(), now, now.plus(RETRY));
            if (problem == SamlIdpMetadata.Problem.ENTITY_ID_CHANGED) {
                // Each time the refusal starts, not at every read while it lasts.
                if (previous == null || !problem.reason().equals(previous.lastError())) {
                    auditLog.record(AuditRecord.instance(AuditAction.SAML_IDP_ENTITY_ID_CHANGED, Actor.SYSTEM)
                        .detail("provider", id)
                        .detail("pinned", previous != null ? previous.entityId() : null)
                        .detail("named", refusal.entityId())
                        .build());
                }
            }
            else if (previous == null || previous.lastError() == null) {
                auditLog.record(AuditRecord.instance(AuditAction.SAML_METADATA_FAILED, Actor.SYSTEM)
                    .detail("provider", id)
                    .detail("reason", problem.reason())
                    .build());
            }
            return null;
        });
    }

    /**
     * The next read: the provider's refresh, sooner when the document's {@code cacheDuration} asks or half its
     * remaining validity runs out first; never sooner than 5 minutes.
     */
    static Instant nextRead(SamlProperties.Provider provider, SamlIdpMetadata.Idp idp, Instant now) {
        Duration wait = provider.metadataRefresh();
        if (idp.cacheDuration() != null && idp.cacheDuration().compareTo(wait) < 0) {
            wait = idp.cacheDuration();
        }
        if (idp.validUntil() != null) {
            Duration half = Duration.between(now, idp.validUntil()).dividedBy(2);
            if (half.compareTo(wait) < 0) {
                wait = half;
            }
        }
        return now.plus(wait.compareTo(RETRY) < 0 ? RETRY : wait);
    }

    /** The document at the location: an {@code https://} URL, or a {@code file:} path. */
    byte[] read(String location) throws SamlIdpMetadata.RefusedException {
        if (location.startsWith("file:")) {
            Path path;
            try {
                path = org.springframework.util.ResourceUtils.getFile(location).toPath();
            }
            catch (IOException ex) {
                throw new SamlIdpMetadata.RefusedException(SamlIdpMetadata.Problem.UNREACHABLE,
                        "the location " + location + " is not a file", ex);
            }
            try (InputStream in = Files.newInputStream(path)) {
                return limited(in);
            }
            catch (IOException ex) {
                throw new SamlIdpMetadata.RefusedException(SamlIdpMetadata.Problem.UNREACHABLE,
                        "the file " + path + " cannot be read: " + ex.getMessage(), ex);
            }
        }
        URI uri = URI.create(location);
        String host = uri.getHost();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<byte @Nullable []> response;
            try {
                // The handler is made right before the send: its deadline covers the request and the body together.
                response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("Accept", "application/samlmetadata+xml, application/xml, text/xml")
                    .GET()
                    .build(), BoundedBodies.ofByteArray(SamlIdpMetadata.MAX_BYTES, TIMEOUT));
            }
            catch (IOException ex) {
                throw new SamlIdpMetadata.RefusedException(SamlIdpMetadata.Problem.UNREACHABLE,
                        "GET " + uri + " failed: " + ex.getMessage(), ex);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SamlIdpMetadata.RefusedException(SamlIdpMetadata.Problem.UNREACHABLE, "interrupted", ex);
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                Optional<String> target = response.headers().firstValue("Location");
                URI next = target.map(uri::resolve).orElse(null);
                if (next == null || !"https".equalsIgnoreCase(next.getScheme())
                        || !Objects.equals(host, next.getHost()) || uri.getPort() != next.getPort()) {
                    throw new SamlIdpMetadata.RefusedException(SamlIdpMetadata.Problem.UNREACHABLE,
                            "GET " + uri + " redirects to another host or port, or not over https");
                }
                uri = next;
                continue;
            }
            if (status != 200) {
                throw new SamlIdpMetadata.RefusedException(SamlIdpMetadata.Problem.UNREACHABLE,
                        "GET " + uri + " answered " + status);
            }
            byte[] document = response.body();
            if (document == null) {
                throw tooLarge();
            }
            return document;
        }
        throw new SamlIdpMetadata.RefusedException(SamlIdpMetadata.Problem.UNREACHABLE,
                "more than " + MAX_REDIRECTS + " redirects from " + location);
    }

    /** A file's bytes up to the limit; past it, the document is refused as an answer past it is. */
    private static byte[] limited(InputStream in) throws IOException, SamlIdpMetadata.RefusedException {
        byte[] read = in.readNBytes(SamlIdpMetadata.MAX_BYTES + 1);
        if (read.length > SamlIdpMetadata.MAX_BYTES) {
            throw tooLarge();
        }
        return read;
    }

    private static SamlIdpMetadata.RefusedException tooLarge() {
        return new SamlIdpMetadata.RefusedException(SamlIdpMetadata.Problem.TOO_LARGE,
                "the document is over " + SamlIdpMetadata.MAX_BYTES + " bytes");
    }

    private static Set<String> fingerprints(List<SamlIdpMetadata.Certificate> certificates) {
        return certificates.stream().map(SamlIdpMetadata.Certificate::sha256).collect(Collectors.toSet());
    }

    private static List<Map<String, String>> certificates(List<SamlIdpMetadata.Certificate> certificates) {
        return certificates.stream()
            .map(certificate -> Map.of("sha256", certificate.sha256(), "notAfter", certificate.notAfter().toString()))
            .toList();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
