package com.flowtivo.tinfirm.model.internal;

import java.util.Optional;
import java.util.UUID;

/**
 * Where the server reaches a company's LiteLLM, for its admin API and for the model requests it relays, and the admin
 * key it uses there: the deployment's bundled gateway and its master key, or the company's own and the key an admin
 * entered. {@link #toString()} never shows the key.
 *
 * @param baseUrl  the gateway's root for its admin API, without a trailing slash
 * @param bundled  whether this is the deployment's gateway: its URL is the operator's, not a member's, so it is not
 *                 held to the outbound guard
 * @param modelUrl the gateway's root for model requests, without a trailing slash: {@code baseUrl}, unless the
 *                 deployment sets another for its bundled gateway ({@code TINFIRM_MODEL_GATEWAY_MODEL_URL})
 * @param vendor   the product the gateway is, whose {@link GatewayAdapter} speaks to it (ADR-0094)
 */
record Gateway(String baseUrl, String adminKey, boolean bundled, String modelUrl, GatewayVendor vendor) {

    /** What the alias of the company's own key names in place of a principal. */
    private static final String COMPANY = "company";

    Gateway {
        baseUrl = withoutSlash(baseUrl);
        modelUrl = withoutSlash(modelUrl);
    }

    /**
     * A gateway whose keys the company's gateway team provides (ADR-0094): its model URL, no admin key. Tinfirm makes,
     * rotates and deletes no key there ({@link #managed()} is false).
     */
    static Gateway provided(String modelUrl, GatewayVendor vendor) {
        return new Gateway(modelUrl, "", false, modelUrl, vendor);
    }

    /** Whether Tinfirm manages the gateway's keys through its admin API: it holds an admin key there. */
    boolean managed() {
        return !adminKey.isEmpty();
    }

    /** A LiteLLM with one URL for its admin API and its model requests: a company's own. */
    Gateway(String baseUrl, String adminKey, boolean bundled) {
        this(baseUrl, adminKey, bundled, baseUrl, GatewayVendor.LITELLM);
    }

    private static String withoutSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The company's LiteLLM team (the security review of the redesign, row 17). */
    static String teamId(UUID companyId) {
        return "tinfirm-" + companyId;
    }

    /** The alias of a principal's first key: Tinfirm manages the keys whose alias starts with the company's prefix. */
    static String keyAlias(UUID companyId, UUID principalId) {
        return keyAlias(companyId, principalId, 0);
    }

    /**
     * The alias of a principal's key of a generation: {@code tinfirm-{companyId}-{principalId}} for the first,
     * {@code ...-r{generation}} for each rotation's, since LiteLLM keeps an alias unique while the previous key serves
     * its grace.
     */
    static String keyAlias(UUID companyId, UUID principalId, int generation) {
        return aliasPrefix(companyId) + principalId + (generation > 0 ? "-r" + generation : "");
    }

    /**
     * The alias of the company's own key of a generation (ADR-0065): {@code tinfirm-{companyId}-company}, and
     * {@code ...-r{generation}} for each rotation's. No principal's alias is one: theirs name a UUID there.
     */
    static String companyKeyAlias(UUID companyId, int generation) {
        return aliasPrefix(companyId) + COMPANY + (generation > 0 ? "-r" + generation : "");
    }

    /** Whether an alias is one of the company's own key, of any generation. */
    static boolean companyKeyOf(String alias, UUID companyId) {
        String base = aliasPrefix(companyId) + COMPANY;
        return alias.equals(base) || alias.startsWith(base + "-r");
    }

    static String aliasPrefix(UUID companyId) {
        return "tinfirm-" + companyId + "-";
    }

    /** The principal an alias with the company's prefix names; empty for an alias that names none. */
    static Optional<UUID> principalOf(String alias, UUID companyId) {
        String prefix = aliasPrefix(companyId);
        if (!alias.startsWith(prefix) || alias.length() < prefix.length() + 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(alias.substring(prefix.length(), prefix.length() + 36)));
        }
        catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "Gateway[vendor=" + vendor + ", baseUrl=" + baseUrl + ", modelUrl=" + modelUrl + ", bundled=" + bundled
                + ", adminKey=" + (managed() ? "***" : "none") + "]";
    }
}
