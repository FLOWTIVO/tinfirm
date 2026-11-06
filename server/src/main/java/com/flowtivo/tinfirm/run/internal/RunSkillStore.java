package com.flowtivo.tinfirm.run.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.runner.RunnerMessages;

/**
 * SQL access to {@code run_skill} (V040), under row-level security: the skills a run was assigned at their versions
 * (the skills contract, §3.6), which its runner host may download.
 */
@Repository
class RunSkillStore {

    private final JdbcClient jdbc;

    RunSkillStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Replaces the run's skills with those of its assignment (a run assigned again gets its new ones). */
    void replace(UUID companyId, UUID runId, List<RunnerMessages.AssignedSkill> skills) {
        jdbc.sql("DELETE FROM run_skill WHERE company_id = ? AND run_id = ?").params(companyId, runId).update();
        for (RunnerMessages.AssignedSkill skill : skills) {
            jdbc.sql("INSERT INTO run_skill (company_id, run_id, skill_id, version) VALUES (?, ?, ?, ?)")
                .params(companyId, runId, skill.skillId(), skill.version())
                .update();
        }
    }

    /** Whether the run was assigned the skill at the version. */
    boolean assigned(UUID companyId, UUID runId, UUID skillId, int version) {
        return jdbc.sql("SELECT count(*) FROM run_skill WHERE company_id = ? AND run_id = ? AND skill_id = ?"
                + " AND version = ?")
            .params(companyId, runId, skillId, version)
            .query(Long.class)
            .single() > 0;
    }
}
