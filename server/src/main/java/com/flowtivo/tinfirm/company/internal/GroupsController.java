package com.flowtivo.tinfirm.company.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.GroupsApi;
import com.flowtivo.tinfirm.shared.api.model.Group;
import com.flowtivo.tinfirm.shared.api.model.GroupInput;
import com.flowtivo.tinfirm.shared.api.model.GroupPage;
import com.flowtivo.tinfirm.shared.api.model.GroupUsage;

@RestController
class GroupsController implements GroupsApi {

    private final GroupService groups;

    GroupsController(GroupService groups) {
        this.groups = groups;
    }

    @Override
    public ResponseEntity<GroupPage> listGroups(UUID companyId, Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(groups.list(companyId, limit, cursor));
    }

    @Override
    public ResponseEntity<Group> createGroup(UUID companyId, GroupInput request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groups.create(companyId, request));
    }

    @Override
    public ResponseEntity<Group> getGroup(UUID companyId, UUID groupId) {
        return ResponseEntity.ok(groups.get(companyId, groupId));
    }

    @Override
    public ResponseEntity<Group> replaceGroup(UUID companyId, UUID groupId, GroupInput request) {
        return ResponseEntity.ok(groups.replace(companyId, groupId, request));
    }

    @Override
    public ResponseEntity<GroupUsage> getGroupUsage(UUID companyId, UUID groupId) {
        return ResponseEntity.ok(groups.usage(companyId, groupId));
    }

    @Override
    public ResponseEntity<Group> addGroupMember(UUID companyId, UUID groupId, UUID userId) {
        return ResponseEntity.ok(groups.addMember(companyId, groupId, userId));
    }

    @Override
    public ResponseEntity<Group> removeGroupMember(UUID companyId, UUID groupId, UUID userId) {
        return ResponseEntity.ok(groups.removeMember(companyId, groupId, userId));
    }

    @Override
    public ResponseEntity<Void> deleteGroup(UUID companyId, UUID groupId) {
        groups.delete(companyId, groupId);
        return ResponseEntity.noContent().build();
    }
}
