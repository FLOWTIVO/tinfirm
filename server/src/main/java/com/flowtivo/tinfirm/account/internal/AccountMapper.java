package com.flowtivo.tinfirm.account.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.shared.api.model.Account;
import com.flowtivo.tinfirm.shared.api.model.AccountGrant;
import com.flowtivo.tinfirm.shared.api.model.GrantChoice;
import com.flowtivo.tinfirm.shared.api.model.MyAccount;
import com.flowtivo.tinfirm.shared.api.model.SystemRef;
import com.flowtivo.tinfirm.shared.mapping.ApiMapperConfig;
import com.flowtivo.tinfirm.system.SystemKind;

/** The DTOs of the Accounts and Grants APIs (the package 1 contract, §6). Never a credential. */
@Mapper(config = ApiMapperConfig.class)
interface AccountMapper {

    /** An account as its manager sees it: its login there, never its credential. */
    record AccountDetails(UUID id, UUID systemId, AccountInfo.Kind kind, AccountInfo.Status status,
            @Nullable String login, @Nullable String lastError, @Nullable String scopes, Instant connectedAt,
            @Nullable Instant lastUsedAt) {

        static AccountDetails of(AccountStore.AccountRow account) {
            return new AccountDetails(account.id(), account.systemId(), account.kind(), account.status(),
                    account.externalLogin(), account.lastError(), account.scopes(), account.connectedAt(),
                    account.lastUsedAt());
        }
    }

    record SystemRefDetails(UUID id, String name, String handle, SystemKind kind, boolean personal) {
    }

    record GrantChoiceDetails(UUID grantId, UUID credentialId, String credentialName, UUID groupId, String groupName,
            GrantInfo.Access access) {
    }

    /** One system of the caller's accounts page. */
    record MyAccountDetails(SystemRefDetails system, @Nullable AccountDetails account,
            List<GrantChoiceDetails> grants, String binding, boolean oauthAvailable) {
    }

    record GrantDetails(UUID id, UUID credentialId, UUID systemId, String systemName, String credentialName, UUID groupId,
            String groupName, GrantInfo.Access access, Instant createdAt, Instant updatedAt) {
    }

    /** The account; its {@code binding} only in the answer of a token stored as one's own. */
    @Mapping(target = "binding", ignore = true)
    Account toDto(AccountDetails account);

    SystemRef toDto(SystemRefDetails system);

    GrantChoice toDto(GrantChoiceDetails grant);

    MyAccount toDto(MyAccountDetails account);

    AccountGrant toDto(GrantDetails grant);
}
