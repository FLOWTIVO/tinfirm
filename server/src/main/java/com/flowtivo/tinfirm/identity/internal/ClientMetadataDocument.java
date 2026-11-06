package com.flowtivo.tinfirm.identity.internal;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;

/**
 * Tinfirm's own Client ID Metadata Document (ADR-0049 §6, CONVENTIONS §4 and §14.13), at
 * {@code {TINFIRM_PUBLIC_URL}/oauth/client-metadata.json}: Tinfirm's client id at the authorization server of an MCP
 * system that advertises {@code client_id_metadata_document_supported}, which fetches it to learn who the client is.
 * Its {@code client_id} is its own URL, the client is {@code Tinfirm}, a public client ({@code none}) with PKCE, the
 * authorization code and refresh token grants, and one redirect URI, the one OAuth callback of accounts on systems
 * ({@code /api/oauth/callback}, the account module's; the package 1 contract §6). Public (no session, no CSRF: a
 * {@code GET}) and cacheable for an hour, the most Tinfirm's own authorization server caches such a document.
 * <p>
 * Not to be confused with {@code /oauth2/**}, Tinfirm's authorization server for people's clients (ADR-0045).
 */
@Configuration(proxyBeanMethods = false)
class ClientMetadataDocument {

    /** Where the document is served, under the public URL. */
    static final String PATH = "/oauth/client-metadata.json";

    /** The one OAuth callback of accounts on systems (the account module's, {@code GET /api/oauth/callback}). */
    static final String CALLBACK_PATH = "/api/oauth/callback";

    static final Duration MAX_AGE = Duration.ofHours(1);

    @Bean
    RouterFunction<ServerResponse> clientMetadataDocumentRoute(TinfirmProperties tinfirm) {
        Map<String, Object> document = document(tinfirm.publicBaseUrl());
        return RouterFunctions.route(RequestPredicates.GET(PATH), request -> ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.maxAge(MAX_AGE).cachePublic())
            .body(document));
    }

    /** The document for the public URL (without a trailing slash). */
    static Map<String, Object> document(String publicBaseUrl) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("client_id", publicBaseUrl + PATH);
        document.put("client_name", "Tinfirm");
        document.put("client_uri", publicBaseUrl);
        document.put("redirect_uris", List.of(publicBaseUrl + CALLBACK_PATH));
        document.put("grant_types", List.of("authorization_code", "refresh_token"));
        document.put("response_types", List.of("code"));
        document.put("token_endpoint_auth_method", "none");
        return Collections.unmodifiableMap(document);
    }
}
