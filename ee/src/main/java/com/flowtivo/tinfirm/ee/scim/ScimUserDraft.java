package com.flowtivo.tinfirm.ee.scim;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.company.scim.ScimException;

/**
 * What a request asks a member to be (ADR-0087, decision 4): read from a whole {@code User} ({@code POST},
 * {@code PUT}), or the member as stored with a {@code PatchOp}'s operations applied (RFC 7644 §3.5.2). The role is
 * {@code roleGiven} only when the request carries the extension's {@code role}, so that a request without it never
 * changes a role. Every attribute Tinfirm does not keep is accepted and ignored, {@code password} among them.
 */
final class ScimUserDraft {

    @Nullable String userName;

    @Nullable String externalId;

    @Nullable String displayName;

    @Nullable String formatted;

    @Nullable String givenName;

    @Nullable String familyName;

    boolean active = true;

    /** Whether the request carries the extension's {@code role}. */
    boolean roleGiven;

    /** The role's value as sent; read against the member, since an OWNER's is ignored. */
    @Nullable Object role;

    /** A whole user: what {@code POST} creates and {@code PUT} replaces with (RFC 7644 §3.5.1). */
    static ScimUserDraft of(Map<String, Object> body) {
        ScimUserDraft draft = new ScimUserDraft();
        if (!ScimUsersJson.has(body, "userName")) {
            throw ScimException.invalidValue("userName is required");
        }
        draft.userName = ScimUsersJson.userName(ScimUsersJson.member(body, "userName"));
        draft.externalId = ScimUsersJson.externalId(ScimUsersJson.member(body, "externalId"));
        draft.displayName = ScimUsersJson.text(ScimUsersJson.member(body, "displayName"), "displayName");
        Map<String, Object> name = ScimUsersJson.object(ScimUsersJson.member(body, "name"), "name");
        if (name != null) {
            draft.formatted = ScimUsersJson.text(ScimUsersJson.member(name, "formatted"), "name.formatted");
            draft.givenName = ScimUsersJson.text(ScimUsersJson.member(name, "givenName"), "name.givenName");
            draft.familyName = ScimUsersJson.text(ScimUsersJson.member(name, "familyName"), "name.familyName");
        }
        Object active = ScimUsersJson.member(body, "active");
        draft.active = active == null || ScimUsersJson.bool(active, "active");
        Map<String, Object> extension = ScimUsersJson.object(ScimUsersJson.member(body, ScimUsersJson.EXTENSION),
                ScimUsersJson.EXTENSION);
        if (extension != null && ScimUsersJson.member(extension, "role") != null) {
            draft.roleGiven = true;
            draft.role = ScimUsersJson.member(extension, "role");
        }
        if (ScimUsersJson.member(body, ScimUsersJson.ROLE_PATH) != null) {
            draft.roleGiven = true;
            draft.role = ScimUsersJson.member(body, ScimUsersJson.ROLE_PATH);
        }
        return draft;
    }

    /** The member as stored, which a {@code PatchOp} changes. */
    static ScimUserDraft stored(String email, @Nullable String externalId, String displayName, boolean scimActive) {
        ScimUserDraft draft = new ScimUserDraft();
        draft.userName = email;
        draft.externalId = externalId;
        draft.displayName = displayName;
        draft.active = scimActive;
        return draft;
    }

    /** Applies a {@code PatchOp}'s operations in order (RFC 7644 §3.5.2). */
    void patch(Map<String, Object> body) {
        Object operations = ScimUsersJson.member(body, "Operations");
        if (!(operations instanceof List<?> list) || list.isEmpty()) {
            throw ScimException.invalidSyntax("A PatchOp needs Operations");
        }
        for (Object item : list) {
            Map<String, Object> operation = ScimUsersJson.object(item, "Operations[]");
            if (operation == null) {
                throw ScimException.invalidSyntax("An operation must be an object");
            }
            String op = ScimUsersJson.text(ScimUsersJson.member(operation, "op"), "op");
            String kind = op != null ? op.trim().toLowerCase(Locale.ROOT) : "";
            if (!List.of("add", "replace", "remove").contains(kind)) {
                throw ScimException.invalidSyntax("op must be add, replace or remove");
            }
            boolean remove = "remove".equals(kind);
            String path = ScimUsersJson.text(ScimUsersJson.member(operation, "path"), "path");
            Object value = ScimUsersJson.member(operation, "value");
            if (path == null || path.isBlank()) {
                if (remove) {
                    throw new ScimException(400, "noTarget", "A remove needs a path");
                }
                Map<String, Object> values = ScimUsersJson.object(value, "value");
                if (values == null) {
                    throw ScimException.invalidSyntax("An operation without a path needs an object as its value");
                }
                values.forEach((name, each) -> apply(name, each, false, false));
            }
            else {
                apply(path.trim(), value, remove, "add".equals(kind));
            }
        }
    }

    private void apply(String rawPath, @Nullable Object rawValue, boolean remove, boolean add) {
        String path = rawPath;
        String core = ScimUsersJson.USER_SCHEMA + ":";
        if (path.regionMatches(true, 0, core, 0, core.length())) {
            path = path.substring(core.length());
        }
        Object value = single(rawValue);
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.equals(ScimUsersJson.EXTENSION.toLowerCase(Locale.ROOT))) {
            Map<String, Object> extension = ScimUsersJson.object(rawValue, ScimUsersJson.EXTENSION);
            if (!remove && extension != null && ScimUsersJson.member(extension, "role") != null) {
                roleGiven = true;
                role = ScimUsersJson.member(extension, "role");
            }
            return;
        }
        switch (lower) {
            case "username" -> {
                if (remove) {
                    throw ScimException.invalidValue("userName is required and cannot be removed");
                }
                userName = ScimUsersJson.userName(value);
            }
            case "externalid" -> {
                if (remove) {
                    throw ScimException.invalidValue("externalId is required and cannot be removed");
                }
                externalId = ScimUsersJson.externalId(value);
            }
            case "active" -> {
                if (!remove) {
                    active = ScimUsersJson.bool(value, "active");
                }
            }
            case "displayname" -> displayName = remove ? null : ScimUsersJson.text(value, "displayName");
            case "name" -> {
                if (remove) {
                    formatted = null;
                    givenName = null;
                    familyName = null;
                    return;
                }
                Map<String, Object> name = ScimUsersJson.object(rawValue, "name");
                if (name == null) {
                    return;
                }
                if (!add || ScimUsersJson.has(name, "formatted")) {
                    formatted = ScimUsersJson.text(ScimUsersJson.member(name, "formatted"), "name.formatted");
                }
                if (!add || ScimUsersJson.has(name, "givenName")) {
                    givenName = ScimUsersJson.text(ScimUsersJson.member(name, "givenName"), "name.givenName");
                }
                if (!add || ScimUsersJson.has(name, "familyName")) {
                    familyName = ScimUsersJson.text(ScimUsersJson.member(name, "familyName"), "name.familyName");
                }
            }
            case "name.formatted" -> formatted = remove ? null : ScimUsersJson.text(value, "name.formatted");
            case "name.givenname" -> givenName = remove ? null : ScimUsersJson.text(value, "name.givenName");
            case "name.familyname" -> familyName = remove ? null : ScimUsersJson.text(value, "name.familyName");
            default -> {
                if (lower.equals(ScimUsersJson.ROLE_PATH.toLowerCase(Locale.ROOT))) {
                    if (!remove && value != null) {
                        roleGiven = true;
                        role = value;
                    }
                }
                // Anything else (emails, which userName wins over, phoneNumbers, title, the enterprise extension,
                // password, groups) is accepted and ignored, never stored (decision 4).
            }
        }
    }

    /**
     * The value of a single-valued attribute: Entra ID, without {@code aadOptscim062020}, sends some as an array of one
     * {@code {"value": …}}.
     */
    private static @Nullable Object single(@Nullable Object value) {
        if (value instanceof List<?> list && list.size() == 1) {
            Object only = list.getFirst();
            if (only instanceof Map<?, ?> map && map.containsKey("value")) {
                return map.get("value");
            }
            return only;
        }
        return value;
    }

    /** The name the user gets: decision 4's order, else the email's local part. */
    String name(String email) {
        return ScimUsersJson.displayName(displayName, formatted, givenName, familyName, email);
    }
}
