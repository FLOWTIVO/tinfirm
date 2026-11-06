package com.flowtivo.tinfirm.shared.mapping;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** MapStruct conversions: timestamps are exposed as RFC 3339 in UTC. */
@Component
public class TimeMapping {

    public @Nullable OffsetDateTime toOffsetDateTime(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
