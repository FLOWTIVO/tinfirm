package com.flowtivo.tinfirm.system.internal.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Google's fixed endpoints of the Gmail add-on (ADR-0083): the outbound profile of the add-on is these and nothing
 * else. Not configured in production: the properties exist so that the tests reach a stub on {@code localhost}
 * ({@code tinfirm.gmail.endpoints.*}, no variables), and the server refuses to start with one changed outside the
 * {@code test} and {@code e2e} profiles ({@link FixedEndpoints}).
 *
 * @param googleKeys the JWK set of the keys Google signs its ID tokens with
 * @param gmailApi   the Gmail API's root
 */
@ConfigurationProperties("tinfirm.gmail.endpoints")
public record GmailEndpoints(@DefaultValue("https://www.googleapis.com/oauth2/v3/certs") String googleKeys,
        @DefaultValue("https://gmail.googleapis.com") String gmailApi) {
}
