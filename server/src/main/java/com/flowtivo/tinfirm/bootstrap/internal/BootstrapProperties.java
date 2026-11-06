package com.flowtivo.tinfirm.bootstrap.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param adminEmail    {@code TINFIRM_BOOTSTRAP_ADMIN_EMAIL}
 * @param adminPassword {@code TINFIRM_BOOTSTRAP_ADMIN_PASSWORD}
 */
@ConfigurationProperties("tinfirm.bootstrap")
record BootstrapProperties(@Nullable String adminEmail, @Nullable String adminPassword) {

    boolean configured() {
        return adminEmail != null && !adminEmail.isBlank() && adminPassword != null && !adminPassword.isEmpty();
    }

    @Override
    public String toString() {
        return "BootstrapProperties[adminEmail=" + adminEmail + ", adminPassword=***]";
    }
}
