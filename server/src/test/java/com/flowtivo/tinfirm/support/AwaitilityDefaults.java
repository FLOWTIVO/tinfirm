package com.flowtivo.tinfirm.support;

import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The tests' defaults of Awaitility, registered for every test class ({@code junit-platform.properties}): the first
 * check at once, and then every 50 ms. Awaitility's own defaults wait 100 ms before the first check and between
 * checks, which every {@code await()} paid even when what it waits for had already happened. Not faster than 50 ms:
 * some checks are HTTP requests to the server whose work they wait for.
 */
public final class AwaitilityDefaults implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        Awaitility.setDefaultPollDelay(Duration.ZERO);
        Awaitility.setDefaultPollInterval(Duration.ofMillis(50));
    }
}
