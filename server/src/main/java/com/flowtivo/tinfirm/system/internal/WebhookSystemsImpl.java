package com.flowtivo.tinfirm.system.internal;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.WebhookSystems;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.vault.RevealedSecret;
import com.flowtivo.tinfirm.vault.SecretWriter;
import com.flowtivo.tinfirm.vault.VaultService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link WebhookSystems} (the package 5 contract, §3.1, §5.12): the webhook columns of {@code external_system} (V019)
 * and its kind's settings ({@code scheme}, {@code secretHeader}, {@code mapping}, {@code maxPerMinute}), under
 * row-level security. Its secrets are managed company secrets ({@code WEBHOOK_SECRET_{HANDLE}}, {@link #secretName})
 * that Tinfirm generates: the Secrets API neither replaces nor deletes them while the system keeps them
 * ({@link SystemSecretKeeper}), and they go with the system.
 */
@Component
class WebhookSystemsImpl implements WebhookSystems {

    static final String SECRET_NAME = "WEBHOOK_SECRET";

    /**
     * A system's secret's name: {@code WEBHOOK_SECRET_{HANDLE}}, at most 35 characters (a handle has at most 20), so
     * that each system takes its own name (and {@code _2} while a rotated one is kept), not one of the company's 99
     * {@code WEBHOOK_SECRET} names, however many webhook systems it has.
     */
    static String secretName(String handle) {
        return SECRET_NAME + "_" + handle.toUpperCase(Locale.ROOT);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String COLUMNS = "id, company_id, name, handle, settings, paused_at, webhook_secret_id,"
            + " webhook_previous_secret_id, webhook_secret_set_at, webhook_previous_until";

    private final JdbcClient jdbc;

    private final SecretWriter secrets;

    private final VaultService vault;

    private final SystemTransactions transactions;

    private final AuditLog auditLog;

    private final TimeSource time;

    WebhookSystemsImpl(JdbcClient jdbc, SecretWriter secrets, VaultService vault, SystemTransactions transactions,
            AuditLog auditLog, TimeSource time) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.vault = vault;
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.time = time;
    }

    /** A system's row with its secrets' ids. */
    record Row(WebhookSystem system, @Nullable UUID secretId, @Nullable UUID previousId) {
    }

    @Override
    public Optional<UUID> companyOf(UUID systemId) {
        return transactions.unscoped(() -> jdbc.sql("SELECT company_id FROM webhook_system_by_id(?)")
            .param(systemId)
            .query(UUID.class)
            .optional());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WebhookSystem> find(UUID companyId, UUID systemId) {
        return row(companyId, systemId, false).map(Row::system);
    }

    Optional<Row> row(UUID companyId, UUID systemId, boolean lock) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_system WHERE company_id = ? AND id = ?"
                + " AND kind = 'WEBHOOK'" + (lock ? " FOR UPDATE" : ""))
            .params(companyId, systemId)
            .query(WebhookSystemsImpl::row)
            .optional();
    }

    @Override
    public Optional<Secrets> reveal(UUID companyId, UUID systemId, String purpose) {
        NoTransaction.require("Revealing a webhook system's secret");
        return transactions.inCompany(companyId, () -> row(companyId, systemId, false).flatMap(row -> {
            if (row.secretId() == null) {
                return Optional.<Secrets>empty();
            }
            String current;
            try (RevealedSecret secret = vault.reveal(companyId, row.secretId(), purpose)) {
                current = secret.asString();
            }
            String previous = null;
            Instant until = row.system().previousUntil();
            if (row.previousId() != null && until != null && until.isAfter(time.now())) {
                try (RevealedSecret secret = vault.reveal(companyId, row.previousId(), purpose)) {
                    previous = secret.asString();
                }
            }
            return Optional.of(new Secrets(current, previous));
        }));
    }

    /**
     * A new system's secret, generated and kept, in the caller's transaction (the registering admin's): answers it,
     * shown once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    String generate(UUID companyId, UUID systemId, String handle) {
        String value = newSecret();
        UUID id = secrets.create(companyId, secretName(handle), "The signing secret of the webhook system " + handle,
                value);
        jdbc.sql("""
                UPDATE external_system SET webhook_secret_id = ?, webhook_secret_set_at = ?, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(id, SystemStore.ts(time.now()), SystemStore.ts(time.now()), companyId, systemId)
            .update();
        return value;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String rotate(UUID companyId, UUID systemId, boolean revokePrevious) {
        Row row = row(companyId, systemId, true).orElseThrow(() -> new IllegalArgumentException(
                "No webhook system " + systemId));
        Instant now = time.now();
        if (row.previousId() != null) {
            secrets.delete(companyId, row.previousId());
        }
        if (revokePrevious && row.secretId() != null) {
            secrets.delete(companyId, row.secretId());
        }
        String value = newSecret();
        UUID id = secrets.create(companyId, secretName(row.system().handle()), "The signing secret of the webhook "
                + "system " + row.system().handle(), value);
        boolean keepPrevious = !revokePrevious && row.secretId() != null;
        Instant previousUntil = keepPrevious ? now.plus(PREVIOUS_KEPT) : null;
        jdbc.sql("""
                UPDATE external_system
                   SET webhook_previous_secret_id = ?, webhook_previous_until = ?,
                       webhook_secret_id = ?, webhook_secret_set_at = ?, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(keepPrevious ? row.secretId() : null, previousUntil != null ? SystemStore.ts(previousUntil) : null,
                    id, SystemStore.ts(now), SystemStore.ts(now), companyId, systemId)
            .update();
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.SYSTEM_WEBHOOK_SECRET_ROTATED,
                CurrentActor.actor())
            .target(SystemService.TARGET, systemId)
            .detail("previousUntil", previousUntil != null ? previousUntil.toString() : null);
        if (revokePrevious) {
            audit.detail("revokedPrevious", true);
        }
        auditLog.record(audit.build());
        return value;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int lowerPerMinute(UUID companyId, int max) {
        List<UUID> above = jdbc.sql("""
                SELECT id FROM external_system
                 WHERE company_id = ? AND kind = 'WEBHOOK' AND (settings ->> 'maxPerMinute')::integer > ?
                 ORDER BY id FOR UPDATE""")
            .params(companyId, max)
            .query(UUID.class)
            .list();
        for (UUID systemId : above) {
            jdbc.sql("""
                    UPDATE external_system SET settings = jsonb_set(settings, '{maxPerMinute}', to_jsonb(?::integer)),
                           updated_at = ?
                     WHERE company_id = ? AND id = ?""")
                .params(max, SystemStore.ts(time.now()), companyId, systemId)
                .update();
            auditLog.record(AuditRecord.company(companyId, AuditAction.SYSTEM_CHANGED, CurrentActor.actor())
                .target(SystemService.TARGET, systemId)
                .detail("changed", List.of("maxPerMinute"))
                .detail("maxPerMinute", max)
                .build());
        }
        return above.size();
    }

    /** The system whose webhook secret the secret is, for {@link SystemSecretKeeper}. */
    Optional<String> keeperOf(UUID companyId, UUID secretId) {
        return jdbc.sql("SELECT name FROM external_system WHERE company_id = ?"
                + " AND (webhook_secret_id = ? OR webhook_previous_secret_id = ?) LIMIT 1")
            .params(companyId, secretId, secretId)
            .query(String.class)
            .optional();
    }

    /** The webhook system's secrets, for its removal. */
    void deleteSecrets(UUID companyId, UUID systemId) {
        row(companyId, systemId, false).ifPresent(row -> {
            if (row.secretId() != null) {
                secrets.delete(companyId, row.secretId());
            }
            if (row.previousId() != null) {
                secrets.delete(companyId, row.previousId());
            }
        });
    }

    /** {@code whsec_} and 32 random bytes in base64, as Standard Webhooks' libraries take a secret. */
    static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getEncoder().encodeToString(bytes);
    }

    /** The settings of a webhook system as stored, from the checked request. */
    static ObjectNode settings(ObjectNode settings, Scheme scheme, @Nullable String secretHeader,
            Map<String, String> mapping, @Nullable Integer maxPerMinute) {
        settings.put("scheme", scheme.name());
        if (scheme == Scheme.SECRET_HEADER) {
            settings.put("secretHeader", secretHeader != null ? secretHeader : DEFAULT_SECRET_HEADER);
        }
        else {
            settings.remove("secretHeader");
        }
        settings.set("mapping", Json.tree(new LinkedHashMap<>(mapping)));
        if (maxPerMinute != null) {
            settings.put("maxPerMinute", maxPerMinute);
        }
        else {
            settings.remove("maxPerMinute");
        }
        return settings;
    }

    static WebhookSystem system(UUID id, UUID companyId, String name, String handle, boolean paused,
            JsonNode settings, @Nullable Instant secretSetAt, @Nullable Instant previousUntil) {
        String scheme = Json.text(settings, "scheme");
        Map<String, String> mapping = new LinkedHashMap<>();
        JsonNode mapped = settings != null ? settings.get("mapping") : null;
        if (mapped != null && mapped.isObject()) {
            mapped.properties().forEach(entry -> {
                if (entry.getValue().isString()) {
                    mapping.put(entry.getKey(), entry.getValue().asString());
                }
            });
        }
        Integer max = settings != null && settings.hasNonNull("maxPerMinute")
                ? settings.get("maxPerMinute").asInt() : null;
        String header = Json.text(settings, "secretHeader");
        return new WebhookSystem(id, companyId, name, handle, paused,
                scheme != null ? Scheme.valueOf(scheme) : Scheme.STANDARD,
                header != null ? header : DEFAULT_SECRET_HEADER, mapping, max, secretSetAt, previousUntil);
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        JsonNode settings = Json.parse(rs.getString("settings"));
        WebhookSystem system = system(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name"), rs.getString("handle"), rs.getObject("paused_at", OffsetDateTime.class) != null,
                Objects.requireNonNullElse(settings, Json.tree(Map.of())), instant(rs, "webhook_secret_set_at"),
                instant(rs, "webhook_previous_until"));
        return new Row(system, rs.getObject("webhook_secret_id", UUID.class),
                rs.getObject("webhook_previous_secret_id", UUID.class));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }
}
