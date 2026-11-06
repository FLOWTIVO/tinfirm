package com.flowtivo.tinfirm.account.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;

/**
 * SQL access to {@code account}, {@code account_grant} (V001) and {@code service_account_credential} (V049), under
 * row-level security: the accounts and grants of the package 1 contract, §3.6 and §3.7, and the credentials a system
 * holds, lent to groups and to service accounts (ADR-0072).
 */
@Repository
class AccountStore {

    private static final String ACCOUNT_COLUMNS = "id, company_id, system_id, principal_id, principal_kind, name,"
            + " enabled, kind, secret_id, external_id, external_login, api_base_url, scopes, access_expires_at, status,"
            + " last_error, connected_at, connected_by, last_used_at";

    /** {@link #ACCOUNT_COLUMNS} of the account aliased {@code a}. */
    private static final String GRANT_COLUMNS = "g.id, g.company_id, g.account_id, a.system_id, g.group_id, g.access,"
            + " g.created_at, g.updated_at";

    /** The principal kind of a credential the system holds (V049). */
    static final String SYSTEM_KIND = "SYSTEM";

    private final JdbcClient jdbc;

    AccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An account row: what {@link AccountInfo} shows, and where its credential is.
     *
     * @param principalId   the member whose own account it is; null for a credential the system holds (ADR-0072)
     * @param principalKind {@code MEMBER}; null for a credential
     * @param name          a credential's name; null for a member's own account
     * @param connectedBy   who connected it last; null once the user is deleted
     */
    record AccountRow(UUID id, UUID companyId, UUID systemId, @Nullable UUID principalId,
            @Nullable PrincipalKind principalKind, @Nullable String name, boolean enabled, AccountInfo.Kind kind,
            @Nullable UUID secretId, @Nullable String externalId, @Nullable String externalLogin,
            @Nullable String apiBaseUrl, @Nullable String scopes, @Nullable Instant accessExpiresAt,
            AccountInfo.Status status, @Nullable String lastError, Instant connectedAt, @Nullable UUID connectedBy,
            @Nullable Instant lastUsedAt) {

        AccountInfo info() {
            return new AccountInfo(id, companyId, systemId, principalId, principalKind, name, kind, externalId,
                    externalLogin, status, enabled);
        }

        boolean credential() {
            return principalId == null;
        }
    }

    /** A grant row with the credential it lends. */
    record GrantRow(GrantInfo info, Instant createdAt, Instant updatedAt) {
    }

    /** A credential lent to a service account on its system (V049). */
    record LendingRow(UUID companyId, UUID servicePrincipalId, UUID systemId, UUID accountId, GrantInfo.Access access,
            Instant createdAt, Instant updatedAt) {
    }

    // ---- Accounts --------------------------------------------------------------------------------------------------

    void insert(AccountRow account, @Nullable UUID connectedBy) {
        jdbc.sql("""
                INSERT INTO account (id, company_id, system_id, principal_id, principal_kind, name, enabled, kind,
                                     secret_id, external_id, external_login, api_base_url, scopes, access_expires_at,
                                     status, last_error, connected_at, connected_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(account.id(), account.companyId(), account.systemId(), account.principalId(),
                    account.principalKind() != null ? account.principalKind().name() : SYSTEM_KIND, account.name(),
                    account.enabled(), account.kind().name(), account.secretId(), account.externalId(),
                    account.externalLogin(), account.apiBaseUrl(), account.scopes(), ts(account.accessExpiresAt()),
                    account.status().name(), account.lastError(), ts(account.connectedAt()), connectedBy,
                    ts(account.connectedAt()))
            .update();
    }

    /** Stores a new credential of the account and who it is: signed in or stored again, {@code OK}. */
    void reconnect(AccountRow account, @Nullable UUID connectedBy) {
        jdbc.sql("""
                UPDATE account SET kind = ?, secret_id = ?, external_id = ?, external_login = ?, api_base_url = ?,
                                   scopes = ?, access_expires_at = ?, status = 'OK', last_error = NULL,
                                   connected_at = ?, connected_by = ?, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(account.kind().name(), account.secretId(), account.externalId(), account.externalLogin(),
                    account.apiBaseUrl(), account.scopes(), ts(account.accessExpiresAt()), ts(account.connectedAt()),
                    connectedBy, ts(account.connectedAt()), account.companyId(), account.id())
            .update();
    }

    /** A refreshed OAuth grant: its expiry and scopes. */
    void refreshed(UUID companyId, UUID id, @Nullable Instant expiresAt, @Nullable String scopes, Instant at) {
        jdbc.sql("""
                UPDATE account SET access_expires_at = ?, scopes = coalesce(?, scopes), updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(ts(expiresAt), scopes, ts(at), companyId, id)
            .update();
    }

    void markStatus(UUID companyId, UUID id, AccountInfo.Status status, @Nullable String lastError, Instant at) {
        jdbc.sql("UPDATE account SET status = ?, last_error = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(status.name(), lastError, ts(at), companyId, id)
            .update();
    }

    void markUsed(UUID companyId, UUID id, Instant at) {
        jdbc.sql("UPDATE account SET last_used_at = ? WHERE company_id = ? AND id = ?")
            .params(ts(at), companyId, id)
            .update();
    }

    Optional<AccountRow> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(AccountStore::account)
            .optional();
    }

    Optional<AccountRow> lock(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE company_id = ? AND id = ? FOR UPDATE")
            .params(companyId, id)
            .query(AccountStore::account)
            .optional();
    }

    /** The member's own account on the system (I1), locked when {@code forUpdate}. */
    Optional<AccountRow> of(UUID companyId, UUID systemId, UUID principalId, boolean forUpdate) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE company_id = ? AND system_id = ?"
                + " AND principal_id = ?" + (forUpdate ? " FOR UPDATE" : ""))
            .params(companyId, systemId, principalId)
            .query(AccountStore::account)
            .optional();
    }

    /**
     * The account on the system with this identity (I2): a member's own when one holds it, else one of the system's
     * credentials, which may share it (ADR-0072).
     */
    Optional<AccountRow> withIdentity(UUID companyId, UUID systemId, String externalId) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE company_id = ? AND system_id = ?"
                + " AND external_id = ? ORDER BY principal_id NULLS LAST, id LIMIT 1")
            .params(companyId, systemId, externalId)
            .query(AccountStore::account)
            .optional();
    }

    /** The system's credentials, by name. */
    List<AccountRow> credentialsOn(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE company_id = ? AND system_id = ?"
                + " AND principal_id IS NULL ORDER BY lower(name), id")
            .params(companyId, systemId)
            .query(AccountStore::account)
            .list();
    }

    /** How many members hold their own account on the system: those {@code OK}, and the others (I4). */
    int[] memberAccountCounts(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT count(*) FILTER (WHERE status = 'OK'), count(*) FILTER (WHERE status <> 'OK')"
                + " FROM account WHERE company_id = ? AND system_id = ? AND principal_id IS NOT NULL AND kind <> 'LINK'")
            .params(companyId, systemId)
            .query((rs, row) -> new int[] {rs.getInt(1), rs.getInt(2)})
            .single();
    }

    /** Whether another credential on the system has this name, ignoring case. */
    boolean credentialNameTaken(UUID companyId, UUID systemId, String name, @Nullable UUID except) {
        return jdbc.sql("SELECT count(*) FROM account WHERE company_id = ? AND system_id = ? AND principal_id IS NULL"
                + " AND lower(name) = lower(?) AND id IS DISTINCT FROM ?")
            .params(companyId, systemId, name, except)
            .query(Long.class)
            .single() > 0;
    }

    void rename(UUID companyId, UUID id, String name, Instant at) {
        jdbc.sql("UPDATE account SET name = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(name, ts(at), companyId, id)
            .update();
    }

    void enable(UUID companyId, UUID id, boolean enabled, Instant at) {
        jdbc.sql("UPDATE account SET enabled = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(enabled, ts(at), companyId, id)
            .update();
    }

    /** The account that keeps its credential in the secret. */
    Optional<AccountRow> withSecret(UUID companyId, UUID secretId) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE company_id = ? AND secret_id = ?"
                + " ORDER BY id LIMIT 1")
            .params(companyId, secretId)
            .query(AccountStore::account)
            .optional();
    }

    /** The member's own accounts, by system. */
    List<AccountRow> ofPrincipal(UUID companyId, UUID principalId) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE company_id = ? AND principal_id = ?"
                + " ORDER BY system_id")
            .params(companyId, principalId)
            .query(AccountStore::account)
            .list();
    }

    /**
     * The credentials the user connected and nobody connected again since (the package 6 contract, §5.5: the token may
     * be one a member who left made).
     */
    List<AccountRow> credentialsConnectedBy(UUID companyId, UUID userId) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE company_id = ? AND connected_by = ?"
                + " AND principal_id IS NULL ORDER BY system_id, id")
            .params(companyId, userId)
            .query(AccountStore::account)
            .list();
    }

    /** The accounts on the system, locked, for its removal. */
    List<AccountRow> lockOnSystem(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE company_id = ? AND system_id = ?"
                + " ORDER BY id FOR UPDATE")
            .params(companyId, systemId)
            .query(AccountStore::account)
            .list();
    }

    /** The member's own accounts, locked, for their leaving. */
    List<AccountRow> lockOfPrincipal(UUID companyId, UUID principalId) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE company_id = ? AND principal_id = ?"
                + " ORDER BY id FOR UPDATE")
            .params(companyId, principalId)
            .query(AccountStore::account)
            .list();
    }

    void delete(UUID companyId, UUID id) {
        jdbc.sql("DELETE FROM account WHERE company_id = ? AND id = ?").params(companyId, id).update();
    }

    // ---- Lendings to service accounts (V049) ------------------------------------------------------------------------

    /** Lends the credential to the service account on its system, replacing what it borrowed there. */
    void lend(LendingRow lending, @Nullable UUID createdBy) {
        jdbc.sql("""
                INSERT INTO service_account_credential (company_id, principal_id, system_id, account_id, access,
                                                        created_at, updated_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (principal_id, system_id)
                DO UPDATE SET account_id = EXCLUDED.account_id, access = EXCLUDED.access,
                              updated_at = EXCLUDED.updated_at""")
            .params(lending.companyId(), lending.servicePrincipalId(), lending.systemId(), lending.accountId(),
                    lending.access().name(), ts(lending.createdAt()), ts(lending.updatedAt()), createdBy)
            .update();
    }

    void withdraw(UUID companyId, UUID servicePrincipalId, UUID systemId) {
        jdbc.sql("DELETE FROM service_account_credential WHERE company_id = ? AND principal_id = ? AND system_id = ?")
            .params(companyId, servicePrincipalId, systemId)
            .update();
    }

    /** What the service account borrows on the system, locked when {@code forUpdate}. */
    Optional<LendingRow> lending(UUID companyId, UUID servicePrincipalId, UUID systemId, boolean forUpdate) {
        return jdbc.sql("SELECT company_id, principal_id, system_id, account_id, access, created_at, updated_at"
                + " FROM service_account_credential WHERE company_id = ? AND principal_id = ? AND system_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""))
            .params(companyId, servicePrincipalId, systemId)
            .query(AccountStore::lending)
            .optional();
    }

    /** Who lent the service account what it borrows on the system, when it was recorded. */
    Optional<UUID> lentBy(UUID companyId, UUID servicePrincipalId, UUID systemId) {
        return jdbc.sql("SELECT created_by FROM service_account_credential"
                + " WHERE company_id = ? AND principal_id = ? AND system_id = ? AND created_by IS NOT NULL")
            .params(companyId, servicePrincipalId, systemId)
            .query(UUID.class)
            .optional();
    }

    /** The service accounts the credential is lent to, oldest lending first. */
    List<LendingRow> lendingsOf(UUID companyId, UUID accountId) {
        return jdbc.sql("SELECT company_id, principal_id, system_id, account_id, access, created_at, updated_at"
                + " FROM service_account_credential WHERE company_id = ? AND account_id = ?"
                + " ORDER BY created_at, principal_id")
            .params(companyId, accountId)
            .query(AccountStore::lending)
            .list();
    }

    /** The lendings of the system's credentials. */
    List<LendingRow> lendingsOn(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT company_id, principal_id, system_id, account_id, access, created_at, updated_at"
                + " FROM service_account_credential WHERE company_id = ? AND system_id = ?"
                + " ORDER BY created_at, principal_id")
            .params(companyId, systemId)
            .query(AccountStore::lending)
            .list();
    }

    /** What the service account borrows, by system. */
    List<LendingRow> lendingsTo(UUID companyId, UUID servicePrincipalId) {
        return jdbc.sql("SELECT company_id, principal_id, system_id, account_id, access, created_at, updated_at"
                + " FROM service_account_credential WHERE company_id = ? AND principal_id = ? ORDER BY system_id")
            .params(companyId, servicePrincipalId)
            .query(AccountStore::lending)
            .list();
    }

    /** The lendings of the credential, locked, for its removal. */
    List<LendingRow> lockLendingsOf(UUID companyId, UUID accountId) {
        return jdbc.sql("SELECT company_id, principal_id, system_id, account_id, access, created_at, updated_at"
                + " FROM service_account_credential WHERE company_id = ? AND account_id = ?"
                + " ORDER BY principal_id FOR UPDATE")
            .params(companyId, accountId)
            .query(AccountStore::lending)
            .list();
    }

    /** The grants of the system's credentials, by (created_at, id). */
    List<GrantRow> grantsOn(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM account_grant g JOIN account a ON a.id = g.account_id"
                + " WHERE g.company_id = ? AND a.system_id = ? ORDER BY g.created_at, g.id")
            .params(companyId, systemId)
            .query(AccountStore::grant)
            .list();
    }

    // ---- Grants ----------------------------------------------------------------------------------------------------

    void insertGrant(GrantInfo grant, Instant at, @Nullable UUID createdBy) {
        jdbc.sql("""
                INSERT INTO account_grant (id, company_id, account_id, group_id, access, created_at, updated_at,
                                           created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(grant.id(), grant.companyId(), grant.accountId(), grant.groupId(), grant.access().name(), ts(at),
                    ts(at), createdBy)
            .update();
    }

    void updateGrant(UUID companyId, UUID id, GrantInfo.Access access, Instant at) {
        jdbc.sql("UPDATE account_grant SET access = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(access.name(), ts(at), companyId, id)
            .update();
    }

    void deleteGrant(UUID companyId, UUID id) {
        jdbc.sql("DELETE FROM account_grant WHERE company_id = ? AND id = ?").params(companyId, id).update();
    }

    Optional<GrantRow> grant(UUID companyId, UUID id, boolean forUpdate) {
        return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM account_grant g JOIN account a ON a.id = g.account_id"
                + " WHERE g.company_id = ? AND g.id = ?" + (forUpdate ? " FOR UPDATE OF g" : ""))
            .params(companyId, id)
            .query(AccountStore::grant)
            .optional();
    }

    boolean grantExists(UUID companyId, UUID accountId, UUID groupId) {
        return jdbc.sql("SELECT count(*) FROM account_grant WHERE company_id = ? AND account_id = ? AND group_id = ?")
            .params(companyId, accountId, groupId)
            .query(Long.class)
            .single() > 0;
    }

    /** Grants ordered by (created_at, id), after the given position. */
    List<GrantRow> grantPage(UUID companyId, @Nullable Instant afterCreated, @Nullable UUID afterId, int limit) {
        List<Object> params = new ArrayList<>(List.of(companyId));
        StringBuilder sql = new StringBuilder("SELECT " + GRANT_COLUMNS
                + " FROM account_grant g JOIN account a ON a.id = g.account_id WHERE g.company_id = ?");
        if (afterCreated != null && afterId != null) {
            sql.append(" AND (g.created_at, g.id) > (?, ?)");
            params.add(ts(afterCreated));
            params.add(afterId);
        }
        sql.append(" ORDER BY g.created_at, g.id LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(AccountStore::grant).list();
    }

    /** The grants to the group, by (created_at, id). */
    List<GrantRow> grantsToGroup(UUID companyId, UUID groupId) {
        return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM account_grant g JOIN account a ON a.id = g.account_id"
                + " WHERE g.company_id = ? AND g.group_id = ? ORDER BY g.created_at, g.id")
            .params(companyId, groupId)
            .query(AccountStore::grant)
            .list();
    }

    /** The grants on the system to any of the groups. */
    List<GrantRow> grantsTo(UUID companyId, UUID systemId, Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM account_grant g JOIN account a ON a.id = g.account_id"
                + " WHERE g.company_id = :companyId AND a.system_id = :systemId AND g.group_id IN (:groups)"
                + " ORDER BY g.created_at, g.id")
            .param("companyId", companyId)
            .param("systemId", systemId)
            .param("groups", Set.copyOf(groupIds))
            .query(AccountStore::grant)
            .list();
    }

    /** The grants of the account, locked, for its removal. */
    List<GrantRow> lockGrantsOf(UUID companyId, UUID accountId) {
        return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM account_grant g JOIN account a ON a.id = g.account_id"
                + " WHERE g.company_id = ? AND g.account_id = ? ORDER BY g.id FOR UPDATE OF g")
            .params(companyId, accountId)
            .query(AccountStore::grant)
            .list();
    }

    /** The grants to the group, locked, for its deletion. */
    List<GrantRow> lockGrantsTo(UUID companyId, UUID groupId) {
        return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM account_grant g JOIN account a ON a.id = g.account_id"
                + " WHERE g.company_id = ? AND g.group_id = ? ORDER BY g.id FOR UPDATE OF g")
            .params(companyId, groupId)
            .query(AccountStore::grant)
            .list();
    }

    static AccountRow account(ResultSet rs, int row) throws SQLException {
        OffsetDateTime expires = rs.getObject("access_expires_at", OffsetDateTime.class);
        OffsetDateTime used = rs.getObject("last_used_at", OffsetDateTime.class);
        String principalKind = rs.getString("principal_kind");
        return new AccountRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getObject("principal_id", UUID.class),
                SYSTEM_KIND.equals(principalKind) ? null : PrincipalKind.valueOf(principalKind), rs.getString("name"),
                rs.getBoolean("enabled"), AccountInfo.Kind.valueOf(rs.getString("kind")),
                rs.getObject("secret_id", UUID.class), rs.getString("external_id"), rs.getString("external_login"),
                rs.getString("api_base_url"), rs.getString("scopes"), expires != null ? expires.toInstant() : null,
                AccountInfo.Status.valueOf(rs.getString("status")), rs.getString("last_error"),
                rs.getObject("connected_at", OffsetDateTime.class).toInstant(),
                rs.getObject("connected_by", UUID.class), used != null ? used.toInstant() : null);
    }

    private static LendingRow lending(ResultSet rs, int row) throws SQLException {
        return new LendingRow(rs.getObject("company_id", UUID.class), rs.getObject("principal_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getObject("account_id", UUID.class),
                GrantInfo.Access.valueOf(rs.getString("access")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static GrantRow grant(ResultSet rs, int row) throws SQLException {
        return new GrantRow(new GrantInfo(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getObject("system_id", UUID.class),
                rs.getObject("group_id", UUID.class), GrantInfo.Access.valueOf(rs.getString("access"))),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
