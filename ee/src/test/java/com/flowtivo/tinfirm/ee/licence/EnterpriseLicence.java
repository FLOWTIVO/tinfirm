package com.flowtivo.tinfirm.ee.licence;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.flowtivo.tinfirm.company.DeclaredCompanies;

/**
 * TEST FIXTURE ONLY: the test class runs under an Enterprise key without a seat count or a cap of companies, signed by
 * the tests' key pair ({@link TestLicences}): the paid features of ADR-0077 in force (the audit's tools, retention
 * settings, legal holds, several declared companies). The key is put in force before the class's first test, as the
 * schema's owner since the API may not be reached yet, and the declarations that waited for it are applied; every key
 * is removed after the class, so that the next class runs as Community.
 */
public class EnterpriseLicence implements BeforeEachCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace
        .create(EnterpriseLicence.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        ExtensionContext.Store store = context.getParent().orElseThrow().getStore(NAMESPACE);
        if (store.get("applied") != null) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.plusYears(1));
        SpringExtension.getApplicationContext(context).getBean(DeclaredCompanies.class).apply();
        store.put("applied", true);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        TestLicences.clear();
    }
}
