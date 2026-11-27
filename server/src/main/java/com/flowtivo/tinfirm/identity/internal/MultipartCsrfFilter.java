package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Refuses a multipart request unread on a chain with CSRF protection, before {@link CsrfFilter}, unless it goes
 * where uploads are taken and carries the CSRF header. Tomcat parses a whole multipart body, writing its files to the
 * disk, as soon as anything asks for a parameter of a POST. On these chains that happened for an anonymous caller,
 * before the request was refused:
 * <ul>
 * <li>without the {@code X-XSRF-TOKEN} header, the CSRF filter looks for the {@code _csrf} parameter;</li>
 * <li>the sign-in filters (the OIDC callback, the SAML answer) and the authorization server's endpoints read their
 * parameters themselves, and a CSRF cookie and header that the caller made up itself pass the CSRF check, which
 * only compares them.</li>
 * </ul>
 * Nothing of Tinfirm posts a multipart with the token in a field, or to another place than an upload of the API:
 * the web app's uploads send the header (Angular's XSRF support), the consent, SAML and OIDC post forms that are
 * not multipart, and the Outlook add-in and MCP clients use the bearer chain, which has no CSRF protection. So:
 * <ul>
 * <li>On the session chain, a multipart POST under {@code /api/**} needs a non-empty header, which the CSRF filter
 * then checks without reading a parameter, and then a session, which is checked before any handler reads the body;
 * without the header it is answered as the CSRF filter answers a missing token, {@code 403} "Missing or invalid CSRF
 * token". Anywhere else on that chain it is {@code 415}.</li>
 * <li>On the authorization server's chain, whose endpoints take no multipart (the token, revocation and consent
 * requests are form-encoded), every multipart is {@code 415}, header or not.</li>
 * </ul>
 */
final class MultipartCsrfFilter extends OncePerRequestFilter {

    /** Where this chain takes a multipart, with the header; null where it takes none. */
    private final @Nullable RequestMatcher uploads;

    private final HandlerExceptionResolver resolver;

    private MultipartCsrfFilter(@Nullable RequestMatcher uploads, HandlerExceptionResolver resolver) {
        this.uploads = uploads;
        this.resolver = resolver;
    }

    /** The session chain's: a multipart goes to {@code uploads} with the header, and nowhere else. */
    static MultipartCsrfFilter headerRequired(RequestMatcher uploads, HandlerExceptionResolver resolver) {
        return new MultipartCsrfFilter(uploads, resolver);
    }

    /** The authorization server's chain: no multipart at all. */
    static MultipartCsrfFilter noMultipart(HandlerExceptionResolver resolver) {
        return new MultipartCsrfFilter(null, resolver);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (multipart(request) && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)) {
            Exception refusal = null;
            if (uploads == null || !uploads.matches(request)) {
                refusal = new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, null,
                        "This endpoint takes no multipart body");
            }
            else if (!StringUtils.hasText(request.getHeader(ConsentCsrfFilter.HEADER))) {
                refusal = new MissingCsrfTokenException(null);
            }
            if (refusal != null) {
                if (resolver.resolveException(request, response, null, refusal) == null) {
                    response.sendError(refusal instanceof ApiException ? HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE
                            : HttpServletResponse.SC_FORBIDDEN);
                }
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean multipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.trim().toLowerCase(Locale.ROOT).startsWith("multipart/");
    }
}
