package com.flowtivo.tinfirm.company.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.shared.security.SystemContext;

/**
 * Company names for the OAuth authorization server (ADR-0045), read across companies as a trusted system task: the
 * caller is often no member (a former member's "Connected apps").
 */
@Component
class CompanyNamesImpl implements CompanyNames {

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    CompanyNamesImpl(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
    }

    @Override
    public Optional<String> nameOf(UUID companyId) {
        return Optional.ofNullable(namesOf(List.of(companyId)).get(companyId));
    }

    @Override
    public Map<UUID, String> namesOf(Collection<UUID> companyIds) {
        if (companyIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = companyIds.stream().distinct().toList();
        Map<UUID, String> names = SystemContext.call(() -> transactions.execute(status -> jdbc
            .sql("SELECT id, name FROM company WHERE id IN (:ids)")
            .param("ids", ids)
            .query((rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("name")))
            .list()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        return names != null ? names : Map.of();
    }
}
