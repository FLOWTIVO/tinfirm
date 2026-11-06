package com.flowtivo.tinfirm.account.internal;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * The one OAuth callback of accounts (the package 1 contract, §3.8 and §6): consumes the pending sign-in of the state
 * once, in a transaction of its own, so that a failure later never makes it usable again; only the caller's own from
 * the same session (another session's stays pending), and only within its ten minutes. Then derives its PKCE verifier
 * from the state again ({@link SignInVerifiers}), completes it in its company ({@link AccountService#completeSignIn})
 * and answers where the browser goes: the sign-in's {@code returnTo} (a path of this site), or {@code /} for a state it
 * does not know, with {@code account=} the outcome.
 */
@Component
class SignInCallback {

    private final SignInStore signIns;

    private final AccountService accounts;

    private final SignInVerifiers verifiers;

    private final TransactionTemplate transactions;

    private final TimeSource time;

    SignInCallback(SignInStore signIns, AccountService accounts, SignInVerifiers verifiers,
            PlatformTransactionManager transactionManager, TimeSource time) {
        this.signIns = signIns;
        this.accounts = accounts;
        this.verifiers = verifiers;
        this.transactions = new TransactionTemplate(transactionManager);
        this.time = time;
    }

    /** The path to send the browser to, with the outcome in its query. */
    String complete(@Nullable String code, @Nullable String state, @Nullable String error, UUID userId,
            String sessionId) {
        if (state == null || state.isBlank()) {
            return back("/", "invalid_state");
        }
        String stateHash = SignInSecrets.hash(state);
        String sessionHash = SignInSecrets.hash(sessionId);
        Optional<SignInStore.Request> consumed = Optional.ofNullable(
                transactions.execute(status -> signIns.consume(stateHash, userId, sessionHash).orElse(null)));
        if (consumed.isEmpty()) {
            return back("/", "invalid_state");
        }
        SignInStore.Request request = consumed.get();
        if (!request.expiresAt().isAfter(time.now())) {
            return back(request.returnTo(), "expired");
        }
        if (error != null && !error.isBlank()) {
            return back(request.returnTo(), "denied");
        }
        if (code == null || code.isBlank()) {
            return back(request.returnTo(), "exchange_failed");
        }
        String outcome;
        try {
            // The verifier is not kept: derived again from the state, which only the browser held.
            outcome = accounts.completeSignIn(request, verifiers.of(state), code);
        }
        catch (ApiException ex) {
            outcome = "forbidden";
        }
        return "connected".equals(outcome) ? back(request.returnTo(), "connected&system=" + request.systemId())
                : back(request.returnTo(), outcome);
    }

    private static String back(String path, String outcome) {
        return path + (path.contains("?") ? "&" : "?") + "account=" + outcome;
    }
}
