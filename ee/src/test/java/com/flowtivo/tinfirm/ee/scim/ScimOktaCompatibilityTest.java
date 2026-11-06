package com.flowtivo.tinfirm.ee.scim;

import org.junit.jupiter.api.Test;

/**
 * ADR-0087, decisions 13 and 14: the requests Okta's SCIM 2.0 client sends, replayed in their documented order: its
 * whole-user {@code PUT} without a role mapping never resets the role, and it deactivates with a {@code PATCH}.
 */
class ScimOktaCompatibilityTest extends ScimTest {

    @Test
    void theDocumentedSequence() {
        ScimRecordedTraffic.replay("okta.json", scim(scimCompany()), uniqueEmail("katherine"));
    }
}
