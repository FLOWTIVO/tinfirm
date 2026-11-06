package com.flowtivo.tinfirm.conversation.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.GitHubStub;

/**
 * A change request under {@code ASK} through the seams between parts 2 and 3 (the package 3 contract, §5.4, §5.8):
 * the turn's end raises part 2's approval ({@link ChangeRequestApprovals}) inside its transaction, one per repository
 * while it is open, kept with the run that pushed ({@code decision.run_id}, which part 3 reads back); a denial opens
 * nothing; once an approval commits, part 2 calls {@link ChangeRequestOpening#approved}, which opens the change
 * request for that run, and a repeat of that call opens nothing more.
 */
class ChangeRequestApprovalIntegrationTest extends ChangeRequestTestBench {

    @Autowired
    ChangeRequestApprovals approvals;

    @Autowired
    ChangeRequestStore changeRequests;

    @Test
    void anApprovalIsRaisedOncePerRepositoryForTheTurnThatPushedAndOnlyAnApprovedOneOpens() {
        Host host = codeHost("ASK");
        Bound bound = bound(host);
        opens(host, "shop", 12);
        opens(host, "web", 3);
        UUID first = pushed(host, bound, host.shopId(), "shop", false, 2);
        UUID shop = single(open(host, bound));
        assertThat(inCompany(host.bench(), () -> changeRequests.runOfDecision(shop))).contains(first);

        // A later push to the same repository while its approval is open answers the same one, for the first turn.
        pushed(host, bound, host.shopId(), "shop", false, 1);
        assertThat(open(host, bound)).containsExactly(shop);
        assertThat(inCompany(host.bench(), () -> changeRequests.runOfDecision(shop))).contains(first);
        // Another repository gets its own.
        UUID third = pushed(host, bound, host.webId(), "web", false, 1);
        List<Object> both = open(host, bound);
        assertThat(both).hasSize(2).contains(shop);
        UUID web = both.stream().map(UUID.class::cast).filter(id -> !id.equals(shop)).findFirst().orElseThrow();
        assertThat(inCompany(host.bench(), () -> changeRequests.runOfDecision(web))).contains(third);
        assertThat(messages(host, bound)).hasSize(2);
        // The seam is called inside a transaction only.
        assertThatThrownBy(() -> approvals.raise(host.bench().companyId(), bound.conversation().id(), third,
                host.webId(), "Open it")).isInstanceOf(IllegalTransactionStateException.class);

        // Denied: nothing is opened.
        assertThat(decide(host, shop, "DENIED").status()).isEqualTo(200);
        // Approved: opened once the approval committed, for the turn that pushed, with the person's account.
        assertThat(decide(host, web, "APPROVED").status()).isEqualTo(200);
        awaitChangeRequests(host, bound, 1);
        GitHubStub.server().verify(1, postRequestedFor(urlEqualTo(host.pulls("web")))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
        assertThat(inCompany(host.bench(), () -> outputs.outputs(host.bench().companyId(), bound.conversation().id())))
            .singleElement()
            .satisfies(output -> {
                assertThat(output.repositoryId()).isEqualTo(host.webId());
                assertThat(output.runId()).isEqualTo(third);
            });
        await().during(Duration.ofSeconds(1)).atMost(WAIT).untilAsserted(() -> GitHubStub.server()
            .verify(0, postRequestedFor(urlEqualTo(host.pulls("shop")))));
        // Approved again (a repeat of part 2's call): nothing more.
        opener.approved(host.bench().companyId(), bound.conversation().id(), web, host.webId());
        GitHubStub.server().verify(1, postRequestedFor(urlEqualTo(host.pulls("web"))));
    }

    private static List<Object> open(Host host, Bound bound) {
        return column(host.bench(), "SELECT id FROM decision WHERE conversation_id = ? AND status = 'OPEN' "
                + "ORDER BY created_at, id", bound.conversation().id());
    }

    private static UUID single(List<Object> ids) {
        assertThat(ids).hasSize(1);
        return (UUID) ids.getFirst();
    }

    private static ApiResponse decide(Host host, UUID decisionId, String outcome) {
        return host.bench().person().post(host.bench().path("/decisions/" + decisionId + "/decide"),
                Map.of("outcome", outcome));
    }
}
