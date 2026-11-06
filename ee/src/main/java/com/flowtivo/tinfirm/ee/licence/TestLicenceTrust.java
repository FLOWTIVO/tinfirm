package com.flowtivo.tinfirm.ee.licence;

import java.security.PublicKey;

/**
 * TEST ONLY: a public key the tests sign their licence keys with, beside FLOWTIVO's ({@link LicenceTrust}). Only a
 * bean makes one, which only the tests define; the server refuses to start with one outside the {@code test} profile.
 */
public record TestLicenceTrust(PublicKey publicKey) {
}
