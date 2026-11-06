package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Refuses every request that carries an {@code Origin} header other than Tinfirm's own with 403 (ADR-0045,
 * ADR-0074): the MCP endpoint is for clients outside the browser, and a browser always sends {@code Origin} on the
 * requests a page can make with a bearer header. The one page let through is Tinfirm's own, the Outlook add-in's task
 * pane, served from {@code TINFIRM_PUBLIC_URL}. This is the MCP specification's guard against DNS rebinding, as the
 * runner host's MCP server has it: a page that rebinds a name to Tinfirm's address sends its own origin.
 */
final class OriginRefusalFilter extends OncePerRequestFilter {

    private final ProblemWriter problems;

    private final String ownOrigin;

    OriginRefusalFilter(ProblemWriter problems, URI publicUrl) {
        this.problems = problems;
        this.ownOrigin = origin(publicUrl);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !ownOrigin.equals(origin.toLowerCase(Locale.ROOT))) {
            problems.write(request, response, ApiException.forbidden("Browser requests are refused on this endpoint"));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The serialized origin of a URL, as a browser sends it in {@code Origin}: the scheme and the host in lower case,
     * and the port only when it is not the scheme's default.
     */
    static String origin(URI url) {
        String scheme = url.getScheme().toLowerCase(Locale.ROOT);
        String host = url.getHost().toLowerCase(Locale.ROOT);
        int port = url.getPort();
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }
}
