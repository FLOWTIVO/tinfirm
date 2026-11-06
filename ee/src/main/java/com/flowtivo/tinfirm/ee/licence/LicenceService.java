package com.flowtivo.tinfirm.ee.licence;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licence;
import com.flowtivo.tinfirm.shared.licence.LicenceChanged;
import com.flowtivo.tinfirm.shared.licence.LicenceStatus;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The licence in force (ADR-0077): the key in force and the next one in {@code licence_key}, each verified against
 * {@link LicenceTrust} once, at the start and again every hour, then the edition's logic of {@link Licences} on
 * today's date (UTC). A key that no longer verifies (another public key in a new image, a row changed in the
 * database) puts nothing in force and is logged at every verification. The next key rules from its start date; the
 * hourly pass then moves it into the slot of the key in force, audited {@code licence.key.activated}.
 */
@Service
class LicenceService implements Licence {

    private static final Logger log = LoggerFactory.getLogger(LicenceService.class);

    private final LicenceKeyStore store;

    private final LicenceTrust trust;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final ApplicationEventPublisher events;

    /** The verified keys by the text they were verified from; emptied every hour. */
    private final Map<String, Verified> verified = new ConcurrentHashMap<>();

    /**
     * The features in force at the last pass, so that a change by the date alone (an Enterprise key past its grace) is
     * announced as {@link LicenceChanged} too, without a restart (ADR-0088, decision 7); null before the first pass.
     */
    private volatile @Nullable Set<Feature> lastFeatures;

    private record Verified(@Nullable LicencePayload payload) {
    }

    /** The two keys held, each null when none is held or it does not verify. */
    record Held(@Nullable LicencePayload current, @Nullable LicencePayload next) {
    }

    LicenceService(LicenceKeyStore store, LicenceTrust trust, AuditLog auditLog, TimeSource time,
            ApplicationEventPublisher events) {
        this.events = events;
        this.store = store;
        this.trust = trust;
        this.auditLog = auditLog;
        this.time = time;
    }

    LocalDate today() {
        return LocalDate.ofInstant(time.now(), ZoneOffset.UTC);
    }

    @Override
    @Transactional(readOnly = true)
    public LicenceStatus status() {
        Held held = held();
        return Licences.status(held.current(), held.next(), today());
    }

    @Override
    @Transactional(readOnly = true)
    public int seatsUsed() {
        return store.seatsUsed();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean seatAvailableFor(UUID userId) {
        store.lockSeats();
        Integer limit = seatLimit();
        return limit == null || store.holdsSeat(userId) || store.seatsUsed() < limit;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean seatAvailableForReactivation(UUID userId) {
        store.lockSeats();
        Integer limit = seatLimit();
        return limit == null || !store.hasSeatRole(userId) || store.seatsUsed() < limit;
    }

    /**
     * The seats a role may be given within: the licence's in force, and a renewal's held for later, which takes over
     * with no more seats than it holds; the smaller of them, null when neither counts.
     */
    @Override
    @Transactional(readOnly = true)
    public @Nullable Integer seatLimit() {
        Held held = held();
        LocalDate today = today();
        Integer licensed = Licences.status(held.current(), held.next(), today).seatsLicensed();
        LicencePayload next = held.next();
        Integer later = next != null && next.startsAt().isAfter(today) ? next.seats() : null;
        if (licensed == null) {
            return later;
        }
        return later == null ? licensed : Integer.valueOf(Math.min(licensed, later));
    }

    /** The keys held, as they verify; each verified once until the hourly verification. */
    Held held() {
        return new Held(payload(store.find(LicenceKeyStore.Slot.CURRENT).orElse(null)),
                payload(store.find(LicenceKeyStore.Slot.NEXT).orElse(null)));
    }

    private @Nullable LicencePayload payload(LicenceKeyStore.@Nullable KeyRow row) {
        if (row == null) {
            return null;
        }
        Verified known = verified.computeIfAbsent(row.keyText(), text -> new Verified(verify(row)));
        LicencePayload payload = known.payload();
        if (payload != null && !payload.equals(new LicencePayload(row.licenceId(), row.rev(), row.customer(),
                row.edition(), row.seats(), row.companies(), row.startsAt(), row.endsAt()))) {
            log.error("The {} licence key (licence {}) does not match its row: it puts nothing in force", row.slot(),
                    row.licenceId());
            return null;
        }
        return payload;
    }

    private @Nullable LicencePayload verify(LicenceKeyStore.KeyRow row) {
        try {
            return LicenceKeyFormat.verify(row.keyText(), trust.keys());
        }
        catch (LicenceKeyFormat.InvalidKey ex) {
            log.error("The {} licence key (licence {}) does not verify, and puts nothing in force: {}", row.slot(),
                    row.licenceId(), ex.getMessage());
            return null;
        }
    }

    /**
     * At the start, and every hour after: every key verified again; the next key, once it started, moved into the
     * slot of the key in force (on one replica: the seat lock, and a slot read again under it); the licence logged.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${tinfirm.licence.verify-interval:PT1H}",
            initialDelayString = "${tinfirm.licence.verify-interval:PT1H}")
    @Transactional
    public void verifyAll() {
        verified.clear();
        activateNext();
        LicenceStatus status = status();
        Set<Feature> before = lastFeatures;
        lastFeatures = status.features();
        if (before != null && !before.equals(status.features())) {
            events.publishEvent(new LicenceChanged());
        }
        log.info("Licence: {} ({}){}{}", status.edition(), status.state(),
                status.endsAt() != null ? ", ends " + status.endsAt() : "",
                status.seatsLicensed() != null ? ", " + status.seatsLicensed() + " seats" : "");
    }

    /** The next key takes over on its start date (decision 6), audited {@code licence.key.activated} by SYSTEM. */
    void activateNext() {
        LicenceKeyStore.KeyRow next = store.find(LicenceKeyStore.Slot.NEXT).orElse(null);
        if (next == null || next.startsAt().isAfter(today())) {
            return;
        }
        store.lockSeats();
        next = store.find(LicenceKeyStore.Slot.NEXT).orElse(null);
        if (next == null || next.startsAt().isAfter(today()) || payload(next) == null) {
            // A next key that does not verify, or whose row no longer matches it, never replaces the key in force.
            return;
        }
        LicenceKeyStore.KeyRow replaced = store.find(LicenceKeyStore.Slot.CURRENT).orElse(null);
        store.clear(LicenceKeyStore.Slot.NEXT);
        store.put(next.in(LicenceKeyStore.Slot.CURRENT));
        auditLog.record(AuditRecord.instance(AuditAction.LICENCE_KEY_ACTIVATED, Actor.SYSTEM)
            .target("licence", next.licenceId())
            .detail("licenceId", next.licenceId())
            .detail("rev", next.rev())
            .detail("replaced", replaced != null ? LicenceAdminService.ref(replaced) : null)
            .build());
        events.publishEvent(new LicenceChanged());
    }
}
