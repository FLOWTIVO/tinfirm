package com.flowtivo.tinfirm.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.info.GitProperties;

class SystemInfoControllerTest {

    private static final String REVISION = "3f2a9c41d07be6855a1c0e9b7d4f6a2318c5e0b9";

    @Test
    void reportsTheFullRevisionOfTheImage() {
        assertThat(SystemInfoController.commitOf(REVISION, git("1234567"))).isEqualTo(REVISION);
    }

    @Test
    void fallsBackToGitPropertiesAndThenToNothing() {
        assertThat(SystemInfoController.commitOf("unknown", git("1234567"))).isEqualTo("1234567");
        assertThat(SystemInfoController.commitOf("", null)).isNull();
        assertThat(SystemInfoController.commitOf(REVISION.toUpperCase(), null)).isNull();
    }

    private static GitProperties git(String shortId) {
        Properties properties = new Properties();
        properties.setProperty("commit.id", shortId + "0".repeat(40 - shortId.length()));
        properties.setProperty("commit.id.abbrev", shortId);
        return new GitProperties(properties);
    }
}
