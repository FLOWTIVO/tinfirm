package com.flowtivo.tinfirm.identity;

import java.util.UUID;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

/** One of a user's company memberships. */
public record UserMembership(UUID companyId, String companyName, CompanyRole role) {
}
