package com.flowtivo.tinfirm.run;

/**
 * Why a turn starts (the package 3 contract, §3.11, §5.7): the conversation's first message ({@code FIRST}), a
 * person's message ({@code MESSAGE}), the last open decision decided ({@code DECIDED}), a binding resolved with parked
 * calls ({@code CONNECTED}), a retry of a failed or cancelled turn ({@code RETRY}). Package 4 adds {@code EVENT} (a
 * trigger fired again on the subject of its open conversation, the package 4 contract §5.6) and
 * {@code REVIEW_RETURNED} (the team returned a result under review with a note, §5.8).
 */
public enum TurnReason {
    FIRST, MESSAGE, DECIDED, CONNECTED, RETRY, EVENT, REVIEW_RETURNED
}
