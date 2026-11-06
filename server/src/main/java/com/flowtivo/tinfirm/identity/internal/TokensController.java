package com.flowtivo.tinfirm.identity.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.identity.CompanyOwners;
import com.flowtivo.tinfirm.identity.MembershipDirectory;
import com.flowtivo.tinfirm.identity.UserMembership;
import com.flowtivo.tinfirm.shared.api.TokensApi;
import com.flowtivo.tinfirm.shared.api.model.CompanyOwnerNotice;
import com.flowtivo.tinfirm.shared.api.model.PersonalAccessToken;
import com.flowtivo.tinfirm.shared.api.model.PersonalAccessTokenCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.PersonalAccessTokenCreated;
import com.flowtivo.tinfirm.shared.api.model.TokenOwnerNotice;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.CurrentActor;

/** The logged-in user's personal access tokens (ADR-0045). Session-only, like every other REST endpoint. */
@RestController
class TokensController implements TokensApi {

    private final ApiTokenService tokens;

    private final ApiTokenMapper mapper;

    private final MembershipDirectory memberships;

    private final CompanyOwners owners;

    private final HttpServletRequest request;

    TokensController(ApiTokenService tokens, ApiTokenMapper mapper, MembershipDirectory memberships,
            CompanyOwners owners, HttpServletRequest request) {
        this.tokens = tokens;
        this.mapper = mapper;
        this.memberships = memberships;
        this.owners = owners;
        this.request = request;
    }

    @Override
    public ResponseEntity<List<PersonalAccessToken>> listOwnTokens() {
        return ResponseEntity.ok(tokens.listOwn().stream().map(mapper::toDto).toList());
    }

    @Override
    public ResponseEntity<PersonalAccessTokenCreated> createOwnToken(PersonalAccessTokenCreateRequest create) {
        ApiTokenService.Created created = tokens.create(new ApiTokenService.CreateRequest(create.getName(),
                ApiScope.valueOf(create.getScope().name()),
                create.getExpiresAt() != null ? create.getExpiresAt().toInstant() : null, create.getCompanyIds(),
                create.getCurrentPassword(), sessionCreatedAt()));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new PersonalAccessTokenCreated(mapper.toDto(created.token()), created.secret(),
                    ownerNotice(created.token().getCompanyIds())));
    }

    @Override
    public ResponseEntity<PersonalAccessToken> renewOwnToken(UUID tokenId) {
        return ResponseEntity.ok(mapper.toDto(tokens.renewOwn(tokenId)));
    }

    /** When the caller's session began: its sign-in, or the sign-in flow it came from. */
    private @Nullable Instant sessionCreatedAt() {
        HttpSession session = request.getSession(false);
        return session != null ? Instant.ofEpochMilli(session.getCreationTime()) : null;
    }

    /**
     * The notice of a token's creation (security review, row 18; the package 6 contract, §5.6): a personal token sees
     * no consent page, so its creation names the OWNERs of every company it reaches, who can read the conversations
     * started with it.
     */
    private TokenOwnerNotice ownerNotice(@Nullable List<UUID> companyIds) {
        List<UserMembership> mine = memberships.membershipsOf(CurrentActor.requireUser().id()).stream()
            .filter(membership -> companyIds == null || companyIds.contains(membership.companyId()))
            .toList();
        Map<UUID, List<String>> byCompany = owners.ownersOf(mine.stream().map(UserMembership::companyId).toList());
        return new TokenOwnerNotice(mine.stream()
            .map(membership -> new CompanyOwnerNotice(membership.companyId(), membership.companyName(),
                    byCompany.getOrDefault(membership.companyId(), List.of())))
            .toList());
    }

    @Override
    public ResponseEntity<Void> revokeOwnToken(UUID tokenId) {
        tokens.revokeOwn(tokenId);
        return ResponseEntity.noContent().build();
    }
}
