package com.flowtivo.tinfirm.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.FakeBotFramework;

/**
 * Receiving from Teams (the package 5 contract, §5.2, C1 to C3, threat model 11.9): the Bot Framework's token
 * verified before anything (signature, endorsements, issuer, audience, expiry), the activity's {@code serviceUrl} the
 * token's {@code serviceurl} claim and on the system's origin, its tenant the system's; one action per
 * {@code {conversation}:{activity}}.
 */
class TeamsReceivingTest extends ChatTestBench {

    @Test
    void verifiesTheBotFrameworksTokenBeforeReadingTheActivity() {
        Bench bench = bench(false);
        Teams teams = teams(bench);
        FakeBotFramework.Tenant tenant = teams.tenant();
        FakeBotFramework.TeamsUser user = tenant.user("Tess", "user");
        String serviceUrl = FakeBotFramework.url() + "/";
        UUID system = teams.systemId();

        Map<String, Object> accepted = tenant.personalMessage(user, "hello");
        assertThat(tenant.deliver(port, system, accepted).status()).isEqualTo(200);
        assertThat(events(bench.id(), system)).hasSize(1);

        assertThat(tenant.deliver(port, system, tenant.personalMessage(user, "no token"), null).status())
            .isEqualTo(401);
        assertThat(tenant.deliver(port, system, tenant.personalMessage(user, "garbage"), "not-a-jwt").status())
            .isEqualTo(401);
        assertThat(tenant.deliver(port, system, tenant.personalMessage(user, "another app"),
                FakeBotFramework.token(UUID.randomUUID().toString(), serviceUrl, Instant.now().plusSeconds(600)))
            .status()).isEqualTo(401);
        assertThat(tenant.deliver(port, system, tenant.personalMessage(user, "expired"),
                FakeBotFramework.token(tenant.appId, serviceUrl, Instant.now().minusSeconds(400))).status())
            .isEqualTo(401);
        // Within the five minutes' skew.
        assertThat(tenant.deliver(port, system, tenant.personalMessage(user, "just expired"),
                FakeBotFramework.token(tenant.appId, serviceUrl, Instant.now().minusSeconds(120))).status())
            .isEqualTo(200);
        assertThat(tenant.deliver(port, system, tenant.personalMessage(user, "issuer"),
                FakeBotFramework.tokenOfIssuer("https://sts.windows.net/x/", tenant.appId, serviceUrl)).status())
            .isEqualTo(401);
        assertThat(tenant.deliver(port, system, tenant.personalMessage(user, "webchat key"),
                FakeBotFramework.tokenOfOtherChannel(tenant.appId, serviceUrl)).status()).isEqualTo(401);
        assertThat(tenant.deliver(port, system, tenant.personalMessage(user, "unknown key"),
                FakeBotFramework.tokenOfUnknownKey(tenant.appId, serviceUrl)).status()).isEqualTo(401);

        // The serviceurl claim must be the activity's serviceUrl, on the system's origin.
        Map<String, Object> elsewhere = tenant.personalMessage(user, "elsewhere");
        elsewhere.put("serviceUrl", "https://smba.example.test/emea/");
        assertThat(tenant.deliver(port, system, elsewhere).status()).isEqualTo(401);
        assertThat(tenant.deliver(port, system, elsewhere, FakeBotFramework.token(tenant.appId,
                "https://smba.example.test/emea/", Instant.now().plusSeconds(600))).status()).isEqualTo(401);
        Map<String, Object> otherPath = tenant.personalMessage(user, "other path");
        otherPath.put("serviceUrl", FakeBotFramework.url() + "/amer/");
        assertThat(tenant.deliver(port, system, otherPath).status()).isEqualTo(401);

        // Another tenant's activity, with a valid token for this app.
        Map<String, Object> otherTenant = tenant.personalMessage(user, "other tenant");
        otherTenant.put("channelData", Map.of("tenant", Map.of("id", UUID.randomUUID().toString())));
        assertThat(tenant.deliver(port, system, otherTenant).status()).isEqualTo(401);

        // Another Teams system's app: its token is not this system's.
        Teams other = teams(bench(false));
        assertThat(tenant.deliver(port, other.systemId(), tenant.personalMessage(user, "wrong system")).status())
            .isEqualTo(401);

        assertThat(events(bench.id(), system)).hasSize(2);
    }

    @Test
    void actsOnceOnAnActivityByItsConversationAndId() {
        Bench bench = bench(false);
        Teams teams = teams(bench);
        FakeBotFramework.Tenant tenant = teams.tenant();
        FakeBotFramework.TeamsUser user = tenant.user("Dee", "user");
        Map<String, Object> activity = tenant.personalMessage(user, "hello");
        assertThat(tenant.deliver(port, teams.systemId(), activity).status()).isEqualTo(200);
        assertThat(tenant.deliver(port, teams.systemId(), activity).status()).isEqualTo(200);
        // The same activity id in another conversation is another activity.
        Map<String, Object> elsewhere = new LinkedHashMap<>(activity);
        elsewhere.put("conversation", Map.of("id", "a:dm-other-" + UUID.randomUUID(), "conversationType",
                "personal", "tenantId", tenant.tenantId));
        assertThat(tenant.deliver(port, teams.systemId(), elsewhere).status()).isEqualTo(200);
        assertThat(events(bench.id(), teams.systemId())).hasSize(2)
            .allSatisfy(row -> assertThat((String) row.get("event_id")).endsWith(":" + activity.get("id")));
    }

    @Test
    void refusesGuestsAndTakesNoGroupChat() {
        Bench bench = bench(false);
        Teams teams = teams(bench);
        FakeBotFramework.Tenant tenant = teams.tenant();
        FakeBotFramework.TeamsUser guest = tenant.user("Gwen Guest", "guest");
        Map<String, Object> fromGuest = tenant.personalMessage(guest, "hello");
        tenant.deliver(port, teams.systemId(), fromGuest);
        String eventId = "a:dm-" + guest.id() + ":" + fromGuest.get("id");
        assertThat(awaitEvent(bench.id(), teams.systemId(), eventId, "REFUSED")).containsEntry("reason", "GUEST");
        assertThat(said(tenant.posts("a:dm-" + guest.id()))).contains("members of this workspace only");

        FakeBotFramework.TeamsUser member = tenant.user("Gary", "user");
        Map<String, Object> group = tenant.activity(member, "groupChat", "19:group-" + UUID.randomUUID());
        group.put("type", "message");
        group.put("text", "hello all");
        tenant.deliver(port, teams.systemId(), group);
        assertThat(events(bench.id(), teams.systemId())).filteredOn(row -> "NOT_SERVED".equals(row.get("reason")))
            .hasSize(1);
    }
}
