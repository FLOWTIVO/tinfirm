package com.flowtivo.tinfirm.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.internal.SamlProperties;
import com.flowtivo.tinfirm.identity.internal.SamlSettings;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * H4 of the security review of 2026-10-27: a provider's IdP is pinned by its first good document, a later one that
 * names another is refused and audited {@code saml.idp_entity_id_changed}, a document that names another provider's
 * IdP or an OIDC issuer is refused, and stored documents that do so stop the start.
 */
class SamlMetadataRefreshTest {

    private static final String PINNED = "https://idp.test/metadata";

    private static final String OTHER = "https://idp.test/other";

    private static final SamlTestIdp IDP = new SamlTestIdp(PINNED);

    private final SamlSettings settings = mock(SamlSettings.class);

    private final SamlMetadataStore store = mock(SamlMetadataStore.class);

    private final AuditLog auditLog = mock(AuditLog.class);

    private final SystemTransactions transactions = mock(SystemTransactions.class);

    private final LockProvider locks = mock(LockProvider.class);

    private final TimeSource time = new TimeSource(Clock.systemUTC());

    SamlMetadataRefreshTest() {
        when(transactions.instanceWide(any())).thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
        when(settings.oidcIssuers()).thenReturn(Set.of());
    }

    @Test
    void aDocumentThatNamesAnotherIdpIsRefusedAndAuditedOnce() throws IOException {
        SamlProperties.Provider provider = provider(document(OTHER), null);
        declare(Map.of("corp", provider));
        SamlMetadataStore.Row good = row("corp", PINNED, null);
        when(store.find("corp")).thenReturn(Optional.of(good));

        assertThat(refresh(false).refresh("corp", provider, good)).isFalse();

        verify(store).failed(eq("corp"), eq("entity_id_changed"), any(), any());
        verify(store, never()).stored(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<AuditRecord> audited = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditLog).record(audited.capture());
        assertThat(audited.getValue().action()).isEqualTo(AuditAction.SAML_IDP_ENTITY_ID_CHANGED);
        assertThat(audited.getValue().details()).containsEntry("provider", "corp")
            .containsEntry("pinned", PINNED)
            .containsEntry("named", OTHER);

        // While the refusal lasts, a read is refused again and not audited again.
        assertThat(refresh(false).refresh("corp", provider, row("corp", PINNED, "entity_id_changed"))).isFalse();
        verify(auditLog, times(1)).record(any());
    }

    @Test
    void theSettingIsThePinOnceTheDeploymentSetsIt() throws IOException {
        SamlProperties.Provider provider = provider(document(OTHER), OTHER);
        declare(Map.of("corp", provider));
        SamlMetadataStore.Row good = row("corp", PINNED, null);
        when(store.find("corp")).thenReturn(Optional.of(good));

        assertThat(refresh(false).refresh("corp", provider, good)).isTrue();

        verify(store).stored(eq("corp"), any(), any(), any(), any(), any());
    }

    @Test
    void aDocumentThatNamesAnotherProvidersIdpOrAnOidcIssuerIsRefused() throws IOException {
        SamlProperties.Provider corp = provider(document(PINNED), null);
        SamlProperties.Provider other = provider(document(PINNED), null);
        declare(Map.of("corp", corp, "other", other));
        when(store.find("corp")).thenReturn(Optional.empty());
        when(store.find("other")).thenReturn(Optional.of(row("other", PINNED, null)));

        assertThat(refresh(false).refresh("corp", corp, null)).isFalse();

        verify(store).failed(eq("corp"), eq("entity_id_in_use"), any(), any());
        ArgumentCaptor<AuditRecord> audited = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditLog).record(audited.capture());
        assertThat(audited.getValue().action()).isEqualTo(AuditAction.SAML_METADATA_FAILED);
        assertThat(audited.getValue().details()).containsEntry("reason", "entity_id_in_use");

        // An OIDC provider's issuer, alone.
        declare(Map.of("corp", corp));
        when(settings.oidcIssuers()).thenReturn(Set.of(PINNED));
        assertThat(refresh(false).refresh("corp", corp, null)).isFalse();
        verify(store, times(2)).failed(eq("corp"), eq("entity_id_in_use"), any(), any());
        verify(store, never()).stored(any(), any(), any(), any(), any(), any());
    }

    @Test
    void twoProvidersThatReadOneDocumentAreOneIdp() throws IOException {
        SamlProperties.Provider corp = provider(document(PINNED), null);
        SamlProperties.Provider encrypted = provider(corp.metadataLocation(), null);
        declare(Map.of("corp", corp, "corpenc", encrypted));
        when(store.find("corp")).thenReturn(Optional.empty());
        when(store.find("corpenc")).thenReturn(Optional.of(row("corpenc", PINNED, null)));

        assertThat(refresh(false).refresh("corp", corp, null)).isTrue();

        verify(store).stored(eq("corp"), any(), any(), any(), any(), any());
    }

    @Test
    void storedDocumentsThatNameOneIdpFromDifferentMetadataStopTheStart() throws IOException {
        SamlProperties.Provider first = provider(document(PINNED), null);
        SamlProperties.Provider second = provider(document(PINNED), null);
        declare(Map.of("first", first, "second", second));
        when(settings.declared()).thenReturn(true);
        when(store.find("first")).thenReturn(Optional.of(row("first", PINNED, null)));
        when(store.find("second")).thenReturn(Optional.of(row("second", PINNED, null)));
        when(locks.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));

        assertThatIllegalStateException().isThrownBy(() -> refresh(true))
            .withMessageContaining("the SAML providers first and second name the IdP " + PINNED);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private SamlMetadataRefresh refresh(boolean worker) {
        TinfirmProperties tinfirm = new TinfirmProperties(URI.create("https://tinfirm.example.com"),
                worker ? Set.of("api", TinfirmProperties.WORKER_ROLE) : Set.of("api"));
        return new SamlMetadataRefresh(settings, store, auditLog, transactions, locks, time, tinfirm,
                mock(Licensing.class));
    }

    private void declare(Map<String, SamlProperties.Provider> providers) {
        when(settings.properties()).thenReturn(new SamlProperties(providers, new SamlProperties.Sp(Map.of(), null)));
    }

    /** A metadata file of the test IdP that names this entity ID; its location. */
    private static String document(String entityId) throws IOException {
        Path file = Files.createTempFile("saml-metadata", ".xml");
        file.toFile().deleteOnExit();
        Files.writeString(file, IDP.metadata().replace(PINNED, entityId));
        return "file:" + file;
    }

    /** A provider that reads this metadata, with this {@code _ENTITY_ID} or none. */
    private static SamlProperties.Provider provider(String metadataLocation, String entityId) {
        // A mock: the settings' types are the core's own, not this package's.
        SamlProperties.Provider provider = mock(SamlProperties.Provider.class);
        when(provider.metadataLocation()).thenReturn(metadataLocation);
        when(provider.entityId()).thenReturn(entityId);
        when(provider.metadataRefresh()).thenReturn(Duration.ofHours(12));
        return provider;
    }

    /** A provider's row with a good document that named this IdP, and the last read's error, if any. */
    private SamlMetadataStore.Row row(String id, String entityId, String lastError) {
        Instant now = time.now();
        return new SamlMetadataStore.Row(id, "<md:EntityDescriptor/>", "0".repeat(64), entityId, List.of(), null, now,
                lastError, lastError != null ? now : null, now);
    }
}
