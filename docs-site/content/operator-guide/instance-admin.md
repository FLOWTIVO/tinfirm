# The Instance admin pages

This page is for the instance admins of a Tinfirm installation: the people who manage its users, see its companies, read the instance's own audit log, and enter its licence key. It says who is an instance admin, how the first one comes about, and what each page of the **Instance admin** section does.

An instance admin sees the section **Instance admin** in the sidebar, with **Users**, **Companies**, **Instance audit log**, **Errors** and **Licence**. In the company switcher, **All companies** opens **Companies**.

## Who is an instance admin

Instance admin is a flag on a user, separate from any role in a company. It lets the user manage the instance's users, see every company, and enter the licence key. It gives them no role in a company: in a company they are a member of, they act by their role there, like anyone else.

### The first instance admin

On its first start, while the database holds no user at all, the server creates one local user who is an instance admin, from two settings:

| Variable | Helm |
|---|---|
| `TINFIRM_BOOTSTRAP_ADMIN_EMAIL` | `bootstrap.secretKeys.adminEmail` in the Secret `bootstrap.existingSecret` |
| `TINFIRM_BOOTSTRAP_ADMIN_PASSWORD` | `bootstrap.secretKeys.adminPassword` in the same Secret |

Both must be set, or the server creates no one. It does this once: when a user exists, the values are ignored, and changing them later changes no user. Sign in with them, then change the password on your **Profile**. A declared company that names no `owner` takes the bootstrap admin's email as its Owner, at every start while it has none, a company declared later included: keep the Secret, or name every company's `owner`. [Try Tinfirm with Docker Compose](quick-start.md#2-sign-in) shows the first sign-in.

A user who signs in through an identity provider is never made an instance admin by it. An instance admin makes others so on **Users**.

## Users

**Users** lists everyone with an account on the instance:

| Column | What it shows |
|---|---|
| **User** | Their name and email, and **You** on your own row |
| **Sign-in** | **Local**, a password kept by Tinfirm, or **SSO**, an identity provider |
| **Admin** | A switch: whether they are an instance admin |
| **Status** | **Active** or **Deactivated** |
| **Last sign-in** | When they last signed in, or **Never** |

### Create a local user

1. Choose **New user**.
2. Enter their **Email**, **Display name** (up to 100 characters) and an **Initial password** of at least 12 characters. Turn on **Instance admin** if they should be one.
3. Choose **Create user**, and give them the password securely. They can change it on their **Profile**.

The email must not belong to another user. SSO users are not created here: they are created on their first sign-in through their company's identity provider.

A new user belongs to no company. A company's Owners and Admins add them on its **Members** page, by their email: [Members and groups](../admin-guide/members.md).

### Set a password

For a local user, the row's menu offers **Set password…**. Enter the **New password** twice and choose **Set password**. It works at once, and every other session of that user ends. An SSO user has no password in Tinfirm.

### Make someone an instance admin

Turn the **Admin** switch on their row on or off. The instance always keeps an active instance admin: the last one cannot be demoted or deactivated (**This is the last active instance admin. Make another user an instance admin first.**).

### Deactivate and activate

**Deactivate…** on a row's menu ends the user's sessions at once, and they can't sign in, locally or through single sign-on, until you activate them again. You can't deactivate yourself.

Their memberships stay, but in every company they belong to they are suspended: nothing acts as them, with their accounts or on their budget, and their running turns are cancelled ([Kill switches](../admin-guide/kill-switches.md#suspend-a-member-or-a-service-account)). A company's Owners and Admins cannot lift that suspension; the page tells them that the user was deactivated.

**Activate** lets them sign in again, and lifts those suspensions, and only those. A deactivated user holds no seat; activating them takes their seats back, and is refused when every seat of the licence is taken.

Tinfirm has no way to delete a user.

## Companies

Companies are declared in the deployment's configuration, never created in the app: `companies` in the Helm values, or `TINFIRM_COMPANIES_<KEY>_NAME` and its siblings in Docker Compose. At every start, the server creates each declared company that does not exist yet. Removing a declaration deletes no company. [Declare companies and how their people sign in](sign-in.md) gives the details.

**Companies** lists every company on the instance:

| Column | What it shows |
|---|---|
| **Name** | The company, which opens it |
| **Key** | The key the deployment declares it by, or **Not declared** for a company that has no key |
| **Your role** | Your role in it, or **Not a member** |
| **Created** | When it was created |

!!! note "Enterprise"

    Several companies in one instance need an Enterprise licence. Without it, the page shows an **Enterprise** badge. A declared company beyond the ones the licence allows is not created: the server logs a warning, and creates it once a key that holds it is entered. A company that exists keeps working whatever the licence.

### Open a company you are not a member of

Choose its name. You get an Admin's view of the company, with one addition: you may appoint an Owner, for a company that has no Owner left to reach. You cannot:

- read people's personal conversations;
- demote, suspend or remove an Owner;
- change retention, set legal holds or erase content, which are the Owner's.

Every request you make there is recorded in the company's audit log as `admin.company.accessed`, with what it needed, and its Owners and Admins can see it. Prefer having the company's own Owner act, and open a company only when they cannot.

## Instance audit log

**Instance audit log** shows the events outside any company, newest first: sign-ins and sign-outs, users created and changed, passwords set, licence keys applied, error reporting, and the vault's keys. Each event is chained to the one before it, as a company's are: [The audit log](../admin-guide/audit-log.md#the-hash-chain).

!!! note "Pro and Enterprise"

    Every event is recorded and chained in every edition. Community shows the last 90 days; **Verify chain** needs a Pro or Enterprise licence.

**Verify chain** recomputes every hash of the instance's chain and says **Chain intact**, or **Chain broken** with the first event that does not match.

## Errors

**Errors** lists the errors of this installation's server and web app, scrubbed when they happened: what you see there is what would be sent, and nothing more. [Error reports](error-reports.md) explains the page and whether reports leave the instance.

## Licence

**Licence** is where you enter the instance's licence key, see the edition and the seats, and hold a renewal for later. One key covers every company. [Enter and renew a licence key](licence.md) gives the steps.
