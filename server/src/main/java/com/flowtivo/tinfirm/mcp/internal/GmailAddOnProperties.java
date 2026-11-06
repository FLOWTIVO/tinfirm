package com.flowtivo.tinfirm.mcp.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Pattern;

/**
 * The Gmail add-on's Google Cloud project (ADR-0083), as {@code gcloud workspace-add-ons get-authorization} names it:
 * the service account Google signs the add-on's requests as ({@code TINFIRM_GMAIL_SERVICE_ACCOUNT}), and the OAuth
 * client of the people's ID tokens ({@code TINFIRM_GMAIL_CLIENT_ID}). Configuration of the deployment, never of the app
 * (ADR-0075). Without both, the add-on's endpoints answer 404.
 *
 * @param serviceAccount the deployment's service account, {@code service-{number}@gcp-sa-gsuiteaddons.iam...}
 * @param clientId       the deployment's OAuth client id, {@code {digits}.apps.googleusercontent.com} or digits
 */
@Validated
@ConfigurationProperties("tinfirm.gmail")
record GmailAddOnProperties(
        @Nullable @Pattern(regexp = "^$|[^@\\s]+@[A-Za-z0-9.-]+",
                message = "must be the email of the add-on's service account") String serviceAccount,
        @Nullable @Pattern(regexp = "^$|[A-Za-z0-9._-]+", message = "must be the add-on's OAuth client id") String clientId) {

    /** Whether the operator set both, without which nothing verifies. */
    boolean configured() {
        return serviceAccount != null && !serviceAccount.isBlank() && clientId != null && !clientId.isBlank();
    }
}
