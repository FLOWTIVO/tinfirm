package com.flowtivo.tinfirm.vault;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What {@link VaultService#scan} found in some content (ADR-0014). Never contains a value.
 *
 * @param vaultSecrets names of the company's vault secrets whose current value occurs in the content, sorted
 * @param formats      names of the known credential formats that occur in the content, sorted
 * @param unscannable  why the content could not be scanned completely (an encrypted, damaged or too large
 *                     archive), or null
 */
public record ScanReport(List<String> vaultSecrets, List<String> formats, @Nullable String unscannable) {

    public ScanReport {
        vaultSecrets = List.copyOf(vaultSecrets);
        formats = List.copyOf(formats);
    }

    /** Nothing found, and everything was scanned. */
    public boolean clean() {
        return vaultSecrets.isEmpty() && formats.isEmpty() && unscannable == null;
    }
}
