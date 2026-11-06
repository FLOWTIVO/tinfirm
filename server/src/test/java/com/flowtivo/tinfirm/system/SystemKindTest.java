package com.flowtivo.tinfirm.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Which systems receive data (the security review of 2026-10-27, D3, accepted with a cap and the class shown on each
 * system's page): every kind an agent may call tools on, so that what it passes as their arguments reaches the system.
 */
class SystemKindTest {

    @Test
    void everyKindWithToolsReceivesDataAndNoOtherDoes() {
        assertThat(Arrays.stream(SystemKind.values()).filter(SystemKind::receivesData))
            .containsExactlyInAnyOrder(SystemKind.JIRA_DC, SystemKind.JIRA_CLOUD, SystemKind.GITHUB,
                    SystemKind.GITLAB, SystemKind.MCP, SystemKind.SLACK);
        for (SystemKind kind : SystemKind.values()) {
            assertThat(kind.receivesData()).as("%s", kind)
                .isEqualTo(!FixedTools.tools(kind).isEmpty() || kind.hasCatalogue());
        }
    }
}
