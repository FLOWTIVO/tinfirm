package com.flowtivo.tinfirm.chat.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Which agents a message mentions, as {@code @handle} outside code. */
class MentionsTest {

    private static final UUID B = UUID.randomUUID();

    private static final UUID WRITER = UUID.randomUUID();

    private static final UUID TEST = UUID.randomUUID();

    private static final UUID REVIEWER = UUID.randomUUID();

    private static final Map<String, UUID> HANDLES = handles();

    @Test
    void aHandleAtTheStartOrAfterSpaceOrPunctuationMentionsItsAgent() {
        assertThat(Mentions.in("@b please write the test scenarios", HANDLES)).containsExactly(B);
        assertThat(Mentions.in("Could you look at this, @reviewer?", HANDLES)).containsExactly(REVIEWER);
        assertThat(Mentions.in("(@b) and [@reviewer](https://example.test) and\n@test-writer", HANDLES))
            .containsExactly(B, REVIEWER, WRITER);
        assertThat(Mentions.in("**@b**: and > @reviewer", HANDLES)).containsExactly(B, REVIEWER);
    }

    @Test
    void mentionsKeepTheOrderOfTheBodyAndCountOnce() {
        assertThat(Mentions.in("@reviewer first, then @b, then @reviewer again and @B", HANDLES))
            .containsExactly(REVIEWER, B);
    }

    @Test
    void handlesMatchIgnoringCase() {
        assertThat(Mentions.in("@Test-Writer and @REVIEWER", HANDLES)).containsExactly(WRITER, REVIEWER);
    }

    @Test
    void aHandleFollowedByPunctuationMentions() {
        assertThat(Mentions.in("@test-writer, please", HANDLES)).containsExactly(WRITER);
        assertThat(Mentions.in("Ask @b.", HANDLES)).containsExactly(B);
        assertThat(Mentions.in("@b's scenarios; @reviewer!", HANDLES)).containsExactly(B, REVIEWER);
        assertThat(Mentions.in("@b_x", HANDLES)).containsExactly(B);
    }

    @Test
    void aHandleThatIsAPrefixOfALongerOneMentionsOnlyTheWholeOne() {
        assertThat(Mentions.in("@test-writer", HANDLES)).containsExactly(WRITER);
        assertThat(Mentions.in("@test", HANDLES)).containsExactly(TEST);
        assertThat(Mentions.in("@test-writers and @test-", HANDLES)).isEmpty();
        assertThat(Mentions.in("@bé and @b2", HANDLES)).isEmpty();
    }

    @Test
    void anUnknownHandleIsText() {
        assertThat(Mentions.in("@nobody and @ b and @", HANDLES)).isEmpty();
        assertThat(Mentions.in("@nobody @b", Map.of())).isEmpty();
    }

    @Test
    void emailsPathsAndOtherWordsMentionNobody() {
        assertThat(Mentions.in("Write to ann@example.com or b@b.test", HANDLES)).isEmpty();
        assertThat(Mentions.in("See owner/@b and x.@b and x_@b and x-@b and @@b and 2@b", HANDLES)).isEmpty();
    }

    @Test
    void codeMentionsNobody() {
        assertThat(Mentions.in("`@b` is the handle; @reviewer reads it", HANDLES)).containsExactly(REVIEWER);
        assertThat(Mentions.in("``a ` @b`` and ` @reviewer", HANDLES)).containsExactly(REVIEWER);
        assertThat(Mentions.in("```\n@b\n```\n@reviewer", HANDLES)).containsExactly(REVIEWER);
        assertThat(Mentions.in("~~~ text\n@b\n~~~~\n\n@test", HANDLES)).containsExactly(TEST);
        assertThat(Mentions.in("````\n```\n@b\n````\n@test", HANDLES)).containsExactly(TEST);
        assertThat(Mentions.in("Before\r\n```java\r\n@b\r\n```\r\n@reviewer", HANDLES)).containsExactly(REVIEWER);
        // An unclosed fence runs to the end.
        assertThat(Mentions.in("@test\n```\n@b", HANDLES)).containsExactly(TEST);
        // Indented four spaces, it is not a fence.
        assertThat(Mentions.in("    ```\n@b", HANDLES)).containsExactly(B);
    }

    @Test
    void withoutCodeKeepsLinesAndBlanksOnlyCode() {
        assertThat(Mentions.withoutCode("a `b` c\n```\nd\n```\ne")).isEqualTo("a     c\n   \n \n   \ne");
    }

    private static Map<String, UUID> handles() {
        Map<String, UUID> handles = new LinkedHashMap<>();
        handles.put("b", B);
        handles.put("reviewer", REVIEWER);
        handles.put("test", TEST);
        handles.put("test-writer", WRITER);
        return handles;
    }
}
