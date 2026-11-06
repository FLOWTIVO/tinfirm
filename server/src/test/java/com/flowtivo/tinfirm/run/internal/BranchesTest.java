package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The branch of a conversation and the directories of a run's repositories. */
class BranchesTest {

    @Test
    void theBranchIsAgentSlashTheLowerCasedKey() {
        assertThat(Branches.of("shop-123-dev-1")).isEqualTo("agent/shop-123-dev-1");
        assertThat(Branches.of("SHOP-241")).isEqualTo("agent/shop-241");
        assertThat(Branches.of("acme/web#12")).isEqualTo("agent/acme-web-12");
        assertThat(Branches.of(" .Weird key..name! ")).isEqualTo("agent/weird-key.name");
        assertThat(Branches.of("///")).isEqualTo("agent/work");
    }

    @Test
    void aConversationKeyOfNinetyThreeCharactersFitsTheBranch() {
        assertThat(Branches.of("k".repeat(93))).hasSize(99);
        String branch = Branches.of("K".repeat(200));
        assertThat(branch).hasSize(Branches.MAX_BRANCH_LENGTH).startsWith("agent/kkk");
        assertThat(Branches.of("k".repeat(94) + "-x")).hasSize(100).doesNotEndWith("-");
    }

    @Test
    void directoriesAreSlugsOfTheRepositoryNamesAndUniqueInARun() {
        assertThat(Branches.dir("server")).isEqualTo("server");
        assertThat(Branches.dir("Web App")).isEqualTo("web-app");
        assertThat(Branches.dir("Crème brûlée")).isEqualTo("creme-brulee");
        assertThat(Branches.dir("...")).isEqualTo("repo");
        assertThat(Branches.dir("a".repeat(60))).hasSize(40);
        assertThat(Branches.uniqueDir("Server", List.of("server"))).isEqualTo("server-2");
        assertThat(Branches.uniqueDir("server", List.of("server", "server-2"))).isEqualTo("server-3");
    }
}
