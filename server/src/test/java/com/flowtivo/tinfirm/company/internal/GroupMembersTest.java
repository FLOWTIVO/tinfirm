package com.flowtivo.tinfirm.company.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.flowtivo.tinfirm.company.GroupEvents.GroupMembersChanged;
import com.flowtivo.tinfirm.shared.api.model.Group;
import com.flowtivo.tinfirm.shared.api.model.GroupMember;
import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * One member added to a group or taken out of it ({@link GroupService#addMember}, {@link GroupService#removeMember}),
 * called on the service as the acting user: by the rules of a replace, idempotent, and recorded and announced as a
 * replace is; row by row, so that admins who change the same group's members at the same moment all keep their change,
 * which reading the group and replacing it lost.
 */
class GroupMembersTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    private GroupService groups;

    @Test
    void eachChangeIsAnnouncedOnceAndTheSameRequestAgainChangesNothing() {
        TestCompany company = companyWithRoles();
        AuthenticatedUser owner = user(company, "OWNER");
        UUID adminId = company.userId("ADMIN");
        UUID memberId = company.userId("MEMBER");
        UUID groupId = createGroup(loginAs(company.email("OWNER")), company, "Ops", List.of(memberId));

        Group added = as(owner, () -> groups.addMember(company.id(), groupId, adminId));
        assertThat(ids(added)).containsExactlyInAnyOrder(memberId, adminId);
        assertThat(as(owner, () -> groups.addMember(company.id(), groupId, adminId))).isEqualTo(added);
        Group removed = as(owner, () -> groups.removeMember(company.id(), groupId, memberId));
        assertThat(ids(removed)).containsExactly(adminId);
        assertThat(removed.getUpdatedAt()).isAfter(added.getUpdatedAt());
        assertThat(as(owner, () -> groups.removeMember(company.id(), groupId, memberId))).isEqualTo(removed);
        assertThat(as(owner, () -> groups.get(company.id(), groupId))).isEqualTo(removed);

        // The same event as a replace's: teams, grants and bindings are looked at again once per change.
        assertThat(RecordedEvents.of(company.id(), GroupMembersChanged.class))
            .containsExactly(new GroupMembersChanged(company.id(), groupId, Set.of(adminId), Set.of()),
                    new GroupMembersChanged(company.id(), groupId, Set.of(), Set.of(memberId)));
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, "group.updated")).hasSize(2);

        // MANAGE_GROUPS, as for a replace.
        assertThatThrownBy(() -> as(user(company, "MEMBER"), () -> groups.addMember(company.id(), groupId, memberId)))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(403));
        assertThatThrownBy(() -> as(user(company, "MEMBER"),
                () -> groups.removeMember(company.id(), groupId, adminId)))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(403));
    }

    @Test
    void adminsChangingDifferentMembersAtTheSameMomentAllKeepTheirChange() throws Exception {
        TestCompany company = companyWithRoles();
        AuthenticatedUser owner = user(company, "OWNER");
        AuthenticatedUser admin = user(company, "ADMIN");
        UUID first = extraMember(company);
        UUID second = extraMember(company);
        UUID groupId = createGroup(loginAs(company.email("OWNER")), company, "Ops",
                List.of(company.userId("MEMBER"), first));
        record Change(AuthenticatedUser actor, UUID userId, boolean add) {
        }
        List<Change> changes = List.of(new Change(owner, company.userId("OWNER"), true),
                new Change(admin, company.userId("ADMIN"), true), new Change(owner, second, true),
                new Change(admin, first, false));

        // Released together while the group's row is held, so that each waits for it with the others.
        ExecutorService threads = Executors.newFixedThreadPool(changes.size());
        try (TenantSql holder = TenantSql.in(company.id())) {
            holder.rows("SELECT id FROM user_group WHERE id = ? FOR UPDATE", groupId);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Group>> answers = new ArrayList<>();
            for (Change change : changes) {
                answers.add(threads.submit(() -> {
                    start.await();
                    return as(change.actor(), () -> change.add()
                            ? groups.addMember(company.id(), groupId, change.userId())
                            : groups.removeMember(company.id(), groupId, change.userId()));
                }));
            }
            start.countDown();
            await().atMost(WAIT).until(() -> waiting(company.id(), "SELECT % FROM user_group %FOR UPDATE")
                    == changes.size());
            holder.commit();
            for (Future<Group> answer : answers) {
                answer.get(30, TimeUnit.SECONDS);
            }
        }
        finally {
            threads.shutdownNow();
        }

        assertThat(ids(as(owner, () -> groups.get(company.id(), groupId)))).containsExactlyInAnyOrder(
                company.userId("MEMBER"), company.userId("OWNER"), company.userId("ADMIN"), second);
        assertThat(RecordedEvents.of(company.id(), GroupMembersChanged.class)).hasSize(changes.size());
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, "group.updated")).hasSize(changes.size());
    }

    @Test
    void aUserWhoLeavesTheCompanyWhileBeingAddedIsRefusedAsANonMember() throws Exception {
        TestCompany company = companyWithRoles();
        AuthenticatedUser owner = user(company, "OWNER");
        UUID leaving = extraMember(company);
        UUID groupId = createGroup(loginAs(company.email("OWNER")), company, "Ops", List.of());

        // The membership goes after the add found it and before its row is checked against it.
        try (TenantSql leaver = TenantSql.in(company.id())) {
            leaver.update("UPDATE principal SET status = 'LEFT' WHERE company_id = ? AND user_id = ?", company.id(),
                    leaving);
            leaver.update("DELETE FROM company_membership WHERE company_id = ? AND user_id = ?", company.id(), leaving);
            CompletableFuture<Group> add = CompletableFuture
                .supplyAsync(() -> as(owner, () -> groups.addMember(company.id(), groupId, leaving)));
            await().atMost(WAIT).until(() -> waiting(company.id(), "INSERT INTO user_group_member%") == 1);
            leaver.commit();
            assertThatThrownBy(() -> add.get(30, TimeUnit.SECONDS)).cause()
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status().value()).isEqualTo(400);
                    assertThat(ex.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                });
        }
        assertThat(as(owner, () -> groups.get(company.id(), groupId)).getMembers()).isEmpty();
    }

    @Test
    void aGroupTakesNoFiveHundredAndFirstMemberButAddingOneAlreadyInItStillAnswers() throws Exception {
        TestCompany company = companyWithRoles();
        AuthenticatedUser owner = user(company, "OWNER");
        UUID groupId = createGroup(loginAs(company.email("OWNER")), company, "Everyone", List.of());
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update("""
                    WITH users AS (
                        INSERT INTO app_user (id, email, display_name, auth_source, password_hash, created_at,
                                              updated_at)
                        SELECT gen_random_uuid(), ? || n || '@example.test', 'Member ' || n, 'LOCAL', 'unused',
                               now(), now()
                          FROM generate_series(1, ?) AS n
                        RETURNING id),
                    memberships AS (
                        INSERT INTO company_membership (company_id, user_id, role, created_at, updated_at)
                        SELECT ?, id, 'MEMBER', now(), now() FROM users
                        RETURNING user_id)
                    INSERT INTO user_group_member (company_id, group_id, user_id)
                    SELECT ?, ?, user_id FROM memberships""", unique("crowd") + "-", GroupService.MAX_MEMBERS - 1,
                    company.id(), company.id(), groupId);
            sql.commit();
        }

        Group full = as(owner, () -> groups.addMember(company.id(), groupId, company.userId("MEMBER")));
        assertThat(full.getMembers()).hasSize(GroupService.MAX_MEMBERS);
        assertThatThrownBy(() -> as(owner, () -> groups.addMember(company.id(), groupId, company.userId("ADMIN"))))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status().value()).isEqualTo(400);
                assertThat(ex.getMessage()).contains("at most " + GroupService.MAX_MEMBERS);
            });
        assertThat(as(owner, () -> groups.addMember(company.id(), groupId, company.userId("MEMBER"))))
            .isEqualTo(full);
    }

    /** A user of the company with the role MEMBER, who is in no group. */
    private UUID extraMember(TestCompany company) {
        String email = uniqueEmail("member");
        UUID userId = createUser(admin(), email, false);
        addMember(loginAs(company.email("OWNER")), company.id(), email, "MEMBER");
        return userId;
    }

    private static AuthenticatedUser user(TestCompany company, String role) {
        return new AuthenticatedUser(company.userId(role), company.email(role), "User " + company.email(role), false);
    }

    /** The work as the user's request would run it: their authentication, their tenant context. */
    private static <T> T as(AuthenticatedUser user, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext acting = SecurityContextHolder.createEmptyContext();
        acting.setAuthentication(new ActorAuthentication(user));
        SecurityContextHolder.setContext(acting);
        try (TenantContextHolder.Scope ignored = TenantContextHolder
            .open(TenantContext.forUser(user.id(), user.instanceAdmin()))) {
            return work.get();
        }
        finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static List<UUID> ids(Group group) {
        return group.getMembers().stream().map(GroupMember::getUserId).toList();
    }

    /** The backends of this database that wait for a lock in a statement like the pattern. */
    private static long waiting(UUID companyId, String statement) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query ILIKE ?", statement);
        }
    }
}
