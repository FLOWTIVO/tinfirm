package com.flowtivo.tinfirm.identity.internal.saml;

import java.io.Serial;

import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;

/**
 * A SAML response or start Tinfirm refuses (ADR-0086, decisions 8 and 12), with the reason the audit records and the
 * code the login page shows: {@code SAML_LOGIN_FAILED} for every check, whose detail is only in the audit and the log.
 * One that provisioning refused ({@link #audited}) carries provisioning's code and is already audited.
 */
final class SamlRefusal extends Saml2AuthenticationException {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String SAML_LOGIN_FAILED = "SAML_LOGIN_FAILED";

    static final String EDITION_REQUIRED = "EDITION_REQUIRED";

    private final String reason;

    private final String code;

    private final boolean audited;

    private SamlRefusal(String reason, String code, boolean audited, String detail) {
        super(new Saml2Error(reason, detail));
        this.reason = reason;
        this.code = code;
        this.audited = audited;
    }

    /** A check failed: audited with the reason, shown as {@code SAML_LOGIN_FAILED}. */
    static SamlRefusal of(String reason, String detail) {
        return new SamlRefusal(reason, SAML_LOGIN_FAILED, false, detail);
    }

    /** The licence does not serve SAML (decision 14). */
    static SamlRefusal editionRequired() {
        return new SamlRefusal("edition_required", EDITION_REQUIRED, false, "the licence does not hold SAML");
    }

    /** Provisioning refused the person, and audited it. */
    static SamlRefusal audited(String reason, String code) {
        return new SamlRefusal(reason, code, true, "refused: " + reason);
    }

    String reason() {
        return reason;
    }

    String code() {
        return code;
    }

    boolean audited() {
        return audited;
    }
}
