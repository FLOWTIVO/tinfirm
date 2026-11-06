package com.flowtivo.tinfirm.skill.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.flowtivo.tinfirm.shared.api.SkillsApi;
import com.flowtivo.tinfirm.shared.api.model.Skill;
import com.flowtivo.tinfirm.shared.api.model.SkillPage;
import com.flowtivo.tinfirm.shared.api.model.SkillSharesRequest;
import com.flowtivo.tinfirm.shared.api.model.SkillVersion;

@RestController
class SkillsController implements SkillsApi {

    private final SkillService skills;

    SkillsController(SkillService skills) {
        this.skills = skills;
    }

    @Override
    public ResponseEntity<SkillPage> listSkills(UUID companyId, @Nullable String q, Boolean deleted, Integer limit,
            @Nullable String cursor) {
        return ResponseEntity.ok(skills.list(companyId, q, deleted, limit, cursor));
    }

    @Override
    public ResponseEntity<Skill> createSkill(UUID companyId, @Nullable MultipartFile file, @Nullable String skillMd,
            @Nullable String note) {
        return ResponseEntity.status(HttpStatus.CREATED).body(skills.create(companyId, file, skillMd, note));
    }

    @Override
    public ResponseEntity<Skill> getSkill(UUID companyId, UUID skillId) {
        return ResponseEntity.ok(skills.get(companyId, skillId));
    }

    @Override
    public ResponseEntity<Void> deleteSkill(UUID companyId, UUID skillId, Boolean removeFromAgents) {
        skills.delete(companyId, skillId, Boolean.TRUE.equals(removeFromAgents));
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Skill> addSkillVersion(UUID companyId, UUID skillId, @Nullable MultipartFile file,
            @Nullable String skillMd, @Nullable String note) {
        SkillService.Added added = skills.addVersion(companyId, skillId, file, skillMd, note);
        return ResponseEntity.status(added.stored() ? HttpStatus.CREATED : HttpStatus.OK).body(added.skill());
    }

    @Override
    public ResponseEntity<SkillVersion> getSkillVersion(UUID companyId, UUID skillId, Integer version) {
        return ResponseEntity.ok(skills.version(companyId, skillId, version));
    }

    @Override
    public ResponseEntity<Resource> downloadSkillVersion(UUID companyId, UUID skillId, Integer version) {
        return zip(skills.content(companyId, skillId, version));
    }

    @Override
    public ResponseEntity<Skill> replaceSkillShares(UUID companyId, UUID skillId, SkillSharesRequest request) {
        return ResponseEntity.ok(skills.replaceShares(companyId, skillId, request.getGroupIds(),
                request.getPrincipalIds()));
    }

    /** A skill's canonical zip, as an attachment, never cached. */
    static ResponseEntity<Resource> zip(SkillService.Download download) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .contentLength(download.content().length)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(download.fileName(), StandardCharsets.UTF_8).build()
                        .toString())
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .body(new ByteArrayResource(download.content()));
    }
}
