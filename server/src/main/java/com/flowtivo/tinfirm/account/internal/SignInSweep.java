package com.flowtivo.tinfirm.account.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.shared.time.TimeSource;

/** Sweeps the sign-ins nobody completed, ten minutes after they expired at the latest. */
@Component
class SignInSweep {

    private static final Logger log = LoggerFactory.getLogger(SignInSweep.class);

    private final SignInStore signIns;

    private final TimeSource time;

    SignInSweep(SignInStore signIns, TimeSource time) {
        this.signIns = signIns;
        this.time = time;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    @Transactional
    public void sweep() {
        int swept = signIns.deleteExpired(time.now());
        if (swept > 0) {
            log.info("Swept {} expired sign-ins", swept);
        }
    }
}
