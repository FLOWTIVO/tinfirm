package com.flowtivo.tinfirm.vault.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.vault.MasterKeyProvider;
import com.flowtivo.tinfirm.vault.RevealedSecret;
import com.flowtivo.tinfirm.vault.ScanReport;
import com.flowtivo.tinfirm.vault.StoredDataKeys;
import com.flowtivo.tinfirm.vault.VaultService;

@Service
class VaultServiceImpl implements VaultService {

    private static final Logger log = LoggerFactory.getLogger(VaultServiceImpl.class);

    private static final int REWRAP_BATCH = 200;

    private static final int MAX_PURPOSE_LENGTH = 200;

    private final SecretRepository secrets;

    private final SecretVersionStore versions;

    private final InstanceKeyStore instanceKeys;

    private final EnvelopeCipher cipher;

    private final MasterKeyProvider masterKeys;

    private final AuditLog auditLog;

    private final TenantScope tenantScope;

    private final TimeSource time;

    private final TransactionTemplate transactions;

    /** The data keys other modules store (the session bundles'), which the rewrap covers too (ADR-0101). */
    private final ObjectProvider<StoredDataKeys> storedDataKeys;

    VaultServiceImpl(SecretRepository secrets, SecretVersionStore versions, InstanceKeyStore instanceKeys,
            EnvelopeCipher cipher, MasterKeyProvider masterKeys, AuditLog auditLog, TenantScope tenantScope,
            TimeSource time, PlatformTransactionManager transactionManager,
            ObjectProvider<StoredDataKeys> storedDataKeys) {
        this.secrets = secrets;
        this.versions = versions;
        this.instanceKeys = instanceKeys;
        this.cipher = cipher;
        this.masterKeys = masterKeys;
        this.auditLog = auditLog;
        this.tenantScope = tenantScope;
        this.time = time;
        this.transactions = new TransactionTemplate(transactionManager);
        this.storedDataKeys = storedDataKeys;
    }

    /*
     * A value that cannot be decrypted leaves the caller's transaction usable: nothing is written before the
     * decryption (the access mark and the audit follow it), so a caller that can do without the value goes on.
     */
    @Override
    @Transactional(noRollbackFor = VaultCryptoException.class)
    public RevealedSecret reveal(UUID companyId, UUID secretId, String purpose) {
        return reveal(companyId, secretId, null, purpose);
    }

    @Override
    @Transactional(noRollbackFor = VaultCryptoException.class)
    public RevealedSecret revealOwned(UUID companyId, UUID secretId, UUID userId, String purpose) {
        return reveal(companyId, secretId, Objects.requireNonNull(userId, "userId"), purpose);
    }

    /**
     * The reveal of both: a secret of the company without a user, a secret the user owns with one (ADR-0049 §5).
     * Anything else is refused before a value is decrypted.
     */
    private RevealedSecret reveal(UUID companyId, UUID secretId, @Nullable UUID userId, String purpose) {
        if (purpose.isBlank() || purpose.length() > MAX_PURPOSE_LENGTH) {
            throw new IllegalArgumentException("purpose must be 1-" + MAX_PURPOSE_LENGTH + " characters");
        }
        // Trusted internal call on behalf of the company (see VaultService#reveal).
        requireNoOtherCompany(companyId);
        tenantScope.enterCompany(companyId);
        SecretEntity secret = secrets.lockByIdAndCompanyId(secretId, companyId)
            .orElseThrow(() -> ApiException.notFound("Secret not found"));
        if (!Objects.equals(secret.getOwnerUserId(), userId)) {
            throw new IllegalStateException(secret.owned()
                    ? "Secret " + secretId + " is a person's own; it is revealed only for a call that acts as its owner"
                    : "Secret " + secretId + " is the company's, not the person's");
        }
        SecretVersionStore.StoredVersion current = versions.find(secretId, secret.getCurrentVersion())
            .orElseThrow(() -> new IllegalStateException("Current version of secret " + secretId + " is missing"));
        byte[] plaintext = cipher.decrypt(current.aad(), current.value());
        secret.markAccessed(time.now());
        auditLog.record(SecretService.owner(AuditRecord.company(companyId, AuditAction.SECRET_ACCESSED,
                CurrentActor.actor())
            .target("secret", secretId)
            .detail("name", secret.getName())
            .detail("version", current.version())
            .detail("purpose", purpose), secret)
            .build());
        return new RevealedSecret(plaintext);
    }

    @Override
    @Transactional(readOnly = true)
    public ScanReport scan(UUID companyId, byte[] content) {
        // Trusted internal call on behalf of the company, like reveal; the values never leave this method.
        requireNoOtherCompany(companyId);
        tenantScope.enterCompany(companyId);
        Map<String, byte[]> values = new LinkedHashMap<>();
        try {
            for (SecretVersionStore.NamedVersion current : versions.currentVersions(companyId)) {
                values.put(current.name(), cipher.decrypt(current.version().aad(), current.version().value()));
            }
            try (CredentialScan scan = new CredentialScan(values)) {
                return scan.scan(content);
            }
        }
        finally {
            values.values().forEach(value -> Arrays.fill(value, (byte) 0));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ContentScanner scanner(UUID companyId) {
        tenantScope.enterCompany(companyId);
        Map<String, byte[]> values = new LinkedHashMap<>();
        try {
            for (SecretVersionStore.NamedVersion current : versions.currentVersions(companyId)) {
                values.put(current.name(), cipher.decrypt(current.version().aad(), current.version().value()));
            }
            // The scan copies what it searches for; the values themselves are wiped now.
            CredentialScan scan = new CredentialScan(values);
            return new ContentScanner() {

                @Override
                public ScanReport scan(InputStream content) throws IOException {
                    return scan.scan(content);
                }

                @Override
                public void close() {
                    scan.close();
                }
            };
        }
        finally {
            values.values().forEach(value -> Arrays.fill(value, (byte) 0));
        }
    }

    @Override
    public RewrapResult rewrapAll() {
        CurrentActor.requireInstanceAdmin();
        String activeKeyId = masterKeys.activeKeyId();
        // Every company's versions: server-side work across companies, in the SYSTEM tenant context (app.system), which
        // the instance admin's own session does not have (CONVENTIONS §7). The admin is audited as the actor below.
        List<String> unavailable = SystemContext.call(() -> transactions.execute(status -> Stream
            .of(versions.keyIdsOtherThan(activeKeyId).stream(), instanceKeys.keyIdsOtherThan(activeKeyId).stream(),
                    storedDataKeys.orderedStream().flatMap(keys -> keys.keyIdsOtherThan(activeKeyId).stream()))
            .flatMap(ids -> ids)
            .distinct()
            .filter(keyId -> !masterKeys.keyIds().contains(keyId))
            .sorted()
            .toList()));
        if (unavailable != null && !unavailable.isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, null, "Data keys are wrapped under master keys "
                    + unavailable + " that are not configured; add them to TINFIRM_VAULT_KEYS and retry");
        }

        long rewrapped = 0;
        int batch;
        do {
            // Short transactions: each batch locks, rewraps and commits its rows.
            Integer done = SystemContext.call(() -> transactions.execute(status -> rewrapBatch(activeKeyId)));
            batch = done != null ? done : 0;
            rewrapped += batch;
        }
        while (batch == REWRAP_BATCH);
        // The keys of the instance (ADR-0045): a handful of rows.
        do {
            Integer done = SystemContext.call(() -> transactions.execute(status -> rewrapInstanceKeys(activeKeyId)));
            batch = done != null ? done : 0;
            rewrapped += batch;
        }
        while (batch == REWRAP_BATCH);
        // The data keys other modules store: each bundle's (ADR-0101).
        for (StoredDataKeys keys : storedDataKeys.orderedStream().toList()) {
            do {
                Integer done = SystemContext.call(() -> transactions.execute(status -> keys.rewrapBatch(activeKeyId,
                        REWRAP_BATCH)));
                batch = done != null ? done : 0;
                rewrapped += batch;
            }
            while (batch == REWRAP_BATCH);
        }

        long total = rewrapped;
        transactions.executeWithoutResult(status -> auditLog.record(
                AuditRecord.instance(AuditAction.VAULT_REWRAPPED, CurrentActor.actor())
                    .target("vault", "master-key")
                    .detail("activeKeyId", activeKeyId)
                    .detail("rewrapped", total)
                    .build()));
        log.info("Vault rewrap finished: activeKeyId={} rewrapped={}", activeKeyId, total);
        return new RewrapResult(activeKeyId, total);
    }

    private int rewrapBatch(String activeKeyId) {
        List<SecretVersionStore.StoredVersion> batch = versions.lockNotWrappedUnder(activeKeyId, REWRAP_BATCH);
        for (SecretVersionStore.StoredVersion version : batch) {
            versions.updateWrapping(version, cipher.rewrap(version.aad(), version.value()));
        }
        return batch.size();
    }

    private int rewrapInstanceKeys(String activeKeyId) {
        List<InstanceKeyStore.StoredKey> batch = instanceKeys.lockNotWrappedUnder(activeKeyId, REWRAP_BATCH);
        for (InstanceKeyStore.StoredKey key : batch) {
            instanceKeys.updateWrapping(key, cipher.rewrap(key.aad(), key.value()));
        }
        return batch.size();
    }

    /**
     * The company a reveal or a scan enters is the one the caller's context is in, or the caller is in none (a user's
     * request before the guard, the system's work): a call that already acts for company A never enters company B,
     * whatever id it was handed. Every caller passes an id it derived on the server; this makes that a guarantee.
     */
    static void requireNoOtherCompany(UUID companyId) {
        UUID current = TenantContextHolder.current().companyId();
        if (current != null && !current.equals(companyId)) {
            throw new IllegalStateException("The vault was asked for company " + companyId
                    + " in the tenant context of company " + current);
        }
    }
}
