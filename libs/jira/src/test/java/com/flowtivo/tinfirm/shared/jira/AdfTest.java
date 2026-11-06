package com.flowtivo.tinfirm.shared.jira;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Atlassian Document Format of Jira Cloud as Markdown, and back (CONVENTIONS §15.2, ADR-0048). */
class AdfTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    // ---- ADF to Markdown -------------------------------------------------------------------------------------------

    @Test
    void aDocumentReadsAsMarkdownBlockByBlock() {
        String adf = """
                {"version":1,"type":"doc","content":[
                  {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Goal"}]},
                  {"type":"paragraph","content":[
                    {"type":"text","text":"Return "},
                    {"type":"text","text":"200","marks":[{"type":"strong"}]},
                    {"type":"text","text":" from "},
                    {"type":"text","text":"/health","marks":[{"type":"code"}]},
                    {"type":"text","text":", see "},
                    {"type":"text","text":"the spec","marks":[{"type":"link","attrs":{"href":"https://example.test/spec"}},{"type":"em"}]},
                    {"type":"text","text":"."},
                    {"type":"hardBreak"},
                    {"type":"text","text":"Second line"}]},
                  {"type":"bulletList","content":[
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"one"}]},
                      {"type":"orderedList","attrs":{"order":1},"content":[
                        {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"nested"}]}]}]}]},
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"two"}]}]}]},
                  {"type":"codeBlock","attrs":{"language":"java"},"content":[{"type":"text","text":"int a = 1;\\nint b = 2;"}]},
                  {"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"quoted"}]}]},
                  {"type":"rule"},
                  {"type":"table","attrs":{"isNumberColumnEnabled":false,"layout":"default"},"content":[
                    {"type":"tableRow","content":[
                      {"type":"tableHeader","attrs":{},"content":[{"type":"paragraph","content":[{"type":"text","text":"Name"}]}]},
                      {"type":"tableHeader","attrs":{},"content":[{"type":"paragraph","content":[{"type":"text","text":"Value"}]}]}]},
                    {"type":"tableRow","content":[
                      {"type":"tableCell","attrs":{},"content":[{"type":"paragraph","content":[{"type":"text","text":"a|b"}]}]},
                      {"type":"tableCell","attrs":{},"content":[{"type":"paragraph","content":[{"type":"text","text":"1"}]}]}]}]}
                ]}""";

        assertThat(Adf.toMarkdown(adf)).isEqualTo("""
                ## Goal

                Return **200** from `/health`, see [*the spec*](https://example.test/spec).
                Second line

                - one
                  1. nested
                - two

                ```java
                int a = 1;
                int b = 2;
                ```

                > quoted

                ---

                | Name | Value |
                | --- | --- |
                | a\\|b | 1 |""");
    }

    @Test
    void mentionsEmojiCardsDatesStatusesPanelsTasksAndMediaKeepTheirText() {
        String adf = """
                {"version":1,"type":"doc","content":[
                  {"type":"paragraph","content":[
                    {"type":"mention","attrs":{"id":"000000000000000000000001","text":"@Jane Doe"}},
                    {"type":"text","text":" please look "},
                    {"type":"emoji","attrs":{"shortName":":thumbsup:","text":"👍"}},
                    {"type":"text","text":" "},
                    {"type":"inlineCard","attrs":{"url":"https://example.test/page"}},
                    {"type":"text","text":" by "},
                    {"type":"date","attrs":{"timestamp":"1759449600000"}},
                    {"type":"text","text":" "},
                    {"type":"status","attrs":{"text":"In progress","color":"blue"}}]},
                  {"type":"panel","attrs":{"panelType":"info"},"content":[{"type":"paragraph","content":[{"type":"text","text":"Note"}]}]},
                  {"type":"taskList","attrs":{"localId":"a"},"content":[
                    {"type":"taskItem","attrs":{"localId":"b","state":"DONE"},"content":[{"type":"text","text":"done"}]},
                    {"type":"taskItem","attrs":{"localId":"c","state":"TODO"},"content":[{"type":"text","text":"open"}]}]},
                  {"type":"mediaSingle","attrs":{"layout":"center"},"content":[{"type":"media","attrs":{"id":"x","type":"file","collection":"c","alt":"diagram.png"}}]},
                  {"type":"expand","attrs":{"title":"Details"},"content":[{"type":"paragraph","content":[{"type":"text","text":"hidden"}]}]},
                  {"type":"someFutureNode","content":[{"type":"paragraph","content":[{"type":"text","text":"kept"}]}]}
                ]}""";

        assertThat(Adf.toMarkdown(adf)).isEqualTo("""
                @Jane Doe please look 👍 <https://example.test/page> by 2025-10-03 **In progress**

                > Note

                - [x] done
                - [ ] open

                [attachment: diagram.png]

                **Details**

                hidden

                kept""");
    }

    @Test
    void emphasisStaysOutsideWhitespaceAndAdjacentRunsMerge() {
        String adf = """
                {"version":1,"type":"doc","content":[{"type":"paragraph","content":[
                  {"type":"text","text":"bold ","marks":[{"type":"strong"}]},
                  {"type":"text","text":"still","marks":[{"type":"strong"}]},
                  {"type":"text","text":" and "},
                  {"type":"text","text":"gone","marks":[{"type":"strike"}]},
                  {"type":"text","text":" and "},
                  {"type":"text","text":"a`b","marks":[{"type":"code"},{"type":"strong"}]}]}]}""";

        assertThat(Adf.toMarkdown(adf)).isEqualTo("**bold still** and ~~gone~~ and ``a`b``");
    }

    @Test
    void aCodeSpanFenceIsLongerThanAnyBacktickRunInsideIt() {
        assertThat(Adf.codeSpan("a")).isEqualTo("`a`");
        assertThat(Adf.codeSpan("a`b")).isEqualTo("``a`b``");
        assertThat(Adf.codeSpan("a``b")).isEqualTo("```a``b```");
        assertThat(Adf.codeSpan("`tick`")).isEqualTo("`` `tick` ``");
        assertThat(Adf.codeSpan("```")).isEqualTo("```` ``` ````");
    }

    @Test
    void textThatIsNotADocumentIsReadAsText() {
        assertThat(Adf.toMarkdown(null)).isNull();
        assertThat(Adf.toMarkdown("  ")).isNull();
        assertThat(Adf.toMarkdown("plain *wiki* text")).isEqualTo("plain *wiki* text");
        assertThat(Adf.toMarkdown("{\"not\":\"a doc\"}")).isEqualTo("{\"not\":\"a doc\"}");
        assertThat(Adf.toMarkdown("\"a json string\"")).isEqualTo("a json string");
        assertThat(Adf.toMarkdown("{\"version\":1,\"type\":\"doc\",\"content\":[]}")).isNull();
        assertThat(Adf.isDocument("{\"type\":\"doc\",\"content\":[]}")).isTrue();
        assertThat(Adf.isDocument("hello")).isFalse();
    }

    // ---- Markdown to ADF -------------------------------------------------------------------------------------------

    @Test
    void markdownBecomesADocumentWithTheSameBlocks() {
        String markdown = """
                # Done

                Implemented the **coupon** field, see [the PR](https://github.com/acme/web/pull/7) and `Cart.java`.
                A second line.

                - one
                  - nested *em*
                - two
                1. first
                2. second

                ```java
                int a = 1;
                ```

                > a quote
                > continues

                | Name | Value |
                |---|---|
                | a | 1 |

                ---

                https://example.test/plain and ~~old~~ and \\*not emphasis\\*""";

        JsonNode doc = json(Adf.fromMarkdown(markdown));

        assertThat(doc.get("type").asString()).isEqualTo("doc");
        assertThat(doc.get("version").asInt()).isEqualTo(1);
        JsonNode content = doc.get("content");
        assertThat(content).extracting(node -> node.get("type").asString()).containsExactly("heading", "paragraph",
                "bulletList", "orderedList", "codeBlock", "blockquote", "table", "rule", "paragraph");
        assertThat(content.get(0).get("attrs").get("level").asInt()).isEqualTo(1);
        JsonNode paragraph = content.get(1).get("content");
        assertThat(paragraph.get(0).get("text").asString()).isEqualTo("Implemented the ");
        assertThat(paragraph.get(1).get("text").asString()).isEqualTo("coupon");
        assertThat(paragraph.get(1).get("marks").get(0).get("type").asString()).isEqualTo("strong");
        assertThat(paragraph.get(3).get("text").asString()).isEqualTo("the PR");
        assertThat(paragraph.get(3).get("marks").get(0).get("attrs").get("href").asString())
            .isEqualTo("https://github.com/acme/web/pull/7");
        assertThat(paragraph.get(5).get("marks").get(0).get("type").asString()).isEqualTo("code");
        assertThat(paragraph).anySatisfy(node -> assertThat(node.get("type").asString()).isEqualTo("hardBreak"));
        JsonNode bullets = content.get(2).get("content");
        assertThat(bullets).hasSize(2);
        JsonNode nested = bullets.get(0).get("content").get(1);
        assertThat(nested.get("type").asString()).isEqualTo("bulletList");
        assertThat(nested.get("content").get(0).get("content").get(0).get("content").get(1).get("marks").get(0)
            .get("type").asString()).isEqualTo("em");
        assertThat(content.get(3).get("attrs").get("order").asInt()).isEqualTo(1);
        assertThat(content.get(3).get("content")).hasSize(2);
        assertThat(content.get(4).get("attrs").get("language").asString()).isEqualTo("java");
        assertThat(content.get(4).get("content").get(0).get("text").asString()).isEqualTo("int a = 1;");
        assertThat(content.get(5).get("content").get(0).get("type").asString()).isEqualTo("paragraph");
        JsonNode table = content.get(6);
        assertThat(table.get("content").get(0).get("content").get(0).get("type").asString()).isEqualTo("tableHeader");
        assertThat(table.get("content").get(1).get("content").get(1).get("type").asString()).isEqualTo("tableCell");
        JsonNode last = content.get(8).get("content");
        assertThat(last.get(0).get("text").asString()).isEqualTo("https://example.test/plain");
        assertThat(last.get(0).get("marks").get(0).get("type").asString()).isEqualTo("link");
        assertThat(last.get(2).get("marks").get(0).get("type").asString()).isEqualTo("strike");
        assertThat(last.get(3).get("text").asString()).isEqualTo(" and *not emphasis*");
    }

    @Test
    void aChecklistBecomesATaskListAndAMixedListStaysBullets() {
        JsonNode tasks = json(Adf.fromMarkdown("- [x] done\n- [ ] open\n  - [ ] nested")).get("content").get(0);
        assertThat(tasks.get("type").asString()).isEqualTo("taskList");
        assertThat(tasks.get("attrs").get("localId").asString()).isNotBlank();
        assertThat(tasks.get("content")).extracting(node -> node.get("type").asString()).containsExactly("taskItem",
                "taskItem", "taskList");
        assertThat(tasks.get("content").get(0).get("attrs").get("state").asString()).isEqualTo("DONE");
        assertThat(tasks.get("content").get(1).get("attrs").get("state").asString()).isEqualTo("TODO");
        assertThat(tasks.get("content").get(0).get("content").get(0).get("text").asString()).isEqualTo("done");

        JsonNode mixed = json(Adf.fromMarkdown("- [x] done\n- plain")).get("content").get(0);
        assertThat(mixed.get("type").asString()).isEqualTo("bulletList");
        assertThat(mixed.get("content").get(0).get("content").get(0).get("content").get(0).get("text").asString())
            .isEqualTo("[x] done");
    }

    @Test
    void theCommonSubsetSurvivesARoundTrip() {
        String markdown = """
                ## Result

                Implemented the **coupon** field; see [the PR](https://github.com/acme/web/pull/7) and `Cart.java`.

                - one
                  - nested *em*
                - two

                1. first
                1. second

                ```
                plain code
                ```

                > a quote

                | Name | Value |
                | --- | --- |
                | a | 1 |

                ---

                <https://example.test/plain> and ~~old~~""";

        assertThat(Adf.toMarkdown(Adf.fromMarkdown(markdown))).isEqualTo(markdown);
        assertThat(Adf.fromMarkdown(null)).isEqualTo("{\"version\":1,\"type\":\"doc\",\"content\":[]}");
    }

    @Test
    void aLabelGoesInFrontOfTheFirstParagraphOrBeforeTheFirstBlock() {
        JsonNode labelled = json(Adf.withLabel("Tinfirm:", Adf.fromMarkdown("Tester could not start.")));
        JsonNode inline = labelled.get("content").get(0).get("content");
        assertThat(inline.get(0).get("text").asString()).isEqualTo("Tinfirm:");
        assertThat(inline.get(0).get("marks").get(0).get("type").asString()).isEqualTo("strong");
        assertThat(inline.get(1).get("text").asString()).isEqualTo(" ");
        assertThat(inline.get(2).get("text").asString()).isEqualTo("Tester could not start.");
        assertThat(Adf.toMarkdown(labelled.toString())).isEqualTo("**Tinfirm:** Tester could not start.");

        JsonNode beforeHeading = json(Adf.withLabel("Reviewer (Tinfirm agent):", Adf.fromMarkdown("# Done\n\nAll good")));
        assertThat(beforeHeading.get("content")).extracting(node -> node.get("type").asString())
            .containsExactly("paragraph", "heading", "paragraph");
        assertThat(Adf.toMarkdown(beforeHeading.toString()))
            .isEqualTo("**Reviewer (Tinfirm agent):**\n\n# Done\n\nAll good");

        assertThat(Adf.toMarkdown(Adf.withLabel("Tinfirm:", "plain text\nsecond line")))
            .isEqualTo("**Tinfirm:** plain text\nsecond line");
        assertThat(Adf.toMarkdown(Adf.withLabel("Tinfirm:", ""))).isEqualTo("**Tinfirm:**");
    }

    @Test
    void aMentionIsFoundByAccountIdIgnoringCase() {
        String adf = Adf.document(List.of()).toString().replace("\"content\":[]", "\"content\":[{\"type\":"
                + "\"paragraph\",\"content\":[{\"type\":\"mention\",\"attrs\":{\"id\":\"712020:ABC-def\",\"text\":"
                + "\"@Dev Bot\"}},{\"type\":\"text\",\"text\":\" please\"}]}]");
        assertThat(Adf.mentions(adf, "712020:abc-DEF")).isTrue();
        assertThat(Adf.mentions(adf, "712020:other")).isFalse();
        assertThat(Adf.mentions("Thanks [~accountid:712020:ABC-def] for the fix", "712020:abc-def")).isTrue();
        assertThat(Adf.mentions("Thanks @Dev Bot", "712020:abc-def")).isFalse();
        assertThat(Adf.toMarkdown(adf)).isEqualTo("@Dev Bot please");
    }

    @Test
    void plainTextBecomesOneParagraphWithHardBreaks() {
        JsonNode doc = Adf.documentOf("first\nsecond");
        JsonNode inline = doc.get("content").get(0).get("content");
        assertThat(inline).extracting(node -> node.get("type").asString()).containsExactly("text", "hardBreak",
                "text");
        assertThat(Adf.documentOf(doc.toString())).isEqualTo(doc);
        assertThat(Adf.documentOf("   ").get("content")).isEmpty();
    }
}
