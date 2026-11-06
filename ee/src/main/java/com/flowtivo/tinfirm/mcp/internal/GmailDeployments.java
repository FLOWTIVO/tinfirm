package com.flowtivo.tinfirm.mcp.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.GmailDeployment;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The deployment of Tinfirm's Gmail add-on (ADR-0083, decision 8), which a Google Workspace administrator creates in
 * the instance's Google Cloud project with {@code gcloud workspace-add-ons deployments create}: one per company, since
 * its endpoints name the company. It asks for the add-on's execution in Gmail, the open message read only, drafts in
 * the open message's thread while the person acts (an answer used as a reply), and the account's email (the person's
 * ID token); its homepage and its one contextual trigger, for every email opened, are the company's endpoints; its
 * links may open the public URL and Gmail only. Every member may download it; nothing in it is secret.
 * <p>
 * The add-on is Pro's and Enterprise's (ADR-0077, decision 4, {@code GMAIL_ADDON}): a member learns it after the
 * access check.
 */
@Component
class GmailDeployments {

    static final String FILE_NAME = "tinfirm-gmail-deployment.json";

    static final List<String> SCOPES = List.of("https://www.googleapis.com/auth/gmail.addons.execute",
            "https://www.googleapis.com/auth/gmail.addons.current.message.readonly",
            "https://www.googleapis.com/auth/gmail.addons.current.action.compose",
            "https://www.googleapis.com/auth/userinfo.email");

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final CompanyAccessGuard guard;

    private final TinfirmProperties tinfirm;

    private final GmailAddOnProperties settings;

    private final Licensing licensing;

    GmailDeployments(CompanyAccessGuard guard, TinfirmProperties tinfirm, GmailAddOnProperties settings,
            Licensing licensing) {
        this.guard = guard;
        this.tinfirm = tinfirm;
        this.settings = settings;
        this.licensing = licensing;
    }

    @Transactional(readOnly = true)
    GmailDeployment deployment(UUID companyId) {
        guard.require(companyId, CompanyPermission.VIEW_COMPANY);
        licensing.require(Feature.GMAIL_ADDON);
        return new GmailDeployment(FILE_NAME, json(tinfirm.publicBaseUrl(), companyId), settings.configured());
    }

    private static String endpoint(String base, UUID companyId, String trigger) {
        return GmailAddOnController.endpoint(base, companyId, trigger);
    }

    static String json(String base, UUID companyId) {
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("name", "Tinfirm");
        common.put("logoUrl", base + "/outlook/icon-128.png");
        common.put("layoutProperties", Map.of("primaryColor", "#1f2937"));
        common.put("homepageTrigger", Map.of("runFunction", endpoint(base, companyId, "home")));
        common.put("openLinkUrlPrefixes", List.of(base + "/", "https://mail.google.com/"));
        Map<String, Object> addOns = new LinkedHashMap<>();
        addOns.put("common", common);
        addOns.put("gmail", Map.of("contextualTriggers", List.of(Map.of("unconditional", Map.of(),
                "onTriggerFunction", endpoint(base, companyId, "open")))));
        addOns.put("httpOptions", Map.of("authorizationHeader", "SYSTEM_ID_TOKEN"));
        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("oauthScopes", SCOPES);
        deployment.put("addOns", addOns);
        return JSON.writeValueAsString(deployment) + "\n";
    }
}
