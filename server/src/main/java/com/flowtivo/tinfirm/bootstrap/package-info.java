/**
 * First start: creates the first instance admin when the user table is empty and both bootstrap variables are set
 * (CONVENTIONS §5). Then, at every start, applies the deployment's declared companies (ADR-0075), whose owner is that
 * admin unless a declaration names another. Idempotent and audited with actor SYSTEM.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Bootstrap")
package com.flowtivo.tinfirm.bootstrap;
