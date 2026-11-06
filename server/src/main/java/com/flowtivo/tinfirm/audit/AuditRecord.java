package com.flowtivo.tinfirm.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.security.Actor;

/**
 * One event to append to an audit chain.
 * <p>
 * {@code details} holds only ids, roles and names of non-personal objects (company, secret): never emails,
 * display names, passwords or secret values. Values must be strings, integers, booleans, null, or lists and maps
 * of those.
 *
 * @param companyId the company chain, or null for the instance chain
 */
public record AuditRecord(@Nullable UUID companyId, Actor actor, AuditAction action, @Nullable String targetType,
        @Nullable String targetId, Map<String, @Nullable Object> details) {

    public AuditRecord {
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static Builder instance(AuditAction action, Actor actor) {
        return new Builder(null, action, actor);
    }

    public static Builder company(UUID companyId, AuditAction action, Actor actor) {
        return new Builder(companyId, action, actor);
    }

    public static final class Builder {

        private final @Nullable UUID companyId;

        private final AuditAction action;

        private final Actor actor;

        private @Nullable String targetType;

        private @Nullable String targetId;

        private final Map<String, @Nullable Object> details = new LinkedHashMap<>();

        private Builder(@Nullable UUID companyId, AuditAction action, Actor actor) {
            this.companyId = companyId;
            this.action = action;
            this.actor = actor;
        }

        public Builder target(String type, Object id) {
            this.targetType = type;
            this.targetId = id.toString();
            return this;
        }

        public Builder detail(String key, @Nullable Object value) {
            details.put(key, value);
            return this;
        }

        /** Each of the details, as {@link #detail} adds one. */
        public Builder details(java.util.Map<String, ? extends @Nullable Object> values) {
            details.putAll(values);
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(companyId, actor, action, targetType, targetId, details);
        }
    }
}
