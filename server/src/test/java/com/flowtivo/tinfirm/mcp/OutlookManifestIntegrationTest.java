package com.flowtivo.tinfirm.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * The Outlook add-in's manifest (ADR-0074): one per company, for every member, the same on every download, naming the
 * company's task pane at the public URL ({@code http://localhost:8080} in tests).
 */
@ExtendWith(EnterpriseLicence.class)
class OutlookManifestIntegrationTest extends IntegrationTest {

    @Test
    void everyMemberDownloadsTheCompanysManifest() throws Exception {
        TestCompany company = companyWithRoles();
        String path = "/api/companies/" + company.id() + "/outlook/manifest";
        ApiResponse response = loginAs(company.email("MEMBER")).get(path);
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        JsonNode body = response.json();
        String taskPane = "http://localhost:8080/outlook/" + company.id();
        assertThat(body.get("fileName").asString()).isEqualTo("tinfirm-outlook-manifest.xml");
        assertThat(body.get("taskPaneUrl").asString()).isEqualTo(taskPane);

        Document manifest = parse(body.get("manifest").asString());
        Element root = manifest.getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo("OfficeApp");
        assertThat(text(manifest, "Id")).isEqualTo(body.get("addInId").asString());
        assertThat(text(manifest, "Permissions")).isEqualTo("ReadItem");
        assertThat(text(manifest, "AppDomain")).isEqualTo("http://localhost:8080");
        assertThat(((Element) manifest.getElementsByTagNameNS("*", "SourceLocation").item(0)).getAttribute(
                "DefaultValue")).isEqualTo(taskPane);
        assertThat(body.get("manifest").asString()).contains("<SupportsPinning>true</SupportsPinning>")
            .contains("http://localhost:8080/outlook/icon-80.png");

        // The same add-in on every download and for every member; another company's is another add-in.
        ApiResponse again = loginAs(company.email("OWNER")).get(path);
        assertThat(again.json().get("addInId").asString()).isEqualTo(body.get("addInId").asString());
        TestCompany other = companyWithRoles();
        ApiResponse otherManifest = loginAs(other.email("MEMBER")).get("/api/companies/" + other.id()
                + "/outlook/manifest");
        assertThat(otherManifest.json().get("addInId").asString()).isNotEqualTo(body.get("addInId").asString());

        // Someone outside the company does not see it.
        assertThat(loginAs(other.email("MEMBER")).get(path).status()).isEqualTo(404);
        assertThat(client().get(path).status()).isEqualTo(401);
        assertThat(UUID.fromString(body.get("addInId").asString())).isNotNull();
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(Document document, String localName) {
        return document.getElementsByTagNameNS("*", localName).item(0).getTextContent().trim();
    }
}
