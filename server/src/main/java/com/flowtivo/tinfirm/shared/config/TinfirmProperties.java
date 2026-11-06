package com.flowtivo.tinfirm.shared.config;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Instance-wide settings.
 *
 * @param publicUrl external base URL ({@code TINFIRM_PUBLIC_URL}); drives OIDC redirects and the {@code Secure} cookie flag
 * @param roles     server roles running in this process ({@code TINFIRM_ROLES}); by default {@code api,worker}, both
 *                  in one replica
 */
@Validated
@ConfigurationProperties("tinfirm")
public record TinfirmProperties(@NotNull URI publicUrl, @NotEmpty Set<String> roles) {

    /** Server role that runs the dispatcher and the reaper. */
    public static final String WORKER_ROLE = "worker";

    /** Whether this process runs the given server role ({@code api} or {@code worker}). */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** Whether cookies must carry the {@code Secure} attribute. */
    public boolean secureCookies() {
        return "https".equalsIgnoreCase(publicUrl.getScheme());
    }

    /** The public URL without a trailing slash. */
    public String publicBaseUrl() {
        String url = publicUrl.toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The web app's page of a work item, the route {@code /c/:companyId/work/:workItemId}, which change requests and
     * the comments on the source link to (it asks for a login first when needed).
     */
    public String workItemUrl(UUID companyId, UUID workItemId) {
        return publicBaseUrl() + "/c/" + companyId + "/work/" + workItemId;
    }
}
