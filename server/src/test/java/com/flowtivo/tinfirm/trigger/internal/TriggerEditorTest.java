package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import com.flowtivo.tinfirm.agent.ToolMode;
import com.flowtivo.tinfirm.agent.TriggerSettings;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.model.Hosts;
import com.flowtivo.tinfirm.model.ModelAccess;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.api.model.AgentOwnServer;
import com.flowtivo.tinfirm.shared.api.model.AgentSettings;
import com.flowtivo.tinfirm.shared.api.model.AgentSettingsRequest;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolInfo;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerRepeat;
import com.flowtivo.tinfirm.trigger.TriggerResults;
import com.flowtivo.tinfirm.trigger.TriggerSource;
import com.flowtivo.tinfirm.trigger.TriggerStatus;
import com.flowtivo.tinfirm.trigger.TriggerSubject;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code TriggerEditor.check} as a table (the package 4 contract, §5.2, T1, T3): each outcome of
 * {@code Accounts.bind(Trigger)}, a payer without the use right, {@code Refuse} and {@code Wait} of {@code forRun},
 * {@code Nowhere}, the settings' refusal, the open events' acknowledgement for the author's own account and a service
 * identity's, a source row lent read-only while the results write (I9), and the one choice for the author and for a
 * service identity with and without an account per system.
 */
class TriggerEditorTest {

    static final UUID COMPANY = UUID.randomUUID();

    static final UUID AUTHOR = UUID.randomUUID();

    static final UUID AUTHOR_USER = UUID.randomUUID();

    static final UUID OTHER_MEMBER = UUID.randomUUID();

    static final UUID SERVICE = UUID.randomUUID();

    static final UUID AGENT = UUID.randomUUID();

    static final UUID JIRA = UUID.randomUUID();

    static final UUID OWN_ACCOUNT = UUID.randomUUID();

    static final UUID SERVICE_ACCOUNT = UUID.randomUUID();

    final Agents agents = mock(Agents.class);

    final TriggerSettings triggerSettings = mock(TriggerSettings.class);

    final Accounts accounts = mock(Accounts.class);

    final Principals principals = mock(Principals.class);

    final ModelAccess modelAccess = mock(ModelAccess.class);

    final Hosts hosts = mock(Hosts.class);

    final Systems systems = mock(Systems.class);

    final GroupMembership groups = mock(GroupMembership.class);

    final TriggerStore store = mock(TriggerStore.class);

    @SuppressWarnings("unchecked")
    final TriggerEditor editor = new TriggerEditor(agents, triggerSettings, accounts, principals, modelAccess, hosts,
            systems, groups, store, mock(org.springframework.beans.factory.ObjectProvider.class));

    final SystemInfo jira = new SystemInfo(JIRA, COMPANY, "Jira", "jira", SystemKind.JIRA_DC, "https://jira.test",
            null, null, false);

    final AccountInfo own = new AccountInfo(OWN_ACCOUNT, COMPANY, JIRA, AUTHOR, PrincipalKind.MEMBER, null,
            AccountInfo.Kind.TOKEN, "ext-" + AUTHOR, "login", AccountInfo.Status.OK, true);

    /** Jira's credential, lent to svc-ba (ADR-0072). */
    final AccountInfo service = credential(SERVICE_ACCOUNT, JIRA, "ext-svc-ba", AccountInfo.Status.OK);

    static AccountInfo credential(UUID id, UUID system, String externalId, AccountInfo.Status status) {
        return new AccountInfo(id, COMPANY, system, null, null, "Credential", AccountInfo.Kind.TOKEN, externalId,
                "login", status, true);
    }

    @BeforeEach
    void anAuthorWhoUsesTheAgentAndOwnsAnAccount() {
        AgentInfo agent = new AgentInfo(AGENT, COMPANY, "BA", "ba", null, "Analyse.", List.of(RuntimeKind.CLAUDE_CODE),
                AgentStatus.ACTIVE, null, 2, null);
        when(agents.find(COMPANY, AGENT)).thenReturn(Optional.of(agent));
        when(agents.access(COMPANY, AGENT, AUTHOR_USER)).thenReturn(new AgentAccess(true, true, false));
        // The agent writes comments on Jira without asking: open events are a risk with the author's own account.
        AgentDefinition definition = new AgentDefinition(List.of(new AgentDefinition.SystemEntry(JIRA,
                List.of(new AgentDefinition.ToolEntry("comment", ToolMode.ALLOW, ToolEffect.WRITE)))), List.of(),
                List.of());
        when(agents.effective(eq(COMPANY), eq(AGENT), any(Integer.class))).thenReturn(Optional.of(new EffectiveAgent(
                agent, 2, definition, AgentDefinition.Widening.NONE)));
        when(systems.find(COMPANY, JIRA)).thenReturn(Optional.of(jira));
        when(systems.findAll(eq(COMPANY), any())).thenReturn(Map.of(JIRA, jira));
        when(systems.tools(COMPANY, JIRA)).thenReturn(List.of(new ToolInfo("comment", null, null,
                JsonMapper.builder().build().createObjectNode(), ToolEffect.WRITE, true, true)));
        when(principals.find(COMPANY, AUTHOR)).thenReturn(Optional.of(new PrincipalInfo(AUTHOR, COMPANY,
                PrincipalKind.MEMBER, AUTHOR_USER, "Alice", PrincipalStatus.ACTIVE)));
        when(principals.find(COMPANY, OTHER_MEMBER)).thenReturn(Optional.of(new PrincipalInfo(OTHER_MEMBER, COMPANY,
                PrincipalKind.MEMBER, UUID.randomUUID(), "Bob", PrincipalStatus.ACTIVE)));
        when(principals.find(COMPANY, SERVICE)).thenReturn(Optional.of(new PrincipalInfo(SERVICE, COMPANY,
                PrincipalKind.SERVICE, null, "svc-ba", PrincipalStatus.ACTIVE)));
        when(principals.mayUse(COMPANY, AUTHOR_USER, SERVICE)).thenReturn(true);
        when(accounts.borrowers(COMPANY, SERVICE_ACCOUNT)).thenReturn(List.of(SERVICE));
        bind(OWN_ACCOUNT, new AccountBinding.Fixed(own, AUTHOR));
        bind(SERVICE_ACCOUNT, new AccountBinding.Fixed(service, SERVICE));
        when(accounts.find(COMPANY, OWN_ACCOUNT)).thenReturn(Optional.of(own));
        when(accounts.find(COMPANY, SERVICE_ACCOUNT)).thenReturn(Optional.of(service));
        when(modelAccess.forRun(any())).thenReturn(new RunModelAccess.Budget(AUTHOR, List.of()));
        when(hosts.eligible(any())).thenReturn(new Hosts.Eligibility.Eligible(List.of(), "No host is connected"));
    }

    /** A row naming the account acts through svc-ba for its credential, the one service account borrowing it. */
    void bind(UUID accountId, AccountBinding.Outcome outcome) {
        UUID holder = accountId.equals(SERVICE_ACCOUNT) ? SERVICE : AUTHOR;
        when(accounts.bind(new AccountBinding.Trigger(COMPANY, AUTHOR, holder, accountId), JIRA)).thenReturn(outcome);
    }

    static TriggerEditor.Draft draft(UUID accountId, UUID payer, boolean acknowledge) {
        return new TriggerEditor.Draft("Assigned to BA", TriggerSource.TRACKER, JIRA,
                List.of(new TriggerEventSpec(TriggerEvent.ASSIGNED, null)), TriggerSubject.ISSUE, "SHOP", List.of(),
                null, null, AGENT, 2, "Prepare it.", List.of(new TriggerEditor.RowInput(JIRA, accountId)), payer, null,
                null, null, null, acknowledge);
    }

    TriggerEditor.Checked check(TriggerEditor.Draft draft) {
        return editor.check(COMPANY, new TriggerEditor.Author(AUTHOR, AUTHOR_USER), draft, null, Instant.now());
    }

    static void refused(Runnable check, ProblemCode code) {
        assertThatThrownBy(check::run).isInstanceOfSatisfying(ApiException.class,
                problem -> assertThat(problem.code()).isEqualTo(code));
    }

    @Test
    void aLentCredentialUnderOpenEventsNeedsTheAcknowledgementToo() {
        // D2, the owner's decision Q3: the open events would write with svc-ba's credential without approval; the
        // author acknowledges every open write, own or lent, and is told that the lender will be.
        refused(() -> check(draft(SERVICE_ACCOUNT, SERVICE, false)), ProblemCode.OPEN_EVENTS_NOT_ACKNOWLEDGED);
        TriggerEditor.Checked previewed = editor.check(COMPANY, new TriggerEditor.Author(AUTHOR, AUTHOR_USER),
                draft(SERVICE_ACCOUNT, SERVICE, false), null, Instant.now(), true);
        assertThat(previewed.unacknowledged()).contains("with a credential lent to a service account")
            .contains("whoever lent the credential is told")
            .doesNotContain("service identity's account");
        TriggerEditor.Checked checked = check(draft(SERVICE_ACCOUNT, SERVICE, true));
        assertThat(checked.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(TriggerEditor.Status.OK);
            assertThat(row.account().credential()).isTrue();
            assertThat(row.holderPrincipalId()).isEqualTo(SERVICE);
        });
        assertThat(checked.payer().id()).isEqualTo(SERVICE);
        assertThat(checked.openWrites()).singleElement().satisfies(write -> assertThat(write.own()).isFalse());
        assertThat(checked.acknowledgementNeeded()).isTrue();
        assertThat(TriggerEditor.who(checked.definition())).isEqualTo("issues in SHOP");
    }

    @Test
    void aCredentialLentReadOnlyWritesNothingAndNeedsNoAcknowledgement() {
        // The tracker's results write nothing here: the READ lending is the row, and no write tool runs with it.
        when(accounts.fixedAccess(COMPANY, JIRA, SERVICE, SERVICE_ACCOUNT)).thenReturn(GrantInfo.Access.READ);
        TriggerEditor.Draft reads = new TriggerEditor.Draft("Assigned to BA", TriggerSource.TRACKER, JIRA,
                List.of(new TriggerEventSpec(TriggerEvent.ASSIGNED, null)), TriggerSubject.ISSUE, "SHOP", List.of(),
                null, null, AGENT, 2, "Prepare it.", List.of(new TriggerEditor.RowInput(JIRA, SERVICE_ACCOUNT)),
                SERVICE, null, null, new TriggerResults(false, false, List.of(), List.of()), null, false);
        TriggerEditor.Checked checked = check(reads);
        assertThat(checked.openWrites()).isEmpty();
        assertThat(checked.acknowledgementNeeded()).isFalse();
    }

    static final UUID EXISTING = UUID.randomUUID();

    /** The trigger as saved, its open events acknowledged by its author. */
    static TriggerInfo existing() {
        return new TriggerInfo(EXISTING, COMPANY, "Assigned to BA", TriggerSource.TRACKER, JIRA,
                List.of(new TriggerEventSpec(TriggerEvent.ASSIGNED, null)), TriggerSubject.ISSUE, "SHOP", List.of(),
                TriggerRepeat.EVERY_TIME, null, AGENT, "Prepare it.", AUTHOR, null, AUTHOR, TriggerResults.DEFAULT,
                30, TriggerStatus.ACTIVE, null, null, null, null, Instant.now(), 0, 0, Instant.now(), null, null,
                Instant.now(), Instant.now());
    }

    /** A change's draft with settings of its own (the editor reads the stored ones otherwise). */
    static TriggerEditor.Draft change(UUID accountId, UUID payer, AgentSettingsRequest settings) {
        return new TriggerEditor.Draft("Assigned to BA", TriggerSource.TRACKER, JIRA,
                List.of(new TriggerEventSpec(TriggerEvent.ASSIGNED, null)), TriggerSubject.ISSUE, "SHOP", List.of(),
                null, null, AGENT, 2, "Prepare it.", List.of(new TriggerEditor.RowInput(JIRA, accountId)), payer,
                settings, null, null, null, true);
    }

    void tableWas(UUID accountId, UUID holder) {
        when(store.accounts(COMPANY, EXISTING)).thenReturn(List.of(new TriggerAccount(JIRA, accountId, holder,
                "ext", TriggerAccount.FilledFrom.ROW, Instant.now())));
    }

    TriggerEditor.Checked onBehalf(TriggerEditor.Draft draft) {
        return editor.check(COMPANY, new TriggerEditor.Author(AUTHOR, AUTHOR_USER), draft, existing(), Instant.now(),
                false, true);
    }

    @Test
    void anAdminOnTheAuthorsBehalfNamesNoAccountOfTheAuthorsOwnTheTriggerDidNotHave() {
        // H2, the owner's decision Q2: the trigger acted with svc-ba's credential; an admin names Alice's own account
        // in its place, on her behalf: refused, with the system.
        when(triggerSettings.acceptedVersion(COMPANY, EXISTING)).thenReturn(2);
        tableWas(SERVICE_ACCOUNT, SERVICE);
        assertThatThrownBy(() -> onBehalf(change(OWN_ACCOUNT, AUTHOR, new AgentSettingsRequest())))
            .isInstanceOfSatisfying(ApiException.class, problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.AUTHOR_ACCOUNT_NOT_ADMINS);
                assertThat(problem.properties().get("details")).isEqualTo(Map.of("systemId", JIRA.toString()));
            });
        // The author's own save names it.
        assertThat(editor.check(COMPANY, new TriggerEditor.Author(AUTHOR, AUTHOR_USER), change(OWN_ACCOUNT, AUTHOR,
                new AgentSettingsRequest()), existing(), Instant.now(), false, false).rows()).singleElement()
            .satisfies(row -> assertThat(row.own()).isTrue());
        // A row the trigger had with the author's own account is the admin's to keep; a lent credential to name.
        tableWas(OWN_ACCOUNT, AUTHOR);
        assertThat(onBehalf(change(OWN_ACCOUNT, AUTHOR, new AgentSettingsRequest())).rows()).hasSize(1);
        assertThat(onBehalf(change(SERVICE_ACCOUNT, SERVICE, new AgentSettingsRequest())).rows()).singleElement()
            .satisfies(row -> assertThat(row.own()).isFalse());
    }

    @Test
    void anAdminOnTheAuthorsBehalfAddsNoMcpServerOfTheAuthorsOwn() {
        // H2: the author's own MCP servers act as the author; an admin adds none the settings did not hold.
        when(triggerSettings.acceptedVersion(COMPANY, EXISTING)).thenReturn(2);
        tableWas(OWN_ACCOUNT, AUTHOR);
        UUID personal = UUID.randomUUID();
        UUID added = UUID.randomUUID();
        AgentSettings stored = mock(AgentSettings.class);
        when(stored.getOwnServers()).thenReturn(List.of(new AgentOwnServer(personal)));
        when(triggerSettings.view(COMPANY, AGENT, EXISTING)).thenReturn(stored);
        AgentSettingsRequest kept = new AgentSettingsRequest().ownServers(List.of(new AgentOwnServer(personal)));
        assertThat(onBehalf(change(OWN_ACCOUNT, AUTHOR, kept)).rows()).hasSize(1);
        AgentSettingsRequest widened = new AgentSettingsRequest().ownServers(List.of(new AgentOwnServer(personal),
                new AgentOwnServer(added)));
        assertThatThrownBy(() -> onBehalf(change(OWN_ACCOUNT, AUTHOR, widened)))
            .isInstanceOfSatisfying(ApiException.class, problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.AUTHOR_ACCOUNT_NOT_ADMINS);
                assertThat(problem.properties().get("details")).isEqualTo(Map.of("systemId", added.toString()));
            });
    }

    @Test
    void theAuthorsOwnAccountUnderOpenEventsNeedsTheAcknowledgement() {
        refused(() -> check(draft(OWN_ACCOUNT, AUTHOR, false)), ProblemCode.OPEN_EVENTS_NOT_ACKNOWLEDGED);
        TriggerEditor.Checked checked = check(draft(OWN_ACCOUNT, AUTHOR, true));
        assertThat(checked.acknowledgementNeeded()).isTrue();
        assertThat(checked.unacknowledged()).isNull();
        // A preview answers the missing acknowledgement in words instead of refusing it.
        TriggerEditor.Checked previewed = editor.check(COMPANY, new TriggerEditor.Author(AUTHOR, AUTHOR_USER),
                draft(OWN_ACCOUNT, AUTHOR, false), null, Instant.now(), true);
        assertThat(previewed.acknowledgementNeeded()).isTrue();
        assertThat(previewed.unacknowledged()).startsWith("Anyone who may comment on or change issues in SHOP")
            .contains("acknowledgeOpenEvents");
    }

    @Test
    void anAccountTheAuthorIsNotEntitledToIsRefused() {
        bind(SERVICE_ACCOUNT, new AccountBinding.NotEntitled(JIRA, AccountBinding.Refusal.NOT_OWNER));
        assertThatThrownBy(() -> check(draft(SERVICE_ACCOUNT, AUTHOR, true)))
            .isInstanceOfSatisfying(ApiException.class, problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.ACCOUNT_NOT_ENTITLED);
                assertThat(problem.toString() + problem.getMessage()).contains(JIRA.toString());
            });
    }

    @Test
    void aSourceRowLentReadOnlyIsRefusedWhileTheResultsWrite() {
        // I9: the credential lent READ to svc-ba cannot write the results' comment back (ADR-0072, open point 1).
        when(accounts.fixedAccess(COMPANY, JIRA, SERVICE, SERVICE_ACCOUNT)).thenReturn(GrantInfo.Access.READ);
        assertThatThrownBy(() -> check(draft(SERVICE_ACCOUNT, SERVICE, false)))
            .isInstanceOfSatisfying(ApiException.class, problem -> {
                assertThat(problem.code()).isEqualTo(ProblemCode.ACCOUNT_READ_ONLY);
                assertThat(problem.getMessage()).contains("Jira").contains("svc-ba");
                assertThat(problem.properties().get("details")).isEqualTo(Map.of("systemId", JIRA.toString()));
            });
        // Results that write nothing are kept: the row reads, and no open write warns.
        TriggerEditor.Draft reads = new TriggerEditor.Draft("Assigned to BA", TriggerSource.TRACKER, JIRA,
                List.of(new TriggerEventSpec(TriggerEvent.ASSIGNED, null)), TriggerSubject.ISSUE, "SHOP", List.of(),
                null, null, AGENT, 2, "Prepare it.", List.of(new TriggerEditor.RowInput(JIRA, SERVICE_ACCOUNT)),
                SERVICE, null, null, new TriggerResults(false, false, List.of(), List.of()), null, false);
        TriggerEditor.Checked checked = check(reads);
        assertThat(checked.rows()).singleElement().satisfies(row -> assertThat(row.holderPrincipalId())
            .isEqualTo(SERVICE));
        assertThat(checked.openWrites()).isEmpty();
    }

    @Test
    void aRowLentReadOnlyOnAnotherSystemThanTheSourceIsAccepted() {
        UUID github = UUID.randomUUID();
        AccountInfo githubCredential = credential(UUID.randomUUID(), github, "gh-bot", AccountInfo.Status.OK);
        when(accounts.find(COMPANY, githubCredential.id())).thenReturn(Optional.of(githubCredential));
        when(accounts.borrowers(COMPANY, githubCredential.id())).thenReturn(List.of(SERVICE));
        when(accounts.bind(new AccountBinding.Trigger(COMPANY, AUTHOR, SERVICE, githubCredential.id()), github))
            .thenReturn(new AccountBinding.Fixed(githubCredential, SERVICE));
        when(accounts.fixedAccess(COMPANY, github, SERVICE, githubCredential.id())).thenReturn(GrantInfo.Access.READ);
        TriggerEditor.Draft draft = new TriggerEditor.Draft("Assigned to BA", TriggerSource.TRACKER, JIRA,
                List.of(new TriggerEventSpec(TriggerEvent.ASSIGNED, null)), TriggerSubject.ISSUE, "SHOP", List.of(),
                null, null, AGENT, 2, "Prepare it.", List.of(new TriggerEditor.RowInput(JIRA, OWN_ACCOUNT),
                        new TriggerEditor.RowInput(github, githubCredential.id())), AUTHOR, null, null, null, null,
                true);
        TriggerEditor.Checked checked = check(draft);
        assertThat(checked.rows()).extracting(TriggerEditor.Row::systemId).containsExactly(JIRA, github);
    }

    @Test
    void anAccountThatNeedsSigningInIsStoredAndSaysWho() {
        bind(OWN_ACCOUNT, new AccountBinding.ConnectRequired(JIRA, AccountBinding.ConnectReason.RECONNECT_YOURS,
                List.of()));
        assertThat(check(draft(OWN_ACCOUNT, AUTHOR, true)).rows().getFirst().status())
            .isEqualTo(TriggerEditor.Status.RECONNECT_YOURS);
        bind(SERVICE_ACCOUNT, new AccountBinding.ConnectRequired(JIRA, AccountBinding.ConnectReason.RECONNECT_COMPANY,
                List.of()));
        assertThat(check(draft(SERVICE_ACCOUNT, SERVICE, true)).rows().getFirst().status())
            .isEqualTo(TriggerEditor.Status.RECONNECT_COMPANY);
    }

    @Test
    void theSourceNeedsAnAccount() {
        TriggerEditor.Draft none = new TriggerEditor.Draft("Assigned", TriggerSource.TRACKER, JIRA,
                List.of(new TriggerEventSpec(TriggerEvent.ASSIGNED, null)), TriggerSubject.ISSUE, null, List.of(),
                null, null, AGENT, 2, "Prepare it.", List.of(), AUTHOR, null, null, null, null, false);
        refused(() -> check(none), ProblemCode.SOURCE_ACCOUNT_REQUIRED);
    }

    @Test
    void aPayerTheAuthorMayNotUseIsRefused() {
        refused(() -> check(draft(SERVICE_ACCOUNT, OTHER_MEMBER, false)), ProblemCode.MODEL_ACCESS_NOT_ENTITLED);
        when(principals.mayUse(COMPANY, AUTHOR_USER, SERVICE)).thenReturn(false);
        refused(() -> check(draft(OWN_ACCOUNT, SERVICE, true)), ProblemCode.MODEL_ACCESS_NOT_ENTITLED);
    }

    @Test
    void forRunRefusesOrWaitsAndHostsMayBeNowhere() {
        when(modelAccess.forRun(any())).thenReturn(new RunModelAccess.Refuse(
                RunModelAccess.RefuseReason.GATEWAY_NOT_CONFIGURED, "No gateway"));
        refused(() -> check(draft(SERVICE_ACCOUNT, SERVICE, false)), ProblemCode.MODEL_ACCESS_UNAVAILABLE);
        RunModelAccess.Wait spent = new RunModelAccess.Wait(RunModelAccess.WaitReason.BUDGET_SPENT, Instant.now(),
                "Spent");
        when(modelAccess.forRun(any())).thenReturn(spent);
        assertThat(check(draft(SERVICE_ACCOUNT, SERVICE, true)).payment()).isEqualTo(spent);
        when(hosts.eligible(any())).thenReturn(new Hosts.Eligibility.Nowhere(new RunModelAccess.Refuse(
                RunModelAccess.RefuseReason.CANNOT_RUN_ANYWHERE, "Nowhere")));
        refused(() -> check(draft(SERVICE_ACCOUNT, SERVICE, false)), ProblemCode.CANNOT_RUN_ANYWHERE);
    }

    @Test
    void theSettingsChecksAreTheTriggers() {
        // T3: the author's own MCP servers with a service identity's row: the settings refuse to widen.
        doThrow(ApiException.badRequest(ProblemCode.SETTINGS_WIDEN, "widen")).when(triggerSettings)
            .check(eq(COMPANY), eq(AGENT), any(TriggerSettings.Owner.class), any());
        refused(() -> check(draft(SERVICE_ACCOUNT, SERVICE, false)), ProblemCode.SETTINGS_WIDEN);
    }

    @Test
    void theOneChoiceOffersServiceIdentitiesFirstWithTheirAccountsPerSystem() {
        when(principals.usableServiceIdentities(COMPANY, AUTHOR_USER)).thenReturn(Set.of(SERVICE));
        when(principals.findAll(eq(COMPANY), any())).thenReturn(Map.of(
                AUTHOR, new PrincipalInfo(AUTHOR, COMPANY, PrincipalKind.MEMBER, AUTHOR_USER, "Alice",
                        PrincipalStatus.ACTIVE),
                SERVICE, new PrincipalInfo(SERVICE, COMPANY, PrincipalKind.SERVICE, null, "svc-ba",
                        PrincipalStatus.ACTIVE)));
        when(accounts.lentTo(COMPANY, JIRA, SERVICE)).thenReturn(Optional.of(new Accounts.Lent(service, SERVICE,
                GrantInfo.Access.WRITE)));
        when(accounts.of(COMPANY, JIRA, AUTHOR)).thenReturn(Optional.empty());
        when(modelAccess.models(COMPANY, SERVICE)).thenReturn(List.of("claude-haiku-*"));
        when(modelAccess.models(COMPANY, AUTHOR)).thenReturn(List.of());
        List<TriggerEditor.Choice> choices = editor.choices(COMPANY, new TriggerEditor.Author(AUTHOR, AUTHOR_USER),
                agents.find(COMPANY, AGENT).orElseThrow(), JIRA);
        assertThat(choices).extracting(choice -> choice.principal().id()).containsExactly(SERVICE, AUTHOR);
        assertThat(choices.get(0).accounts().get(JIRA)).contains(service);
        assertThat(choices.get(1).accounts().get(JIRA)).isEmpty();
        // Each payer's models, as its key allows them: the service identity's own list, the author's every model.
        assertThat(choices.get(0).models()).containsExactly("claude-haiku-*");
        assertThat(choices.get(1).models()).isEmpty();
    }

    @Test
    void theOneChoiceOffersNoAccountATableMayNotHold() {
        // A channel trigger's source, Slack: the author's link and the bot's account are refused in a table (C9), so
        // a choice never fills them; an account to sign in to again stays, as the save keeps it.
        UUID slack = UUID.randomUUID();
        AccountInfo link = new AccountInfo(UUID.randomUUID(), COMPANY, slack, AUTHOR, PrincipalKind.MEMBER, null,
                AccountInfo.Kind.LINK, "U0AUTHOR", "alice", AccountInfo.Status.OK, true);
        AccountInfo bot = credential(UUID.randomUUID(), slack, "U0BOT", AccountInfo.Status.OK);
        AccountInfo stale = new AccountInfo(UUID.randomUUID(), COMPANY, JIRA, AUTHOR, PrincipalKind.MEMBER, null,
                AccountInfo.Kind.TOKEN, "ext-author", "alice", AccountInfo.Status.RECONNECT, true);
        when(principals.usableServiceIdentities(COMPANY, AUTHOR_USER)).thenReturn(Set.of(SERVICE));
        when(principals.findAll(eq(COMPANY), any())).thenReturn(Map.of(
                AUTHOR, new PrincipalInfo(AUTHOR, COMPANY, PrincipalKind.MEMBER, AUTHOR_USER, "Alice",
                        PrincipalStatus.ACTIVE),
                SERVICE, new PrincipalInfo(SERVICE, COMPANY, PrincipalKind.SERVICE, null, "svc-bot",
                        PrincipalStatus.ACTIVE)));
        when(accounts.of(COMPANY, slack, AUTHOR)).thenReturn(Optional.of(link));
        when(accounts.lentTo(COMPANY, slack, SERVICE)).thenReturn(Optional.of(new Accounts.Lent(bot, SERVICE,
                GrantInfo.Access.WRITE)));
        when(accounts.of(COMPANY, JIRA, AUTHOR)).thenReturn(Optional.of(stale));
        when(accounts.lentTo(COMPANY, JIRA, SERVICE)).thenReturn(Optional.of(new Accounts.Lent(service, SERVICE,
                GrantInfo.Access.WRITE)));
        when(accounts.bind(new AccountBinding.Trigger(COMPANY, AUTHOR, AUTHOR, link.id()), slack))
            .thenReturn(new AccountBinding.NotEntitled(slack, AccountBinding.Refusal.ACCOUNT_NOT_USABLE));
        when(accounts.bind(new AccountBinding.Trigger(COMPANY, AUTHOR, SERVICE, bot.id()), slack))
            .thenReturn(new AccountBinding.NotEntitled(slack, AccountBinding.Refusal.BOT_ACCOUNT));
        bind(stale.id(), new AccountBinding.ConnectRequired(JIRA, AccountBinding.ConnectReason.RECONNECT_YOURS,
                List.of()));
        List<TriggerEditor.Choice> choices = editor.choices(COMPANY, new TriggerEditor.Author(AUTHOR, AUTHOR_USER),
                agents.find(COMPANY, AGENT).orElseThrow(), slack);
        assertThat(choices).extracting(choice -> choice.principal().id()).containsExactly(SERVICE, AUTHOR);
        assertThat(choices.get(0).accounts().get(slack)).isEmpty();
        assertThat(choices.get(0).accounts().get(JIRA)).contains(service);
        assertThat(choices.get(1).accounts().get(slack)).isEmpty();
        assertThat(choices.get(1).accounts().get(JIRA)).contains(stale);
    }
}
