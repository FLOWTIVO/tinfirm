package com.flowtivo.tinfirm.system.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.flowtivo.tinfirm.system.internal.http.ChatEndpoints;
import com.flowtivo.tinfirm.system.internal.http.FixedEndpoints;
import com.flowtivo.tinfirm.system.internal.http.GmailEndpoints;
import com.flowtivo.tinfirm.system.internal.http.OutboundProperties;
import com.flowtivo.tinfirm.system.internal.oauth.OAuthDiscoveryProperties;

/**
 * The settings of the system module: its outbound client's, the chat platforms' fixed endpoints, Google's of the
 * Gmail add-on, and the origins an MCP server's authorization server may name endpoints on.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ OutboundProperties.class, ChatEndpoints.class, GmailEndpoints.class,
        OAuthDiscoveryProperties.class })
class SystemConfiguration {

    /**
     * @throws IllegalStateException when a fixed endpoint is changed outside the tests' profiles
     *                               ({@link FixedEndpoints})
     */
    SystemConfiguration(Environment environment, ChatEndpoints chatEndpoints, GmailEndpoints gmailEndpoints) {
        FixedEndpoints.refuseChangesOutsideTests(environment, chatEndpoints, gmailEndpoints);
    }
}
