package com.flowtivo.tinfirm.shared.tenant;

import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The runtime role cannot bypass row-level security (ADR-0009, CONVENTIONS §7): at startup, and until the answer is
 * known, the server checks that its database role ({@code current_user}) is not a superuser, has no
 * {@code BYPASSRLS}, owns no relation in {@code public} and is not a member of {@code tinfirm_owner} nor of any other
 * role that owns one there (an owner may switch row-level security off on its tables). A role that fails any of them
 * holds the replica out of traffic: this indicator is part of the readiness group ({@code application.yaml}), so the
 * replica never becomes ready, and the start logs one line that names the role and the condition. Such a role would
 * void every policy silently, turning any later {@code WHERE company_id} mistake into a cross-tenant read.
 * <p>
 * The answer is kept once known; a database that does not answer leaves it open and the indicator down, as the
 * readiness group's {@code db} indicator is then.
 */
@Component(DatabaseRoleCheck.NAME)
public class DatabaseRoleCheck implements HealthIndicator, ApplicationRunner {

    /** The indicator's name in the health groups. */
    public static final String NAME = "databaseRole";

    /** The schema owner's role (CONVENTIONS §7, {@code deploy/compose/postgres/init.sql}). */
    static final String OWNER_ROLE = "tinfirm_owner";

    private static final Logger log = LoggerFactory.getLogger(DatabaseRoleCheck.class);

    private final JdbcClient jdbc;

    /** Empty when the role passed, the failed condition otherwise; null until the check ran against the database. */
    private volatile @Nullable Optional<String> verdict;

    public DatabaseRoleCheck(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** The role's attributes and what it owns, as {@code current_user} sees them in the catalog. */
    private record RoleFacts(String role, boolean superuser, boolean bypassRls, boolean ownsRelation,
            boolean memberOfOwnerRole, @Nullable String memberOfOwner) {
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            check();
        }
        catch (DataAccessException ex) {
            log.warn("The database role could not be checked at startup; the readiness probe checks it again");
        }
    }

    @Override
    public Health health() {
        Optional<String> failure;
        try {
            failure = check();
        }
        catch (DataAccessException ex) {
            // Fail closed: not ready until the role is known to pass.
            return Health.down().withDetail("reason", "not checked yet").build();
        }
        return failure.isEmpty() ? Health.up().build() : Health.down().withDetail("reason", failure.get()).build();
    }

    /** The failed condition, empty when the role passes; asks the database until it answers once. */
    Optional<String> check() {
        Optional<String> known = verdict;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (verdict == null) {
                RoleFacts facts = facts();
                Optional<String> failure = failure(facts);
                failure.ifPresent(reason -> log.error("The database role {} {}: the server does not become ready "
                        + "(row-level security, CONVENTIONS §7)", facts.role(), reason));
                verdict = failure;
            }
            return verdict;
        }
    }

    private RoleFacts facts() {
        return jdbc.sql("""
                SELECT r.rolname, r.rolsuper, r.rolbypassrls,
                       EXISTS (SELECT 1 FROM pg_catalog.pg_class c
                                 JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                                WHERE n.nspname = 'public' AND c.relowner = r.oid) AS owns_relation,
                       EXISTS (SELECT 1 FROM pg_catalog.pg_roles o
                                WHERE o.rolname = :owner
                                  AND pg_catalog.pg_has_role(r.oid, o.oid, 'MEMBER')) AS member_of_owner_role,
                       (SELECT pg_catalog.pg_get_userbyid(c.relowner) FROM pg_catalog.pg_class c
                          JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public' AND c.relowner <> r.oid
                           AND pg_catalog.pg_has_role(r.oid, c.relowner, 'MEMBER')
                         LIMIT 1) AS member_of_owner
                  FROM pg_catalog.pg_roles r
                 WHERE r.rolname = current_user""")
            .param("owner", OWNER_ROLE)
            .query((rs, row) -> new RoleFacts(rs.getString("rolname"), rs.getBoolean("rolsuper"),
                    rs.getBoolean("rolbypassrls"), rs.getBoolean("owns_relation"),
                    rs.getBoolean("member_of_owner_role"), rs.getString("member_of_owner")))
            .single();
    }

    /** The first condition the role fails, in the order of the checks. */
    private static Optional<String> failure(RoleFacts facts) {
        if (facts.superuser()) {
            return Optional.of("is a superuser");
        }
        if (facts.bypassRls()) {
            return Optional.of("has BYPASSRLS");
        }
        if (facts.ownsRelation()) {
            return Optional.of("owns a relation in the schema public");
        }
        if (facts.memberOfOwnerRole()) {
            return Optional.of("is a member of " + OWNER_ROLE);
        }
        if (facts.memberOfOwner() != null) {
            return Optional.of("is a member of " + facts.memberOfOwner() + ", which owns a relation in the schema public");
        }
        return Optional.empty();
    }
}
