package com.flowtivo.tinfirm.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.xmlsec.encryption.support.EncryptionConstants;
import org.opensaml.xmlsec.signature.support.SignatureConstants;

import tools.jackson.databind.JsonNode;

import com.flowtivo.tinfirm.support.SamlTestKeys;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.TestDatabase;

/**
 * Tinfirm's checks of a SAML response one by one (ADR-0086, decisions 8 and 16): responses built with OpenSAML and
 * signed with test keys, each changed in one way from one that signs in. Each refusal lands on
 * {@code /login?error=SAML_LOGIN_FAILED}, audited with its reason and never the response.
 */
class SamlResponseChecksTest extends SamlIntegrationTest {

    private static final String FAILED = BASE + "/login?error=SAML_LOGIN_FAILED";

    @Test
    void aResponseAndAssertionSignedByTheIdpSignIn() {
        Attempt attempt = attempt("stub", r -> r.attribute("email", "signed-" + UUID.randomUUID() + "@example.com")
            .attribute("displayName", "Sam Signed"));

        assertThat(attempt.location()).isEqualTo(BASE + "/");
        JsonNode me = attempt.browser().get("/api/me").json();
        assertThat(me.get("authSource").asString()).isEqualTo("SAML");
        assertThat(me.get("displayName").asString()).isEqualTo("Sam Signed");
    }

    @Test
    void anAssertionSignedAloneOrAResponseSignedAloneSignsIn() {
        assertThat(attempt("stub", r -> {
            email(r);
            r.signResponse = false;
        }).location()).isEqualTo(BASE + "/");
        assertThat(attempt("stub", r -> {
            email(r);
            r.signAssertion = false;
        }).location()).isEqualTo(BASE + "/");
    }

    @Test
    void anUnsignedResponseIsRefused() {
        refused("stub", "unsigned", r -> {
            email(r);
            r.signResponse = false;
            r.signAssertion = false;
        });
    }

    @Test
    void aResponseSignedByAKeyNotInTheMetadataIsRefused() {
        SamlTestKeys other = SamlTestKeys.generate("not-the-idp");
        refused("stub", "signature_invalid", r -> {
            email(r);
            r.signingKeys = other;
        });
    }

    @Test
    void sha1IsRefused() {
        refused("stub", "weak_algorithm", r -> {
            email(r);
            r.signatureAlgorithm = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA1;
        });
        refused("stub", "weak_algorithm", r -> {
            email(r);
            r.digestAlgorithm = SignatureConstants.ALGO_ID_DIGEST_SHA1;
        });
    }

    @Test
    void aWrongIssuerIsRefused() {
        refused("stub", "issuer_mismatch", r -> {
            email(r);
            r.response = response -> {
                Issuer issuer = SamlTestIdp.object(Issuer.DEFAULT_ELEMENT_NAME);
                issuer.setValue("https://idp.test/another");
                response.setIssuer(issuer);
            };
        });
        refused("stub", "issuer_mismatch", r -> {
            email(r);
            r.assertion = assertion -> assertion.getIssuer().setValue("https://idp.test/another");
        });
    }

    @Test
    void aWrongAudienceDestinationOrRecipientIsRefused() {
        refused("stub", "audience_mismatch", r -> {
            email(r);
            r.spEntityId = "https://another.sp.test/metadata";
        });
        refused("stub", "destination_mismatch", r -> {
            email(r);
            r.response = response -> response.setDestination(BASE + "/login/saml2/sso/other");
        });
        refused("stub", "destination_mismatch", r -> {
            email(r);
            r.response = response -> response.setDestination(null);
        });
        refused("stub", "recipient_mismatch", r -> {
            email(r);
            r.assertion = assertion -> assertion.getSubject()
                .getSubjectConfirmations()
                .get(0)
                .getSubjectConfirmationData()
                .setRecipient(BASE + "/login/saml2/sso/other");
        });
    }

    @Test
    void aResponseToAnotherRequestIsRefused() {
        refused("stub", "in_response_to_mismatch", r -> {
            email(r);
            r.inResponseTo = "_not-a-request-" + UUID.randomUUID();
        });
    }

    @Test
    void anAssertionSignedAloneMustItselfAnswerTheRequest() {
        // An IdP-initiated assertion, signed alone, wrapped in an unsigned response that names this browser's request.
        refused("stub", "in_response_to_mismatch", r -> {
            email(r);
            r.signResponse = false;
            r.assertion = assertion -> assertion.getSubject()
                .getSubjectConfirmations()
                .get(0)
                .getSubjectConfirmationData()
                .setInResponseTo(null);
        });
        refused("stub", "in_response_to_mismatch", r -> {
            email(r);
            r.signResponse = false;
            r.assertion = assertion -> assertion.getSubject()
                .getSubjectConfirmations()
                .get(0)
                .getSubjectConfirmationData()
                .setInResponseTo("_another-request");
        });
    }

    @Test
    void aPercentEncodedPathMeetsTheGuardToo() {
        ApiClient browser = new ApiClient(PORT);
        // Spring's matchers decode the path: /login/saml2/ss%6F/stub is the ACS, and only a POST passes the guard.
        ApiResponse get = browser.get("/login/saml2/ss%6F/stub?SAMLResponse=x");
        assertThat(get.header("Location")).isEqualTo(FAILED);
        assertThat(latestFailedLogin("stub").get("details").get("reason").asString()).isEqualTo("invalid_response");
    }

    @Test
    void anUnsolicitedResponseIsRefused() {
        refused("stub", "unsolicited", r -> {
            email(r);
            r.inResponseTo = null;
        });
    }

    @Test
    void anotherBrowsersResponseIsRefused() {
        ApiClient asked = new ApiClient(PORT);
        Started started = start(asked, "stub");
        SamlTestIdp.ResponseBuilder response = STUB.respond(spEntityId("stub"), acs("stub"), started.requestId());
        email(response);

        ApiClient other = new ApiClient(PORT);
        ApiResponse posted = post(other, "stub", response.encoded());

        assertThat(posted.header("Location")).isEqualTo(FAILED);
        assertThat(latestFailedLogin("stub").get("details").get("reason").asString()).isEqualTo("browser_mismatch");
        // The browser that asked still signs in with it.
        assertThat(post(asked, "stub", response.encoded()).header("Location")).isEqualTo(BASE + "/");
    }

    @Test
    void aRequestIsAnsweredOnce() {
        Attempt first = attempt("stub", SamlResponseChecksTest::email);
        assertThat(first.location()).isEqualTo(BASE + "/");

        SamlTestIdp.ResponseBuilder again = STUB.respond(spEntityId("stub"), acs("stub"), first.requestId());
        email(again);
        ApiResponse posted = post(first.browser(), "stub", again.encoded());

        assertThat(posted.header("Location")).isEqualTo(FAILED);
        assertThat(latestFailedLogin("stub").get("details").get("reason").asString())
            .isEqualTo("in_response_to_mismatch");
    }

    @Test
    void aRefusedResponseLeavesTheRequestForTheIdpsOwn() {
        // A page of another site posts a forged response from the browser while its sign-in is under way: the binding
        // cookie goes with it, but the request is used up only by a response that passes every check.
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "stub");
        SamlTestIdp.ResponseBuilder forged = STUB.respond(spEntityId("stub"), acs("stub"), started.requestId());
        email(forged);
        forged.signingKeys = SamlTestKeys.generate("forger");

        ApiResponse refused = post(browser, "stub", forged.encoded());
        assertThat(refused.header("Location")).isEqualTo(FAILED);
        assertThat(refused.setCookies()).noneMatch(cookie -> cookie.startsWith(SamlAuthnRequests.COOKIE + "="));

        SamlTestIdp.ResponseBuilder own = STUB.respond(spEntityId("stub"), acs("stub"), started.requestId());
        email(own);
        ApiResponse signedIn = post(browser, "stub", own.encoded());
        assertThat(signedIn.header("Location")).isEqualTo(BASE + "/");
        assertThat(signedIn.setCookies()).anyMatch(cookie -> cookie.startsWith(SamlAuthnRequests.COOKIE + "=;")
                && cookie.contains("Max-Age=0"));
    }

    @Test
    void aPostThatIsNotANavigationIsRefusedBeforeItIsRead() {
        // A hidden frame or a script of another site: the browser says so, and the response is neither parsed nor
        // counted against the address, nor does it use up the request.
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "stub");
        SamlTestIdp.ResponseBuilder response = STUB.respond(spEntityId("stub"), acs("stub"), started.requestId());
        email(response);
        for (String destination : new String[] { "iframe", "empty", "frame", "object" }) {
            assertThat(post(browser, "stub", response.encoded(), destination).status()).as(destination)
                .isEqualTo(403);
        }

        assertThat(post(browser, "stub", response.encoded(), "document").header("Location")).isEqualTo(BASE + "/");
    }

    @Test
    void aRequestOlderThanFiveMinutesIsRefused() throws Exception {
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "stub");
        try (Connection connection = TestDatabase.connectAsOwner();
                PreparedStatement older = connection
                    .prepareStatement("UPDATE saml_authn_request SET created_at = now() - interval '6 minutes' "
                            + "WHERE id = ?")) {
            older.setString(1, started.requestId());
            assertThat(older.executeUpdate()).isEqualTo(1);
        }
        SamlTestIdp.ResponseBuilder response = STUB.respond(spEntityId("stub"), acs("stub"), started.requestId());
        email(response);

        assertThat(post(browser, "stub", response.encoded()).header("Location")).isEqualTo(FAILED);
        assertThat(latestFailedLogin("stub").get("details").get("reason").asString())
            .isEqualTo("in_response_to_mismatch");
    }

    @Test
    void theConditionsHoldWithASkewOfSixtySeconds() {
        // Within the skew on both sides: signs in.
        assertThat(attempt("stub", r -> {
            email(r);
            r.assertion = assertion -> assertion.getConditions().setNotBefore(Instant.now().plusSeconds(45));
        }).location()).isEqualTo(BASE + "/");
        assertThat(attempt("stub", r -> {
            email(r);
            r.assertion = assertion -> {
                Instant end = Instant.now().minusSeconds(45);
                assertion.getConditions().setNotOnOrAfter(end);
                assertion.getSubject().getSubjectConfirmations().get(0).getSubjectConfirmationData()
                    .setNotOnOrAfter(end);
            };
        }).location()).isEqualTo(BASE + "/");
        // Beyond it: refused.
        refused("stub", "not_yet_valid", r -> {
            email(r);
            r.assertion = assertion -> assertion.getConditions().setNotBefore(Instant.now().plusSeconds(90));
        });
        refused("stub", "expired", r -> {
            email(r);
            r.assertion = assertion -> {
                Instant end = Instant.now().minusSeconds(90);
                assertion.getConditions().setNotOnOrAfter(end);
                assertion.getSubject().getSubjectConfirmations().get(0).getSubjectConfirmationData()
                    .setNotOnOrAfter(end);
            };
        });
    }

    @Test
    void anAssertionIssuedMoreThanFiveMinutesAgoIsRefused() {
        refused("stub", "expired", r -> {
            email(r);
            r.assertion = assertion -> assertion.setIssueInstant(Instant.now().minus(Duration.ofMinutes(7)));
        });
    }

    @Test
    void anAssertionIsUsedOnceWhateverRequestItAnswers() {
        String id = "_replayed-" + UUID.randomUUID();
        String email = "replay-" + UUID.randomUUID() + "@example.com";
        assertThat(attempt("stub", r -> {
            r.attribute("email", email);
            r.assertion = assertion -> assertion.setID(id);
        }).location()).isEqualTo(BASE + "/");

        refused("stub", "replayed", r -> {
            r.attribute("email", email);
            r.assertion = assertion -> assertion.setID(id);
        });
    }

    @Test
    void twoAssertionsAreRefused() {
        refused("stub", "multiple_assertions", r -> {
            email(r);
            r.signAssertion = false;
            r.response = response -> {
                Assertion second = SamlTestIdp.object(Assertion.DEFAULT_ELEMENT_NAME);
                second.setID("_second-" + UUID.randomUUID());
                second.setIssueInstant(Instant.now());
                Issuer issuer = SamlTestIdp.object(Issuer.DEFAULT_ELEMENT_NAME);
                issuer.setValue(STUB.entityId);
                second.setIssuer(issuer);
                response.getAssertions().add(second);
            };
        });
    }

    @Test
    void aFailedStatusIsRefused() {
        refused("stub", "status_not_success", r -> {
            email(r);
            r.response = response -> response.getStatus().getStatusCode().setValue(StatusCode.RESPONDER);
        });
    }

    @Test
    void anAssertionWithoutAnAuthnStatementIsRefused() {
        refused("stub", "invalid_response", r -> {
            email(r);
            r.assertion = assertion -> assertion.getAuthnStatements().clear();
        });
    }

    @Test
    void aTransientNameIdIsRefused() {
        refused("stub", "transient_subject", r -> {
            email(r);
            r.nameIdFormat = NameIDType.TRANSIENT;
        });
    }

    @Test
    void theSubjectMayBeAnAttribute() {
        String uid = "uid-" + UUID.randomUUID();
        Attempt first = attempt("stubuid", r -> {
            email(r);
            r.attribute("uid", uid);
        });
        assertThat(first.location()).isEqualTo(BASE + "/");
        String userId = first.browser().get("/api/me").json().get("id").asString();

        // Another NameID, the same uid: the same user.
        Attempt second = attempt("stubuid", r -> r.attribute("uid", uid));
        assertThat(second.browser().get("/api/me").json().get("id").asString()).isEqualTo(userId);
    }

    @Test
    void aPlainAssertionIsRefusedWhereEncryptionIsRequired() {
        refused("stubenc", "not_encrypted", SamlResponseChecksTest::email);

        Attempt encrypted = attempt("stubenc", r -> {
            email(r);
            r.encryptFor = SP;
        });
        assertThat(encrypted.location()).isEqualTo(BASE + "/");
    }

    @Test
    void rsaPkcs1KeyTransportIsRefused() {
        refused("stubenc", "weak_algorithm", r -> {
            email(r);
            r.encryptFor = SP;
            r.keyTransport = EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSA15;
        });
    }

    @Test
    void anAssertionEncryptedInAnUnsignedResponseIsRefused() {
        refused("stubenc", "unsigned", r -> {
            email(r);
            r.encryptFor = SP;
            r.signResponse = false;
        });
    }

    @Test
    void theAuditHoldsNeitherTheResponseNorTheEmail() {
        String email = "secret-" + UUID.randomUUID() + "@example.com";
        refused("stub", "unsigned", r -> {
            r.attribute("email", email);
            r.signResponse = false;
            r.signAssertion = false;
        });

        JsonNode failed = latestFailedLogin("stub");
        assertThat(failed.get("details").get("method").asString()).isEqualTo("saml");
        assertThat(failed.toString()).doesNotContain(email).doesNotContain("SAMLResponse");
    }

    /** A sign-in through a provider whose response the test changed. */
    record Attempt(ApiClient browser, String requestId, String location) {
    }

    private static Attempt attempt(String provider, Consumer<SamlTestIdp.ResponseBuilder> change) {
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, provider);
        SamlTestIdp.ResponseBuilder response = STUB.respond(spEntityId(provider), acs(provider), started.requestId());
        change.accept(response);
        ApiResponse posted = post(browser, provider, response.encoded());
        assertThat(posted.status()).as("%s", posted).isEqualTo(302);
        return new Attempt(browser, started.requestId(), posted.header("Location"));
    }

    private static void refused(String provider, String reason, Consumer<SamlTestIdp.ResponseBuilder> change) {
        Attempt attempt = attempt(provider, change);
        assertThat(attempt.location()).as(reason).isEqualTo(FAILED);
        assertThat(attempt.browser().get("/api/me").status()).isEqualTo(401);
        JsonNode failed = latestFailedLogin(provider);
        assertThat(failed.get("details").get("reason").asString()).isEqualTo(reason);
        assertThat(failed.get("details").get("method").asString()).isEqualTo("saml");
    }

    /** Posts a response with the {@code Sec-Fetch-Dest} a browser sends. */
    private static ApiResponse post(ApiClient browser, String id, String samlResponse, String destination) {
        return browser.send("POST", "/login/saml2/sso/" + id,
                "SAMLResponse=" + URLEncoder.encode(samlResponse, StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "text/html",
                        "Sec-Fetch-Dest", destination));
    }

    private static void email(SamlTestIdp.ResponseBuilder response) {
        response.attribute("email", "check-" + UUID.randomUUID() + "@example.com");
    }
}
