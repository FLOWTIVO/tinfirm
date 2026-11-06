package com.flowtivo.tinfirm.identity.internal.saml;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.identity.internal.SamlSettings;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * Sweeps the SAML tables (V063) on a worker every ten minutes: the requests nobody answered, the assertions past
 * their validity, and the metadata of providers the deployment no longer declares.
 */
@Component
class SamlSweep {

    private static final Logger log = LoggerFactory.getLogger(SamlSweep.class);

    private final SamlAuthnRequests requests;

    private final SamlAssertionsSeen seen;

    private final SamlMetadataStore metadata;

    private final SamlSettings settings;

    private final TinfirmProperties tinfirm;

    private final TimeSource time;

    SamlSweep(SamlAuthnRequests requests, SamlAssertionsSeen seen, SamlMetadataStore metadata, SamlSettings settings,
            TinfirmProperties tinfirm, TimeSource time) {
        this.requests = requests;
        this.seen = seen;
        this.metadata = metadata;
        this.settings = settings;
        this.tinfirm = tinfirm;
        this.time = time;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    void sweep() {
        if (!tinfirm.hasRole(TinfirmProperties.WORKER_ROLE)) {
            return;
        }
        Instant now = time.now();
        int swept = requests.sweep(now) + seen.sweep(now)
                + metadata.deleteOthers(List.copyOf(settings.properties().providers().keySet()));
        if (swept > 0) {
            log.info("Swept {} expired SAML rows", swept);
        }
    }
}
