package com.flowtivo.tinfirm.system.internal.tools;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.internal.github.GitHubTracker;
import com.flowtivo.tinfirm.system.internal.gitlab.GitLabTracker;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.jira.JiraTracker;
import com.flowtivo.tinfirm.system.internal.oauth.OAuthProfile;

/**
 * The tracker of a system for one call with one credential, at the system's own origin: its base URL, or, for a Jira
 * Cloud OAuth grant, Atlassian's gateway for the site, which must be {@code https://api.atlassian.com/ex/jira/{cloudId}}
 * (any other address the credential names is ignored, so a token goes only where it is valid). A credential the
 * tracker cannot use fails {@code UNAUTHORIZED}: the account must be connected again.
 */
public final class Trackers {

    /** A cloud id after Atlassian's gateway. */
    private static final Pattern CLOUD_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private Trackers() {
    }

    /** The tracker of the kind, or why the credential cannot call it. */
    public static CallResult<Tracker> of(OutboundHttp http, SystemKind kind, @Nullable String baseUrl,
            CallCredential credential) {
        String token = credential.token();
        if (baseUrl == null || !kind.tracker()) {
            return CallResult.failed(CallResult.Kind.INVALID, "A " + kind + " system has no tracker");
        }
        if (token == null || token.isBlank()) {
            return CallResult.failed(CallResult.Kind.UNAUTHORIZED, "The account holds no credential to call the "
                    + "system with");
        }
        return switch (kind) {
            case JIRA_DC -> CallResult.ok(JiraTracker.dataCenter(http, baseUrl, token));
            case JIRA_CLOUD -> jiraCloud(http, baseUrl, credential, token);
            case GITHUB -> CallResult.ok(new GitHubTracker(http, baseUrl, token));
            case GITLAB -> CallResult.ok(new GitLabTracker(http, baseUrl, token, credential.oauth()));
            default -> CallResult.failed(CallResult.Kind.INVALID, "A " + kind + " system has no tracker");
        };
    }

    /**
     * A poll's session of reads of the tracker (the package 4 contract, §5.4), at the same origin as its tools, or why
     * the credential cannot call it.
     */
    public static CallResult<com.flowtivo.tinfirm.system.SystemPolls.Session> polls(OutboundHttp http,
            SystemKind kind, @Nullable String baseUrl, CallCredential credential) {
        String token = credential.token();
        if (baseUrl == null || !kind.tracker()) {
            return CallResult.failed(CallResult.Kind.INVALID, "A " + kind + " system is not polled");
        }
        if (token == null || token.isBlank()) {
            return CallResult.failed(CallResult.Kind.UNAUTHORIZED, "The account holds no credential to call the "
                    + "system with");
        }
        return switch (kind) {
            case JIRA_DC -> CallResult.ok(com.flowtivo.tinfirm.system.internal.jira.JiraPolls.dataCenter(http, baseUrl,
                    token));
            case JIRA_CLOUD -> {
                if (!credential.oauth()) {
                    String problem = JiraTracker.cloudCredentialProblem(token);
                    yield problem == null ? CallResult.ok(com.flowtivo.tinfirm.system.internal.jira.JiraPolls.cloud(
                            http, baseUrl, baseUrl, token, false))
                            : CallResult.failed(CallResult.Kind.UNAUTHORIZED, problem);
                }
                String gateway = credential.apiBaseUrl();
                if (gateway == null || !gateway.startsWith(OAuthProfile.ATLASSIAN_GATEWAY)
                        || !CLOUD_ID.matcher(gateway.substring(OAuthProfile.ATLASSIAN_GATEWAY.length())).matches()) {
                    yield CallResult.failed(CallResult.Kind.UNAUTHORIZED, "The account's Atlassian grant names no "
                            + "gateway for the site; it must be connected again");
                }
                yield CallResult.ok(com.flowtivo.tinfirm.system.internal.jira.JiraPolls.cloud(http, baseUrl, gateway,
                        token, true));
            }
            case GITHUB -> CallResult.ok(new com.flowtivo.tinfirm.system.internal.github.GitHubPolls(http, baseUrl,
                    token));
            case GITLAB -> CallResult.ok(new com.flowtivo.tinfirm.system.internal.gitlab.GitLabPolls(http, baseUrl,
                    token, credential.oauth()));
            default -> CallResult.failed(CallResult.Kind.INVALID, "A " + kind + " system is not polled");
        };
    }

    /** The code host of a GitHub or GitLab system, or why it has none or the credential cannot call it. */
    public static CallResult<Tracker.CodeHost> codeHost(OutboundHttp http, SystemKind kind, @Nullable String baseUrl,
            CallCredential credential) {
        if (kind != SystemKind.GITHUB && kind != SystemKind.GITLAB) {
            return CallResult.failed(CallResult.Kind.INVALID, "A repository of a " + kind + " system gets no change "
                    + "request: only GitHub and GitLab have an API for them");
        }
        return of(http, kind, baseUrl, credential).map(tracker -> (Tracker.CodeHost) tracker);
    }

    private static CallResult<Tracker> jiraCloud(OutboundHttp http, String siteUrl, CallCredential credential,
            String token) {
        if (!credential.oauth()) {
            String problem = JiraTracker.cloudCredentialProblem(token);
            return problem == null ? CallResult.ok(JiraTracker.cloud(http, siteUrl, siteUrl, token, false))
                    : CallResult.failed(CallResult.Kind.UNAUTHORIZED, problem);
        }
        String gateway = credential.apiBaseUrl();
        if (gateway == null || !gateway.startsWith(OAuthProfile.ATLASSIAN_GATEWAY)
                || !CLOUD_ID.matcher(gateway.substring(OAuthProfile.ATLASSIAN_GATEWAY.length())).matches()) {
            return CallResult.failed(CallResult.Kind.UNAUTHORIZED, "The account's Atlassian grant names no gateway "
                    + "for the site; it must be connected again");
        }
        return CallResult.ok(JiraTracker.cloud(http, siteUrl, gateway, token, true));
    }
}
