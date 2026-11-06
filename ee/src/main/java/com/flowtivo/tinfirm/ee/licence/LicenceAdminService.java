package com.flowtivo.tinfirm.ee.licence;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.api.model.AdminLicence;
import com.flowtivo.tinfirm.shared.api.model.LicenceKeyView;
import com.flowtivo.tinfirm.shared.licence.LicenceChanged;
import com.flowtivo.tinfirm.shared.licence.LicenceStatus;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * {@code /api/admin/licence} (ADR-0077, decisions 5 and 6), instance admins only: the key in force and the next one,
 * without their strings, and a key applied once it verifies. One key is in force at a time: a key that starts by
 * today replaces it, a later one (a renewal) is held as the next key in place of any held. A key of a licence held
 * with a revision not higher than the one held is refused, and so is a key with fewer seats than are used. Applying
 * takes the seat lock, as a grant of a role does, and is audited in the instance chain.
 */
@Service
class LicenceAdminService {

    private final LicenceKeyStore store;

    private final LicenceService licence;

    private final LicenceTrust trust;

    private final UserDirectory users;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final ApplicationEventPublisher events;

    LicenceAdminService(LicenceKeyStore store, LicenceService licence, LicenceTrust trust, UserDirectory users,
            AuditLog auditLog, TimeSource time, ApplicationEventPublisher events) {
        this.events = events;
        this.store = store;
        this.licence = licence;
        this.trust = trust;
        this.users = users;
        this.auditLog = auditLog;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public AdminLicence view() {
        CurrentActor.requireInstanceAdmin();
        LicenceKeyStore.KeyRow current = store.find(LicenceKeyStore.Slot.CURRENT).orElse(null);
        LicenceKeyStore.KeyRow next = store.find(LicenceKeyStore.Slot.NEXT).orElse(null);
        Map<UUID, UserSummary> names = users.findAllById(Stream.of(current, next)
            .filter(Objects::nonNull)
            .map(LicenceKeyStore.KeyRow::appliedBy)
            .filter(Objects::nonNull)
            .distinct()
            .toList());
        return new AdminLicence().current(current != null ? dto(current, names) : null)
            .next(next != null ? dto(next, names) : null);
    }

    @Transactional
    public AdminLicence apply(String text) {
        CurrentActor.requireInstanceAdmin();
        String key = LicenceKeyFormat.normalise(text);
        LicencePayload payload;
        try {
            payload = LicenceKeyFormat.verify(key, trust.keys());
        }
        catch (LicenceKeyFormat.InvalidKey ex) {
            throw ApiException.badRequest(ProblemCode.LICENCE_KEY_INVALID, ex.getMessage());
        }
        store.lockSeats();
        licence.activateNext();
        LocalDate today = licence.today();
        if (payload.endsAt().isBefore(today)) {
            throw ApiException.conflict(ProblemCode.LICENCE_KEY_EXPIRED, "This key ended on " + payload.endsAt());
        }
        LicenceKeyStore.KeyRow current = store.find(LicenceKeyStore.Slot.CURRENT).orElse(null);
        LicenceKeyStore.KeyRow next = store.find(LicenceKeyStore.Slot.NEXT).orElse(null);
        for (LicenceKeyStore.KeyRow held : Stream.of(current, next).filter(Objects::nonNull).toList()) {
            if (held.licenceId().equals(payload.id()) && payload.rev() <= held.rev()) {
                throw ApiException.conflict(ProblemCode.LICENCE_KEY_SUPERSEDED, "The instance holds revision "
                        + held.rev() + " of licence " + held.licenceId() + ": a key of it needs a higher revision");
            }
        }
        boolean later = payload.startsAt().isAfter(today);
        LicenceKeyStore.Slot slot = later ? LicenceKeyStore.Slot.NEXT : LicenceKeyStore.Slot.CURRENT;
        LicenceKeyStore.KeyRow replaced = later ? next : current;
        // The seats in use must fit on the day the key comes into force, today for one that replaces the key in force.
        requireSeats(payload, later ? payload.startsAt() : today);
        LicenceKeyStore.KeyRow row = new LicenceKeyStore.KeyRow(slot, payload.id(), payload.rev(),
                payload.customer(), payload.edition(), payload.seats(), payload.companies(), payload.startsAt(),
                payload.endsAt(), key, time.now(), CurrentActor.requireUser().id());
        store.put(row);
        if (!later && next != null && next.licenceId().equals(payload.id()) && next.rev() < payload.rev()) {
            // A later revision of the renewal itself: the renewal it supersedes goes.
            store.clear(LicenceKeyStore.Slot.NEXT);
        }
        auditLog.record(AuditRecord.instance(AuditAction.LICENCE_KEY_APPLIED, CurrentActor.actor())
            .target("licence", payload.id())
            .detail("licenceId", payload.id())
            .detail("rev", payload.rev())
            .detail("edition", payload.edition().name())
            .detail("seats", payload.seats())
            .detail("companies", payload.companies())
            .detail("startsAt", payload.startsAt().toString())
            .detail("endsAt", payload.endsAt().toString())
            .detail("next", later)
            .detail("replaced", replaced != null ? ref(replaced) : null)
            .build());
        if (!later) {
            events.publishEvent(new LicenceChanged());
        }
        return view();
    }

    /** The audit's reference to a key: its licence and revision, never the key. */
    static Map<String, Object> ref(LicenceKeyStore.KeyRow row) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("licenceId", row.licenceId());
        ref.put("rev", row.rev());
        return ref;
    }

    /** {@code 409 LICENCE_SEATS_TOO_FEW} when the key would count fewer seats than are in use. */
    private void requireSeats(LicencePayload key, LocalDate day) {
        LicenceStatus status = Licences.status(key, null, day);
        Integer licensed = status.seatsLicensed();
        int used = store.seatsUsed();
        if (licensed != null && licensed < used) {
            throw ApiException.conflict(ProblemCode.LICENCE_SEATS_TOO_FEW, used + " people hold a seat, and the key "
                    + "holds " + licensed + ": make Members Viewers first",
                    Map.of("details", Map.of("required", used, "licensed", licensed)));
        }
    }

    private static LicenceKeyView dto(LicenceKeyStore.KeyRow row, Map<UUID, UserSummary> names) {
        UserSummary appliedBy = row.appliedBy() != null ? names.get(row.appliedBy()) : null;
        return new LicenceKeyView(row.licenceId(), row.rev(), row.customer(),
                com.flowtivo.tinfirm.shared.api.model.Edition.fromValue(row.edition().name()), row.startsAt(),
                row.endsAt(), row.appliedAt().atOffset(ZoneOffset.UTC))
            .seats(row.seats())
            .companies(row.companies())
            .appliedBy(row.appliedBy())
            .appliedByName(appliedBy != null ? appliedBy.displayName() : null);
    }
}
