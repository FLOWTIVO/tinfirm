package com.flowtivo.tinfirm.conversation.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.api.model.AgentRepositoryAccess;
import com.flowtivo.tinfirm.shared.api.model.ConversationBindingChoice;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * How a conversation's binding reads (the package 3 contract, §5.3's table): the header ("GitHub: your own account",
 * "GitHub: via Analysts, read-only"), the card of a waiting binding, the note of a gone one, and the prompt's
 * {@code # Accounts} line. Reads Tinfirm's rows only, in the caller's transaction.
 */
@Component
class BindingTexts {

    private final Systems systems;

    private final Accounts accounts;

    private final GroupMembership groups;

    private final Principals principals;

    BindingTexts(Systems systems, Accounts accounts, GroupMembership groups, Principals principals) {
        this.systems = systems;
        this.accounts = accounts;
        this.groups = groups;
        this.principals = principals;
    }

    /** What a grant shows: its group's name, its holder's name and its access now. */
    record GrantView(UUID grantId, UUID groupId, @Nullable String groupName, @Nullable String credentialName,
            GrantInfo.Access access) {
    }

    String systemName(UUID companyId, UUID systemId) {
        return systems.find(companyId, systemId).map(SystemInfo::name).orElse("A removed system");
    }

    /**
     * The grants a waiting binding offers, as its card shows them: each with its group, credential and access now; a
     * grant that is gone meanwhile is left out.
     */
    List<ConversationBindingChoice> choices(UUID companyId, List<UUID> grantIds) {
        List<ConversationBindingChoice> choices = new ArrayList<>();
        for (UUID grantId : grantIds) {
            grant(companyId, grantId).ifPresent(grant -> choices.add(new ConversationBindingChoice()
                .grantId(grant.grantId())
                .groupId(grant.groupId())
                .groupName(grant.groupName())
                .credentialName(grant.credentialName())
                .access(AgentRepositoryAccess.fromValue(grant.access().name()))));
        }
        return choices;
    }

    Optional<GrantView> grant(UUID companyId, @Nullable UUID grantId) {
        if (grantId == null) {
            return Optional.empty();
        }
        return accounts.grant(companyId, grantId).map(grant -> {
            Map<UUID, String> names = groups.groupNames(companyId, Set.of(grant.groupId()));
            String credential = accounts.find(companyId, grant.accountId()).map(AccountInfo::name).orElse(null);
            return new GrantView(grant.id(), grant.groupId(), names.get(grant.groupId()), credential,
                    grant.access());
        });
    }

    /** The name of whom the binding acts through: a member, or a service account. */
    String holderName(BindingInfo binding) {
        return binding.holderPrincipalId() == null ? "a shared credential"
                : principals.find(binding.companyId(), binding.holderPrincipalId()).map(p -> p.name())
                    .orElse("a former member");
    }

    /** ", read-only" for a trigger's service account's credential lent {@code READ} (ADR-0072); else nothing. */
    private String readOnly(BindingInfo binding) {
        return binding.holderPrincipalId() != null && binding.accountId() != null
                && accounts.fixedAccess(binding.companyId(), binding.systemId(), binding.holderPrincipalId(),
                        binding.accountId()) == GrantInfo.Access.READ ? ", read-only" : "";
    }

    /** What an admin does to the credential a waiting binding names: enable it, store its token or sign it in again. */
    private String reconnectCompany(BindingInfo binding) {
        AccountInfo credential = binding.accountId() != null
                ? accounts.find(binding.companyId(), binding.accountId()).filter(AccountInfo::credential).orElse(null)
                : null;
        if (credential == null) {
            return "an admin must sign the shared credential in again, or enable it";
        }
        if (!credential.enabled()) {
            return "an admin must enable the shared credential " + credential.name();
        }
        return switch (credential.kind()) {
            case OAUTH -> "an admin must sign the shared credential " + credential.name() + " in again";
            case CLIENT -> "an admin must store new client credentials for the shared credential " + credential.name();
            default -> "an admin must store a new token for the shared credential " + credential.name();
        };
    }

    /** A trigger's binding: "Ana's account", or a service account's credential, "svc-dev's CI token". */
    private String fixedName(BindingInfo binding) {
        String holder = holderName(binding);
        String credential = binding.accountId() != null
                ? accounts.find(binding.companyId(), binding.accountId()).map(AccountInfo::name).orElse(null) : null;
        return credential != null ? holder + "'s " + credential : holder + "'s account";
    }

    /** The prompt's {@code # Accounts} line of an MCP server that needs no account (ADR-0071). */
    static final String NO_ACCOUNT = "no account needed: calls run without a credential";

    /** The header of a bound binding: "GitHub: your own account", "GitHub: via Analysts, read-only". */
    String bound(BindingInfo binding, GrantInfo.@Nullable Access access) {
        String system = systemName(binding.companyId(), binding.systemId());
        if (binding.open()) {
            return system + ": no account needed";
        }
        if (binding.how() == BindingInfo.How.OWN) {
            return system + ": your own account";
        }
        if (binding.how() == BindingInfo.How.FIXED) {
            return system + ": " + fixedName(binding) + ", chosen by the trigger" + readOnly(binding);
        }
        Optional<GrantView> grant = grant(binding.companyId(), binding.grantId());
        String group = grant.map(GrantView::groupName).orElse("a group's grant");
        GrantInfo.Access now = access != null ? access : grant.map(GrantView::access).orElse(GrantInfo.Access.READ);
        return system + ": via " + group + (now == GrantInfo.Access.READ ? ", read-only" : "");
    }

    /**
     * The note of a trigger's binding that followed its service account to the credential it borrows now (ADR-0072):
     * "Jira: svc-dev's Second now, in place of First: an admin lent it to svc-dev".
     *
     * @param replaced the name of the credential it acted with until now; null when that one is gone
     */
    String followed(BindingInfo binding, @Nullable String replaced) {
        return systemName(binding.companyId(), binding.systemId()) + ": " + fixedName(binding) + " now"
                + readOnly(binding) + (replaced != null ? ", in place of " + replaced : "") + ": an admin lent it to "
                + holderName(binding);
    }

    /** The binding in words, whatever its state. */
    String text(BindingInfo binding) {
        return switch (binding.state()) {
            case BOUND -> bound(binding, null);
            case WAITING -> card(binding);
            case GONE -> gone(binding);
        };
    }

    /** The card of a waiting binding: what the person (or an admin) does. */
    String card(BindingInfo binding) {
        String system = systemName(binding.companyId(), binding.systemId());
        BindingInfo.Reason reason = binding.reason() != null ? binding.reason() : BindingInfo.Reason.NONE;
        return switch (reason) {
            case NONE -> binding.open()
                    ? system + " needs accounts now: connect your account to go on."
                    : system + " is not connected for this conversation yet: connect your account to go on.";
            case RECONNECT_YOURS -> system + ": your account needs signing in again.";
            case RECONNECT_COMPANY -> system + ": " + reconnectCompany(binding) + "; the admins are told.";
            case CHOOSE -> system + ": your own account needs signing in again, or use a shared account instead.";
            case PICK_GRANT -> system + ": pick one of the shared accounts for this conversation, or connect your own.";
            case SUSPENDED -> system + ": the service account it acts through is suspended; an admin resumes it.";
            case SYSTEM_OFF -> system + ": the company switched personal MCP servers off.";
            case NOT_ENTITLED -> system + ": the trigger's author may no longer use " + holderName(binding)
                    + ", or it no longer borrows the credential; it goes on once they may again, or the trigger is "
                    + "changed.";
            default -> gone(binding);
        };
    }

    /** The note of a gone binding. */
    String gone(BindingInfo binding) {
        String system = systemName(binding.companyId(), binding.systemId());
        String why = switch (binding.reason() != null ? binding.reason() : BindingInfo.Reason.SYSTEM_REMOVED) {
            case GRANT_REMOVED -> "the shared credential's grant was withdrawn";
            case LEFT_GROUP -> "you are no longer in the group the credential is shared with";
            case IDENTITY_CHANGED -> "the account is another identity on " + system + " now";
            case HOLDER_LEFT -> "the account's holder is gone";
            case SYSTEM_REMOVED -> "the system was removed";
            case ACCOUNT_REMOVED -> binding.open() ? "it needs accounts now, and the trigger names none there"
                    : "the trigger's new author's choices name no account there";
            default -> "it can no longer be used";
        };
        return system + " can no longer be used in this conversation: " + why + ". Start a new conversation to use "
                + system + ".";
    }

    /**
     * The prompt's {@code # Accounts} line of a system: "the person's own account", "{credential} via {group},
     * read-only", or "not connected yet: your first call asks the person".
     */
    String forPrompt(@Nullable BindingInfo binding, String personName) {
        if (binding == null) {
            return "not connected yet: your first call asks " + personName + " to connect it";
        }
        return switch (binding.state()) {
            case BOUND -> {
                if (binding.open()) {
                    yield NO_ACCOUNT;
                }
                if (binding.how() == BindingInfo.How.OWN) {
                    yield personName + "'s own account";
                }
                if (binding.how() == BindingInfo.How.FIXED) {
                    yield fixedName(binding) + ", chosen by the trigger" + readOnly(binding);
                }
                Optional<GrantView> grant = grant(binding.companyId(), binding.grantId());
                yield grant.map(view -> (view.credentialName() != null ? view.credentialName() : "a shared credential")
                        + " via "
                        + (view.groupName() != null ? view.groupName() : "a group")
                        + (view.access() == GrantInfo.Access.READ ? ", read-only" : ""))
                    .orElse("a shared credential");
            }
            case WAITING -> "not connected yet: your first call asks " + personName + " to connect it";
            case GONE -> "not available in this conversation";
        };
    }
}
