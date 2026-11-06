package com.flowtivo.tinfirm.shared.jira;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Issues, comments, users and searches read out of Jira's JSON, Cloud's and Data Center's. */
class JiraIssuesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void aCloudIssueComesWithItsRawFieldsUsersAndCommentsOldestFirst() {
        JiraIssue issue = JiraIssues.parse(json("""
                {"id":"10001","key":"SUP-12","fields":{
                  "summary":"VPN drops","project":{"key":"SUP"},"issuetype":{"name":"Bug"},
                  "status":{"name":"Done"},"resolution":{"name":"Fixed"},
                  "resolutiondate":"2026-03-02T10:15:30.000+0100","created":"2026-03-01T09:00:00.000+0100",
                  "updated":"2026-03-02T10:15:30.000+0100","labels":["vpn",7,"windows"],
                  "description":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[
                    {"type":"text","text":"It drops every hour"}]}]},
                  "assignee":{"accountId":"5b10a","displayName":"Alice"},
                  "reporter":"not an object",
                  "comment":{"total":5,"comments":[
                    {"id":"300","created":"2026-03-02T08:00:00.000+0100","author":{"accountId":"5b10a"},
                     "body":{"type":"doc","version":1,"content":[]}},
                    {"id":"200","created":"2026-03-01T12:00:00.000+0100","body":"plain"},
                    {"id":"x","created":"2026-03-01T12:00:00.000+0100"},
                    {"id":"400"}]}}}"""));

        assertThat(issue).isNotNull();
        assertThat(issue.id()).isEqualTo("10001");
        assertThat(issue.key()).isEqualTo("SUP-12");
        assertThat(issue.project()).isEqualTo("SUP");
        assertThat(issue.type()).isEqualTo("Bug");
        assertThat(issue.status()).isEqualTo("Done");
        assertThat(issue.resolution()).isEqualTo("Fixed");
        assertThat(issue.resolved()).isTrue();
        assertThat(issue.resolvedAt()).isEqualTo(Instant.parse("2026-03-02T09:15:30Z"));
        assertThat(issue.created()).isEqualTo(Instant.parse("2026-03-01T08:00:00Z"));
        assertThat(issue.labels()).containsExactly("vpn", "windows");
        assertThat(issue.description()).startsWith("{\"type\":\"doc\"");
        assertThat(JiraIssues.markdown(issue.description(), true)).isEqualTo("It drops every hour");
        assertThat(issue.assignee()).isEqualTo(new JiraIssue.User("5b10a", null, null, "Alice"));
        assertThat(issue.reporter()).isNull();
        // Ordered by id; without a numeric id or a time a comment is left out.
        assertThat(issue.comments()).extracting(JiraIssue.Comment::id).containsExactly(200L, 300L);
        assertThat(issue.comments().getFirst().author()).isNull();
        assertThat(issue.comments().getFirst().body()).isEqualTo("plain");
        assertThat(issue.commentTotal()).isEqualTo(5);
    }

    @Test
    void aCommentOnlySomeReadersSeeIsMarkedRestricted() {
        List<JiraIssue.Comment> comments = JiraIssues.comments(json("""
                [{"id":"1","created":"2026-03-01T12:00:00.000+0100","body":"everyone"},
                 {"id":"2","created":"2026-03-01T12:00:00.000+0100","body":"admins",
                  "visibility":{"type":"role","value":"Administrators"}},
                 {"id":"3","created":"2026-03-01T12:00:00.000+0100","body":"agents","jsdPublic":false},
                 {"id":"4","created":"2026-03-01T12:00:00.000+0100","body":"customer","jsdPublic":true},
                 {"id":"5","created":"2026-03-01T12:00:00.000+0100","body":"internal on Data Center",
                  "properties":[{"key":"sd.public.comment","value":{"internal":true}}]},
                 {"id":"6","created":"2026-03-01T12:00:00.000+0100","body":"odd","visibility":{}},
                 {"id":"7","created":"2026-03-01T12:00:00.000+0100","body":"public","visibility":null}]"""));

        // An empty or odd visibility counts as restricted: never taken for public on an answer that says otherwise.
        assertThat(comments).extracting(JiraIssue.Comment::restricted)
            .containsExactly(false, true, true, false, true, true, false);
    }

    @Test
    void aDataCenterIssueKeepsItsWikiMarkupAndItsUsersKeysAndNames() {
        JiraIssue issue = JiraIssues.parse(json("""
                {"id":10002,"key":"OPS-7","fields":{"summary":"Disk full","updated":"2026-03-02T10:15:30.000+0000",
                  "description":"*Bold* text","resolutiondate":null,
                  "assignee":{"key":"JIRAUSER10","name":"bob","displayName":"Bob"}}}"""));

        assertThat(issue).isNotNull();
        assertThat(issue.id()).isEqualTo("10002");
        assertThat(issue.project()).isNull();
        assertThat(JiraIssues.projectOf(issue.key())).isEqualTo("OPS");
        assertThat(issue.description()).isEqualTo("*Bold* text");
        assertThat(JiraIssues.markdown(issue.description(), false)).isEqualTo("**Bold** text");
        assertThat(issue.resolved()).isFalse();
        assertThat(issue.created()).isNull();
        assertThat(issue.assignee()).isEqualTo(new JiraIssue.User(null, "JIRAUSER10", "bob", "Bob"));
        assertThat(issue.comments()).isEmpty();
        assertThat(issue.commentTotal()).isZero();
    }

    @Test
    void anIssueWithoutAKeyFieldsOrAnUpdateTimeIsLeftOut() {
        assertThat(JiraIssues.parse(json("{\"fields\":{\"updated\":\"2026-03-02T10:15:30.000+0000\"}}"))).isNull();
        assertThat(JiraIssues.parse(json("{\"key\":\"A-1\"}"))).isNull();
        assertThat(JiraIssues.parse(json("{\"key\":\"A-1\",\"fields\":{\"updated\":\"yesterday\"}}"))).isNull();
        assertThat(JiraIssues.issues(json("""
                {"issues":[{"key":"A-1"},{"key":"A-2","fields":{"updated":"2026-03-02T10:15:30Z"}}]}""")))
            .extracting(JiraIssue::key)
            .containsExactly("A-2");
    }

    @Test
    void timesAreJirasOrIso() {
        assertThat(JiraTime.parse("2026-10-03T10:15:30.000+0200")).isEqualTo(Instant.parse("2026-10-03T08:15:30Z"));
        assertThat(JiraTime.parse("2026-10-03T08:15:30Z")).isEqualTo(Instant.parse("2026-10-03T08:15:30Z"));
        assertThat(JiraTime.parse("03/10/2026")).isNull();
        assertThat(JiraTime.parse(null)).isNull();
    }

    @Test
    void searchUrlsPagesAndErrors() {
        assertThat(JiraSearch.cloudUrl("https://acme.atlassian.net/rest/api/3", "project = SUP ORDER BY key",
                "summary,updated", 100, null))
            .isEqualTo("https://acme.atlassian.net/rest/api/3/search/jql?jql=project%20%3D%20SUP%20ORDER%20BY%20key"
                    + "&maxResults=100&fields=summary%2Cupdated");
        assertThat(JiraSearch.cloudUrl("https://a.atlassian.net/rest/api/3", "x", "updated", 1, "t+1"))
            .endsWith("&nextPageToken=t%2B1");
        assertThat(JiraSearch.dataCenterUrl("https://jira.example.com/rest/api/2", "project = OPS", "updated", 50, 25))
            .isEqualTo("https://jira.example.com/rest/api/2/search?jql=project%20%3D%20OPS&startAt=50&maxResults=25"
                    + "&fields=updated");

        JiraSearch.CloudPage more = JiraSearch.cloudPage(json("""
                {"issues":[{"key":"A-1","fields":{"updated":"2026-03-02T10:15:30Z"}},{"key":"A-2"}],
                 "nextPageToken":"next","isLast":false}"""));
        assertThat(more.issues()).hasSize(1);
        assertThat(more.count()).isEqualTo(2);
        assertThat(more.nextPageToken()).isEqualTo("next");
        assertThat(more.last()).isFalse();
        assertThat(JiraSearch.cloudPage(json("{\"issues\":[],\"nextPageToken\":\"x\",\"isLast\":true}")).last())
            .isTrue();
        assertThat(JiraSearch.cloudPage(json("{\"issues\":[]}")).last()).isTrue();

        assertThat(JiraSearch.errors(json("""
                {"errorMessages":["The value 'X' does not exist for the field 'project'."],"errors":{"jql":"bad"}}""")))
            .containsExactly("The value 'X' does not exist for the field 'project'.", "jql: bad");
        assertThat(JiraSearch.errors(json("{\"message\":\"Client must be authenticated\"}")))
            .containsExactly("Client must be authenticated");
        assertThat(JiraSearch.errors(null)).isEmpty();
    }
}
