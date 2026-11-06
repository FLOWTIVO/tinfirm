package com.flowtivo.tinfirm.system;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Webhook systems (the package 5 contract, §3.1, §5.12), for the {@code trigger} module, which receives their
 * deliveries: a {@code WEBHOOK} system's scheme, header, mapping and rate, its secret and the previous one for 24
 * hours after a rotation. The secrets are managed company secrets the system keeps
 * ({@code WEBHOOK_SECRET_{HANDLE}}): Tinfirm generates them ({@code whsec_} and 32 random bytes in base64), the
 * Secrets API neither replaces nor deletes them, and they go with the system.
 */
public interface WebhookSystems {

    /** The header {@code SECRET_HEADER} reads when the system names none. */
    String DEFAULT_SECRET_HEADER = "X-Tinfirm-Secret";

    /** How long a rotated secret is still accepted. */
    java.time.Duration PREVIOUS_KEPT = java.time.Duration.ofHours(24);

    /** How a delivery proves it is the sender's (open point 15). */
    enum Scheme {
        /** Standard Webhooks: {@code webhook-id}, {@code webhook-timestamp}, {@code webhook-signature}. */
        STANDARD,
        /** The secret itself in a header, for a sender that cannot sign: weaker (§5.12). */
        SECRET_HEADER
    }

    /** What a mapping reads, each by a JSON Pointer (RFC 6901). */
    enum MappingField {
        EVENT("event"), SUBJECT_KEY("subject.key"), SUBJECT_TITLE("subject.title"), SUBJECT_URL("subject.url"),
        ACTOR_ID("actor.id"), ACTOR_NAME("actor.name"), TEXT("text"), DELIVERY_ID("deliveryId");

        private final String key;

        MappingField(String key) {
            this.key = key;
        }

        /** Its name in the mapping: {@code subject.key}. */
        public String key() {
            return key;
        }
    }

    /**
     * A webhook system.
     *
     * @param mapping        each mapped field's JSON Pointer, by {@link MappingField#key()}
     * @param maxPerMinute   its deliveries a minute; null: the company's default
     * @param secretSetAt    when its secret was generated or last rotated
     * @param previousUntil  until when the previous secret is accepted; null when none is
     */
    record WebhookSystem(UUID id, UUID companyId, String name, String handle, boolean paused, Scheme scheme,
            String secretHeader, Map<String, String> mapping, @Nullable Integer maxPerMinute,
            @Nullable Instant secretSetAt, @Nullable Instant previousUntil) {

        public WebhookSystem {
            mapping = Map.copyOf(mapping);
        }

        public @Nullable String pointer(MappingField field) {
            return mapping.get(field.key());
        }
    }

    /** The secrets a delivery may be verified with: the current one, and the previous for 24 hours. Never logged. */
    record Secrets(String current, @Nullable String previous) {

        @Override
        public String toString() {
            return "Secrets[***]";
        }
    }

    /**
     * The company of a webhook system by its id alone, for an inbound request (C2): the definer lookup
     * {@code webhook_system_by_id}, in a short transaction of its own without any company. Empty for anything else.
     */
    Optional<UUID> companyOf(UUID systemId);

    /** The company's webhook system, in the caller's transaction and tenant context. */
    Optional<WebhookSystem> find(UUID companyId, UUID systemId);

    /**
     * Reveals the system's secrets for one use, each audited as {@code secret.accessed} with the purpose
     * ({@code webhook-signing:{systemId}}). Outside any transaction: it reads and reveals in a short transaction of its
     * own. Empty when it keeps none.
     */
    Optional<Secrets> reveal(UUID companyId, UUID systemId, String purpose);

    /**
     * A new secret (§5.12): the current one is kept as the previous for 24 hours, the one before that goes. In the
     * caller's transaction, which authorised an admin; audited as {@code system.webhook_secret_rotated}. Answers the
     * new secret, which is shown once.
     */
    default String rotate(UUID companyId, UUID systemId) {
        return rotate(companyId, systemId, false);
    }

    /**
     * A new secret, as {@link #rotate(UUID, UUID)}; with {@code revokePrevious} the current one and any previous one
     * go at once instead of being kept (the security review of 2026-10-27, L8: a leaked secret is revoked by one
     * rotation), audited with {@code revokedPrevious}. Every replica drops a revoked secret at its next delivery, which
     * reads the system's {@code previousUntil}.
     */
    String rotate(UUID companyId, UUID systemId, boolean revokePrevious);

    /**
     * The company lowered its maximum of deliveries a minute: each webhook system above it is lowered to it, audited as
     * {@code system.changed}. In the caller's transaction. Answers how many were.
     */
    int lowerPerMinute(UUID companyId, int max);
}
