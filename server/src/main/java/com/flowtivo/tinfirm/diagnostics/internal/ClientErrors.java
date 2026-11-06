package com.flowtivo.tinfirm.diagnostics.internal;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedException;
import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedFrame;
import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.Source;
import com.flowtivo.tinfirm.shared.api.model.ClientError;
import com.flowtivo.tinfirm.shared.api.model.ClientErrorFrame;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * A web app's error as a captured error (ADR-0070, §1, §2): its type, masked message and route pattern, its frames
 * as the bundle's path, line and column (a script of another origin, such as a browser extension's, is
 * {@code <external>} and not Tinfirm's code), and the browser's name and major version from {@code User-Agent}. What
 * the browser sends is held to the shapes of Tinfirm's own code, so that a member cannot put text where only code is
 * expected: a route is lowercase segments and {@code :params} as the web app's routes are, a file one of its bundles
 * ({@code /main-<hash>.js}, {@code /chunk-<hash>.js}), and a frame's function is never kept: a minified name says
 * nothing, and the tracker restores the names from the source maps. Anything else is dropped. At most
 * {@code perUserMinute} a minute from one user, and at most {@code webFingerprintsPerUserHour} distinct kinds of error
 * (fingerprints) an hour from one user (L18): a member who varies the message cannot fill the reports with new kinds,
 * while a kind already taken from them keeps counting.
 */
@Component
class ClientErrors {

    private static final Pattern KIND = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]{0,99}");

    /** {@code /}, or up to 12 segments as {@code app.routes.ts} writes them: {@code /c/:companyId/kill-switches}. */
    private static final Pattern ROUTE = Pattern.compile(
            "/|(?:/(?:[a-z][a-z0-9-]{0,39}|:[a-z][A-Za-z0-9]{0,39}|\\*\\*)){1,12}");

    /** The web app's bundles as the Angular build names them, with their 8-character hash (none in development). */
    private static final Pattern BUNDLE = Pattern.compile(
            "/(?:main|polyfills|scripts|chunk)(?:-[A-Za-z0-9_-]{8})?\\.js");

    private static final Pattern URL = Pattern.compile("(?i)^([a-z][a-z0-9+.-]*:)(//[^/?#]*)?([^?#]*)");

    private static final List<Browser> BROWSERS = List.of(
            new Browser("Edge", Pattern.compile("Edg(?:e|A|iOS)?/(\\d+)")),
            new Browser("Opera", Pattern.compile("OPR/(\\d+)")),
            new Browser("Firefox", Pattern.compile("(?:Firefox|FxiOS)/(\\d+)")),
            new Browser("Chrome", Pattern.compile("(?:Chrome|CriOS)/(\\d+)")),
            new Browser("Safari", Pattern.compile("Version/(\\d+)[^ ]* (?:Mobile/\\S+ )?Safari/")));

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private static final Duration HOUR = Duration.ofHours(1);

    /** The users whose windows are kept; beyond, the ended windows are dropped. */
    private static final int MAX_TRACKED_USERS = 10_000;

    private final String origin;

    private final TimeSource time;

    private final int perUserMinute;

    private final int fingerprintsPerUserHour;

    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Kinds> kinds = new ConcurrentHashMap<>();

    /** A user's minute, from their first error in it. */
    private record Window(Instant start, int count) {
    }

    /** The kinds of error taken from a user in their hour, from the first. */
    private record Kinds(Instant start, Set<String> fingerprints) {
    }

    private record Browser(String name, Pattern version) {
    }

    ClientErrors(TinfirmProperties tinfirm, TimeSource time, ErrorReportProperties properties) {
        URI publicUrl = tinfirm.publicUrl();
        this.origin = (publicUrl.getScheme() + "://" + publicUrl.getRawAuthority()).toLowerCase(Locale.ROOT);
        this.time = time;
        this.perUserMinute = properties.perUserMinute();
        this.fingerprintsPerUserHour = properties.webFingerprintsPerUserHour();
    }

    /** Whether one more error of this user is taken: at most {@code perUserMinute} in a minute from their first. */
    boolean allow(UUID userId) {
        Instant now = time.now();
        Instant minuteAgo = now.minus(MINUTE);
        Window window = windows.compute(userId, (id, current) -> current == null || !current.start().isAfter(minuteAgo)
                ? new Window(now, 1) : new Window(current.start(), current.count() + 1));
        if (windows.size() > MAX_TRACKED_USERS) {
            windows.entrySet().removeIf(entry -> !entry.getValue().start().isAfter(minuteAgo));
        }
        return window.count() <= perUserMinute;
    }

    /**
     * Whether an error of this kind is taken from the user: a kind already taken from them in their hour, or a new
     * one while they have taken fewer than {@code webFingerprintsPerUserHour} kinds in it.
     */
    boolean allowKind(UUID userId, String fingerprint) {
        Instant now = time.now();
        Instant hourAgo = now.minus(HOUR);
        boolean[] taken = new boolean[1];
        kinds.compute(userId, (id, current) -> {
            Kinds window = current == null || !current.start().isAfter(hourAgo)
                    ? new Kinds(now, new HashSet<>()) : current;
            Set<String> seen = window.fingerprints();
            taken[0] = seen.contains(fingerprint) || (seen.size() < fingerprintsPerUserHour && seen.add(fingerprint));
            return window;
        });
        if (kinds.size() > MAX_TRACKED_USERS) {
            kinds.entrySet().removeIf(entry -> !entry.getValue().start().isAfter(hourAgo));
        }
        return taken[0];
    }

    CapturedError of(ClientError error, @Nullable String userAgent) {
        String kind = error.getKind() != null && KIND.matcher(error.getKind()).matches() ? error.getKind() : "Error";
        String message = Scrubber.mask(error.getMessage());
        String route = error.getRoute() != null && ROUTE.matcher(error.getRoute()).matches() ? error.getRoute() : null;
        List<CapturedFrame> frames = new ArrayList<>();
        if (error.getFrames() != null) {
            for (ClientErrorFrame frame : error.getFrames()) {
                if (frames.size() == ServerErrors.MAX_FRAMES) {
                    break;
                }
                frames.add(frame(frame));
            }
        }
        return new CapturedError(Source.WEB, CapturedError.webFingerprint(kind, message, route), kind, message, null,
                null, route, browser(userAgent), List.of(new CapturedException(kind, message, frames)));
    }

    private CapturedFrame frame(ClientErrorFrame frame) {
        String file = null;
        boolean inApp = false;
        if (frame.getFile() != null) {
            Matcher url = URL.matcher(frame.getFile());
            String path = frame.getFile();
            if (url.find() && url.group(2) != null) {
                String scheme = url.group(1).toLowerCase(Locale.ROOT);
                String fileOrigin = scheme + url.group(2).toLowerCase(Locale.ROOT);
                path = fileOrigin.equals(origin) ? url.group(3) : null;
            }
            else if (!path.startsWith("/")) {
                path = null;
            }
            if (path != null && BUNDLE.matcher(path).matches()) {
                file = path;
                inApp = true;
            }
            else {
                file = "<external>";
            }
        }
        return new CapturedFrame(null, null, file, frame.getLine(), frame.getColumn(), inApp);
    }

    /** {@code Chrome 141}: the browser's name and major version, null when it is none of the known ones. */
    static @Nullable String browser(@Nullable String userAgent) {
        if (userAgent == null) {
            return null;
        }
        for (Browser browser : BROWSERS) {
            Matcher version = browser.version().matcher(userAgent);
            if (version.find()) {
                return browser.name() + " " + version.group(1);
            }
        }
        return null;
    }
}
