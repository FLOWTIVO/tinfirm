package com.flowtivo.tinfirm.trigger;

/**
 * Why a row of the write-back outbox was queued ({@code outbox.cause}, the package 4 contract, §5.9): a turn's result
 * and its actions, a failure and its actions, the questions of a turn, an answer given in Tinfirm, a change request
 * opened for a Jira issue. Package 5 adds the chat's causes (the package 5 contract, §5.9): a message taken
 * ({@code RECEIVED}), an approval's card, a card's update once decided ({@code DECIDED}), a Connect card and its update
 * ({@code CONNECT}, {@code CONNECTED}), and Tinfirm's notes ({@code NOTICE}).
 */
public enum OutboxCause {
    RESULT, FAILURE, QUESTION, ANSWER, ON_SUCCESS, ON_FAILURE, CHANGE_REQUEST, RECEIVED, APPROVAL, DECIDED, CONNECT,
    CONNECTED, NOTICE
}
