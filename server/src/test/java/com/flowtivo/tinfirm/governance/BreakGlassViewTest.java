package com.flowtivo.tinfirm.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.BearerGrant;
import com.flowtivo.tinfirm.shared.security.BearerGrantHolder;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;

/**
 * The instance admin's break-glass (the package 6 contract, §5.10; open point 12): an instance admin who is not a
 * member gets ADMIN's view (another person's personal conversation, its runs and decisions are {@code 404}) and the
 * audited right to appoint an OWNER, nothing beyond; one who is a member acts by their role only. Through a bearer
 * credential and at the authorization server's consent there is no break-glass, and a suspended member is a non-member
 * there ({@code 404}).
 */
@ExtendWith(EnterpriseLicence.class)
class BreakGlassViewTest extends IntegrationTest {

    @Autowired
    private CompanyAccessGuard guard;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void anInstanceAdminWhoIsNotAMemberHasAnAdminsView() {
        TestCompany company = companyWithRoles();
        ApiClient admin = admin();
        UUID principal = memberPrincipal(company, company.userId("MEMBER"));
        UUID agentId = TestRuns.agent(company.id());
        UUID conversationId = UUID.randomUUID();
        String key = "bg-" + conversationId.toString().substring(0, 8);
        TestConversations.bare(company.id(), conversationId, key, agentId, principal, "CLAUDE_CODE");
        UUID runId = UUID.randomUUID();
        TestRuns.ofConversation(company.id(), runId, conversationId, key, agentId, principal, 1, "SUCCEEDED");

        // Personal conversations and their runs are 404, as for ADMIN; OWNER reads them.
        assertThat(admin.get(company.path("/conversations/" + conversationId)).status()).isEqualTo(404);
        assertThat(admin.get(company.path("/runs/" + runId)).status()).isEqualTo(404);
        assertThat(loginAs(company.email("ADMIN")).get(company.path("/conversations/" + conversationId)).status())
            .isEqualTo(404);
        assertThat(loginAs(company.email("OWNER")).get(company.path("/conversations/" + conversationId)).status())
            .isEqualTo(200);
        // ADMIN's rights: members, systems; not an OWNER's own (the company's retention).
        assertThat(admin.get(company.path("/members")).status()).isEqualTo(200);
        assertThat(admin.get(company.path("/retention/open-conversations")).status()).isEqualTo(403);

        // The audited right to appoint an OWNER, and nothing more of the OWNER role.
        ApiResponse appointed = admin.patch(company.path("/members/" + company.userId("ADMIN")),
                Map.of("role", "OWNER"));
        assertThat(appointed.status()).as("%s", appointed).isEqualTo(200);
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, "member.role.changed").getFirst()
            .get("details").get("role").asString()).isEqualTo("OWNER");
        assertThat(admin.patch(company.path("/members/" + company.userId("ADMIN")), Map.of("role", "MEMBER"))
            .status()).isEqualTo(403);
        assertThat(admin.delete(company.path("/members/" + company.userId("OWNER"))).status()).isEqualTo(403);
    }

    @Test
    void anInstanceAdminWhoIsAMemberActsByTheirRoleOnly() {
        TestCompany company = companyWithRoles();
        String email = uniqueEmail("ops");
        createUser(admin(), email, true);
        addMember(loginAs(company.email("OWNER")), company.id(), email, "MEMBER");
        ApiClient ops = loginAs(email);
        assertThat(ops.get(company.path("/audit-events")).status()).isEqualTo(403);
        assertThat(ops.post(company.path("/runs-stop"), Map.of("reason", "x")).status()).isEqualTo(403);
    }

    @Test
    void noBreakGlassThroughABearerCredentialOrTheConsent() {
        TestCompany company = companyWithRoles();
        AuthenticatedUser instanceAdmin = new AuthenticatedUser(UUID.fromString(admin().get("/api/me").json()
            .get("id").asString()), ADMIN_EMAIL, "Admin", true);
        AuthenticatedUser member = new AuthenticatedUser(company.userId("MEMBER"), company.email("MEMBER"),
                "Member", false);

        // Without a bearer credential, the instance admin gets the break-glass view.
        assertThat(as(instanceAdmin, false, () -> guard.require(company.id(), CompanyPermission.VIEW_COMPANY))
            .viaInstanceAdmin()).isTrue();
        // Through one, or at the consent, nothing: 404.
        assertNotFound(() -> as(instanceAdmin, true,
                () -> guard.require(company.id(), CompanyPermission.VIEW_COMPANY)));
        assertNotFound(() -> as(instanceAdmin, false,
                () -> guard.requireActiveMember(company.id(), CompanyPermission.VIEW_COMPANY)));
        // A member passes, until suspended: then their bearer calls and the consent are a non-member's.
        assertThat(as(member, true, () -> guard.require(company.id(), CompanyPermission.VIEW_COMPANY)).role())
            .isNotNull();
        assertThat(loginAs(company.email("OWNER")).post(company.path("/members/" + member.id() + "/suspend"),
                Map.of("reason", "token leaked")).status()).isEqualTo(200);
        assertNotFound(() -> as(member, true, () -> guard.require(company.id(), CompanyPermission.VIEW_COMPANY)));
        assertNotFound(() -> as(member, false,
                () -> guard.requireActiveMember(company.id(), CompanyPermission.VIEW_COMPANY)));
        // Their session still reaches the pages a suspended member reads.
        assertThat(as(member, false,
                () -> guard.requireAllowingSuspended(company.id(), CompanyPermission.VIEW_COMPANY)).role())
            .isNotNull();
    }

    private CompanyAccess as(AuthenticatedUser user, boolean bearer, java.util.function.Supplier<CompanyAccess> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext acting = SecurityContextHolder.createEmptyContext();
        acting.setAuthentication(new ActorAuthentication(user));
        SecurityContextHolder.setContext(acting);
        try (TenantContextHolder.Scope ignored = TenantContextHolder
            .open(TenantContext.forUser(user.id(), user.instanceAdmin()))) {
            TransactionTemplate transactions = new TransactionTemplate(transactionManager);
            if (!bearer) {
                return transactions.execute(status -> work.get());
            }
            try (BearerGrantHolder.Scope ignoredGrant = BearerGrantHolder
                .open(new BearerGrant(BearerGrant.Kind.PERSONAL_ACCESS_TOKEN, UUID.randomUUID(), ApiScope.READ))) {
                return transactions.execute(status -> work.get());
            }
        }
        finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.status().value()).isEqualTo(404));
    }
}
