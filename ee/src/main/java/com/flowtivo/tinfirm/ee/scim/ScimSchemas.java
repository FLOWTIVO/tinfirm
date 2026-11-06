package com.flowtivo.tinfirm.ee.scim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.flowtivo.tinfirm.company.scim.ScimException;

/**
 * The endpoint's discovery documents (RFC 7643 §5, §6, §7): {@code ServiceProviderConfig}, the one
 * {@code ResourceType}, {@code User} with Tinfirm's extension, and the {@code Schema}s of the attributes Tinfirm keeps
 * (ADR-0087, decisions 1 and 4).
 */
final class ScimSchemas {

    static final int MAX_RESULTS = 100;

    private static final String CONFIG_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";

    private static final String RESOURCE_TYPE_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ResourceType";

    private static final String SCHEMA_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Schema";

    private ScimSchemas() {
    }

    static Map<String, Object> serviceProviderConfig(String baseUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(CONFIG_SCHEMA));
        body.put("documentationUri", "https://docs.tinfirm.com/admin-guide/scim/");
        body.put("patch", Map.of("supported", true));
        body.put("bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0));
        body.put("filter", Map.of("supported", true, "maxResults", MAX_RESULTS));
        body.put("changePassword", Map.of("supported", false));
        body.put("sort", Map.of("supported", false));
        body.put("etag", Map.of("supported", false));
        body.put("authenticationSchemes", List.of(Map.of("type", "oauthbearertoken", "name", "Bearer token",
                "description", "A SCIM token the company's OWNER issues in Tinfirm (tfs_…)", "primary", true)));
        body.put("meta", Map.of("resourceType", "ServiceProviderConfig", "location",
                baseUrl + "/ServiceProviderConfig"));
        return body;
    }

    static Map<String, Object> resourceTypes(String baseUrl) {
        return ScimUsersJson.list(List.of(userResourceType(baseUrl)), 1, 1);
    }

    /** The resource type at its {@code meta.location} (RFC 7644 §4): {@code User}, or {@code 404}. */
    static Map<String, Object> resourceType(String baseUrl, String name) {
        if (!"User".equals(name)) {
            throw ScimException.notFound("No resource type " + name);
        }
        return userResourceType(baseUrl);
    }

    private static Map<String, Object> userResourceType(String baseUrl) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("schemas", List.of(RESOURCE_TYPE_SCHEMA));
        user.put("id", "User");
        user.put("name", "User");
        user.put("endpoint", "/Users");
        user.put("description", "A member of the company");
        user.put("schema", ScimUsersJson.USER_SCHEMA);
        user.put("schemaExtensions", List.of(Map.of("schema", ScimUsersJson.EXTENSION, "required", false)));
        user.put("meta", Map.of("resourceType", "ResourceType", "location", baseUrl + "/ResourceTypes/User"));
        return user;
    }

    static Map<String, Object> schemas(String baseUrl) {
        List<Map<String, Object>> schemas = userSchemas(baseUrl);
        return ScimUsersJson.list(schemas, schemas.size(), 1);
    }

    /** The schema at its {@code meta.location} (RFC 7644 §4), by its URI; {@code 404} for any other. */
    static Map<String, Object> schema(String baseUrl, String id) {
        return userSchemas(baseUrl).stream()
            .filter(schema -> schema.get("id").equals(id))
            .findFirst()
            .orElseThrow(() -> ScimException.notFound("No schema " + id));
    }

    private static List<Map<String, Object>> userSchemas(String baseUrl) {
        Map<String, Object> core = schema(ScimUsersJson.USER_SCHEMA, "User", "A member of the company", List.of(
                attribute("userName", "string", true, false, "server", "The user's email; unique on the instance"),
                attribute("externalId", "string", true, true, "server",
                        "The identity provider's stable id of the person; unique in the company; binds the first "
                                + "sign-in"),
                complex("name", List.of(attribute("formatted", "string", false, false, "none", "The full name"),
                        attribute("givenName", "string", false, false, "none", "The given name"),
                        attribute("familyName", "string", false, false, "none", "The family name"))),
                attribute("displayName", "string", false, false, "none", "The name shown in Tinfirm"),
                multiValuedEmails(),
                attribute("active", "boolean", false, false, "none",
                        "Whether the membership is in effect; false suspends the member at once")),
                baseUrl);
        Map<String, Object> extension = schema(ScimUsersJson.EXTENSION, "Tinfirm User",
                "Tinfirm's attributes of a member", List.of(attribute("role", "string", false, false, "none",
                        "The member's role in the company: admin, member or viewer; owner is answered for an owner "
                                + "and never accepted")),
                baseUrl);
        return List.of(core, extension);
    }

    private static Map<String, Object> schema(String id, String name, String description,
            List<Map<String, Object>> attributes, String baseUrl) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("schemas", List.of(SCHEMA_SCHEMA));
        schema.put("id", id);
        schema.put("name", name);
        schema.put("description", description);
        schema.put("attributes", attributes);
        schema.put("meta", Map.of("resourceType", "Schema", "location", baseUrl + "/Schemas/" + id));
        return schema;
    }

    private static Map<String, Object> attribute(String name, String type, boolean required, boolean caseExact,
            String uniqueness, String description) {
        Map<String, Object> attribute = new LinkedHashMap<>();
        attribute.put("name", name);
        attribute.put("type", type);
        attribute.put("multiValued", false);
        attribute.put("description", description);
        attribute.put("required", required);
        attribute.put("caseExact", caseExact);
        attribute.put("mutability", "readWrite");
        attribute.put("returned", "default");
        attribute.put("uniqueness", uniqueness);
        return attribute;
    }

    private static Map<String, Object> complex(String name, List<Map<String, Object>> subAttributes) {
        Map<String, Object> attribute = new LinkedHashMap<>();
        attribute.put("name", name);
        attribute.put("type", "complex");
        attribute.put("multiValued", false);
        attribute.put("description", "The components of the user's name");
        attribute.put("required", false);
        attribute.put("subAttributes", subAttributes);
        attribute.put("mutability", "readWrite");
        attribute.put("returned", "default");
        attribute.put("uniqueness", "none");
        return attribute;
    }

    private static Map<String, Object> multiValuedEmails() {
        Map<String, Object> attribute = new LinkedHashMap<>();
        attribute.put("name", "emails");
        attribute.put("type", "complex");
        attribute.put("multiValued", true);
        attribute.put("description", "The user's email, as userName holds it; userName wins when they differ");
        attribute.put("required", false);
        attribute.put("subAttributes", List.of(attribute("value", "string", false, false, "none", "The address"),
                attribute("type", "string", false, false, "none", "work"),
                attribute("primary", "boolean", false, false, "none", "Always true")));
        attribute.put("mutability", "readWrite");
        attribute.put("returned", "default");
        attribute.put("uniqueness", "none");
        return attribute;
    }
}
