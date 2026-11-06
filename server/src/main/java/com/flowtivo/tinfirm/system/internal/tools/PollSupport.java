package com.flowtivo.tinfirm.system.internal.tools;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;

/**
 * What the trackers' poll sessions share (the package 4 contract, §5.4): the rate limit a response names, the time to
 * wait after a refusal, and the mentions of a Markdown comment.
 */
public final class PollSupport {

    /** {@code @login} on GitHub and GitLab: not inside a word, an address or a path. */
    private static final Pattern AT_MENTION = Pattern.compile("(?<![A-Za-z0-9_./@-])@([A-Za-z0-9](?:[A-Za-z0-9_.-]*"
            + "[A-Za-z0-9_])?)");

    /** Jira's wiki mention: {@code [~name]}, {@code [~accountid:…]}. */
    private static final Pattern WIKI_MENTION = Pattern.compile("\\[~(?:accountid:)?([^\\]\\s]{1,255})\\]",
            Pattern.CASE_INSENSITIVE);

    /** A wait longer than this is not believed: a poll backs off an hour at most. */
    private static final Duration MAX_WAIT = Duration.ofHours(1);

    private PollSupport() {
    }

    /**
     * The rate limit the response names: GitHub's {@code x-ratelimit-*} (the reset in epoch seconds), GitLab's
     * {@code RateLimit-*}, Jira Cloud's {@code X-RateLimit-*} (the reset as a date-time); null when it names none.
     */
    public static SystemPolls.@Nullable RateLimit rate(OutboundHttp.Response response) {
        Integer limit = number(response.header("x-ratelimit-limit").or(() -> response.header("ratelimit-limit")));
        Integer remaining = number(response.header("x-ratelimit-remaining")
            .or(() -> response.header("ratelimit-remaining")));
        Instant reset = time(response.header("x-ratelimit-reset").or(() -> response.header("ratelimit-reset")));
        if (limit == null && remaining == null && reset == null) {
            return null;
        }
        return new SystemPolls.RateLimit(limit, remaining, reset);
    }

    /**
     * The failure of a listing or a read: a {@code 401} is {@code UNAUTHORIZED}; a rate limit ({@code 429}, a
     * {@code 403} with GitHub's headers) {@code RATE_LIMITED} with the wait its {@code Retry-After} or its reset names,
     * at most an hour; any other {@code 403} {@code FORBIDDEN}; {@code 404} {@code NOT_FOUND}; {@code 5xx}
     * {@code UNAVAILABLE}.
     */
    public static CallResult.Failure failure(CallResult.Failure given, OutboundHttp.Response response) {
        if (given.kind() != CallResult.Kind.RATE_LIMITED) {
            return given;
        }
        Duration wait = given.retryAfter();
        if (wait == null) {
            wait = response.header("Retry-After").map(OutboundHttp::retryAfter).orElse(null);
        }
        if (wait == null) {
            SystemPolls.RateLimit rate = rate(response);
            if (rate != null && rate.resetsAt() != null) {
                wait = Duration.between(Instant.now(), rate.resetsAt());
            }
        }
        if (wait != null) {
            wait = wait.isNegative() ? Duration.ZERO : wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
        }
        return new CallResult.Failure(CallResult.Kind.RATE_LIMITED, given.message(), wait);
    }

    /** The {@code @login} mentions of a Markdown text, lower case. */
    public static Set<String> atMentions(@Nullable String text) {
        Set<String> mentions = new LinkedHashSet<>();
        if (text == null) {
            return mentions;
        }
        Matcher matcher = AT_MENTION.matcher(text);
        while (matcher.find()) {
            mentions.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return mentions;
    }

    /** Jira's wiki mentions of a text, {@code [~name]} and {@code [~accountid:…]}, lower case. */
    public static Set<String> wikiMentions(@Nullable String text) {
        Set<String> mentions = new LinkedHashSet<>();
        if (text == null) {
            return mentions;
        }
        Matcher matcher = WIKI_MENTION.matcher(text);
        while (matcher.find()) {
            mentions.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return mentions;
    }

    /** A text cut to {@code max} characters (code points). */
    public static String cut(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }

    private static @Nullable Integer number(Optional<String> value) {
        return value.map(String::strip).filter(text -> text.matches("[0-9]{1,9}")).map(Integer::parseInt).orElse(null);
    }

    private static @Nullable Instant time(Optional<String> value) {
        String text = value.map(String::strip).orElse(null);
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (text.matches("[0-9]{1,12}")) {
            return Instant.ofEpochSecond(Long.parseLong(text));
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        }
        catch (DateTimeParseException ex) {
            try {
                return Instant.parse(text);
            }
            catch (DateTimeParseException again) {
                return null;
            }
        }
    }
}
