package com.flowtivo.tinfirm.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.core.Response;

import com.flowtivo.tinfirm.identity.internal.SamlProperties;

/** Who an assertion says the person is (ADR-0086, decision 5): the subject, the email, the name, the role's values. */
class SamlPersonTest {

    private static final SamlTestIdp IDP = new SamlTestIdp("https://idp.test/person");

    @Test
    void theNameIdIsTheSubjectAndTheFirstEmailAttributeInOrderWins() {
        SamlPerson person = person(provider(SamlProperties.Provider.NAMEID, null), r -> {
            r.nameId = "00u1abcd";
            r.attribute("mail", "second@example.com");
            r.attribute("email", "not an email", "first@example.com");
            r.attribute("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress", "third@example.com");
        });

        assertThat(person.subject()).isEqualTo("00u1abcd");
        // email holds no address in its first value, so mail, the next attribute, gives it.
        assertThat(person.email()).isEqualTo("second@example.com");
        assertThat(person.emailAttribute()).isEqualTo("mail");
    }

    @Test
    void anEmailAddressNameIdIsTheEmailWhenNoAttributeGivesOne() {
        SamlPerson person = person(provider(SamlProperties.Provider.NAMEID, null), r -> {
            r.nameId = "ann@example.com";
            r.nameIdFormat = NameIDType.EMAIL;
        });

        assertThat(person.email()).isEqualTo("ann@example.com");
        assertThat(person.emailAttribute()).isEqualTo(SamlPerson.NAMEID);
    }

    @Test
    void theNameIsTheFirstNameAttributeElseTheGivenNameAndSurname() {
        assertThat(person(provider(SamlProperties.Provider.NAMEID, null), r -> {
            r.attribute("givenName", "Ann");
            r.attribute("http://schemas.microsoft.com/identity/claims/displayname", "Ann E.");
        }).name()).isEqualTo("Ann E.");
        assertThat(person(provider(SamlProperties.Provider.NAMEID, null), r -> {
            r.attribute("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/givenname", "Ann");
            r.attribute("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/surname", "Example");
        }).name()).isEqualTo("Ann Example");
        assertThat(person(provider(SamlProperties.Provider.NAMEID, null), r -> {
        }).name()).isNull();
    }

    @Test
    void anAttributeMayBeTheSubjectWithOneValueOnly() {
        SamlProperties.Provider byObjectId = provider("http://schemas.microsoft.com/identity/claims/objectidentifier",
                null);
        assertThat(person(byObjectId, r -> r.attribute("http://schemas.microsoft.com/identity/claims/objectidentifier",
                "6b0c7d1e")).subject()).isEqualTo("6b0c7d1e");
        refused(byObjectId, "invalid_response", r -> r
            .attribute("http://schemas.microsoft.com/identity/claims/objectidentifier", "a", "b"));
        refused(byObjectId, "invalid_response", r -> {
        });
    }

    @Test
    void aTransientOrOverlongNameIdIsRefused() {
        refused(provider(SamlProperties.Provider.NAMEID, null), "transient_subject",
                r -> r.nameIdFormat = NameIDType.TRANSIENT);
        refused(provider(SamlProperties.Provider.NAMEID, null), "invalid_response", r -> r.nameId = "x".repeat(256));
    }

    @Test
    void theRoleAttributeGivesEveryValue() {
        SamlPerson person = person(provider(SamlProperties.Provider.NAMEID, "groups"),
                r -> r.attribute("groups", "staff", "admins"));

        assertThat(person.roleValues()).containsExactly("staff", "admins");
    }

    private static void refused(SamlProperties.Provider provider, String reason,
            Consumer<SamlTestIdp.ResponseBuilder> change) {
        assertThatThrownBy(() -> person(provider, change)).isInstanceOfSatisfying(SamlRefusal.class,
                refusal -> assertThat(refusal.reason()).isEqualTo(reason));
    }

    private static SamlPerson person(SamlProperties.Provider provider, Consumer<SamlTestIdp.ResponseBuilder> change) {
        SamlTestIdp.ResponseBuilder builder = IDP.respond("https://sp.test", "https://sp.test/acs", "_r");
        builder.signResponse = false;
        builder.signAssertion = false;
        change.accept(builder);
        try {
            Response response = (Response) XMLObjectSupport.getUnmarshaller(builder.element())
                .unmarshall(builder.element());
            Assertion assertion = response.getAssertions().get(0);
            return SamlPerson.of(assertion, provider);
        }
        catch (org.opensaml.core.xml.io.UnmarshallingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A provider as the deployment declares it, with the defaults of decision 1 for the rest. */
    private static SamlProperties.Provider provider(String subject, String roleClaim) {
        java.util.Map<String, String> settings = new java.util.HashMap<>();
        settings.put("tinfirm.auth.saml.providers.corp.metadata-location", "https://idp.test/metadata");
        settings.put("tinfirm.auth.saml.providers.corp.subject", subject);
        if (roleClaim != null) {
            settings.put("tinfirm.auth.saml.providers.corp.role-claim", roleClaim);
            settings.put("tinfirm.auth.saml.providers.corp.role-map-member", "staff");
        }
        return new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(settings))
            .bind("tinfirm.auth.saml", SamlProperties.class)
            .get()
            .provider("corp");
    }
}
