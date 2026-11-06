package com.flowtivo.tinfirm.company.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.identity.CompanyOwners;
import com.flowtivo.tinfirm.shared.security.SystemContext;

/**
 * The OWNERs of companies for the notices of the identity and MCP modules (the package 6 contract, §5.6), read as a
 * trusted system task, as {@link CompanyNamesImpl} reads names: the caller asks for the companies of its own
 * memberships or of a consent it was authorised for, and gets display names only.
 */
@Component
class CompanyOwnersImpl implements CompanyOwners {

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    CompanyOwnersImpl(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
    }

    @Override
    public Map<UUID, List<String>> ownersOf(Collection<UUID> companyIds) {
        if (companyIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = companyIds.stream().distinct().toList();
        List<Map.Entry<UUID, String>> rows = SystemContext.call(() -> transactions.execute(status -> jdbc
            .sql("""
                    SELECT m.company_id, u.display_name
                      FROM company_membership m
                      JOIN app_user u ON u.id = m.user_id
                     WHERE m.company_id IN (:ids) AND m.role = 'OWNER'
                     ORDER BY u.display_name, u.id""")
            .param("ids", ids)
            .query((rs, row) -> Map.entry(rs.getObject("company_id", UUID.class), rs.getString("display_name")))
            .list()));
        Map<UUID, List<String>> owners = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> row : rows != null ? rows : List.<Map.Entry<UUID, String>>of()) {
            owners.computeIfAbsent(row.getKey(), id -> new ArrayList<>()).add(row.getValue());
        }
        owners.replaceAll((id, names) -> List.copyOf(names));
        return owners;
    }
}
