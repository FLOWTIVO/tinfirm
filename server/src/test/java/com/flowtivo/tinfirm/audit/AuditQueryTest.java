package com.flowtivo.tinfirm.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestContent;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * The filters of {@code GET /audit-events} (the package 6 contract, §5.8, §9 {@code AuditQueryTest}; CONVENTIONS
 * §15.11): each filter and their combination, the events about a principal, a conversation by its key.
 */
@ExtendWith(EnterpriseLicence.class)
class AuditQueryTest extends IntegrationTest {

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private SystemTransactions transactions;

    @Test
    void filtersCombineAndPageNewestFirst() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        owner.patch(company.path(""), Map.of("name", unique("Renamed")));
        UUID conversationId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        UUID ownerId = company.userId("OWNER");
        for (int i = 0; i < 3; i++) {
            record(company.id(), AuditAction.CONVERSATION_READ, Actor.user(ownerId), "conversation", conversationId,
                    Map.<String, Object>of("conversationId", conversationId.toString(), "personPrincipalId",
                            principalId.toString(),
                            "kind", "DETAIL"));
        }
        record(company.id(), AuditAction.HOLD_SET, Actor.user(ownerId), "legal_hold", UUID.randomUUID(),
                Map.<String, Object>of("principalId", principalId.toString()));

        // An exact action.
        assertThat(actions(owner, company, "action=company.updated")).containsExactly("company.updated");
        // A prefix ending in a dot.
        assertThat(actions(owner, company, "action=member.")).isNotEmpty()
            .allSatisfy(action -> assertThat(action).startsWith("member."));
        // The actor (the fixture's OWNER closed the bootstrap admin's departure first, package 6).
        assertThat(actions(owner, company, "actorType=USER&actorId=" + ownerId))
            .containsExactly("hold.set", "conversation.read", "conversation.read", "conversation.read",
                    "company.updated", "principal.departure_closed");
        assertThat(actions(owner, company, "actorType=SYSTEM")).doesNotContain("company.updated");
        // The target.
        assertThat(actions(owner, company, "targetType=conversation&targetId=" + conversationId)).hasSize(3);
        // The details' keys.
        assertThat(actions(owner, company, "conversationId=" + conversationId)).hasSize(3);
        assertThat(actions(owner, company, "principalId=" + principalId)).containsExactly("hold.set");
        assertThat(actions(owner, company, "conversationId=" + conversationId + "&action=hold.set")).isEmpty();
        // Time: from included, to not.
        assertThat(actions(owner, company, "from=" + Instant.now().plusSeconds(3600))).isEmpty();
        assertThat(actions(owner, company, "to=2000-01-01T00:00:00Z")).isEmpty();
        assertThat(actions(owner, company, "from=2000-01-01T00:00:00Z&action=hold.set")).hasSize(1);

        // Pages of one keep the filter.
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = owner.get(company.path("/audit-events?limit=1&conversationId=" + conversationId
                    + (cursor != null ? "&cursor=" + cursor : ""))).json();
            page.get("items").forEach(item -> paged.add(item.get("seq").asString()));
            cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
        }
        while (cursor != null);
        assertThat(paged).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void aPrincipalsEventsAreThoseThatNameItTargetItOrItsMemberActedIn() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID ownerId = company.userId("OWNER");
        UUID alex = company.userId("MEMBER");
        UUID principal = memberPrincipal(company, alex);
        UUID other = memberPrincipal(company, company.userId("ADMIN"));
        UUID named = UUID.randomUUID();
        UUID acted = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        record(company.id(), AuditAction.HOLD_SET, Actor.user(ownerId), "legal_hold", named,
                Map.<String, Object>of("principalId", principal.toString()));
        record(company.id(), AuditAction.PRINCIPAL_SUSPENDED, Actor.user(ownerId), "principal", principal,
                Map.<String, Object>of("kind", "MEMBER"));
        record(company.id(), AuditAction.SYSTEM_SETTINGS_CHANGED, Actor.user(alex), "system", acted,
                Map.<String, Object>of());
        record(company.id(), AuditAction.HOLD_SET, Actor.user(ownerId), "legal_hold", unrelated,
                Map.<String, Object>of("principalId", other.toString()));

        List<JsonNode> about = events(owner, company, "principalId=" + principal);

        // Named in the details, the target as a principal or as the member's user (added by the bootstrap admin), the
        // actor; never another's.
        assertThat(about).extracting(event -> event.path("targetId").asString(""))
            .contains(named.toString(), principal.toString(), alex.toString(), acted.toString())
            .doesNotContain(unrelated.toString());
        assertThat(about).allSatisfy(event -> assertThat(principal.toString().equals(event.path("details")
            .path("principalId").asString(""))
                || principal.toString().equals(event.path("targetId").asString(""))
                || alex.toString().equals(event.path("targetId").asString(""))
                || alex.toString().equals(event.get("actor").path("id").asString(""))).as("%s", event).isTrue());
        assertThat(about).extracting(event -> event.get("seq").asLong()).isSortedAccordingTo((a, b) -> b.compareTo(a));
        // With the other filters, and in pages of one that keep it.
        assertThat(actions(owner, company, "principalId=" + principal + "&actorType=USER&actorId=" + alex))
            .containsExactly("system.settings_changed");
        assertThat(actions(owner, company, "principalId=" + principal + "&action=hold.set")).containsExactly(
                "hold.set");
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = owner.get(company.path("/audit-events?limit=1&principalId=" + principal
                    + (cursor != null ? "&cursor=" + cursor : ""))).json();
            page.get("items").forEach(item -> paged.add(item.get("seq").asString()));
            cursor = page.has("nextCursor") && !page.get("nextCursor").isNull() ? page.get("nextCursor").asString()
                    : null;
        }
        while (cursor != null);
        assertThat(paged).doesNotHaveDuplicates()
            .containsExactlyElementsOf(about.stream().map(event -> event.get("seq").asString()).toList());

        // A service identity never acts: named in the details or the target.
        UUID svc = createServiceIdentity(owner, company, unique("svc"));
        record(company.id(), AuditAction.HOLD_SET, Actor.user(ownerId), "legal_hold", UUID.randomUUID(),
                Map.<String, Object>of("principalId", svc.toString()));
        assertThat(actions(owner, company, "principalId=" + svc)).containsExactly("hold.set", "principal.created");
    }

    @Test
    void aConversationsKeyFindsItsEventsAndAnUnknownKeyNone() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID alex = company.userId("MEMBER");
        String key = unique("audit-key").toLowerCase();
        UUID conversation = TestContent.conversation(company.id(), TestRuns.agent(company.id()),
                memberPrincipal(company, alex), alex, key, "TINFIRM_THREAD", false).conversationId();
        UUID ownerId = company.userId("OWNER");
        for (UUID id : List.of(conversation, conversation, UUID.randomUUID())) {
            record(company.id(), AuditAction.CONVERSATION_READ, Actor.user(ownerId), "conversation", id,
                    Map.<String, Object>of("conversationId", id.toString(), "what", "conversation"));
        }

        assertThat(actions(owner, company, "conversationKey=" + key)).hasSize(2);
        // As typed: any case, spaces around.
        assertThat(actions(owner, company, "conversationKey=%20" + key.toUpperCase() + "%20")).hasSize(2);
        assertThat(actions(owner, company, "conversationKey=" + key + "&conversationId=" + conversation)).hasSize(2);
        assertThat(actions(owner, company, "conversationKey=" + key + "&conversationId=" + UUID.randomUUID()))
            .isEmpty();
        assertThat(actions(owner, company, "conversationKey=nobody-has-this-1")).isEmpty();
        assertThat(loginAs(company.email("ADMIN")).get(company.path("/audit-events?conversationKey=" + key))
            .json().get("items")).hasSize(2);
        assertThat(loginAs(company.email("MEMBER")).get(company.path("/audit-events?conversationKey=" + key))
            .status()).isEqualTo(403);
    }

    @Test
    void anAdminFiltersAndAMemberDoesNotRead() {
        TestCompany company = companyWithRoles();
        assertThat(loginAs(company.email("ADMIN")).get(company.path("/audit-events?action=member.")).status())
            .isEqualTo(200);
        assertThat(loginAs(company.email("MEMBER")).get(company.path("/audit-events?action=member.")).status())
            .isEqualTo(403);
        ApiResponse invalid = loginAs(company.email("OWNER")).get(company.path("/audit-events?actorType=ROBOT"));
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(loginAs(company.email("OWNER")).get(company.path(
                "/audit-events?from=2030-01-01T00:00:00Z&to=2020-01-01T00:00:00Z")).status()).isEqualTo(400);
    }

    private void record(UUID companyId, AuditAction action, Actor actor, String targetType, UUID targetId,
            Map<String, Object> details) {
        transactions.runInCompany(companyId, () -> {
            AuditRecord.Builder builder = AuditRecord.company(companyId, action, actor).target(targetType, targetId);
            details.forEach(builder::detail);
            auditLog.record(builder.build());
        });
    }

    private static List<JsonNode> events(ApiClient reader, TestCompany company, String query) {
        ApiResponse response = reader.get(company.path("/audit-events?limit=200&" + query));
        assertThat(response.status()).as("%s: %s", query, response).isEqualTo(200);
        List<JsonNode> events = new ArrayList<>();
        response.json().get("items").forEach(events::add);
        return events;
    }

    private static List<String> actions(ApiClient reader, TestCompany company, String query) {
        ApiResponse response = reader.get(company.path("/audit-events?limit=200&" + query));
        assertThat(response.status()).as("%s: %s", query, response).isEqualTo(200);
        List<String> actions = new ArrayList<>();
        response.json().get("items").forEach(item -> actions.add(item.get("action").asString()));
        return actions;
    }
}
