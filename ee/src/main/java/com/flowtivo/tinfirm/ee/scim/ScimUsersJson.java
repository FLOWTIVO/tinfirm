package com.flowtivo.tinfirm.ee.scim;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.company.scim.ScimException;
import com.flowtivo.tinfirm.company.scim.ScimMembers;
import com.flowtivo.tinfirm.identity.ScimUsers;
import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * A member as SCIM's {@code User} (ADR-0087, decision 4; RFC 7643 §4.1), and the values of a request read as SCIM's
 * clients send them: Entra ID's strings for booleans ({@code "False"}), names and operators in any case.
 */
final class ScimUsersJson {

    static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    static final String EXTENSION = "urn:ietf:params:scim:schemas:extension:tinfirm:2.0:User";

    static final String ROLE_PATH = EXTENSION + ":role";

    static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";

    static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    /** A user's name holds at most this many characters, as the identity module's. */
    static final int MAX_NAME = 100;

    static final int MAX_EMAIL = 320;

    static final int MAX_EXTERNAL_ID = 255;

    private ScimUsersJson() {
    }

    /** The member as it is stored. {@code active} is whether the membership is in effect (decision 4). */
    static Map<String, Object> user(ScimMembers.Member member, ScimUsers.ScimUser user, String baseUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(USER_SCHEMA, EXTENSION));
        body.put("id", user.id().toString());
        if (member.externalId() != null) {
            body.put("externalId", member.externalId());
        }
        body.put("userName", user.email());
        body.put("name", Map.of("formatted", user.displayName()));
        body.put("displayName", user.displayName());
        body.put("emails", List.of(Map.of("value", user.email(), "type", "work", "primary", true)));
        body.put("active", !member.suspended() && user.active());
        body.put(EXTENSION, Map.of("role", member.role().name().toLowerCase(Locale.ROOT)));
        Instant modified = member.updatedAt().isAfter(user.updatedAt()) ? member.updatedAt() : user.updatedAt();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resourceType", "User");
        meta.put("created", timestamp(member.createdAt()));
        meta.put("lastModified", timestamp(modified));
        meta.put("location", baseUrl + "/Users/" + user.id());
        body.put("meta", meta);
        return body;
    }

    static Map<String, Object> list(List<Map<String, Object>> resources, int total, int startIndex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(LIST_SCHEMA));
        body.put("totalResults", total);
        body.put("startIndex", startIndex);
        body.put("itemsPerPage", resources.size());
        body.put("Resources", resources);
        return body;
    }

    static String timestamp(Instant at) {
        return DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.MILLIS));
    }

    /** A string value; a number or boolean as its text; null for null. */
    static @Nullable String text(@Nullable Object value, String attribute) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        throw ScimException.invalidValue(attribute + " must be a string");
    }

    /** A boolean, or the strings {@code "true"} and {@code "false"} in any case, as Entra ID sends them. */
    static boolean bool(@Nullable Object value, String attribute) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(text.trim())) {
                return false;
            }
        }
        throw ScimException.invalidValue(attribute + " must be true or false");
    }

    /**
     * A role of the extension's {@code role}, in any case: {@code admin}, {@code member} or {@code viewer};
     * {@code owner} or anything else is {@code 400 invalidValue} (decision 4).
     */
    static CompanyRole role(@Nullable Object value) {
        String text = text(value, "role");
        if (text != null) {
            switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "admin" -> {
                    return CompanyRole.ADMIN;
                }
                case "member" -> {
                    return CompanyRole.MEMBER;
                }
                case "viewer" -> {
                    return CompanyRole.VIEWER;
                }
                default -> {
                }
            }
        }
        throw ScimException.invalidValue("role must be admin, member or viewer");
    }

    /** {@code userName}: an email, in lower case. */
    static String userName(@Nullable Object value) {
        String text = text(value, "userName");
        String email = text != null ? text.trim().toLowerCase(Locale.ROOT) : "";
        int at = email.indexOf('@');
        if (email.isEmpty() || at <= 0 || at == email.length() - 1 || email.length() > MAX_EMAIL
                || email.chars().anyMatch(Character::isWhitespace)) {
            throw ScimException.invalidValue("userName must be the user's email address");
        }
        return email;
    }

    /** {@code externalId}: required and not empty (decision 4), since a sign-in binds by it. */
    static String externalId(@Nullable Object value) {
        String text = text(value, "externalId");
        String id = text != null ? text.trim() : "";
        if (id.isEmpty() || id.length() > MAX_EXTERNAL_ID) {
            throw ScimException.invalidValue("externalId is required, 1 to " + MAX_EXTERNAL_ID + " characters");
        }
        return id;
    }

    /**
     * A user's name from SCIM's: {@code displayName}, else {@code name.formatted}, else the given and family names
     * joined, else the email's local part (decision 4); at most 100 characters.
     */
    static String displayName(@Nullable String displayName, @Nullable String formatted, @Nullable String givenName,
            @Nullable String familyName, String email) {
        String name = blankToNull(displayName);
        if (name == null) {
            name = blankToNull(formatted);
        }
        if (name == null) {
            String joined = ((givenName != null ? givenName.trim() : "") + " "
                    + (familyName != null ? familyName.trim() : "")).trim();
            name = blankToNull(joined);
        }
        if (name == null) {
            name = email.substring(0, Math.max(1, email.indexOf('@')));
        }
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** A JSON object's member by name, case-insensitive (RFC 7643 §2.1). */
    static @Nullable Object member(Map<String, Object> object, String name) {
        if (object.containsKey(name)) {
            return object.get(name);
        }
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Whether the object holds a member of this name, case-insensitive. */
    static boolean has(Map<String, Object> object, String name) {
        return object.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    @SuppressWarnings("unchecked")
    static @Nullable Map<String, Object> object(@Nullable Object value, String attribute) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw ScimException.invalidValue(attribute + " must be an object");
    }
}
