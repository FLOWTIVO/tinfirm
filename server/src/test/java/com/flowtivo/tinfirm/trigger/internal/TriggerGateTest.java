package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.account.AccountBinding;
import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.agent.AgentAccess;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.AgentStatus;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveAgent;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.ModelAccessSetting;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.model.ModelAccess;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.runner.EffortLevel;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.support.TestSettings;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerRepeat;
import com.flowtivo.tinfirm.trigger.TriggerResults;
import com.flowtivo.tinfirm.trigger.TriggerSource;
import com.flowtivo.tinfirm.trigger.TriggerStatus;
import com.flowtivo.tinfirm.trigger.TriggerSubject;
import com.flowtivo.tinfirm.trigger.WaitingReason;

/**
 * {@code TriggerGate} as a table (the package 4 contract, §5.3 with §11, open point 9; T7): every row of the gate's
 * checks, against fakes of packages 1 to 3, changing nothing ({@link TriggerGateImpl#verdict}).
 */
class TriggerGateTest {

    static final UUID COMPANY = UUID.randomUUID();

    static final UUID AUTHOR = UUID.randomUUID();

    static final UUID AUTHOR_USER = UUID.randomUUID();

    static final UUID SERVICE = UUID.randomUUID();

    static final UUID AGENT = UUID.randomUUID();

    static final UUID SOURCE = UUID.randomUUID();

    static final UUID OTHER_SYSTEM = UUID.randomUUID();

    final TriggerStore store = mock(TriggerStore.class);

    final Principals principals = mock(Principals.class);

    final Agents agents = mock(Agents.class);

    final Accounts accounts = mock(Accounts.class);

    final Settings settings = mock(Settings.class);

    final ModelAccess modelAccess = mock(ModelAccess.class);

    final CompanySwitches companySwitches = mock(CompanySwitches.class);

    final CompanyDirectory companies = mock(CompanyDirectory.class);

    @SuppressWarnings("unchecked")
    final TriggerGateImpl gate = new TriggerGateImpl(store, null, principals, agents, accounts, settings,
            modelAccess, companySwitches, mock(com.flowtivo.tinfirm.system.Systems.class),
            mock(org.springframework.beans.factory.ObjectProvider.class), companies);

    final List<TriggerAccount> rows = new ArrayList<>();

    TriggerInfo trigger;

    @BeforeEach
    void everythingPasses() {
        trigger = trigger(SERVICE);
        principal(AUTHOR, PrincipalKind.MEMBER, PrincipalStatus.ACTIVE);
        principal(SERVICE, PrincipalKind.SERVICE, PrincipalStatus.ACTIVE);
        when(companies.roleOf(COMPANY, AUTHOR_USER)).thenReturn(Optional.of(CompanyRole.MEMBER));
        when(principals.mayUse(COMPANY, AUTHOR_USER, SERVICE)).thenReturn(true);
        AgentInfo agent = new AgentInfo(AGENT, COMPANY, "BA", "ba", null, "Analyse.", List.of(RuntimeKind.CLAUDE_CODE),
                AgentStatus.ACTIVE, null, 1, null);
        when(agents.find(COMPANY, AGENT)).thenReturn(Optional.of(agent));
        when(agents.access(COMPANY, AGENT, AUTHOR_USER)).thenReturn(new AgentAccess(true, true, false));
        rows.add(row(SOURCE, SERVICE, UUID.randomUUID()));
        rows.add(row(OTHER_SYSTEM, AUTHOR, UUID.randomUUID()));
        when(store.accounts(COMPANY, trigger.id())).thenReturn(rows);
        usable(SOURCE);
        usable(OTHER_SYSTEM);
        settingsWithModel(TestSettings.MODEL);
        when(modelAccess.forRun(any())).thenReturn(new RunModelAccess.Budget(SERVICE, List.of()));
    }

    /** The trigger's settings in effect, a budget's, with this model. */
    void settingsWithModel(@Nullable String model) {
        AgentInfo agent = agents.find(COMPANY, AGENT).orElseThrow();
        EffectiveAgent effectiveAgent = new EffectiveAgent(agent, 1, AgentDefinition.EMPTY,
                AgentDefinition.Widening.NONE);
        when(settings.effective(eq(COMPANY), eq(AGENT), any(SettingsOwner.Trigger.class)))
            .thenReturn(new EffectiveSettings(AGENT, new SettingsOwner.Trigger(trigger.id()), true, effectiveAgent,
                    RuntimeKind.CLAUDE_CODE, null, model, EffortLevel.MEDIUM, new ModelAccessSetting.Budget(), null,
                    List.of(), List.of(), Set.of(), List.of(), List.of()));
    }

    TriggerInfo trigger(UUID payer) {
        return new TriggerInfo(UUID.randomUUID(), COMPANY, "Assigned", TriggerSource.TRACKER, SOURCE,
                List.of(new TriggerEventSpec(com.flowtivo.tinfirm.trigger.TriggerEvent.ASSIGNED, null)),
                TriggerSubject.ISSUE, "SHOP", List.of(), TriggerRepeat.EVERY_TIME, null, AGENT, "Prepare it.", AUTHOR,
                null, payer, TriggerResults.DEFAULT, 30, TriggerStatus.ACTIVE, null, null, null, null, Instant.now(), 0,
                0, null, null, null, Instant.now(), Instant.now());
    }

    void principal(UUID id, PrincipalKind kind, PrincipalStatus status) {
        when(principals.find(COMPANY, id)).thenReturn(Optional.of(new PrincipalInfo(id, COMPANY, kind,
                kind == PrincipalKind.MEMBER ? AUTHOR_USER : null, "P", status)));
    }

    static TriggerAccount row(UUID system, UUID holder, @Nullable UUID account) {
        return new TriggerAccount(system, account, holder, "ext-" + holder, TriggerAccount.FilledFrom.ROW,
                Instant.now());
    }

    void usable(UUID system) {
        TriggerAccount row = rows.stream().filter(r -> r.systemId().equals(system)).findFirst().orElseThrow();
        when(accounts.check(new AccountBinding.Stored(COMPANY, system, AUTHOR, row.holderPrincipalId(),
                row.accountId(), null, row.externalId()))).thenReturn(new AccountBinding.Usable(new AccountInfo(
                        row.accountId(), COMPANY, system, row.holderPrincipalId(), PrincipalKind.MEMBER, null,
                        AccountInfo.Kind.TOKEN, row.externalId(), "login", AccountInfo.Status.OK, true),
                        GrantInfo.Access.WRITE));
    }

    void stopped(UUID system, AccountBinding.StopReason reason) {
        TriggerAccount row = rows.stream().filter(r -> r.systemId().equals(system)).findFirst().orElseThrow();
        when(accounts.check(new AccountBinding.Stored(COMPANY, system, AUTHOR, row.holderPrincipalId(),
                row.accountId(), null, row.externalId()))).thenReturn(new AccountBinding.Stopped(reason));
    }

    TriggerGateImpl.Verdict verdict() {
        return gate.verdict(trigger);
    }

    void assertPauses(PausedReason reason) {
        assertThat(verdict()).isInstanceOfSatisfying(TriggerGateImpl.Verdict.Pause.class,
                pause -> assertThat(pause.reason()).isEqualTo(reason));
    }

    @Test
    void aTriggerWhoseChoicesAllHoldPasses() {
        assertThat(verdict()).isInstanceOf(TriggerGateImpl.Verdict.Passes.class);
    }

    @Test
    void aBudgetsSettingsWithoutAModelOrWithOneTheKeyDoesNotAllowPause() {
        settingsWithModel(null);
        assertThat(verdict()).isInstanceOfSatisfying(TriggerGateImpl.Verdict.Pause.class, pause -> {
            assertThat(pause.reason()).isEqualTo(PausedReason.NOT_ENTITLED);
            assertThat(pause.detail()).startsWith("No model is chosen");
        });
        settingsWithModel("gpt-9");
        when(modelAccess.forRun(any())).thenReturn(new RunModelAccess.Budget(SERVICE, List.of("claude-*")));
        assertThat(verdict()).isInstanceOfSatisfying(TriggerGateImpl.Verdict.Pause.class,
                pause -> assertThat(pause.detail()).isEqualTo("The model gpt-9 is not one the paying principal's "
                        + "model key allows"));
    }

    @Test
    void anAuthorWhoLeftOrIsSuspended() {
        principal(AUTHOR, PrincipalKind.MEMBER, PrincipalStatus.LEFT);
        assertPauses(PausedReason.AUTHOR_LEFT);
        principal(AUTHOR, PrincipalKind.MEMBER, PrincipalStatus.SUSPENDED);
        assertPauses(PausedReason.NOT_ENTITLED);
    }

    @Test
    void anAuthorWhoHoldsNoSeatPauses() {
        // M7 of the security review of 2026-10-27: a member demoted to VIEWER (by an admin, the identity provider's
        // claim, SCIM or a renewal) authors no trigger: it pauses NOT_ENTITLED at the gate, whatever demoted them.
        when(companies.roleOf(COMPANY, AUTHOR_USER)).thenReturn(Optional.of(CompanyRole.VIEWER));
        assertThat(verdict()).isInstanceOfSatisfying(TriggerGateImpl.Verdict.Pause.class, pause -> {
            assertThat(pause.reason()).isEqualTo(PausedReason.NOT_ENTITLED);
            assertThat(pause.detail()).contains("Viewer");
        });
        when(companies.roleOf(COMPANY, AUTHOR_USER)).thenReturn(Optional.empty());
        assertPauses(PausedReason.NOT_ENTITLED);
        for (CompanyRole seat : List.of(CompanyRole.OWNER, CompanyRole.ADMIN, CompanyRole.MEMBER)) {
            when(companies.roleOf(COMPANY, AUTHOR_USER)).thenReturn(Optional.of(seat));
            assertThat(verdict()).as("%s", seat).isInstanceOf(TriggerGateImpl.Verdict.Passes.class);
        }
    }

    @Test
    void anAgentNoLongerSharedWithTheAuthorOrDeleted() {
        when(agents.access(COMPANY, AGENT, AUTHOR_USER)).thenReturn(new AgentAccess(true, false, false));
        assertPauses(PausedReason.NOT_ENTITLED);
        when(agents.find(COMPANY, AGENT)).thenReturn(Optional.empty());
        assertThat(verdict()).isInstanceOfSatisfying(TriggerGateImpl.Verdict.Delete.class,
                delete -> assertThat(delete.cause()).isEqualTo("agent.deleted"));
    }

    @Test
    void eachRowIsCheckedForTheAuthorWithoutAGrant() {
        stopped(SOURCE, AccountBinding.StopReason.USE_RIGHT_GONE);
        assertPauses(PausedReason.NOT_ENTITLED);
        stopped(SOURCE, AccountBinding.StopReason.IDENTITY_CHANGED);
        assertPauses(PausedReason.ACCOUNT_REMOVED);
        // §11, open point 9: a broken account, the source's or another's, pauses the whole trigger.
        stopped(SOURCE, AccountBinding.StopReason.RECONNECT_COMPANY);
        assertPauses(PausedReason.ACCOUNT_BROKEN);
        usable(SOURCE);
        stopped(OTHER_SYSTEM, AccountBinding.StopReason.RECONNECT_YOURS);
        assertPauses(PausedReason.ACCOUNT_BROKEN);
    }

    @Test
    void aHolderWhoIsSuspendedOrGone() {
        stopped(SOURCE, AccountBinding.StopReason.PRINCIPAL_INACTIVE);
        principal(SERVICE, PrincipalKind.SERVICE, PrincipalStatus.SUSPENDED);
        assertPauses(PausedReason.ACCOUNT_BROKEN);
        when(principals.find(COMPANY, SERVICE)).thenReturn(Optional.empty());
        assertPauses(PausedReason.ACCOUNT_REMOVED);
        usable(SOURCE);
        stopped(OTHER_SYSTEM, AccountBinding.StopReason.PRINCIPAL_INACTIVE);
        assertPauses(PausedReason.NOT_ENTITLED);
    }

    @Test
    void aRemovedSystemDeletesTheSourcesTriggerAndPausesAnothers() {
        stopped(SOURCE, AccountBinding.StopReason.SYSTEM_GONE);
        assertThat(verdict()).isInstanceOfSatisfying(TriggerGateImpl.Verdict.Delete.class,
                delete -> assertThat(delete.cause()).isEqualTo("system.deleted"));
        usable(SOURCE);
        stopped(OTHER_SYSTEM, AccountBinding.StopReason.SYSTEM_GONE);
        assertThat(verdict()).isInstanceOfSatisfying(TriggerGateImpl.Verdict.Pause.class, pause -> {
            assertThat(pause.reason()).isEqualTo(PausedReason.SYSTEM_REMOVED);
            assertThat(pause.systemId()).isEqualTo(OTHER_SYSTEM);
        });
    }

    @Test
    void aRowWithoutItsAccountOrASourceWithoutARow() {
        rows.set(1, row(OTHER_SYSTEM, AUTHOR, null));
        assertPauses(PausedReason.ACCOUNT_REMOVED);
        rows.clear();
        assertPauses(PausedReason.ACCOUNT_REMOVED);
    }

    @Test
    void aPayerTheAuthorMayNoLongerUse() {
        when(principals.mayUse(COMPANY, AUTHOR_USER, SERVICE)).thenReturn(false);
        assertPauses(PausedReason.NOT_ENTITLED);
    }

    @Test
    void theModelAccessWaitsOrPauses() {
        when(modelAccess.forRun(any())).thenReturn(new RunModelAccess.Refuse(
                RunModelAccess.RefuseReason.PRINCIPAL_INACTIVE, "suspended"));
        assertThat(verdict()).isInstanceOfSatisfying(TriggerGateImpl.Verdict.Wait.class,
                wait -> assertThat(wait.reason()).isEqualTo(WaitingReason.SUSPENDED));
        when(modelAccess.forRun(any())).thenReturn(new RunModelAccess.Refuse(
                RunModelAccess.RefuseReason.GATEWAY_NOT_CONFIGURED, "no gateway"));
        assertThat(verdict()).isInstanceOfSatisfying(TriggerGateImpl.Verdict.Wait.class,
                wait -> assertThat(wait.reason()).isEqualTo(WaitingReason.GATEWAY_NOT_CONFIGURED));
        when(modelAccess.forRun(any())).thenReturn(new RunModelAccess.Refuse(
                RunModelAccess.RefuseReason.SUBSCRIPTION_GONE, "gone"));
        assertPauses(PausedReason.NOT_ENTITLED);
        // A spent budget fires: the turn waits (V7).
        when(modelAccess.forRun(any())).thenReturn(new RunModelAccess.Wait(RunModelAccess.WaitReason.BUDGET_SPENT,
                Instant.now(), "spent"));
        assertThat(verdict()).isInstanceOf(TriggerGateImpl.Verdict.Passes.class);
    }

    @Test
    void aPausedSystemPausesAndAStoppedCompanyWaits() {
        // Package 6, open point 6: a row on a paused system pauses the trigger SYSTEM_PAUSED, its row kept.
        stopped(OTHER_SYSTEM, AccountBinding.StopReason.SYSTEM_PAUSED);
        assertThat(verdict()).isInstanceOfSatisfying(TriggerGateImpl.Verdict.Pause.class, pause -> {
            assertThat(pause.reason()).isEqualTo(PausedReason.SYSTEM_PAUSED);
            assertThat(pause.systemId()).isNull();
        });
        // Open point 7: while the company's runs are stopped a trigger that would fire waits; what pauses it pauses.
        when(companySwitches.runsStopped(COMPANY)).thenReturn(Optional.of(new CompanySwitches.RunsStop(Instant.now(),
                null, "Incident")));
        assertPauses(PausedReason.SYSTEM_PAUSED);
        usable(OTHER_SYSTEM);
        assertThat(verdict()).isInstanceOfSatisfying(TriggerGateImpl.Verdict.Wait.class,
                wait -> assertThat(wait.reason()).isEqualTo(WaitingReason.COMPANY_STOPPED));
    }
}
