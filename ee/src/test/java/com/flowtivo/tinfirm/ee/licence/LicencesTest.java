package com.flowtivo.tinfirm.ee.licence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.licence.Edition;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.LicenceState;
import com.flowtivo.tinfirm.shared.licence.LicenceStatus;

/** ADR-0077, decisions 4, 6 and 7: what the key in force and the next one put in force on a day. */
class LicencesTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    private static final LocalDate END = LocalDate.of(2026, 12, 31);

    private static LicencePayload pro(int seats, LocalDate start, LocalDate end) {
        return new LicencePayload("a", 1, "Acme", Edition.PRO, seats, null, start, end);
    }

    private static LicencePayload enterprise(Integer seats, Integer companies) {
        return new LicencePayload("e", 1, "Acme", Edition.ENTERPRISE, seats, companies, START, END);
    }

    @Test
    void noKeyIsCommunity() {
        assertThat(Licences.status(null, null, START)).isEqualTo(LicenceStatus.COMMUNITY);
        assertThat(LicenceStatus.COMMUNITY.features()).isEmpty();
        assertThat(LicenceStatus.COMMUNITY.countsSeats()).isFalse();
    }

    @Test
    void aProKeyInForce() {
        LicenceStatus status = Licences.status(pro(10, START, END), null, LocalDate.of(2026, 6, 1));

        assertThat(status.edition()).isEqualTo(Edition.PRO);
        assertThat(status.state()).isEqualTo(LicenceState.ACTIVE);
        assertThat(status.seatsLicensed()).isEqualTo(10);
        assertThat(status.companiesLicensed()).isEqualTo(1);
        assertThat(status.endsAt()).isEqualTo(END);
        assertThat(status.graceEndsAt()).isEqualTo(END.plusDays(30));
        assertThat(status.auditExportUntil()).isEqualTo(END.plusDays(120));
        assertThat(status.features()).containsExactlyInAnyOrder(Feature.OUTLOOK_ADDIN, Feature.GMAIL_ADDON,
                Feature.AUDIT_TOOLS, Feature.AUDIT_FULL_HISTORY, Feature.AUDIT_ANCHOR, Feature.RETENTION_SETTINGS,
                Feature.ELASTIC_RUNNERS);
    }

    @Test
    void theNextKeyTakesOverOnItsStartDate() {
        LicencePayload next = new LicencePayload("b", 1, "Acme", Edition.ENTERPRISE, 40, null, END.plusDays(1),
                END.plusYears(1));

        assertThat(Licences.status(pro(10, START, END), next, END).edition()).isEqualTo(Edition.PRO);
        LicenceStatus renewed = Licences.status(pro(10, START, END), next, END.plusDays(1));
        assertThat(renewed.edition()).isEqualTo(Edition.ENTERPRISE);
        assertThat(renewed.seatsLicensed()).isEqualTo(40);
        assertThat(renewed.endsAt()).isEqualTo(END.plusYears(1));
        assertThat(Licences.status(null, next, END).state()).as("only a renewal held: Community until its start")
            .isEqualTo(LicenceState.NONE);
    }

    @Test
    void enterpriseCountsWhatItsKeyCounts() {
        LicenceStatus counted = Licences.status(enterprise(50, 3), null, START);
        assertThat(counted.seatsLicensed()).isEqualTo(50);
        assertThat(counted.companiesLicensed()).isEqualTo(3);
        assertThat(counted.features()).contains(Feature.LEGAL_HOLDS, Feature.MULTI_COMPANY, Feature.SAML,
                Feature.OUTLOOK_ADDIN);

        LicenceStatus uncounted = Licences.status(enterprise(null, null), null, START);
        assertThat(uncounted.seatsLicensed()).isNull();
        assertThat(uncounted.companiesLicensed()).isNull();
    }

    @Test
    void expiringFromThirtyDaysBeforeTheEnd() {
        LicencePayload key = pro(10, START, END);

        assertThat(Licences.status(key, null, END.minusDays(31)).state()).isEqualTo(LicenceState.ACTIVE);
        assertThat(Licences.status(key, null, END.minusDays(30)).state()).isEqualTo(LicenceState.EXPIRING);
        assertThat(Licences.status(key, null, END).state()).isEqualTo(LicenceState.EXPIRING);
    }

    @Test
    void aRenewalThatFollowsWithoutAGapIsNotExpiring() {
        LicencePayload key = pro(10, START, END);

        assertThat(Licences.status(key, pro(10, END.plusDays(1), END.plusYears(1)), END.minusDays(10)).state())
            .isEqualTo(LicenceState.ACTIVE);
        assertThat(Licences.status(key, pro(10, END.plusDays(2), END.plusYears(1)), END.minusDays(10)).state())
            .isEqualTo(LicenceState.EXPIRING);
    }

    @Test
    void graceForThirtyDaysThenExpired() {
        LicencePayload key = enterprise(20, 2);

        LicenceStatus grace = Licences.status(key, null, END.plusDays(30));
        assertThat(grace.state()).isEqualTo(LicenceState.GRACE);
        assertThat(grace.edition()).isEqualTo(Edition.ENTERPRISE);
        assertThat(grace.seatsLicensed()).isEqualTo(20);
        assertThat(grace.features()).contains(Feature.LEGAL_HOLDS);

        LicenceStatus expired = Licences.status(key, null, END.plusDays(31));
        assertThat(expired.state()).isEqualTo(LicenceState.EXPIRED);
        assertThat(expired.edition()).isEqualTo(Edition.COMMUNITY);
        assertThat(expired.lapsedEdition()).isEqualTo(Edition.ENTERPRISE);
        assertThat(expired.countsSeats()).as("the roles stay, and no seat is counted").isFalse();
        assertThat(expired.features()).as("SAML keeps working, SCIM stops").containsExactly(Feature.SAML);
        assertThat(expired.auditExportUntil()).isEqualTo(END.plusDays(120));
    }

    @Test
    void anExpiredProKeepsNoFeature() {
        LicenceStatus expired = Licences.status(pro(10, START, END), null, END.plusDays(100));

        assertThat(expired.features()).isEmpty();
        assertThat(expired.lapsedEdition()).isEqualTo(Edition.PRO);
    }
}
