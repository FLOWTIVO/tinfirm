package com.flowtivo.tinfirm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.flowtivo.tinfirm.audit.AuditPrincipals;
import com.flowtivo.tinfirm.audit.AuditTargetNames;
import com.flowtivo.tinfirm.shared.directory.AgentDisplayNames;
import com.flowtivo.tinfirm.shared.directory.UserDisplayNames;

/**
 * {@link AuditNames} against fakes of the modules (CONVENTIONS §8): each source of names asked once per page, whatever
 * the page's length; a principal named by its member's user or its own name, "(left)" for one who left; an erased
 * person named nowhere, and named again once they joined again; the instance's chain naming its users.
 */
class AuditNamesTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private final UserDisplayNames users = mock(UserDisplayNames.class);

    private final AgentDisplayNames agents = mock(AgentDisplayNames.class);

    private final AuditPrincipals principals = mock(AuditPrincipals.class);

    private final AuditTargetNames systems = mock(AuditTargetNames.class);

    private final AuditEventStore store = mock(AuditEventStore.class);

    private final AuditNames names;

    private final AuditTargetNames.Reader reader = new AuditTargetNames.Reader(COMPANY, UUID.randomUUID(),
            UUID.randomUUID(), true);

    AuditNamesTest() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("agents", agents);
        beans.addBean("principals", principals);
        beans.addBean("systems", systems);
        names = new AuditNames(users, beans.getBeanProvider(AgentDisplayNames.class),
                beans.getBeanProvider(AuditPrincipals.class), beans.getBeanProvider(AuditTargetNames.class), store,
                beans.getBeanProvider(com.flowtivo.tinfirm.shared.directory.ScimTokenDisplayNames.class));
        when(systems.targetTypes()).thenReturn(Set.of("system"));
        when(store.erasedAfterLeaving(any(), anyCollection())).thenReturn(Set.of());
        when(principals.find(any(), anyCollection())).thenReturn(Map.of());
        when(principals.ofUsers(any(), anyCollection())).thenReturn(List.of());
    }

    @Test
    void asksEachSourceOncePerPageWhateverItsLength() {
        List<StoredAuditEvent> events = new ArrayList<>();
        Map<UUID, String> userNames = new HashMap<>();
        Map<String, String> systemNames = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            UUID user = UUID.randomUUID();
            UUID system = UUID.randomUUID();
            userNames.put(user, "User " + i);
            systemNames.put(system.toString(), "System " + i);
            events.add(event("USER", user, "system", system.toString()));
            events.add(event("AGENT", UUID.randomUUID(), "agent_settings", UUID.randomUUID().toString()));
        }
        when(users.displayNames(anyCollection())).thenReturn(userNames);
        when(systems.names(eq(reader), eq("system"), any())).thenReturn(systemNames);

        AuditNames.Names resolved = names.of(reader, events);

        verify(users, times(1)).displayNames(anyCollection());
        verify(agents, times(1)).names(anyCollection());
        verify(principals, times(1)).ofUsers(eq(COMPANY), anyCollection());
        verify(store, times(1)).erasedAfterLeaving(eq(COMPANY), anyCollection());
        verify(systems, times(1)).names(eq(reader), eq("system"), any());
        assertThat(resolved.target(events.get(0))).isEqualTo("System 0");
        assertThat(resolved.actors().get(events.get(98).actorId())).isEqualTo("User 49");
        // A type that no module names has none.
        assertThat(resolved.target(events.get(1))).isNull();
    }

    @Test
    void namesAPrincipalByItsMemberOrItsOwnNameAndOneWhoLeftAsSuch() {
        UUID dave = UUID.randomUUID();
        AuditPrincipals.Principal member = new AuditPrincipals.Principal(UUID.randomUUID(), dave, null, false);
        AuditPrincipals.Principal left = new AuditPrincipals.Principal(UUID.randomUUID(), dave, null, true);
        AuditPrincipals.Principal service = new AuditPrincipals.Principal(UUID.randomUUID(), null, "svc-dev", false);
        when(principals.find(eq(COMPANY), anyCollection()))
            .thenReturn(Map.of(member.id(), member, left.id(), left, service.id(), service));
        when(principals.ofUsers(eq(COMPANY), anyCollection())).thenReturn(List.of(member, left));
        when(users.displayNames(anyCollection())).thenReturn(Map.of(dave, "Dave Malý"));
        List<StoredAuditEvent> events = List.of(event("SYSTEM", null, "principal", member.id().toString()),
                event("SYSTEM", null, "principal_model_key", left.id().toString()),
                event("SYSTEM", null, "principal", service.id().toString()),
                event("SYSTEM", null, "principal", UUID.randomUUID().toString()),
                event("SYSTEM", null, "principal", "not-an-id"));

        AuditNames.Names resolved = names.of(reader, events);

        assertThat(events).extracting(resolved::target)
            .containsExactly("Dave Malý", "Dave Malý (left)", "svc-dev", null, null);
    }

    @Test
    void namesAnErasedPersonNowhereUntilTheyJoinAgain() {
        UUID alex = UUID.randomUUID();
        AuditPrincipals.Principal left = new AuditPrincipals.Principal(UUID.randomUUID(), alex, null, true);
        when(principals.find(eq(COMPANY), anyCollection())).thenReturn(Map.of(left.id(), left));
        when(principals.ofUsers(eq(COMPANY), anyCollection())).thenReturn(List.of(left));
        when(store.erasedAfterLeaving(eq(COMPANY), eq(Set.of(left.id())))).thenReturn(Set.of(left.id()));
        when(users.displayNames(anyCollection())).thenReturn(Map.of(alex, "Alex Analyst"));
        List<StoredAuditEvent> events = List.of(event("USER", alex, "user", alex.toString()),
                event("SYSTEM", null, "principal", left.id().toString()),
                event("SYSTEM", null, "principal_model_key", left.id().toString()));

        AuditNames.Names erased = names.of(reader, events);

        assertThat(erased.erasedUsers()).containsExactly(alex);
        assertThat(erased.actors()).doesNotContainKey(alex);
        assertThat(events).extracting(erased::target).containsOnlyNulls();

        AuditPrincipals.Principal again = new AuditPrincipals.Principal(UUID.randomUUID(), alex, null, false);
        when(principals.ofUsers(eq(COMPANY), anyCollection())).thenReturn(List.of(left, again));

        AuditNames.Names rejoined = names.of(reader, events);

        assertThat(rejoined.erasedUsers()).isEmpty();
        assertThat(rejoined.actors()).containsEntry(alex, "Alex Analyst");
        assertThat(events).extracting(rejoined::target)
            .containsExactly("Alex Analyst", "Alex Analyst (left)", "Alex Analyst (left)");
    }

    @Test
    void theInstancesChainNamesItsUsersAndAsksNoCompanysPrincipals() {
        UUID olga = UUID.randomUUID();
        when(users.displayNames(anyCollection())).thenReturn(Map.of(olga, "Olga Owner"));
        AuditTargetNames.Reader admin = new AuditTargetNames.Reader(null, UUID.randomUUID(), null, false);
        StoredAuditEvent created = event("USER", olga, "user", olga.toString());

        AuditNames.Names resolved = names.of(admin, List.of(created));

        assertThat(resolved.target(created)).isEqualTo("Olga Owner");
        assertThat(resolved.actors()).containsEntry(olga, "Olga Owner");
        verify(principals, never()).ofUsers(any(), anyCollection());
        verify(store, never()).erasedAfterLeaving(any(), anyCollection());
    }

    private static StoredAuditEvent event(String actorType, @Nullable UUID actorId, String targetType,
            String targetId) {
        return new StoredAuditEvent(UUID.randomUUID(), COMPANY, 1, Instant.now(), actorType, actorId, "x.y",
                targetType, targetId, "{}", "0".repeat(64), "0".repeat(64));
    }
}
