package com.flowtivo.tinfirm.shared.licence;

/**
 * Published when another key came into force (ADR-0077): an instance admin applied one, or the next key took over on
 * its start date. What depends on the licence and is applied only at the start (the declared companies) is applied
 * again after the commit.
 */
public record LicenceChanged() {
}
