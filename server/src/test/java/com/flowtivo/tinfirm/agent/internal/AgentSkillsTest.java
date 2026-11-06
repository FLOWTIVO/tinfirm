package com.flowtivo.tinfirm.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.api.model.AgentSkillInput;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.skill.SkillInfo;
import com.flowtivo.tinfirm.skill.SkillVersionInfo;
import com.flowtivo.tinfirm.skill.Skills;

/**
 * An agent's skills as a unit (the security review of 2026-10-27, L23): an editor moves a pin only to a version of a
 * skill they see in the library, as they add only a skill they see; keeping or removing a pin needs no sight of it.
 */
class AgentSkillsTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID AGENT = UUID.randomUUID();

    private static final UUID EDITOR = UUID.randomUUID();

    private static final UUID SKILL = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    private final AgentSkillStore store = mock(AgentSkillStore.class);

    private final Skills skills = mock(Skills.class);

    private final AgentSkills agentSkills = new AgentSkills(store, skills);

    @BeforeEach
    void pinnedAtVersionOne() {
        SkillInfo skill = new SkillInfo(SKILL, COMPANY, "triage", 3, null, null);
        when(store.pins(COMPANY, AGENT)).thenReturn(List.of(new AgentSkillStore.Pin(SKILL, 1)));
        when(skills.findAll(eq(COMPANY), any())).thenReturn(Map.of(SKILL, skill));
        when(skills.version(eq(COMPANY), eq(SKILL), anyInt())).thenReturn(Optional.of(mock(SkillVersionInfo.class)));
    }

    @Test
    void anEditorWhoNoLongerSeesTheSkillCannotMoveItsPin() {
        when(skills.sees(COMPANY, SKILL, EDITOR)).thenReturn(false);

        assertThatThrownBy(() -> agentSkills.replace(COMPANY, AGENT, List.of(input(3)), EDITOR, NOW))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("not one you see in the library");
        verify(store, never()).move(any(), any(), any(), any(), any());
    }

    @Test
    void anEditorWhoSeesTheSkillMovesItsPin() {
        when(skills.sees(COMPANY, SKILL, EDITOR)).thenReturn(true);

        AgentSkills.Change change = agentSkills.replace(COMPANY, AGENT, List.of(input(3)), EDITOR, NOW);

        assertThat(change.moved()).containsExactly(new AgentSkills.Move(SKILL, "triage", 1, 3));
        verify(store).move(COMPANY, AGENT, new AgentSkillStore.Pin(SKILL, 3), EDITOR, NOW);
    }

    @Test
    void keepingOrRemovingAPinNeedsNoSightOfTheSkill() {
        when(skills.sees(COMPANY, SKILL, EDITOR)).thenReturn(false);

        assertThat(agentSkills.replace(COMPANY, AGENT, List.of(input(null)), EDITOR, NOW).none()).isTrue();
        assertThat(agentSkills.replace(COMPANY, AGENT, List.of(input(1)), EDITOR, NOW).none()).isTrue();
        assertThat(agentSkills.replace(COMPANY, AGENT, List.of(), EDITOR, NOW).removed())
            .containsExactly(new AgentSkills.Entry(SKILL, "triage", 1));
    }

    private static AgentSkillInput input(Integer version) {
        return new AgentSkillInput().skillId(SKILL).version(version);
    }
}
