/**
 * Shared kernel: tenant context, security primitives (actor, company access port), problem details,
 * cursor pagination, UUIDv7 ids, time, and the API interfaces and DTOs generated from
 * {@code api/openapi.yaml} (packages {@code shared.api} and {@code shared.api.model}).
 * <p>
 * The module is {@link org.springframework.modulith.ApplicationModule.Type#OPEN open}: every other module may
 * use all of its types. It depends on no other module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Shared kernel", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.flowtivo.tinfirm.shared;
