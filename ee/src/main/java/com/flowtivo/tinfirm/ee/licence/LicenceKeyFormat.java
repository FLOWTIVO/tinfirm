package com.flowtivo.tinfirm.ee.licence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.licence.Edition;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The licence key's format (ADR-0077, decision 6): {@code TINFIRM1.<base64url(payload)>.<base64url(signature)>},
 * the payload a JSON object, the signature Ed25519 over the payload's bytes as the key carries them.
 * <p>
 * The payload: {@code {"v":2, "kid", "id", "rev", "customer", "edition":"PRO"|"ENTERPRISE", "seats": int|null,
 * "companies": int|null, "startsAt":"YYYY-MM-DD", "endsAt":"YYYY-MM-DD"}}. {@code kid} names the public key the key is
 * signed with ({@link LicenceTrust}): a key whose id the server does not trust is refused, and the signature is
 * verified against that key alone. A Pro key holds a seat count and no cap of companies; an Enterprise key may hold
 * either. Unknown fields are refused, so that a later version's key is never read as this one. Version 1, without
 * {@code kid}, is refused.
 */
final class LicenceKeyFormat {

    static final String PREFIX = "TINFIRM1.";

    /** The most seats or companies a key holds: far beyond any price list, so that a sum never overflows. */
    static final int MAX_COUNT = 1_000_000;

    private static final Pattern FORM = Pattern.compile("^TINFIRM1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

    /** A key id: the name of a file of {@code ee/licence-public-keys/} without {@code .pub}. */
    static final Pattern KEY_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private static final Set<String> FIELDS = Set.of("v", "kid", "id", "rev", "customer", "edition", "seats", "companies",
            "startsAt", "endsAt");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LicenceKeyFormat() {
    }

    /** Why a key is not one of FLOWTIVO's: the message is safe to show, and never holds the key. */
    static final class InvalidKey extends Exception {

        InvalidKey(String message) {
            super(message, null, false, false);
        }
    }

    /** The key without the white space around it, as people paste it. */
    static String normalise(String key) {
        return key.strip();
    }

    /** The key's payload, once its signature verifies against the trusted public key it names. */
    static LicencePayload verify(String key, Map<String, PublicKey> trusted) throws InvalidKey {
        if (key.length() > 4096 || !FORM.matcher(key).matches()) {
            throw new InvalidKey("This is not a Tinfirm licence key: it starts with TINFIRM1. and has two parts");
        }
        String[] parts = key.substring(PREFIX.length()).split("\\.");
        byte[] payload;
        byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(parts[0]);
            signature = Base64.getUrlDecoder().decode(parts[1]);
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidKey("This is not a Tinfirm licence key: its parts are not base64url");
        }
        String keyId = keyId(payload);
        if (keyId == null) {
            throw new InvalidKey("This key does not name the FLOWTIVO key it is signed with: it is not signed by "
                    + "FLOWTIVO, or is of a format this Tinfirm does not read");
        }
        PublicKey publicKey = trusted.get(keyId);
        if (publicKey == null) {
            throw new InvalidKey("This key is signed with a FLOWTIVO key this Tinfirm does not trust (" + keyId
                    + "): update Tinfirm, or ask FLOWTIVO for a new key");
        }
        if (!signedBy(payload, signature, publicKey)) {
            throw new InvalidKey("This key is not signed by FLOWTIVO");
        }
        return parse(payload);
    }

    /**
     * The key id the payload names, read before its signature is verified, to choose the public key: null when the
     * payload is not a JSON object with a valid {@code kid}. {@link #parse} reads it again, once the signature holds.
     */
    private static @Nullable String keyId(byte[] payload) {
        try {
            JsonNode node = JSON.readTree(new String(payload, StandardCharsets.UTF_8));
            JsonNode kid = node != null && node.isObject() ? node.get("kid") : null;
            return kid != null && kid.isString() && KEY_ID.matcher(kid.asString()).matches() ? kid.asString() : null;
        }
        catch (JacksonException ex) {
            return null;
        }
    }

    private static boolean signedBy(byte[] payload, byte[] signature, PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            return verifier.verify(signature);
        }
        catch (GeneralSecurityException ex) {
            // A signature of the wrong length, or a key of another algorithm: not this key's signer.
            return false;
        }
    }

    static LicencePayload parse(byte[] payload) throws InvalidKey {
        JsonNode node;
        try {
            node = JSON.readTree(new String(payload, StandardCharsets.UTF_8));
        }
        catch (JacksonException ex) {
            throw new InvalidKey("The key's payload is not JSON");
        }
        if (node == null || !node.isObject()) {
            throw new InvalidKey("The key's payload is not a JSON object");
        }
        for (String field : node.propertyNames()) {
            if (!FIELDS.contains(field)) {
                throw new InvalidKey("The key's payload has an unknown field: " + field);
            }
        }
        if (!node.path("v").isInt() || node.path("v").asInt() != 2) {
            throw new InvalidKey("The key's payload is not of version 2: update Tinfirm to read it");
        }
        if (!KEY_ID.matcher(text(node, "kid", 64)).matches()) {
            throw new InvalidKey("The key's kid is not valid");
        }
        String id = text(node, "id", 100);
        if (!ID.matcher(id).matches()) {
            throw new InvalidKey("The key's id is not valid");
        }
        Integer rev = count(node, "rev");
        if (rev == null) {
            throw new InvalidKey("The key's rev is missing");
        }
        String customer = text(node, "customer", 200);
        Edition edition = switch (text(node, "edition", 16)) {
            case "PRO" -> Edition.PRO;
            case "ENTERPRISE" -> Edition.ENTERPRISE;
            default -> throw new InvalidKey("The key's edition is neither PRO nor ENTERPRISE");
        };
        Integer seats = count(node, "seats");
        Integer companies = count(node, "companies");
        if (edition == Edition.PRO && (seats == null || companies != null)) {
            throw new InvalidKey("A Pro key holds a seat count and no count of companies");
        }
        LocalDate startsAt = date(node, "startsAt");
        LocalDate endsAt = date(node, "endsAt");
        if (endsAt.isBefore(startsAt)) {
            throw new InvalidKey("The key ends before it starts");
        }
        return new LicencePayload(id, rev, customer, edition, seats, companies, startsAt, endsAt);
    }

    private static String text(JsonNode node, String field, int max) throws InvalidKey {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank() || value.asString().length() > max) {
            throw new InvalidKey("The key's " + field + " is missing or not valid");
        }
        return value.asString().strip();
    }

    private static @Nullable Integer count(JsonNode node, String field) throws InvalidKey {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isInt() || value.asInt() < 1 || value.asInt() > MAX_COUNT) {
            throw new InvalidKey("The key's " + field + " is not a positive count");
        }
        return value.asInt();
    }

    private static LocalDate date(JsonNode node, String field) throws InvalidKey {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new InvalidKey("The key's " + field + " is missing");
        }
        try {
            return LocalDate.parse(value.asString());
        }
        catch (DateTimeParseException ex) {
            throw new InvalidKey("The key's " + field + " is not a date (YYYY-MM-DD)");
        }
    }
}
