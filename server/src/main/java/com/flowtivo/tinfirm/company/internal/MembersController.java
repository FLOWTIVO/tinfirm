package com.flowtivo.tinfirm.company.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.MembersApi;
import com.flowtivo.tinfirm.shared.api.model.Member;
import com.flowtivo.tinfirm.shared.api.model.MemberAccountList;
import com.flowtivo.tinfirm.shared.api.model.MemberAddRequest;
import com.flowtivo.tinfirm.shared.api.model.MemberPage;
import com.flowtivo.tinfirm.shared.api.model.MemberUpdateRequest;

@RestController
class MembersController implements MembersApi {

    private final MemberService memberService;

    private final MemberAccountsReader memberAccounts;

    MembersController(MemberService memberService, MemberAccountsReader memberAccounts) {
        this.memberService = memberService;
        this.memberAccounts = memberAccounts;
    }

    @Override
    public ResponseEntity<MemberPage> listMembers(UUID companyId, Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(memberService.list(companyId, limit, cursor));
    }

    @Override
    public ResponseEntity<Member> addMember(UUID companyId, MemberAddRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(memberService.add(companyId, request));
    }

    @Override
    public ResponseEntity<Member> updateMember(UUID companyId, UUID userId, MemberUpdateRequest request) {
        return ResponseEntity.ok(memberService.changeRole(companyId, userId, request));
    }

    @Override
    public ResponseEntity<Void> removeMember(UUID companyId, UUID userId) {
        memberService.remove(companyId, userId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<MemberAccountList> listMemberAccounts(UUID companyId, UUID userId) {
        return ResponseEntity.ok(memberAccounts.list(companyId, userId));
    }
}
