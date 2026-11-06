package com.flowtivo.tinfirm.mcp.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.identity.CompanyOwners;

/**
 * The notice an AI client's person is given (security review, row 18; the package 6 contract, §5.6, G10): what they
 * start through the client is personal, paid by them and acting with their accounts, and the company's OWNERs, by
 * name, can read it in full, each read recorded and shown to them. The consent page shows it before the client gets a
 * token, a personal token's creation on {@code /me} before the token is used; here it stands in every answer of
 * {@code start_conversation}, and the {@code initialize} instructions say it without the names.
 */
@Component
class OwnerNotices {

    /** The notice of the {@code initialize} instructions, which name no company. */
    static final String GENERAL = "Conversations you start through this AI client are personal: agents act with "
            + "your own accounts and budget. The company's Owners can read them in full; each read is recorded and "
            + "shown to you.";

    private final CompanyNames names;

    private final CompanyOwners owners;

    OwnerNotices(CompanyNames names, CompanyOwners owners) {
        this.names = names;
        this.owners = owners;
    }

    /** The notice for a company, with its name and its OWNERs' names. */
    String of(UUID companyId) {
        return "Notice: " + body(companyId, "this AI client");
    }

    /**
     * The notice for a company and a client of the person's ({@code the Gmail add-on}, ADR-0083), with the company's
     * name and its OWNERs' names, without the leading "Notice:".
     */
    String of(UUID companyId, String client) {
        return "C" + body(companyId, client).substring(1);
    }

    private String body(UUID companyId, String client) {
        String company = names.nameOf(companyId).orElse("this company");
        List<String> ownerNames = owners.ownersOf(companyId);
        return "conversations you start through " + client + " are personal: agents act with your own "
                + "accounts and budget. The Owners of " + company + (ownerNames.isEmpty() ? "" : " ("
                        + String.join(", ", ownerNames) + ")")
                + " can read them in full; each read is recorded and shown to you.";
    }
}
