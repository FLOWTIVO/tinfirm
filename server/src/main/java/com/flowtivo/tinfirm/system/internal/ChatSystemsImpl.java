package com.flowtivo.tinfirm.system.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.api.model.ExternalSystem;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.vault.RevealedSecret;
import com.flowtivo.tinfirm.vault.SecretWriter;
import com.flowtivo.tinfirm.vault.VaultService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link ChatSystems} (the package 5 contract, §3.1, §3.2): the chat columns of {@code external_system} (V017) and its
 * kind's settings, under row-level security, in the caller's transaction. A Slack system's own secrets are managed
 * company secrets the system keeps ({@code SLACK_SIGNING_SECRET}, {@code SLACK_APP_TOKEN}): the Secrets API neither
 * replaces nor deletes them while it does ({@link SystemSecretKeeper}), and they go with the system.
 */
@Component
class ChatSystemsImpl implements ChatSystems {

    static final String SIGNING_SECRET_NAME = "SLACK_SIGNING_SECRET";

    static final String APP_TOKEN_NAME = "SLACK_APP_TOKEN";

    private static final String COLUMNS = "id, company_id, name, handle, kind, base_url, settings, status,"
            + " bot_account_id, chat_signing_secret_id, chat_app_token_secret_id, paused_at";

    private final JdbcClient jdbc;

    private final SecretWriter secrets;

    private final VaultService vault;

    private final TransactionTemplate transactions;

    private final TimeSource time;

    private final ObjectProvider<SystemService> systemService;

    ChatSystemsImpl(JdbcClient jdbc, SecretWriter secrets, VaultService vault,
            PlatformTransactionManager transactionManager, TimeSource time,
            ObjectProvider<SystemService> systemService) {
        this.systemService = systemService;
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.vault = vault;
        this.transactions = new TransactionTemplate(transactionManager);
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChatSystemInfo> find(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_system WHERE company_id = ? AND id = ?"
                + " AND kind IN ('SLACK', 'TEAMS')")
            .params(companyId, systemId)
            .query(ChatSystemsImpl::row)
            .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ChatSystemInfo> lock(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_system WHERE company_id = ? AND id = ?"
                + " AND kind IN ('SLACK', 'TEAMS') FOR UPDATE")
            .params(companyId, systemId)
            .query(ChatSystemsImpl::row)
            .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void setBot(UUID companyId, UUID systemId, UUID accountId, @Nullable String teamId,
            @Nullable String teamName, @Nullable String botUserId) {
        ObjectNode settings = settings(companyId, systemId);
        if (teamId != null) {
            settings.put("teamId", teamId);
        }
        if (teamName != null) {
            settings.put("teamName", teamName);
        }
        if (botUserId != null) {
            settings.put("botUserId", botUserId);
        }
        jdbc.sql("""
                UPDATE external_system SET bot_account_id = ?, settings = ?::jsonb, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(accountId, Json.write(settings), SystemStore.ts(time.now()), companyId, systemId)
            .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearBot(UUID companyId, UUID systemId) {
        ObjectNode settings = settings(companyId, systemId);
        settings.remove("botUserId");
        jdbc.sql("""
                UPDATE external_system SET bot_account_id = NULL, settings = ?::jsonb, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(Json.write(settings), SystemStore.ts(time.now()), companyId, systemId)
            .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean storeSecret(UUID companyId, UUID systemId, ChatSecret which, String value) {
        ChatSystemInfo system = find(companyId, systemId).orElseThrow();
        UUID current = which == ChatSecret.SIGNING_SECRET ? system.signingSecretId() : system.appTokenId();
        if (current != null && secrets.replace(companyId, current, value)) {
            return true;
        }
        String name = which == ChatSecret.SIGNING_SECRET ? SIGNING_SECRET_NAME : APP_TOKEN_NAME;
        String description = (which == ChatSecret.SIGNING_SECRET ? "The signing secret" : "The app-level token")
                + " of the Slack system " + system.system().handle();
        UUID created = secrets.create(companyId, name, description, value);
        String column = which == ChatSecret.SIGNING_SECRET ? "chat_signing_secret_id" : "chat_app_token_secret_id";
        jdbc.sql("UPDATE external_system SET " + column + " = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(created, SystemStore.ts(time.now()), companyId, systemId)
            .update();
        return false;
    }

    @Override
    public Optional<String> revealSecret(UUID companyId, UUID systemId, ChatSecret which, String purpose) {
        NoTransaction.require("Revealing a chat system's secret");
        return Objects.requireNonNull(transactions.execute(status -> {
            ChatSystemInfo system = find(companyId, systemId).orElse(null);
            UUID secretId = system == null ? null
                    : which == ChatSecret.SIGNING_SECRET ? system.signingSecretId() : system.appTokenId();
            if (secretId == null) {
                return Optional.<String>empty();
            }
            try (RevealedSecret secret = vault.reveal(companyId, secretId, purpose)) {
                return Optional.of(secret.asString());
            }
        }));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void status(UUID companyId, UUID systemId, String status, @Nullable String lastError) {
        jdbc.sql("UPDATE external_system SET status = ?, last_error = ? WHERE company_id = ? AND id = ?"
                + " AND (status IS DISTINCT FROM ? OR last_error IS DISTINCT FROM ?)")
            .params(status, lastError != null ? Json.cut(lastError, 500) : null, companyId, systemId, status,
                    lastError != null ? Json.cut(lastError, 500) : null)
            .update();
    }

    @Override
    public ExternalSystem view(UUID companyId, UUID systemId) {
        return systemService.getObject().get(companyId, systemId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> botOf(UUID companyId, UUID accountId) {
        return jdbc.sql("SELECT id FROM external_system WHERE company_id = ? AND bot_account_id = ?")
            .params(companyId, accountId)
            .query(UUID.class)
            .optional();
    }

    /** The system whose own chat secret the secret is, for {@link SystemSecretKeeper}. */
    Optional<String> keeperOf(UUID companyId, UUID secretId) {
        return jdbc.sql("SELECT name FROM external_system WHERE company_id = ?"
                + " AND (chat_signing_secret_id = ? OR chat_app_token_secret_id = ?) LIMIT 1")
            .params(companyId, secretId, secretId)
            .query(String.class)
            .optional();
    }

    /** The chat system's own secrets, for its removal. */
    void deleteSecrets(UUID companyId, UUID systemId) {
        find(companyId, systemId).ifPresent(system -> {
            if (system.signingSecretId() != null) {
                secrets.delete(companyId, system.signingSecretId());
            }
            if (system.appTokenId() != null) {
                secrets.delete(companyId, system.appTokenId());
            }
        });
    }

    private ObjectNode settings(UUID companyId, UUID systemId) {
        String json = jdbc.sql("SELECT settings FROM external_system WHERE company_id = ? AND id = ?")
            .params(companyId, systemId)
            .query(String.class)
            .single();
        JsonNode node = Json.parse(json);
        return node instanceof ObjectNode object ? object : (ObjectNode) Json.tree(Map.of());
    }

    private static ChatSystemInfo row(ResultSet rs, int row) throws SQLException {
        JsonNode settings = Json.parse(rs.getString("settings"));
        SystemKind kind = SystemKind.valueOf(rs.getString("kind"));
        SystemInfo system = new SystemInfo(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name"), rs.getString("handle"), kind, rs.getString("base_url"), null, null, false,
                rs.getObject("paused_at", java.time.OffsetDateTime.class) != null);
        return new ChatSystemInfo(system, Json.text(settings, "transport"), Json.text(settings, "teamId"),
                Json.text(settings, "teamName"), Json.text(settings, "tenantId"), Json.text(settings, "appId"),
                Json.text(settings, "botUserId"), rs.getObject("bot_account_id", UUID.class),
                rs.getObject("chat_signing_secret_id", UUID.class),
                rs.getObject("chat_app_token_secret_id", UUID.class), rs.getString("status"));
    }
}
