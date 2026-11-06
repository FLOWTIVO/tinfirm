package com.flowtivo.tinfirm.system.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * A system's handle made from its name took a 17-character base and {@code _2}, {@code _3},
 * ... while taken, so the 100th try ({@code _100}) made 21 characters, which the handle's pattern refuses. Every try
 * now fits 20 characters: the base is cut further by a longer suffix.
 */
class FreeHandleTest {

    /** The system's handle ({@code external_system}'s and the API's). */
    private static final Pattern HANDLE = Pattern.compile("^[a-z][a-z0-9_]{0,19}$");

    @Test
    void aLongNamesHandleFitsTwentyCharactersAtEveryCollision() {
        String name = "Abcdefghijklmnopqrstuvwxyz";
        assertThat(after(name, 0)).isEqualTo("abcdefghijklmnopq");
        assertThat(after(name, 1)).isEqualTo("abcdefghijklmnopq_2");
        assertThat(after(name, 98)).isEqualTo("abcdefghijklmnopq_99");
        assertThat(after(name, 99)).isEqualTo("abcdefghijklmnop_100");
        assertThat(after(name, 100)).isEqualTo("abcdefghijklmnop_101");
        assertThat(after(name, 1000)).isEqualTo("abcdefghijklmno_1001");
    }

    @Test
    void aCutThatEndsInAnUnderscoreLosesItAndAShortNameKeepsItsWhole() {
        // "Abcdefghijklmno Pqrs": 20 characters, its own handle; cut to 16 for _100 it ends in "_", which goes.
        assertThat(after("Abcdefghijklmno Pqrs", 0)).isEqualTo("abcdefghijklmno_pqrs");
        assertThat(after("Abcdefghijklmno Pqrs", 1)).isEqualTo("abcdefghijklmno_p_2");
        assertThat(after("Abcdefghijklmno Pqrs", 99)).isEqualTo("abcdefghijklmno_100");
        assertThat(after("Abcdefghijklmno Pqrs", 1000)).isEqualTo("abcdefghijklmno_1001");
        assertThat(after("Jira", 99)).isEqualTo("jira_100");
        assertThat(after("Jira", 1000)).isEqualTo("jira_1001");
        assertThat(after("42 Desk", 0)).isEqualTo("s_42_desk");
    }

    /** The handle made from the name when its first {@code collisions} tries are taken; every try fits the pattern. */
    private static String after(String name, int collisions) {
        List<String> tried = new ArrayList<>();
        String handle = SystemService.freeHandle(name, candidate -> {
            tried.add(candidate);
            return tried.size() <= collisions;
        });
        assertThat(tried).hasSize(collisions + 1).doesNotHaveDuplicates()
            .allSatisfy(candidate -> assertThat(candidate).matches(HANDLE).hasSizeLessThanOrEqualTo(20));
        assertThat(handle).isEqualTo(tried.getLast());
        return handle;
    }
}
