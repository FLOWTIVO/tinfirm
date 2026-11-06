package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.identity.McpChallenges;
import com.flowtivo.tinfirm.shared.security.ApiScope;

/** {@link McpChallenges} through the same {@link McpChallenge} as the bearer chain's 401. */
@Component
class McpChallengesImpl implements McpChallenges {

    private final McpChallenge challenge;

    McpChallengesImpl(OAuthResources resources) {
        this.challenge = new McpChallenge(resources);
    }

    @Override
    public String insufficientScope(UUID companyId, ApiScope required) {
        return challenge.insufficientScope(companyId, required);
    }
}
