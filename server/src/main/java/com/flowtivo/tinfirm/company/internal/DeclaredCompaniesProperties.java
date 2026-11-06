package com.flowtivo.tinfirm.company.internal;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.flowtivo.tinfirm.company.scim.ScimRoleSource;
import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * The companies the deployment declares (ADR-0075), keyed by their key: {@code TINFIRM_COMPANIES_<KEY>_NAME},
 * {@code _OWNER} and {@code _ID}, as Spring's relaxed binding reads them. A key is lower-case letters and digits,
 * starting with a letter, so that it survives an environment variable's name; the server does not start with another.
 *
 * @param companies the declarations by key, in key order
 */
@Validated
@ConfigurationProperties("tinfirm")
record DeclaredCompaniesProperties(@DefaultValue Map<String, @Valid Declaration> companies) {

    static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9]{0,39}$");

    DeclaredCompaniesProperties {
        for (String key : companies.keySet()) {
            if (!KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("tinfirm.companies." + key + ": a company's key is 1 to 40 "
                        + "lower-case letters and digits, starting with a letter");
            }
        }
        companies = java.util.Collections.unmodifiableMap(new TreeMap<>(companies));
    }

    /** The key of the only declared company, when exactly one is declared. */
    Optional<String> onlyKey() {
        return companies.size() == 1 ? Optional.of(companies.keySet().iterator().next()) : Optional.empty();
    }

    List<String> keys() {
        return List.copyOf(companies.keySet());
    }

    /**
     * @param name  {@code TINFIRM_COMPANIES_<KEY>_NAME}: the name the company is created with, and that a company
     *              from before keys is adopted by
     * @param owner {@code TINFIRM_COMPANIES_<KEY>_OWNER}: the email of the user who becomes its OWNER while it has
     *              none; blank for the bootstrap admin's
     * @param id    {@code TINFIRM_COMPANIES_<KEY>_ID}: a company from before keys to adopt
     * @param scim  {@code TINFIRM_COMPANIES_<KEY>_SCIM_*}: whether the company serves SCIM (ADR-0087, decision 2)
     * @param runnerHost {@code TINFIRM_COMPANIES_<KEY>_RUNNER_HOST_*}: the company runner host that the deployment
     *              runs itself (ADR-0101, decision 8); null for none
     */
    record Declaration(@NotBlank @Size(max = 100) String name, @Nullable String owner, @Nullable UUID id,
            @Valid @DefaultValue Scim scim, @Nullable RunnerHost runnerHost) {

        @ConstructorBinding
        Declaration {
        }

        Declaration(String name, @Nullable String owner, @Nullable UUID id) {
            this(name, owner, id, Scim.OFF, null);
        }

        /** The declared owner's email in lower case, else the default's; null for none. */
        @Nullable String ownerOr(@Nullable String defaultOwner) {
            String email = owner != null && !owner.isBlank() ? owner : defaultOwner;
            return email != null && !email.isBlank() ? email.trim().toLowerCase(Locale.ROOT) : null;
        }
    }

    /**
     * A company runner host that the deployment runs itself (ADR-0101, decision 8), such as the Helm chart's runner pod:
     * the server registers it at its start, with the declared token, and replaces its token when the declaration's
     * changes. Both values are checked when the declaration is applied, not while binding, so that a refused token is
     * never in a message; both blank, as Compose passes unset variables, is no host.
     *
     * @param name  {@code TINFIRM_COMPANIES_<KEY>_RUNNER_HOST_NAME}
     * @param token {@code TINFIRM_COMPANIES_<KEY>_RUNNER_HOST_TOKEN}: {@code tfr_} + 43 base64url characters, which the
     *              runner host is given too
     */
    record RunnerHost(@Nullable String name, @Nullable String token) {

        /** Neither is set. */
        boolean blank() {
            return (name == null || name.isBlank()) && (token == null || token.isBlank());
        }

        @Override
        public String toString() {
            return "RunnerHost[name=" + name + ", token=***]";
        }
    }

    /**
     * A declared company's SCIM (ADR-0087, decision 2): the operator allows it per company, and the company's OWNER
     * then issues the token in the app.
     *
     * @param enabled    {@code TINFIRM_COMPANIES_<KEY>_SCIM_ENABLED}
     * @param role       {@code TINFIRM_COMPANIES_<KEY>_SCIM_ROLE}: a new member's role when the request names none,
     *                   ADMIN, MEMBER or VIEWER
     * @param roleSource {@code TINFIRM_COMPANIES_<KEY>_SCIM_ROLE_SOURCE}: who decides the role after creation
     *                   (ADR-0088, decision 8)
     */
    record Scim(@DefaultValue("false") boolean enabled, @DefaultValue("MEMBER") @NotNull CompanyRole role,
            @DefaultValue("IDP") @NotNull ScimRoleSource roleSource) {

        static final Scim OFF = new Scim(false, CompanyRole.MEMBER, ScimRoleSource.IDP);

        /** SCIM never makes owners (ADR-0087, decision 7). */
        @AssertTrue(message = "scim.role must be ADMIN, MEMBER or VIEWER")
        boolean isRoleNotOwner() {
            return role != CompanyRole.OWNER;
        }
    }
}
