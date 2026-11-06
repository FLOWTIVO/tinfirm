package com.flowtivo.tinfirm.ee.licence;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.LicenceApi;
import com.flowtivo.tinfirm.shared.api.model.Edition;
import com.flowtivo.tinfirm.shared.api.model.Licence;
import com.flowtivo.tinfirm.shared.api.model.AdminLicence;
import com.flowtivo.tinfirm.shared.api.model.LicenceFeature;
import com.flowtivo.tinfirm.shared.api.model.LicenceKeyRequest;
import com.flowtivo.tinfirm.shared.api.model.LicenceSeats;
import com.flowtivo.tinfirm.shared.api.model.LicenceState;
import com.flowtivo.tinfirm.shared.licence.LicenceStatus;
import com.flowtivo.tinfirm.shared.security.CurrentActor;

/** {@code GET /api/licence} for every signed-in user, and the instance admins' {@code /api/admin/licence}. */
@RestController
class LicenceController implements LicenceApi {

    private final LicenceService licence;

    private final LicenceAdminService keys;

    LicenceController(LicenceService licence, LicenceAdminService keys) {
        this.licence = licence;
        this.keys = keys;
    }

    @Override
    public ResponseEntity<Licence> getLicence() {
        CurrentActor.requireUser();
        LicenceStatus status = licence.status();
        Licence body = new Licence(Edition.fromValue(status.edition().name()),
                LicenceState.fromValue(status.state().name()),
                new LicenceSeats(licence.seatsUsed()).licensed(status.seatsLicensed()),
                status.features().stream().map(feature -> LicenceFeature.fromValue(feature.name())).sorted().toList())
            .customer(status.customer())
            .endsAt(status.endsAt())
            .graceEndsAt(status.graceEndsAt())
            .auditExportUntil(status.auditExportUntil())
            .companiesLicensed(status.companiesLicensed());
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<AdminLicence> getAdminLicence() {
        return ResponseEntity.ok(keys.view());
    }

    @Override
    public ResponseEntity<AdminLicence> putLicenceKey(LicenceKeyRequest request) {
        return ResponseEntity.ok(keys.apply(request.getKey()));
    }
}
