package com.flowtivo.tinfirm.system.internal;

import java.security.PrivateKey;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.api.model.GitHubApp;
import com.flowtivo.tinfirm.shared.api.model.GitHubAppInstallation;
import com.flowtivo.tinfirm.shared.api.model.GitHubAppInstallationList;
import com.flowtivo.tinfirm.shared.api.model.GitHubAppPermissionsInner;
import com.flowtivo.tinfirm.shared.api.model.GitHubAppRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.GitHubApps;
import com.flowtivo.tinfirm.system.SystemAccounts;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.internal.github.GitHubAppClient;
import com.flowtivo.tinfirm.vault.RevealedSecret;
import com.flowtivo.tinfirm.vault.SecretWriter;
import com.flowtivo.tinfirm.vault.VaultService;

/**
 * A {@code GITHUB} system's GitHub App (ADR-0116, decision 6; the owner's answers 2 and 3): set, read and removed by
 * whoever manages company systems ({@code MANAGE_SYSTEMS}), its installations listed for them, and {@link GitHubApps}
 * for the account module. The private key is a managed company secret, {@value #KEY_SECRET_NAME}, revealed only to
 * sign one JWT, audited as {@code secret.accessed} with the purpose, and never shown again. Calls to GitHub run
 * outside any transaction, between the one that authorises the caller and the one that stores what GitHub answered.
 */
@Service
class GitHubAppService implements GitHubApps {

    /** The managed secret of an App's private key; the vault suffixes a second one. */
    static final String KEY_SECRET_NAME = "SYSTEM_GITHUB_APP_KEY";

    static final String TARGET = SystemService.TARGET;

    /** The longest PEM taken. */
    static final int MAX_KEY_LENGTH = 10_000;

    private final SystemAccess access;

    private final SystemStore systems;

    private final GitHubAppStore store;

    private final SystemClients clients;

    private final SecretWriter secrets;

    private final VaultService vault;

    /** The account module's port, looked up when used: that module depends on this one's {@link GitHubApps}. */
    private final org.springframework.beans.factory.ObjectProvider<SystemAccounts> accounts;

    private final RunnerDirectory directory;

    private final RunnerConnections connections;

    private final Principals principals;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final TransactionTemplate transactions;

    GitHubAppService(SystemAccess access, SystemStore systems, GitHubAppStore store, SystemClients clients,
            SecretWriter secrets, VaultService vault,
            org.springframework.beans.factory.ObjectProvider<SystemAccounts> accounts, RunnerDirectory directory,
            RunnerConnections connections, Principals principals, AuditLog auditLog, TimeSource time,
            PlatformTransactionManager transactionManager) {
        this.access = access;
        this.systems = systems;
        this.store = store;
        this.clients = clients;
        this.secrets = secrets;
        this.vault = vault;
        this.accounts = accounts;
        this.directory = directory;
        this.connections = connections;
        this.principals = principals;
        this.auditLog = auditLog;
        this.time = time;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ---- The API ---------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public GitHubApp get(UUID companyId, UUID systemId) {
        SystemAccess.Caller caller = access.caller(companyId);
        SystemStore.SystemRow system = access.visible(caller, systemId);
        access.requireAdmin(caller);
        return view(store.find(companyId, system.id()).orElseThrow(GitHubAppService::noApp));
    }

    /**
     * Sets the App, or replaces its key: the key is read, a JWT signed with it, and GitHub asked for the App
     * ({@code GET /app}) before anything is stored.
     */
    public GitHubApp set(UUID companyId, UUID systemId, GitHubAppRequest request) {
        SystemStore.SystemRow system = inTransaction(() -> appSystem(access.caller(companyId), systemId));
        long appId = request.getAppId() != null ? request.getAppId() : 0;
        if (appId <= 0) {
            throw ApiException.invalid("appId must be the App's id, a positive number", "appId");
        }
        String pem = request.getPrivateKey() != null ? request.getPrivateKey().strip() : "";
        if (pem.isEmpty() || pem.length() > MAX_KEY_LENGTH) {
            throw ApiException.invalid("privateKey must be the App's PEM private key, 1 to " + MAX_KEY_LENGTH
                    + " characters", "privateKey");
        }
        PrivateKey key = switch (GitHubAppClient.parse(pem)) {
            case GitHubAppClient.Key found -> found.key();
            case GitHubAppClient.BadKey bad -> throw ApiException.invalid(bad.reason(), "privateKey");
        };
        GitHubAppClient client = new GitHubAppClient(clients.http(system), Objects.requireNonNull(system.baseUrl()));
        CallResult<GitHubAppClient.AppDetails> answered = client.app(GitHubAppClient.jwt(appId, key, time.now()));
        if (answered.failure() != null) {
            if (answered.failure().kind() == CallResult.Kind.UNAVAILABLE
                    || answered.failure().kind() == CallResult.Kind.RATE_LIMITED) {
                throw unreachable(answered.failureMessage());
            }
            throw ApiException.badRequest(ProblemCode.GITHUB_APP_REFUSED, "GitHub refused the App's id and key: "
                    + answered.failureMessage());
        }
        GitHubAppClient.AppDetails details = Objects.requireNonNull(answered.value());
        if (details.id() != appId) {
            throw ApiException.badRequest(ProblemCode.GITHUB_APP_REFUSED, "The private key is the key of the App "
                    + details.id() + ", not of " + appId);
        }
        return inTransaction(() -> {
            SystemAccess.Caller caller = access.caller(companyId);
            SystemStore.SystemRow current = appSystem(caller, systemId);
            Optional<GitHubAppStore.AppRow> before = store.lock(companyId, systemId);
            if (before.isPresent() && before.get().appId() != appId) {
                // An installation's credential stands for an installation of the App an admin verified: another App's
                // key would mint for it, or fail. Its key alone may be replaced (a rotation).
                List<SystemAccounts.AppCredential> left = accounts.getObject().appCredentials(companyId, systemId);
                if (!left.isEmpty()) {
                    throw inUse(left, "of the App " + before.get().appId() + ": remove " + (left.size() == 1 ? "it"
                            : "them") + " before you set another App, or set the App " + before.get().appId()
                            + "'s key again.");
                }
            }
            UUID secretId = before.map(GitHubAppStore.AppRow::privateKeySecretId).orElse(null);
            if (secretId == null || !secrets.replace(companyId, secretId, pem)) {
                secretId = secrets.create(companyId, KEY_SECRET_NAME, "The private key of the GitHub App of the "
                        + "system " + current.handle(), pem);
            }
            GitHubAppStore.AppRow row = new GitHubAppStore.AppRow(systemId, companyId, appId,
                    cut(details.slug(), 100), cut(details.name(), 200), secretId, time.now(), caller.userId());
            store.upsert(row);
            auditLog.record(AuditRecord.company(companyId, AuditAction.SYSTEM_GITHUB_APP_SET, CurrentActor.actor())
                .target(TARGET, systemId)
                .detail("appId", appId)
                .detail("slug", row.slug())
                .detail("keyReplaced", before.isPresent())
                .detail("previousAppId", before.map(GitHubAppStore.AppRow::appId).orElse(null))
                .build());
            return view(row);
        });
    }

    /** Removes the App and deletes its key; 409 while credentials of kind {@code APP} remain. */
    @Transactional
    public void remove(UUID companyId, UUID systemId) {
        SystemAccess.Caller caller = access.caller(companyId);
        SystemStore.SystemRow system = access.managed(caller, systemId);
        access.requireAdmin(caller);
        GitHubAppStore.AppRow app = store.lock(companyId, system.id()).orElseThrow(GitHubAppService::noApp);
        List<SystemAccounts.AppCredential> left = accounts.getObject().appCredentials(companyId, systemId);
        if (!left.isEmpty()) {
            throw inUse(left, "of the App: remove " + (left.size() == 1 ? "it" : "them") + " first.");
        }
        deleteApp(app);
        auditLog.record(AuditRecord.company(companyId, AuditAction.SYSTEM_GITHUB_APP_REMOVED, CurrentActor.actor())
            .target(TARGET, systemId)
            .detail("appId", app.appId())
            .build());
    }

    /** The App's installations, each with the system's credential that is it. */
    public GitHubAppInstallationList installations(UUID companyId, UUID systemId) {
        record Read(SystemStore.SystemRow system, PrivateKey key, long appId,
                Map<Long, SystemAccounts.AppCredential> credentials) {
        }
        Read read = inTransaction(() -> {
            SystemAccess.Caller caller = access.caller(companyId);
            SystemStore.SystemRow system = access.visible(caller, systemId);
            access.requireAdmin(caller);
            GitHubAppStore.AppRow app = store.find(companyId, system.id()).orElseThrow(GitHubAppService::noApp);
            Map<Long, SystemAccounts.AppCredential> byInstallation = new HashMap<>();
            accounts.getObject().appCredentials(companyId, systemId)
                .forEach(credential -> byInstallation.put(credential.installationId(), credential));
            return new Read(system, key(app, "github-app:installations"), app.appId(), byInstallation);
        });
        GitHubAppClient client = new GitHubAppClient(clients.http(read.system()),
                Objects.requireNonNull(read.system().baseUrl()));
        CallResult<List<GitHubApps.Installation>> listed = client.installations(GitHubAppClient.jwt(read.appId(),
                read.key(), time.now()));
        if (listed.failure() != null) {
            throw unreachable(listed.failureMessage());
        }
        List<GitHubAppInstallation> items = new ArrayList<>();
        for (GitHubApps.Installation installation : Objects.requireNonNull(listed.value())) {
            SystemAccounts.AppCredential credential = read.credentials().get(installation.id());
            items.add(new GitHubAppInstallation(installation.id(), installation.accountLogin(),
                    installation.accountType(), installation.repositorySelection())
                .credentialId(credential != null ? credential.id() : null)
                .credentialName(credential != null ? credential.name() : null));
        }
        return new GitHubAppInstallationList(items);
    }

    /** The system's App is removed with it: its key goes first, in the removal's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    void systemDeleting(UUID companyId, UUID systemId) {
        store.lock(companyId, systemId).ifPresent(this::deleteApp);
    }

    private void deleteApp(GitHubAppStore.AppRow app) {
        store.delete(app.companyId(), app.systemId());
        secrets.delete(app.companyId(), app.privateKeySecretId());
    }

    /** A company {@code GITHUB} system the caller manages, locked, not paused. */
    private SystemStore.SystemRow appSystem(SystemAccess.Caller caller, UUID systemId) {
        SystemStore.SystemRow system = access.managed(caller, systemId);
        access.requireAdmin(caller);
        if (system.kind() != SystemKind.GITHUB || system.personal() || system.baseUrl() == null) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, system.name() + " is a "
                    + system.kind().label() + " system: only a company GitHub system has a GitHub App");
        }
        if (system.paused()) {
            throw ApiException.conflict(ProblemCode.SYSTEM_PAUSED, system.name() + " is paused by an admin");
        }
        return system;
    }

    private GitHubApp view(GitHubAppStore.AppRow app) {
        int connected = 0;
        int without = 0;
        for (RunnerHostInfo host : directory.hosts(app.companyId())) {
            if (host.scope() != HostScope.COMPANY || !connections.isConnected(host.id())) {
                continue;
            }
            connected++;
            if (!connections.supports(host.id(), RunnerMessages.FEATURE_RUN_GIT_REFRESH)
                    || !connections.supports(host.id(), RunnerMessages.FEATURE_RUN_GIT_REVOKE)) {
                without++;
            }
        }
        String setBy = app.setBy() != null ? principals.ofMember(app.companyId(), app.setBy())
            .map(PrincipalInfo::name)
            .orElse(null) : null;
        return new GitHubApp(app.systemId(), app.appId(), app.setAt().atOffset(ZoneOffset.UTC), connected, without,
                PERMISSIONS)
            .slug(app.slug())
            .name(app.name())
            .setByName(setBy);
    }

    /** What the App's page says to give it at GitHub, and never to give. */
    static final List<GitHubAppPermissionsInner> PERMISSIONS = List.of(
            permission("Contents", GitHubAppPermissionsInner.AccessEnum.WRITE),
            permission("Pull requests", GitHubAppPermissionsInner.AccessEnum.WRITE),
            permission("Metadata", GitHubAppPermissionsInner.AccessEnum.READ),
            permission("Issues", GitHubAppPermissionsInner.AccessEnum.WRITE),
            permission("Workflows", GitHubAppPermissionsInner.AccessEnum.NEVER));

    private static GitHubAppPermissionsInner permission(String name, GitHubAppPermissionsInner.AccessEnum access) {
        return new GitHubAppPermissionsInner(name, access);
    }

    /** Whether the system holds an App, in the caller's transaction. */
    boolean has(UUID companyId, UUID systemId) {
        return store.find(companyId, systemId).isPresent();
    }

    // ---- GitHubApps, for the account module ------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<App> app(UUID companyId, UUID systemId) {
        return store.find(companyId, systemId)
            .map(GitHubAppService::app);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<App> lockApp(UUID companyId, UUID systemId) {
        return store.lockShared(companyId, systemId).map(GitHubAppService::app);
    }

    private static App app(GitHubAppStore.AppRow row) {
        return new App(row.systemId(), row.appId(), row.slug(), row.name(), row.setAt(), row.privateKeySecretId());
    }

    @Override
    public CallResult<Installation> installation(SystemInfo system, long installationId) {
        Signed signed = signed(system, "github-app:installation");
        if (signed.failure() != null) {
            return CallResult.failed(signed.failure());
        }
        return signed.client().installation(signed.jwt(), installationId);
    }

    @Override
    public CallResult<Installation> installation(SystemInfo system, App expected, long installationId) {
        Signed signed = signed(system, "github-app:installation", expected);
        if (signed.failure() != null) {
            return CallResult.failed(signed.failure());
        }
        return signed.client().installation(signed.jwt(), installationId);
    }

    @Override
    public CallResult<InstallationToken> mint(SystemInfo system, long installationId,
            @Nullable List<String> repositories, Map<String, String> permissions, String purpose) {
        if (permissions.containsKey(WORKFLOWS)) {
            throw new IllegalArgumentException("No token Tinfirm mints carries " + WORKFLOWS);
        }
        Signed signed = signed(system, purpose);
        if (signed.failure() != null) {
            return CallResult.failed(signed.failure());
        }
        return signed.client().accessToken(signed.jwt(), installationId, repositories, permissions);
    }

    /** The App's client with a JWT signed now, or why there is none. */
    private record Signed(@Nullable GitHubAppClient client, @Nullable String jwt,
            CallResult.@Nullable Failure failure) {

        @Override
        public String toString() {
            return "Signed[jwt=***]";
        }
    }

    private Signed signed(SystemInfo system, String purpose) {
        return signed(system, purpose, null);
    }

    /** As {@link #signed(SystemInfo, String)}, with the App {@code expected} only, when it is given. */
    private Signed signed(SystemInfo system, String purpose, @Nullable App expected) {
        NoTransaction.require("A call of a GitHub App");
        record Found(SystemStore.@Nullable SystemRow system, @Nullable PrivateKey key, long appId) {
        }
        Found found = transactions.execute(status -> {
            SystemStore.SystemRow row = systems.find(system.companyId(), system.id()).orElse(null);
            if (row == null || row.kind() != SystemKind.GITHUB || row.baseUrl() == null) {
                return null;
            }
            GitHubAppStore.AppRow app = store.find(system.companyId(), system.id()).orElse(null);
            if (app != null && expected != null && !expected.sameApp(app(app))) {
                return new Found(null, null, app.appId());
            }
            return app != null ? new Found(row, key(app, purpose), app.appId()) : null;
        });
        if (found == null) {
            return new Signed(null, null, CallResult.Failure.of(CallResult.Kind.NOT_FOUND, system.name()
                    + " has no GitHub App"));
        }
        if (found.system() == null || found.key() == null) {
            return new Signed(null, null, CallResult.Failure.of(CallResult.Kind.OTHER, system.name()
                    + "'s GitHub App was replaced meanwhile"));
        }
        GitHubAppClient client = new GitHubAppClient(clients.http(found.system()),
                Objects.requireNonNull(found.system().baseUrl()));
        return new Signed(client, GitHubAppClient.jwt(found.appId(), found.key(), time.now()), null);
    }

    /** The App's key, revealed for one JWT, audited with the purpose; a key that cannot be read any more is a bug. */
    private PrivateKey key(GitHubAppStore.AppRow app, String purpose) {
        try (RevealedSecret secret = vault.reveal(app.companyId(), app.privateKeySecretId(), purpose)) {
            return switch (GitHubAppClient.parse(secret.asString())) {
                case GitHubAppClient.Key found -> found.key();
                case GitHubAppClient.BadKey bad -> throw new IllegalStateException("The stored key of the GitHub App "
                        + app.appId() + " is not usable: " + bad.reason());
            };
        }
    }

    /** {@code 409 GITHUB_APP_IN_USE}, with the credentials that are installations of the App. */
    private static ApiException inUse(List<SystemAccounts.AppCredential> left, String rest) {
        return ApiException.conflict(ProblemCode.GITHUB_APP_IN_USE, left.size() + (left.size() == 1
                ? " credential is" : " credentials are") + " an installation " + rest,
                Map.of("details", Map.of("credentials", left.stream().map(SystemAccounts.AppCredential::name)
                    .toList())));
    }

    private static ApiException noApp() {
        return ApiException.notFound("The system has no GitHub App");
    }

    private static ApiException unreachable(@Nullable String why) {
        return new ApiException(HttpStatus.BAD_GATEWAY, ProblemCode.GITHUB_UNREACHABLE, "Tinfirm could not ask "
                + "GitHub: " + why);
    }

    private static @Nullable String cut(@Nullable String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }
}
