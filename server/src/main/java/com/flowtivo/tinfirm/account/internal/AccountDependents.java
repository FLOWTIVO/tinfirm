package com.flowtivo.tinfirm.account.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.company.GroupDependents;
import com.flowtivo.tinfirm.principal.PrincipalDependents;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.SystemAccounts;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.vault.SecretWriter;

/**
 * What goes with accounts when something they hang from goes, each in that change's transaction and audited with its
 * cause: a removed system's accounts and credentials ({@code system.deleted}); what a deleted service account borrows,
 * which goes back to its systems ({@code principal.deleted}); a leaving member's accounts and every secret they own in
 * the company ({@code principal.left}, I8); the grants to a deleted group ({@code group.deleted}). The OAuth grants of a
 * leaving member's accounts are revoked at their systems after the commit ({@link AccountRevocations}). Also the
 * credential for testing a system or reading its catalogue, the caller's own or one of the system's (ADR-0072), outside
 * any transaction.
 */
@Component
class AccountDependents implements SystemAccounts, PrincipalDependents, GroupDependents {

    private final AccountStore store;

    private final SignInStore signIns;

    private final AccountService accounts;

    private final AccountCredentials credentials;

    private final Credentials credentialService;

    private final Grants grants;

    private final Principals principals;

    private final SecretWriter secrets;

    private final TransactionTemplate transactions;

    private final ChatSystems chatSystems;

    AccountDependents(AccountStore store, SignInStore signIns, AccountService accounts,
            AccountCredentials credentials, Credentials credentialService, Grants grants, Principals principals,
            SecretWriter secrets, PlatformTransactionManager transactionManager, ChatSystems chatSystems) {
        this.store = store;
        this.signIns = signIns;
        this.accounts = accounts;
        this.credentials = credentials;
        this.credentialService = credentialService;
        this.grants = grants;
        this.principals = principals;
        this.secrets = secrets;
        this.transactions = new TransactionTemplate(transactionManager);
        this.chatSystems = chatSystems;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void systemDeleting(UUID companyId, UUID systemId) {
        removeAllOn(companyId, systemId, "system.deleted");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int systemMoving(UUID companyId, UUID systemId) {
        return removeAllOn(companyId, systemId, "system.moved");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OAuthOnSystem oauthOn(UUID companyId, UUID systemId) {
        int accounts = 0;
        List<NamedCredential> named = new ArrayList<>();
        for (AccountStore.AccountRow account : store.lockOnSystem(companyId, systemId)) {
            if (account.kind() != AccountInfo.Kind.OAUTH) {
                continue;
            }
            if (account.credential()) {
                named.add(new NamedCredential(account.id(), account.name()));
            }
            else {
                accounts++;
            }
        }
        return new OAuthOnSystem(accounts, named);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int appEndpointsChanging(UUID companyId, UUID systemId) {
        List<AccountStore.AccountRow> oauth = store.lockOnSystem(companyId, systemId)
            .stream()
            .filter(account -> account.kind() == AccountInfo.Kind.OAUTH)
            .toList();
        for (AccountStore.AccountRow account : oauth) {
            // Revoked nowhere: the old endpoints are the ones in question, and the new ones never issued the grant.
            accounts.remove(account, accounts.memberOf(account), "system.app_endpoints_changed", false);
        }
        signIns.deleteOnSystem(companyId, systemId);
        return oauth.size();
    }

    /** Every account and credential on the system, and its pending sign-ins; how many accounts went. */
    private int removeAllOn(UUID companyId, UUID systemId, String cause) {
        List<AccountStore.AccountRow> on = store.lockOnSystem(companyId, systemId);
        for (AccountStore.AccountRow account : on) {
            // The app goes with the system, or with its old address: nothing to revoke the grants with.
            accounts.remove(account, accounts.memberOf(account), cause, false);
        }
        signIns.deleteOnSystem(companyId, systemId);
        return on.size();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> holderOf(UUID companyId, UUID accountId) {
        return store.find(companyId, accountId).flatMap(account -> account.principalId() != null
                ? Optional.of(account.principalId())
                : store.lendingsOf(companyId, accountId).stream()
                    .map(AccountStore.LendingRow::servicePrincipalId)
                    .findFirst());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> credentialName(UUID companyId, UUID systemId, UUID credentialId) {
        return store.find(companyId, credentialId)
            .filter(account -> account.credential() && account.systemId().equals(systemId))
            .map(AccountStore.AccountRow::name);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> onlyUsableCredential(UUID companyId, UUID systemId) {
        List<AccountStore.AccountRow> usable = store.credentialsOn(companyId, systemId).stream()
            .filter(account -> account.enabled() && account.status() == AccountInfo.Status.OK)
            .filter(account -> chatSystems.botOf(companyId, account.id()).isEmpty())
            .toList();
        return usable.size() == 1 ? Optional.of(usable.getFirst().id()) : Optional.empty();
    }

    @Override
    public Optional<OwnCredential> revealCredential(UUID companyId, UUID credentialId, String purpose) {
        NoTransaction.require("Revealing a credential");
        boolean usable = Boolean.TRUE.equals(transactions.execute(status -> store.find(companyId, credentialId)
            .filter(AccountStore.AccountRow::credential)
            .isPresent()));
        if (!usable) {
            return Optional.empty();
        }
        // A credential is the company's: revealed for the admin's call, for nobody in particular.
        return credentials.reveal(companyId, credentialId, null, purpose)
            .map(revealed -> new OwnCredential(credentialId, revealed.credential(), revealed.apiBaseUrl(),
                    revealed.endpoint()));
    }

    /** The member's own {@code OK} account on a system, as a short transaction found it. */
    private record Own(UUID accountId, UUID memberPrincipalId) {
    }

    @Override
    public Optional<OwnCredential> ownCredential(UUID companyId, UUID systemId, UUID userId, String purpose) {
        NoTransaction.require("Revealing an account's credential");
        Own own = transactions.execute(status -> principals.ofMember(companyId, userId)
            .flatMap(member -> store.of(companyId, systemId, member.id(), false)
                .filter(account -> account.status() == AccountInfo.Status.OK)
                .map(account -> new Own(account.id(), member.id())))
            .orElse(null));
        if (own == null) {
            return Optional.empty();
        }
        return credentials.reveal(companyId, own.accountId(), own.memberPrincipalId(), purpose)
            .map(revealed -> new OwnCredential(own.accountId(), revealed.credential(), revealed.apiBaseUrl(),
                    revealed.endpoint()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void principalDeleting(UUID companyId, UUID principalId) {
        // C9 (the package 5 contract, §3.2): the bot never goes without system.bot_removed.
        List<AccountStore.LendingRow> borrowed = store.lendingsTo(companyId, principalId);
        for (AccountStore.LendingRow lending : borrowed) {
            if (chatSystems.botOf(companyId, lending.accountId()).isPresent()) {
                throw ApiException.conflict(ProblemCode.SERVICE_IDENTITY_IN_USE, "The service account borrows the bot "
                        + "of the chat system " + lending.systemId() + ": remove the bot on the system's page first");
            }
        }
        // What it borrows goes back to its systems (ADR-0072): the credentials stay.
        for (AccountStore.LendingRow lending : borrowed) {
            store.lending(companyId, principalId, lending.systemId(), true)
                .ifPresent(locked -> credentialService.withdrawn(locked, "principal.deleted"));
        }
        removeAll(companyId, principalId, "principal.deleted");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberLeaving(UUID companyId, UUID principalId, UUID userId) {
        removeAll(companyId, principalId, "principal.left");
        // I8: every secret the member owns in the company goes with them, whatever made it.
        secrets.deleteAllOwned(companyId, userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void groupDeleting(UUID companyId, UUID groupId) {
        grants.removeAllTo(companyId, groupId);
    }

    private void removeAll(UUID companyId, UUID principalId, String cause) {
        PrincipalInfo member = principals.find(companyId, principalId).filter(PrincipalInfo::member).orElse(null);
        for (AccountStore.AccountRow account : store.lockOfPrincipal(companyId, principalId)) {
            accounts.removeAndRevokeAfterCommit(account, member, cause);
        }
        signIns.deleteOfPrincipal(companyId, principalId);
    }
}
