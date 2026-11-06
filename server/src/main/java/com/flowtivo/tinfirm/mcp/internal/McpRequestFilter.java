package com.flowtivo.tinfirm.mcp.internal;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.flowtivo.tinfirm.identity.McpChallenges;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.BearerGrant;
import com.flowtivo.tinfirm.shared.security.BearerGrantHolder;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The HTTP rules of the MCP endpoint that the transport does not know (ADR-0045), on {@code POST} to
 * {@code /api/companies/{companyId}/mcp}, after the bearer filter chain authenticated the request:
 * <ul>
 * <li>A credential that has spent its requests ({@link McpRateLimiter}: per bearer grant, else per user) is refused
 * with 429, {@code Retry-After} and a JSON-RPC error whose {@code data.code} is {@code RATE_LIMITED}, before
 * anything else is checked, so a refused request costs no parse.</li>
 * <li>A body above {@value #MAX_BODY_BYTES} bytes is refused with 413, before it is parsed.</li>
 * <li>A body that is no JSON, or has a duplicate key, is refused with 400: what this filter cannot read, it cannot
 * check, so the transport never gets it.</li>
 * <li>A JSON-RPC batch (an array) is refused with 400: the endpoint is stateless, one message per request.</li>
 * <li>A request for a method the server does not serve ({@link #SERVED_METHODS}: the stateless server offers tools
 * only) is answered {@code -32601} "Method not found", as JSON-RPC 2.0 says, with 200 like the server's other
 * errors: the transport would answer it with a 500.</li>
 * <li>A bearer whose user is not an active member of the company ({@link McpMembers}: a non-member, a suspended
 * member, an instance admin who is not a member) is refused with 404, as a non-member's bearer credentials are
 * everywhere (CONVENTIONS §15.13: the company is not theirs to find), whatever the method: a {@code ping} or
 * {@code tools/list} reaches no company the token's user does not belong to, and a member SCIM deactivated loses
 * the endpoint with their membership (the security review of 2026-10-27, H1 and M16).</li>
 * <li>A {@code tools/call} of a tool whose scope ({@link McpToolCatalogue}) the credential lacks is refused with 403
 * and {@code WWW-Authenticate: Bearer error="insufficient_scope", scope="...", resource_metadata="..."} (the MCP
 * specification's step-up authorization). Claude Code fails the call on it rather than authorizing again; the person
 * chooses a higher level on the consent page, which offers all three.</li>
 * <li>A request without {@code Accept: application/json, text/event-stream} is answered as if it had sent both: the
 * stateless transport always answers {@code application/json}, and some clients and scripts send neither.</li>
 * </ul>
 * Each refusal is a JSON-RPC error with the request's id where it is known. Everything else reaches the transport
 * with the body as it came.
 */
final class McpRequestFilter extends OncePerRequestFilter {

    /** The largest request body: 1 MiB. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    /** The JSON-RPC error code of a refused tool call (the server-error range of JSON-RPC 2.0). */
    static final int INSUFFICIENT_SCOPE_CODE = -32003;

    /** The JSON-RPC error code of a request over the rate limit (the server-error range of JSON-RPC 2.0). */
    static final int RATE_LIMITED_CODE = -32029;

    /** The JSON-RPC error code of a method the server does not serve (JSON-RPC 2.0). */
    static final int METHOD_NOT_FOUND_CODE = -32601;

    /**
     * The requests the stateless server answers: its lifecycle, {@code ping} and its tools (§14.11). Notifications have
     * no answer and pass. Extend it with the capabilities the server adds.
     */
    static final Set<String> SERVED_METHODS = Set.of("initialize", "ping", "tools/list", "tools/call");

    private static final Pattern ENDPOINT = Pattern.compile("^/api/companies/([^/]+)/mcp$");

    private static final String ACCEPTED = MediaType.APPLICATION_JSON_VALUE + ", " + MediaType.TEXT_EVENT_STREAM_VALUE;

    /** Refuses duplicate keys, so the tool this filter checks is the tool the transport calls. */
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();

    private final McpToolCatalogue catalogue;

    private final McpChallenges challenges;

    private final McpRateLimiter limiter;

    private final Licensing licensing;

    private final Predicate<UUID> members;

    /**
     * @param members whether the call's user is an active member of the company, as a tool call's access check answers
     *                ({@link McpMembers})
     */
    McpRequestFilter(McpToolCatalogue catalogue, McpChallenges challenges, McpRateLimiter limiter,
            Licensing licensing, Predicate<UUID> members) {
        this.licensing = licensing;
        this.members = members;
        this.catalogue = catalogue;
        this.challenges = challenges;
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || companyOf(request).isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID companyId = companyOf(request).orElseThrow();
        if (request.getHeader(HttpHeaders.ORIGIN) != null && !licensing.has(Feature.OUTLOOK_ADDIN)
                && members.test(companyId)) {
            // Only Tinfirm's own page sends an Origin here, the Outlook add-in's task pane (ADR-0074), which is Pro's
            // and Enterprise's (ADR-0077, decision 4). After the access check: a caller who may not reach the company
            // gets the endpoint's usual answer, never the edition.
            error(response, HttpStatus.FORBIDDEN, idOf(request), -32600, "The Outlook add-in needs the PRO edition",
                    ProblemCode.EDITION_REQUIRED, null, Feature.OUTLOOK_ADDIN);
            return;
        }
        Optional<Duration> wait = credential().flatMap(limiter::admit);
        if (wait.isPresent()) {
            long seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
            error(response, HttpStatus.TOO_MANY_REQUESTS, idOf(request), RATE_LIMITED_CODE,
                    "Too many requests from this token: try again in " + seconds + " s", ProblemCode.RATE_LIMITED,
                    null);
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            error(response, HttpStatus.CONTENT_TOO_LARGE, null, -32600,
                    "The request is larger than " + MAX_BODY_BYTES + " bytes", ProblemCode.VALIDATION_FAILED, null);
            return;
        }
        if (!members.test(companyId)) {
            // After the limiter, so that a flood of a stranger's token costs no membership read. 404 and no code,
            // as the REST API answers a company the user cannot reach: nothing says whether it exists.
            error(response, HttpStatus.NOT_FOUND, idOf(request), -32600, "Company not found", null, null);
            return;
        }
        byte[] body = read(request.getInputStream());
        if (body == null) {
            error(response, HttpStatus.CONTENT_TOO_LARGE, null, -32600,
                    "The request is larger than " + MAX_BODY_BYTES + " bytes", ProblemCode.VALIDATION_FAILED, null);
            return;
        }

        JsonNode message = parse(body);
        if (message == null) {
            // What this filter cannot read, the transport does not get: its scope could not be checked.
            error(response, HttpStatus.BAD_REQUEST, null, -32700, "Parse error: the request is not one JSON object",
                    ProblemCode.VALIDATION_FAILED, null);
            return;
        }
        if (message.isArray()) {
            error(response, HttpStatus.BAD_REQUEST, null, -32600,
                    "Batched requests are not supported: send one JSON-RPC message per request",
                    ProblemCode.VALIDATION_FAILED, null);
            return;
        }
        String method = message.isObject() ? text(message.get("method")) : null;
        if (method != null && message.hasNonNull("id") && !SERVED_METHODS.contains(method)) {
            error(response, HttpStatus.OK, message.get("id"), METHOD_NOT_FOUND_CODE, "Method not found: " + method,
                    ProblemCode.VALIDATION_FAILED, null);
            return;
        }
        if (message.isObject() && "tools/call".equals(method)) {
            String tool = text(message.path("params").get("name"));
            Optional<ApiScope> required = tool != null ? catalogue.requiredScope(tool) : Optional.empty();
            Optional<BearerGrant> grant = BearerGrantHolder.current();
            if (required.isPresent() && (grant.isEmpty() || !grant.get().allows(required.get()))) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                        challenges.insufficientScope(companyId, required.get()));
                error(response, HttpStatus.FORBIDDEN, message.get("id"), INSUFFICIENT_SCOPE_CODE,
                        "The tool " + tool + " needs the scope " + required.get().oauthScope()
                                + ", which this token does not have. Sign in again and allow it.",
                        ProblemCode.INSUFFICIENT_SCOPE, required.get().oauthScope());
                return;
            }
        }
        chain.doFilter(new BufferedRequest(request, body), response);
    }

    /** Whose bucket a request spends: the bearer grant's id, else the user's (the bearer chain sets both). */
    private static Optional<UUID> credential() {
        Optional<BearerGrant> grant = BearerGrantHolder.current();
        if (grant.isPresent()) {
            return Optional.of(grant.get().id());
        }
        return CurrentActor.user().map(user -> user.id());
    }

    /** The JSON-RPC id of a refused request, when its body is small enough to read and holds one. */
    private static @Nullable JsonNode idOf(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return null;
        }
        byte[] body = read(request.getInputStream());
        JsonNode message = body != null ? parse(body) : null;
        return message != null && message.isObject() ? message.get("id") : null;
    }

    /** The company of the endpoint's path, when the path is the endpoint itself with a company id. */
    static Optional<UUID> companyOf(HttpServletRequest request) {
        Matcher matcher = ENDPOINT.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(matcher.group(1));
            return id.toString().equals(matcher.group(1)) ? Optional.of(id) : Optional.empty();
        }
        catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** The body, or null when it is larger than the cap. */
    private static byte @Nullable [] read(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        return bytes.length > MAX_BODY_BYTES ? null : bytes;
    }

    private static @Nullable JsonNode parse(byte[] body) {
        try {
            return JSON.readTree(body);
        }
        catch (JacksonException ex) {
            return null;
        }
    }

    private static @Nullable String text(@Nullable JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private static void error(HttpServletResponse response, HttpStatus status, @Nullable JsonNode id, int code,
            String message, @Nullable ProblemCode problem, @Nullable String scope) throws IOException {
        error(response, status, id, code, message, problem, scope, null);
    }

    /** A JSON-RPC error; for {@code EDITION_REQUIRED}, its {@code details} as the REST problem names them. */
    /** @param problem the problem code the error's {@code data.code} names; null for a refusal that names none */
    private static void error(HttpServletResponse response, HttpStatus status, @Nullable JsonNode id, int code,
            String message, @Nullable ProblemCode problem, @Nullable String scope, @Nullable Feature feature)
            throws IOException {
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        if (id != null && (id.isString() || id.isNumber())) {
            body.set("id", id);
        }
        else {
            body.putNull("id");
        }
        ObjectNode error = body.putObject("error");
        error.put("code", code);
        error.put("message", message);
        ObjectNode data = error.putObject("data");
        if (problem != null) {
            data.put("code", problem.name());
        }
        if (scope != null) {
            data.put("scope", scope);
        }
        if (feature != null) {
            data.putObject("details").put("feature", feature.name()).put("edition", feature.edition().name());
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(JSON.writeValueAsBytes(body));
    }

    /** The request with its body read once, and an {@code Accept} the stateless transport takes. */
    static final class BufferedRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private final boolean replaceAccept;

        BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
            this.replaceAccept = !acceptsBoth(request);
        }

        private static boolean acceptsBoth(HttpServletRequest request) {
            List<MediaType> types = new ArrayList<>();
            for (Enumeration<String> values = request.getHeaders(HttpHeaders.ACCEPT); values.hasMoreElements();) {
                try {
                    types.addAll(MediaType.parseMediaTypes(values.nextElement()));
                }
                catch (IllegalArgumentException ex) {
                    return false;
                }
            }
            return types.contains(MediaType.APPLICATION_JSON) && types.contains(MediaType.TEXT_EVENT_STREAM);
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    getCharacterEncoding() != null ? Charset.forName(getCharacterEncoding()) : StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public @Nullable String getHeader(String name) {
            if (replaceAccept && HttpHeaders.ACCEPT.equalsIgnoreCase(name)) {
                return ACCEPTED;
            }
            if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                return Integer.toString(body.length);
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (replaceAccept && HttpHeaders.ACCEPT.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(ACCEPTED));
            }
            if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(Integer.toString(body.length)));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Map<String, Boolean> names = new LinkedHashMap<>();
            for (Enumeration<String> each = super.getHeaderNames(); each.hasMoreElements();) {
                names.put(each.nextElement(), true);
            }
            if (replaceAccept && names.keySet().stream().noneMatch(HttpHeaders.ACCEPT::equalsIgnoreCase)) {
                names.put(HttpHeaders.ACCEPT, true);
            }
            return Collections.enumeration(names.keySet());
        }
    }
}
