package com.flowtivo.tinfirm.identity.internal;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The Outlook add-in's pages (ADR-0074): the hosts that may frame its task pane, Outlook on the web and the new Outlook
 * for Windows, beside Tinfirm's own origin ({@code TINFIRM_OUTLOOK_FRAME_ANCESTORS}). Classic Outlook on Windows does
 * not frame the pane; a national cloud's Outlook needs its hosts here.
 *
 * @param frameAncestors https origins, each optionally with a leading {@code *.} on its host, which the add-in's CSP
 *                       names in {@code frame-ancestors}
 */
@Validated
@ConfigurationProperties("tinfirm.outlook")
record OutlookAddInProperties(@NotEmpty @DefaultValue({ "https://outlook.office.com", "https://outlook.office365.com",
        "https://outlook.live.com", "https://outlook.cloud.microsoft" }) List<@Pattern(regexp = ORIGIN,
                message = "must be an https origin such as https://outlook.office.com") String> frameAncestors) {

    /** An https origin, a host of letters, digits, dots and hyphens, perhaps behind {@code *.}, and a port. */
    static final String ORIGIN = "https://(\\*\\.)?[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*(:[0-9]{1,5})?";

    /**
     * Office.js and the files it loads beside itself, which an add-in must load from Microsoft's CDN: the path
     * {@code /lib/1/hosted/} only, not the whole host (a CSP path ending in a slash matches everything below it).
     */
    static final String OFFICE_JS = "https://appsforoffice.microsoft.com/lib/1/hosted/";

    /**
     * MicrosoftAjax.js, which Office.js loads from Microsoft's Ajax CDN for Outlook before it starts; refused, Office.js
     * stops with "Not able to load MicrosoftAjax.js" and the add-in never sees Outlook. The exact file, not the host:
     * the CDN serves every ASP.NET library of Microsoft, and the one URL is what Office.js loads
     * ({@code //ajax.aspnetcdn.com/ajax/3.5/MicrosoftAjax.js}). A new version of Office.js that loads another file shows
     * as "Not able to load MicrosoftAjax.js" and must be checked in a real Outlook.
     */
    static final String MICROSOFT_AJAX = "https://ajax.aspnetcdn.com/ajax/3.5/MicrosoftAjax.js";

    /**
     * The add-in's CSP: the strict one of every other page (CONVENTIONS §5), with Office.js in {@code script-src} and
     * {@code connect-src}, MicrosoftAjax.js in {@code script-src}, and Tinfirm itself and Outlook's hosts in
     * {@code frame-ancestors}.
     */
    String contentSecurityPolicy() {
        return "default-src 'self'; script-src 'self' " + OFFICE_JS + " " + MICROSOFT_AJAX
                + "; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; font-src 'self' data:; connect-src 'self' " + OFFICE_JS + "; "
                + "frame-ancestors 'self' " + String.join(" ", frameAncestors) + "; base-uri 'self'; "
                + "form-action 'self'; object-src 'none'";
    }
}
