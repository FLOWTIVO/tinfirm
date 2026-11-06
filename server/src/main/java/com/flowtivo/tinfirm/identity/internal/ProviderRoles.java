package com.flowtivo.tinfirm.identity.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * Who decides the role of a provider's members, and how its claim maps to a role (ADR-0088), the same for an OIDC and a
 * SAML provider: {@code _ROLE_SOURCE}, {@code _ROLE_CLAIM} and {@code _ROLE_MAP_ADMIN}, {@code _ROLE_MAP_MEMBER},
 * {@code _ROLE_MAP_VIEWER} for the provider's own company ({@code _COMPANY}), and {@code _COMPANIES_<KEY>_ROLE_MAP_*}
 * for each further declared company the claim fills (ADR-0089). There is no map for OWNER, which a provider never
 * gives.
 *
 * @param source    the configured mode, or null when unset
 * @param claim     the OIDC claim or the SAML attribute that carries the role's values, or null
 * @param home      the map of the provider's own company ({@code _ROLE_MAP_*})
 * @param companies the maps of the further companies, by the declared company's key ({@code _COMPANIES_<KEY>_*})
 */
record ProviderRoles(@Nullable Mode source, @Nullable String claim, RoleMap home, Map<String, RoleMap> companies) {

    static final ProviderRoles NONE = new ProviderRoles(null, null, RoleMap.NONE, Map.of());

    /** {@code _ROLE_SOURCE}. */
    enum Mode {

        /** The provider decides the role at every sign-in, and the app cannot change it. */
        IDP,

        /** The provider gives a new user a role once, and the app decides after (ADR-0075 decision 4). */
        APP
    }

    /**
     * The claim's values that give each role in one company: {@code ROLE_MAP_ADMIN}, {@code ROLE_MAP_MEMBER} and
     * {@code ROLE_MAP_VIEWER}, matched exactly.
     */
    record RoleMap(List<String> admin, List<String> member, List<String> viewer) {

        static final RoleMap NONE = new RoleMap(List.of(), List.of(), List.of());

        RoleMap {
            admin = trimmed(admin);
            member = trimmed(member);
            viewer = trimmed(viewer);
        }

        /** Whether any value is listed. */
        boolean isMapped() {
            return !admin.isEmpty() || !member.isEmpty() || !viewer.isEmpty();
        }

        /**
         * The role the claim's values map to, the highest when several roles' values are there (ADMIN, then MEMBER,
         * then VIEWER); null when none of its values is listed.
         */
        @Nullable CompanyRole mapped(List<String> values) {
            if (values.stream().anyMatch(admin::contains)) {
                return CompanyRole.ADMIN;
            }
            if (values.stream().anyMatch(member::contains)) {
                return CompanyRole.MEMBER;
            }
            if (values.stream().anyMatch(viewer::contains)) {
                return CompanyRole.VIEWER;
            }
            return null;
        }

        /** The problems of this map, each naming the setting with {@code prefix} before {@code ROLE_MAP_*}. */
        List<String> problems(String prefix) {
            List<String> problems = new ArrayList<>();
            Map<String, String> seen = new HashMap<>();
            Map<String, List<String>> maps = new LinkedHashMap<>();
            maps.put(prefix + "ROLE_MAP_ADMIN", admin);
            maps.put(prefix + "ROLE_MAP_MEMBER", member);
            maps.put(prefix + "ROLE_MAP_VIEWER", viewer);
            maps.forEach((setting, values) -> values.forEach(value -> {
                if (value.isEmpty()) {
                    problems.add(setting + " holds an empty value");
                    return;
                }
                String other = seen.putIfAbsent(value, setting);
                if (other != null && !other.equals(setting)) {
                    problems.add(setting + " lists '" + value + "', which " + other + " lists too");
                }
            }));
            return problems;
        }

        /** Trims each value, keeping empty ones, which {@link #problems} reports. */
        private static List<String> trimmed(@Nullable List<String> values) {
            return values == null ? List.of() : values.stream().map(String::trim).toList();
        }
    }

    /** The settings of ADR-0088 alone: a provider of one company. */
    ProviderRoles(@Nullable Mode source, @Nullable String claim, List<String> mapAdmin, List<String> mapMember,
            List<String> mapViewer) {
        this(source, claim, new RoleMap(mapAdmin, mapMember, mapViewer), Map.of());
    }

    ProviderRoles {
        claim = claim == null || claim.isBlank() ? null : claim.trim();
        home = home == null ? RoleMap.NONE : home;
        Map<String, RoleMap> keyed = new TreeMap<>();
        if (companies != null) {
            companies.forEach((key, map) -> keyed.put(key.trim().toLowerCase(Locale.ROOT), map == null ? RoleMap.NONE
                    : map));
        }
        companies = Map.copyOf(keyed);
    }

    /**
     * Who decides: {@code IDP} when a claim is named and the mode is unset or {@code IDP}; {@code APP} otherwise. A
     * provider configured before ADR-0088, without a claim, runs as {@code APP} (decision 6).
     */
    Mode mode() {
        if (claim == null) {
            return Mode.APP;
        }
        return source != null ? source : Mode.IDP;
    }

    /** Whether the provider decides its members' roles at every sign-in. */
    boolean decides() {
        return mode() == Mode.IDP;
    }

    /** Whether the claim fills further companies beside the provider's own ({@code _COMPANIES_<KEY>_*}). */
    boolean fillsCompanies() {
        return !companies.isEmpty();
    }

    /**
     * The problems that stop the start, each naming the setting, as suffixes of the provider's variables; empty when
     * the settings are valid.
     */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (source == Mode.IDP && claim == null) {
            problems.add("ROLE_SOURCE=IDP needs ROLE_CLAIM, the claim or attribute that carries the role");
        }
        boolean mapped = home.isMapped() || companies.values().stream().anyMatch(RoleMap::isMapped);
        if (home.isMapped() && claim == null) {
            problems.add("ROLE_MAP_* needs ROLE_CLAIM, the claim or attribute whose values it maps");
        }
        if (!companies.isEmpty() && claim == null) {
            problems.add("COMPANIES_<KEY>_ROLE_MAP_* needs ROLE_CLAIM, the claim or attribute whose values it maps");
        }
        if (claim != null && !mapped) {
            problems.add("ROLE_CLAIM needs at least one of ROLE_MAP_ADMIN, ROLE_MAP_MEMBER and ROLE_MAP_VIEWER, "
                    + "or COMPANIES_<KEY>_ROLE_MAP_*");
        }
        problems.addAll(home.problems(""));
        companies.forEach((key, map) -> {
            String prefix = "COMPANIES_" + key.toUpperCase(Locale.ROOT) + "_";
            if (!DeclaredKey.valid(key)) {
                problems.add(prefix + "ROLE_MAP_*: '" + key + "' is not a company's key (1 to 40 lower-case letters "
                        + "and digits, starting with a letter)");
            }
            if (!map.isMapped()) {
                problems.add(prefix + "ROLE_MAP_*: at least one of ROLE_MAP_ADMIN, ROLE_MAP_MEMBER and ROLE_MAP_VIEWER "
                        + "is needed");
            }
            problems.addAll(map.problems(prefix));
        });
        return problems;
    }

    /**
     * The role the claim's values map to in the provider's own company, the highest when several roles' values are
     * there (ADMIN, then MEMBER, then VIEWER); null when the claim is missing or none of its values is listed.
     */
    @Nullable CompanyRole mapped(@Nullable Object claimValue) {
        return home.mapped(values(claimValue));
    }

    /** The role the claim's values map to in the further company of this key; null for none, or an unknown key. */
    @Nullable CompanyRole mappedIn(String companyKey, @Nullable Object claimValue) {
        RoleMap map = companies.get(companyKey);
        return map == null ? null : map.mapped(values(claimValue));
    }

    /**
     * The claim's values: a string is one value, a collection its strings; anything else, or a missing claim, holds
     * none.
     */
    static List<String> values(@Nullable Object claimValue) {
        if (claimValue instanceof String value) {
            return List.of(value);
        }
        if (claimValue instanceof Collection<?> collection) {
            return collection.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    /** A declared company's key as ADR-0075 defines it, which an environment variable's name survives. */
    static final class DeclaredKey {

        private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile("^[a-z][a-z0-9]{0,39}$");

        private DeclaredKey() {
        }

        static boolean valid(String key) {
            return KEY.matcher(key).matches();
        }
    }
}
