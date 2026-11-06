import type { Routes } from '@angular/router';
import { adminGuard, authGuard, guestGuard } from './core/auth.guard';
import { companyAdminGuard, companyWorkerGuard } from './core/company-guards';
import { homeRedirectGuard } from './pages/home/home-redirect.guard';
import { chatLinkGuard } from './pages/link/chat-link-token';
import { gmailLinkGuard } from './pages/link/gmail-link-token';

/** The pages under `/c/:companyId` (plan §3); the sidebar's items are in `shell/navigation.ts`. */
export const companyRoutes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'inbox' },

  // --- Work: every member -----------------------------------------------------------------
  {
    path: 'inbox',
    title: 'Inbox',
    loadComponent: () => import('./pages/company/inbox/inbox').then((m) => m.Inbox),
  },
  {
    path: 'conversations',
    title: 'Conversations',
    loadComponent: () =>
      import('./pages/company/conversations/conversations').then((m) => m.Conversations),
  },
  {
    path: 'conversations/new',
    title: 'New conversation',
    canActivate: [companyWorkerGuard],
    loadComponent: () =>
      import('./pages/company/conversations/new-conversation').then((m) => m.NewConversation),
  },
  {
    path: 'conversations/:conversationId',
    title: 'Conversation',
    data: { wide: true },
    loadComponent: () =>
      import('./pages/company/conversations/conversation-page').then((m) => m.ConversationPage),
  },
  {
    path: 'runs/:runId',
    title: 'Run',
    data: { wide: true },
    loadComponent: () => import('./pages/company/runs/run').then((m) => m.RunPage),
  },
  {
    path: 'agents',
    title: 'Agents',
    loadComponent: () => import('./pages/company/agents/agents').then((m) => m.Agents),
  },
  {
    path: 'agents/new',
    title: 'New agent',
    canActivate: [companyWorkerGuard],
    loadComponent: () => import('./pages/company/agents/agent-editor').then((m) => m.AgentEditor),
  },
  {
    path: 'agents/:agentId',
    title: 'Agent',
    loadComponent: () => import('./pages/company/agents/agent-page').then((m) => m.AgentPage),
  },
  {
    path: 'agents/:agentId/edit',
    title: 'Edit agent',
    loadComponent: () => import('./pages/company/agents/agent-editor').then((m) => m.AgentEditor),
  },
  {
    path: 'agents/:agentId/settings',
    title: 'My settings',
    canActivate: [companyWorkerGuard],
    loadComponent: () =>
      import('./pages/company/agents/agent-settings-page').then((m) => m.AgentSettingsPage),
  },
  // The library of skills (ADR-0064): every member reads it and creates skills.
  {
    path: 'skills',
    title: 'Skills',
    loadComponent: () => import('./pages/company/skills/skills').then((m) => m.Skills),
  },
  {
    path: 'skills/new',
    title: 'New skill',
    canActivate: [companyWorkerGuard],
    loadComponent: () => import('./pages/company/skills/new-skill').then((m) => m.NewSkill),
  },
  {
    path: 'skills/:skillId',
    title: 'Skill',
    loadComponent: () => import('./pages/company/skills/skill-page').then((m) => m.SkillPage),
  },
  {
    path: 'triggers',
    title: 'Triggers',
    loadComponent: () => import('./pages/company/triggers/triggers').then((m) => m.Triggers),
  },
  {
    path: 'triggers/new',
    title: 'New trigger',
    canActivate: [companyWorkerGuard],
    data: { wide: true },
    loadComponent: () =>
      import('./pages/company/triggers/trigger-editor').then((m) => m.TriggerEditor),
  },
  {
    path: 'triggers/:triggerId',
    title: 'Trigger',
    loadComponent: () => import('./pages/company/triggers/trigger-page').then((m) => m.TriggerPage),
  },
  {
    path: 'triggers/:triggerId/edit',
    title: 'Edit trigger',
    data: { wide: true },
    loadComponent: () =>
      import('./pages/company/triggers/trigger-editor').then((m) => m.TriggerEditor),
  },
  {
    path: 'accounts',
    title: 'Accounts',
    canActivate: [companyWorkerGuard],
    loadComponent: () => import('./pages/company/accounts/accounts').then((m) => m.Accounts),
  },

  // --- Company: OWNER and ADMIN ------------------------------------------------------------
  {
    path: 'systems',
    title: 'Systems',
    canActivate: [companyAdminGuard],
    loadComponent: () => import('./pages/company/systems/systems').then((m) => m.Systems),
  },
  // A member reaches their own MCP server's page from Accounts; the server answers 404 otherwise.
  {
    path: 'systems/:systemId',
    title: 'System',
    loadComponent: () => import('./pages/company/systems/system-page').then((m) => m.SystemPage),
  },
  {
    path: 'service-identities',
    title: 'Service accounts',
    canActivate: [companyAdminGuard],
    loadComponent: () =>
      import('./pages/company/service-identities/service-identities').then(
        (m) => m.ServiceIdentities,
      ),
  },
  {
    path: 'service-identities/:principalId',
    title: 'Service account',
    canActivate: [companyAdminGuard],
    loadComponent: () =>
      import('./pages/company/service-identities/service-identity-page').then(
        (m) => m.ServiceIdentityPage,
      ),
  },
  {
    path: 'model-access',
    title: 'Model access',
    canActivate: [companyAdminGuard],
    loadComponent: () =>
      import('./pages/company/model-access/model-access').then((m) => m.ModelAccess),
  },
  {
    path: 'runner-hosts',
    title: 'Runner hosts',
    canActivate: [companyAdminGuard],
    loadComponent: () =>
      import('./pages/company/runner-hosts/runner-hosts').then((m) => m.RunnerHosts),
  },
  {
    path: 'members',
    title: 'Members',
    canActivate: [companyAdminGuard],
    loadComponent: () => import('./pages/company/members/members').then((m) => m.Members),
  },
  {
    path: 'members/departures/:principalId',
    title: 'A member who left',
    canActivate: [companyAdminGuard],
    loadComponent: () =>
      import('./pages/company/members/departure-page').then((m) => m.DeparturePage),
  },
  {
    path: 'audit',
    title: 'Audit log',
    canActivate: [companyAdminGuard],
    loadComponent: () => import('./pages/company/company-audit').then((m) => m.CompanyAudit),
  },
  // Every member reads the company's stop and their own suspension here; admins see every switch.
  {
    path: 'kill-switches',
    title: 'Kill switches',
    loadComponent: () =>
      import('./pages/company/kill-switches/kill-switches').then((m) => m.KillSwitchesPage),
  },
  // Every member sees the spend within their scope (G1); admins reach it from Model access too.
  {
    path: 'spend',
    title: 'Spend',
    loadComponent: () => import('./pages/company/spend/spend-page').then((m) => m.SpendPage),
  },
  {
    path: 'retention',
    title: 'Retention',
    canActivate: [companyAdminGuard],
    loadComponent: () => import('./pages/company/retention/retention').then((m) => m.RetentionPage),
  },
  // One section a page; the index of the sections is the layout's (plan §5).
  {
    path: 'settings',
    canActivate: [companyAdminGuard],
    loadComponent: () =>
      import('./pages/company/settings/settings-layout').then((m) => m.SettingsLayout),
    children: [
      {
        path: '',
        title: 'Settings',
        loadComponent: () =>
          import('./pages/company/settings/general-settings').then((m) => m.GeneralSettings),
      },
      {
        path: 'limits',
        title: 'Chats and webhooks',
        loadComponent: () =>
          import('./pages/company/settings/chat-rates-setting').then((m) => m.ChatRatesSetting),
      },
      {
        path: 'memory',
        title: 'Memory settings',
        loadComponent: () =>
          import('./pages/company/settings/memory-settings').then((m) => m.MemorySettingsSection),
      },
      {
        path: 'secrets',
        title: 'Secrets',
        loadComponent: () => import('./pages/company/secrets/secrets').then((m) => m.Secrets),
      },
      {
        path: 'sign-in',
        title: 'Sign-in',
        loadComponent: () =>
          import('./pages/company/settings/sign-in-settings').then((m) => m.SignInSettings),
      },
      {
        path: 'licence',
        title: 'Licence',
        loadComponent: () =>
          import('./pages/company/settings/licence-settings').then((m) => m.LicenceSettings),
      },
      { path: '**', redirectTo: '' },
    ],
  },

  // --- Older paths, redirected to the pages that replace them --------------------------------
  { path: 'work', redirectTo: 'conversations' },
  { path: 'work/:workItemId', redirectTo: 'conversations' },
  { path: 'connections', redirectTo: 'systems' },
  { path: 'connections/:connectionId', redirectTo: 'systems' },
  { path: 'credentials', redirectTo: 'model-access' },
  { path: 'runners', redirectTo: 'runner-hosts' },
  { path: 'groups', redirectTo: ({ params }) => `/c/${params['companyId']}/members?tab=groups` },
  { path: 'secrets', redirectTo: 'settings/secrets' },
];

export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in',
    canActivate: [guestGuard],
    loadComponent: () => import('./pages/login/login').then((m) => m.Login),
  },
  {
    // The consent page of the OAuth authorization server (ADR-0045), outside the app shell.
    path: 'authorize',
    title: 'Authorize an app',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/authorize/authorize').then((m) => m.Authorize),
  },
  // The Outlook add-in (ADR-0074), outside the shell and without a session: its sign-in callback,
  // then its task pane, which call the company's MCP endpoint with a bearer token.
  {
    path: 'outlook/callback',
    title: 'Signing in',
    loadComponent: () => import('./pages/outlook/outlook-callback').then((m) => m.OutlookCallback),
  },
  {
    path: 'outlook/:companyId',
    title: 'Ask an agent',
    loadComponent: () => import('./pages/outlook/task-pane').then((m) => m.TaskPane),
  },
  {
    // The Gmail add-on's sign-in link (ADR-0083), outside the shell: the token is in the fragment.
    path: 'gmail/link/:companyId',
    title: 'Link your Google account',
    canActivate: [gmailLinkGuard],
    loadComponent: () => import('./pages/link/gmail-link').then((m) => m.GmailLinkPage),
  },
  {
    // A chat user's sign-in link (package 5, §5.4), outside the shell: the token is in the fragment.
    path: 'link/:companyId',
    title: 'Link your chat account',
    canActivate: [chatLinkGuard],
    loadComponent: () => import('./pages/link/chat-link').then((m) => m.ChatLinkPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./shell/shell').then((m) => m.Shell),
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Home',
        canActivate: [homeRedirectGuard],
        loadComponent: () => import('./pages/home/no-company').then((m) => m.NoCompany),
      },
      {
        path: 'c/:companyId',
        loadComponent: () => import('./pages/company/company-layout').then((m) => m.CompanyLayout),
        children: companyRoutes,
      },
      {
        path: 'me',
        title: 'Profile',
        loadComponent: () => import('./pages/me/profile').then((m) => m.Profile),
      },
      {
        path: 'admin',
        canActivate: [adminGuard],
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'users' },
          {
            path: 'users',
            title: 'Users',
            loadComponent: () => import('./pages/admin/users/users').then((m) => m.Users),
          },
          {
            path: 'companies',
            title: 'Companies',
            loadComponent: () =>
              import('./pages/admin/companies/companies').then((m) => m.Companies),
          },
          {
            path: 'audit',
            title: 'Instance audit log',
            loadComponent: () =>
              import('./pages/admin/instance-audit').then((m) => m.InstanceAudit),
          },
          {
            path: 'licence',
            title: 'Licence',
            loadComponent: () => import('./pages/admin/licence/licence').then((m) => m.LicencePage),
          },
          {
            path: 'errors',
            title: 'Errors',
            loadComponent: () =>
              import('./pages/admin/error-reports/error-reports').then((m) => m.ErrorReports),
          },
        ],
      },
      {
        path: '**',
        title: 'Not found',
        loadComponent: () => import('./pages/not-found').then((m) => m.NotFound),
      },
    ],
  },
];
