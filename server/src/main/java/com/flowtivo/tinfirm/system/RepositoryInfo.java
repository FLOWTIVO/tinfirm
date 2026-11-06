package com.flowtivo.tinfirm.system;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A git repository of a code host (the package 1 contract, §3.4).
 *
 * @param defaultBranch the branch work starts from; null: the repository's own default
 */
public record RepositoryInfo(UUID id, UUID companyId, UUID systemId, String url, String name,
        @Nullable String defaultBranch) {
}
