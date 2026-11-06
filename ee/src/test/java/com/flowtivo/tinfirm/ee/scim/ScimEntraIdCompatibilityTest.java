package com.flowtivo.tinfirm.ee.scim;

import org.junit.jupiter.api.Test;

/**
 * ADR-0087, decisions 13 and 14: the requests Entra ID's provisioning service sends, with and without the flag
 * {@code aadOptscim062020} that makes its {@code PATCH} follow RFC 7644, replayed in their documented order.
 */
class ScimEntraIdCompatibilityTest extends ScimTest {

    @Test
    void theDocumentedSequenceWithTheFlag() {
        ScimRecordedTraffic.replay("entra-id.json", scim(scimCompany()), uniqueEmail("grace"));
    }

    @Test
    void theSequenceWithoutTheFlag() {
        ScimRecordedTraffic.replay("entra-id-legacy.json", scim(scimCompany()), uniqueEmail("hedy"));
    }
}
