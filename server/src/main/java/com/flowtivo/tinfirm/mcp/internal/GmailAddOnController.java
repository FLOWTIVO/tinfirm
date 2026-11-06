package com.flowtivo.tinfirm.mcp.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.mcp.internal.GoogleIdTokens.Verified;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Google's requests to the Gmail add-on (ADR-0083, decisions 1 and 2): {@code POST /api/gmail/{companyId}/home},
 * {@code /open} and {@code /act}, outside the session chain ({@link GmailConfiguration}). Each is verified before
 * anything else is read: 404 while the operator has not configured the add-on or for an unknown company, 413 beyond
 * 256 KiB (read raw, never more), 400 for a body that is not JSON, 401 when Google's tokens do not verify (logged with why, never with a
 * token). A verified request is answered with a card by the add-on, a paid feature's code under {@code ee/}
 * ({@link GmailAddOn}), which the core finds when used; without the licence's {@code GMAIL_ADDON} (ADR-0077), or without
 * {@code ee/}, a card that says so.
 */
@RestController
class GmailAddOnController {

    private static final Logger log = LoggerFactory.getLogger(GmailAddOnController.class);

    static final Set<String> TRIGGERS = Set.of("home", "open", "act");

    static final int MAX_BODY = 256 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GmailAddOnProperties settings;

    private final GoogleIdTokens tokens;

    private final ObjectProvider<GmailAddOn> addOn;

    private final CompanyNames companies;

    private final TinfirmProperties tinfirm;

    GmailAddOnController(GmailAddOnProperties settings, GoogleIdTokens tokens, ObjectProvider<GmailAddOn> addOn,
            CompanyNames companies, TinfirmProperties tinfirm) {
        this.settings = settings;
        this.tokens = tokens;
        this.addOn = addOn;
        this.companies = companies;
        this.tinfirm = tinfirm;
    }

    /** The company's endpoint of a trigger: {@code home}, {@code open} or {@code act}, the audience of its token. */
    static String endpoint(String base, UUID companyId, String trigger) {
        return base + "/api/gmail/" + companyId + "/" + trigger;
    }

    @PostMapping(path = "/api/gmail/{companyId}/{trigger}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> handle(@PathVariable String companyId, @PathVariable String trigger,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) @Nullable String authorization,
            HttpServletRequest request) throws IOException {
        UUID company = uuid(companyId);
        if (!settings.configured() || company == null || !TRIGGERS.contains(trigger)) {
            return ResponseEntity.notFound().build();
        }
        Optional<byte[]> body = body(request);
        if (body.isEmpty()) {
            return ResponseEntity.status(413).build();
        }
        JsonNode event = parse(body.get());
        if (event == null) {
            return ResponseEntity.badRequest().build();
        }
        GmailAddOn.Event parsed = GmailAddOn.Event.of(event);
        String endpoint = endpoint(tinfirm.publicBaseUrl(), company, trigger);
        String systemToken = bearer(authorization).orElse(parsed.systemIdToken());
        Verified verified = tokens.verify(settings, systemToken, parsed.userIdToken(), endpoint);
        if (verified.account() == null) {
            log.warn("A Gmail add-on request for company {} was refused: {}", company, verified.problem());
            return ResponseEntity.status(401).build();
        }
        Optional<String> name = companies.nameOf(company);
        if (name.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        GmailAddOn found = addOn.getIfAvailable();
        if (found == null) {
            // Community without ee/ (ADR-0077, decision 4): said in the card, once Google's request verified.
            return ResponseEntity.ok(GmailAddOn.unlicensed(trigger));
        }
        return ResponseEntity.ok(found.handle(company, name.get(), trigger, parsed, verified.account()));
    }

    private static Optional<String> bearer(@Nullable String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = authorization.substring(7).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    /** At most {@value #MAX_BODY} bytes, read raw; empty beyond it. */
    private static Optional<byte[]> body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY) {
            return Optional.empty();
        }
        InputStream in = request.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
            total += read;
            if (total > MAX_BODY) {
                return Optional.empty();
            }
            out.write(buffer, 0, read);
        }
        return Optional.of(out.toByteArray());
    }

    private static @Nullable JsonNode parse(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode tree = JSON.readTree(body);
            return tree != null && tree.isObject() ? tree : null;
        }
        catch (JacksonException ex) {
            return null;
        }
    }

    private static @Nullable UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
