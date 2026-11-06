package com.flowtivo.tinfirm.runner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The runner host versions that change what the server does with a host (the package 2 contract, §7 and open point
 * 6; ADR-0059), by the version of its latest {@code hello.runnerVersion}:
 * <ul>
 * <li>a run paid by a budget goes only to a host of {@link #MODEL_PROXY_ALWAYS_ON} or later, the first version whose
 * model proxy is always on. An older host would put the run's one-run token into the sandbox, and the protocol does
 * not tell whether a host proxies, so its version decides;</li>
 * <li>the model relay requires the host's own token next to a run's token from a host of {@link #HOST_TOKEN_TO_RELAY}
 * or later, the first version whose model proxy sends it; an older host's requests without it are accepted for
 * compatibility.</li>
 * </ul>
 * Versions compare as semantic versions: a pre-release ({@code 0.2.0-rc.1}) is below its release, and a version that
 * is not {@code major.minor.patch} counts as too old.
 */
public final class RunnerVersions {

    /** The first runner host version with the always-on model proxy. */
    public static final String MODEL_PROXY_ALWAYS_ON = "0.2.0";

    /**
     * The first runner host version whose model proxy sends the host's own token to the model relay, in
     * {@code X-Tinfirm-Runner-Token} next to the run's one-run token.
     */
    public static final String HOST_TOKEN_TO_RELAY = "0.3.0";

    private static final Pattern SEMVER = Pattern
        .compile("^(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$");

    private static final int[] GATEWAY_RUNS = { 0, 2, 0 };

    private static final int[] HOST_TOKEN = { 0, 3, 0 };

    private RunnerVersions() {
    }

    /** Whether a host of this version may receive runs paid by a budget (a gateway's runs). */
    public static boolean supportsGatewayRuns(@Nullable String runnerVersion) {
        return atLeast(runnerVersion, GATEWAY_RUNS);
    }

    /**
     * Whether a host of this version sends its own token to the model relay, so that the relay refuses a run's token
     * without it.
     */
    public static boolean sendsHostTokenToRelay(@Nullable String runnerVersion) {
        return atLeast(runnerVersion, HOST_TOKEN);
    }

    private static boolean atLeast(@Nullable String runnerVersion, int[] minimum) {
        if (runnerVersion == null) {
            return false;
        }
        Matcher matcher = SEMVER.matcher(runnerVersion.trim());
        if (!matcher.matches()) {
            return false;
        }
        for (int part = 0; part < minimum.length; part++) {
            int value = Integer.parseInt(matcher.group(part + 1));
            if (value != minimum[part]) {
                return value > minimum[part];
            }
        }
        // The release itself, not one of its pre-releases.
        return matcher.group(4) == null;
    }
}
