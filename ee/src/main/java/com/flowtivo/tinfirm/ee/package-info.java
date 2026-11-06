/**
 * Tinfirm's commercial part, {@code ee/} (ADR-0077, decision 1): in every image, beside the MIT core, under the
 * Tinfirm Enterprise licence. This module is the licence: the keys an instance admin enters, verified offline
 * against the public key they name of those the server ships with ({@code ee/licence-public-keys/}) at every start
 * and every hour; the edition, the features and the state they put in force; and the count of seats across the
 * instance. The core reads it through
 * {@link com.flowtivo.tinfirm.shared.licence.Licence}, which this module implements, and which {@code Licensing}
 * answers as Community without it.
 * <p>
 * It stands on {@code audit}, {@code identity}, {@code company}, {@code conversation} (the port {@code InboxItems}) and
 * {@code shared}, through their public types only; no other module depends on it. The paid features' code is under
 * {@code ee/} too, in the packages of the modules it belongs to (ADR-0077, decision 1; CONVENTIONS §15.24).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Enterprise")
package com.flowtivo.tinfirm.ee;
