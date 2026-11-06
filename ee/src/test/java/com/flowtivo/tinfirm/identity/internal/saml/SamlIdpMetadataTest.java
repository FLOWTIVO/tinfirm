package com.flowtivo.tinfirm.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.SamlTestKeys;

/** The IdP metadata Tinfirm accepts (ADR-0086, decision 3), and why it refuses the rest. */
class SamlIdpMetadataTest {

    private static final SamlTestIdp IDP = new SamlTestIdp("https://idp.test/metadata");

    private final Instant now = Instant.now();

    @Test
    void anEntityDescriptorGivesTheIdpItsRedirectServiceAndItsSigningKeys() throws Exception {
        SamlTestKeys next = SamlTestKeys.generate("next-idp-key");

        SamlIdpMetadata.Idp idp = SamlIdpMetadata.parse(bytes(IDP.metadata(next)), null, now);

        assertThat(idp.entityId()).isEqualTo("https://idp.test/metadata");
        assertThat(idp.ssoLocation()).isEqualTo("https://idp.test/sso");
        // The next certificate beside the current one: both trusted, so a rollover signs nobody out.
        assertThat(idp.signing()).containsExactly(IDP.keys.certificate(), next.certificate());
        assertThat(idp.certificates()).extracting(SamlIdpMetadata.Certificate::sha256)
            .containsExactly(SamlIdpMetadata.fingerprint(IDP.keys.certificate()),
                    SamlIdpMetadata.fingerprint(next.certificate()));
        assertThat(idp.validUntil()).isNull();
    }

    @Test
    void anEntitiesDescriptorNeedsTheEntityIdWhenItHoldsSeveralIdps() throws Exception {
        String other = IDP.metadata().replace("https://idp.test/metadata", "https://idp.test/other");
        String group = "<md:EntitiesDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\">"
                + strip(IDP.metadata()) + strip(other) + "</md:EntitiesDescriptor>";

        refused(group, null, SamlIdpMetadata.Problem.NO_IDP, "set _ENTITY_ID");
        assertThat(SamlIdpMetadata.parse(bytes(group), "https://idp.test/other", now).entityId())
            .isEqualTo("https://idp.test/other");
        refused(group, "https://idp.test/missing", SamlIdpMetadata.Problem.NO_IDP, "no SAML 2.0 IdP");
    }

    @Test
    void metadataWhoseValidUntilPassedIsRefused() {
        String expired = IDP.metadata().replace("entityID=", "validUntil=\"2020-01-01T00:00:00Z\" entityID=");

        refused(expired, null, SamlIdpMetadata.Problem.EXPIRED, "validUntil");
    }

    @Test
    void aNestedGroupsValidUntilAndCacheDurationHoldForTheIdpsItHolds() throws Exception {
        String nested = "<md:EntitiesDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\""
                + " cacheDuration=\"PT6H\"><md:EntitiesDescriptor validUntil=\"%s\" cacheDuration=\"PT1H\">"
                + strip(IDP.metadata())
                + "</md:EntitiesDescriptor></md:EntitiesDescriptor>";

        refused(nested.formatted("2020-01-01T00:00:00Z"), null, SamlIdpMetadata.Problem.EXPIRED, "validUntil");
        Instant later = Instant.parse("2099-01-01T00:00:00Z");
        SamlIdpMetadata.Idp idp = SamlIdpMetadata.parse(bytes(nested.formatted(later)), null, now);
        assertThat(idp.validUntil()).isEqualTo(later);
        assertThat(idp.cacheDuration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void anIdpWithoutARedirectServiceOrASigningKeyIsRefused() {
        refused(IDP.metadata().replace("HTTP-Redirect", "HTTP-POST"), null, SamlIdpMetadata.Problem.NO_IDP,
                "HTTP-Redirect");
        refused(IDP.metadata().replace("Location=\"https://idp.test/sso\"", "Location=\"http://idp.test/sso\""), null,
                SamlIdpMetadata.Problem.NO_IDP, "https HTTP-Redirect");
        refused(IDP.metadata().replace("use=\"signing\"", "use=\"encryption\""), null, SamlIdpMetadata.Problem.NO_IDP,
                "no signing certificate");
    }

    @Test
    void aDoctypeIsRefusedAsTheResponsesAre() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE md:EntityDescriptor [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + strip(IDP.metadata());

        refused(xxe, null, SamlIdpMetadata.Problem.INVALID, "not SAML metadata");
    }

    @Test
    void aDocumentOverOneMebibyteOrNotMetadataIsRefused() {
        byte[] large = new byte[SamlIdpMetadata.MAX_BYTES + 1];
        assertThatThrownBy(() -> SamlIdpMetadata.parse(large, null, now))
            .isInstanceOfSatisfying(SamlIdpMetadata.RefusedException.class,
                    ex -> assertThat(ex.problem()).isEqualTo(SamlIdpMetadata.Problem.TOO_LARGE));
        refused("<html><body>Sign in</body></html>", null, SamlIdpMetadata.Problem.INVALID, "not SAML metadata");
    }

    /**
     * H4 of the security review of 2026-10-27: without {@code _ENTITY_ID}, a document that names another IdP than the
     * provider's first good one is refused; with it, the setting is the pin.
     */
    @Test
    void aDocumentThatNamesAnotherIdpThanThePinnedOneIsRefused() throws Exception {
        SamlIdpMetadata.Idp other = SamlIdpMetadata.parse(
                bytes(IDP.metadata().replace("https://idp.test/metadata", "https://idp.test/other")), null, now);

        assertThatThrownBy(() -> SamlIdpMetadata.requirePinned(other, null, "https://idp.test/metadata"))
            .isInstanceOfSatisfying(SamlIdpMetadata.RefusedException.class, ex -> {
                assertThat(ex.problem()).isEqualTo(SamlIdpMetadata.Problem.ENTITY_ID_CHANGED);
                assertThat(ex.entityId()).isEqualTo("https://idp.test/other");
            })
            .hasMessageContaining("set _ENTITY_ID");
        // The first document pins; the same IdP again, or the one _ENTITY_ID names, passes.
        SamlIdpMetadata.requirePinned(other, null, null);
        SamlIdpMetadata.requirePinned(other, null, "https://idp.test/other");
        SamlIdpMetadata.requirePinned(other, "https://idp.test/other", "https://idp.test/metadata");
    }

    private void refused(String document, String entityId, SamlIdpMetadata.Problem problem, String message) {
        assertThatThrownBy(() -> SamlIdpMetadata.parse(bytes(document), entityId, now))
            .isInstanceOfSatisfying(SamlIdpMetadata.RefusedException.class,
                    ex -> assertThat(ex.problem()).isEqualTo(problem))
            .hasMessageContaining(message);
    }

    private static String strip(String metadata) {
        return metadata.replaceFirst("<\\?xml[^>]*\\?>", "");
    }

    private static byte[] bytes(String document) {
        return document.getBytes(StandardCharsets.UTF_8);
    }
}
