package com.flowtivo.tinfirm.trigger;

/**
 * What an active trigger waits on (the package 4 contract, §3.1, §5.3, §5.4 and §11): it fires nothing until the event
 * that repairs it ({@code PrincipalResumed}, {@code ModelKeyStatusChanged}, the system answering again). Since the
 * review (§11, point 9) an account that cannot be used pauses the trigger ({@code ACCOUNT_BROKEN}) instead of
 * {@code RECONNECT_YOURS}, {@code RECONNECT_COMPANY} and {@code SUSPENDED} of a holder; {@code SUSPENDED} stays for a
 * suspended payer, and the poll's {@code 401} makes the poll wait. {@code COMPANY_STOPPED}: the company's runs are
 * stopped (the package 6 contract, §5.4, open point 7); the lift re-arms the trigger. Package 5's channel triggers wait
 * on their chat system's bot ({@code BOT_MISSING}, {@code BOT_RECONNECT}, {@code BOT_SUSPENDED}: the package 5
 * contract's open point 21, a deliberate exception to decision 9) and go on by themselves once it can be used, and on
 * their channels ({@code CHANNEL_UNAVAILABLE}: every one of them is).
 */
public enum WaitingReason {
    RECONNECT_YOURS, RECONNECT_COMPANY, SUSPENDED, SYSTEM_UNREACHABLE, SCOPE_UNREADABLE, GATEWAY_NOT_CONFIGURED,
    COMPANY_STOPPED, BOT_MISSING, BOT_RECONNECT, BOT_SUSPENDED, CHANNEL_UNAVAILABLE
}
