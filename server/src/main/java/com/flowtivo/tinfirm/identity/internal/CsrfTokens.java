package com.flowtivo.tinfirm.identity.internal;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.web.csrf.CsrfToken;

final class CsrfTokens {

    private CsrfTokens() {
    }

    /** Loads the deferred CSRF token, which makes the repository write the {@code XSRF-TOKEN} cookie if needed. */
    static void render(HttpServletRequest request) {
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
            token.getToken();
        }
    }
}
