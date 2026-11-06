package com.flowtivo.tinfirm.trigger.internal;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Seam for part b: the subjects a tracker trigger may run on now ({@code GET /triggers/{triggerId}/subjects?query=},
 * the package 4 contract §6), read with its source account in its scope, outside any transaction, after part a's API
 * checked that the caller sees the trigger. At most 20, newest change first. Without part b's bean: none.
 */
public interface SubjectSearch {

    List<SubjectHit> search(UUID companyId, UUID triggerId, @Nullable String query);

    /**
     * A subject in the trigger's scope.
     *
     * @param kind {@code ISSUE} or {@code CHANGE_REQUEST}
     */
    record SubjectHit(String key, String kind, @Nullable String title, @Nullable String status,
            @Nullable String url) {
    }
}
