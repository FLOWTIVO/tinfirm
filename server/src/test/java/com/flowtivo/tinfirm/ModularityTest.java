package com.flowtivo.tinfirm;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import com.flowtivo.tinfirm.shared.licence.Feature;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/** CONVENTIONS §10: module boundaries are verified in the build. */
class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(TinfirmApplication.class);

    @Test
    void verifiesModuleBoundariesAndTheAbsenceOfCycles() {
        MODULES.verify();
    }

    /**
     * The package 6 contract, §2.3: {@code governance} stands on the content modules through their public types, and
     * nothing below it depends on it (only {@code mcp} may, for its tools).
     */
    @Test
    void nothingButTheMcpModuleDependsOnGovernance() {
        MODULES.stream()
            .filter(module -> !module.getIdentifier().toString().equals("governance"))
            .filter(module -> !module.getIdentifier().toString().equals("mcp"))
            .forEach(module -> assertThat(module.getDirectDependencies(MODULES).containsModuleNamed("governance"))
                .as("%s depends on governance", module.getIdentifier())
                .isFalse());
    }

    /** ADR-0077: the core reads the licence through {@code shared.licence}, never {@code ee} itself. */
    @Test
    void nothingDependsOnTheEnterpriseModule() {
        MODULES.stream()
            .filter(module -> !module.getIdentifier().toString().equals("ee"))
            .forEach(module -> assertThat(module.getDirectDependencies(MODULES).containsModuleNamed("ee"))
                .as("%s depends on ee", module.getIdentifier())
                .isFalse());
    }

    /**
     * ADR-0077, decision 1: the paid features' code is under {@code ee/}, in the packages of the modules it belongs to,
     * and the core reaches it only through interfaces of its own, found when used. No class outside {@code ee/} names
     * one under it, so that the core builds and runs as Community without it.
     */
    @Test
    void noClassOfTheCoreNamesOneUnderEe() throws IOException {
        Set<String> underEe = classesUnderEe();
        assertThat(underEe).contains("com.flowtivo.tinfirm.ee.licence.Licences",
                "com.flowtivo.tinfirm.audit.internal.AuditToolsImpl");
        DescribedPredicate<JavaClass> core = DescribedPredicate.describe("are outside ee/",
                javaClass -> !underEe.contains(javaClass.getName().split("\\$", 2)[0]));
        DescribedPredicate<JavaClass> ee = DescribedPredicate.describe("are under ee/",
                javaClass -> underEe.contains(javaClass.getName().split("\\$", 2)[0]));
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.flowtivo.tinfirm");
        noClasses().that(core).should().dependOnClassesThat(ee).check(classes);
    }

    @Test
    void hasExactlyTheConventionalModules() {
        assertThat(MODULES.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
            .containsExactlyInAnyOrder("shared", "identity", "company", "audit", "vault", "bootstrap", "runner",
                    "mcp", "principal", "system", "account", "model", "agent", "run", "conversation", "governance",
                    "trigger", "chat", "skill", "diagnostics", "ee");
    }

    /**
     * ADR-0077, decision 1: a paid feature's code is under {@code ee/}, and the core names {@link Feature} only where
     * it keeps a limit that serves every edition (CONVENTIONS §15.24) or answers Community without {@code ee/}. A new
     * gate in the core fails here until it is either moved under {@code ee/} or added to this list on purpose.
     */
    @Test
    void onlyTheCoresKnownGatesNameAFeature() throws IOException {
        Set<String> underEe = classesUnderEe();
        Set<String> gates = Set.of(
                // The limits that stay in the core, which serve every edition.
                "com.flowtivo.tinfirm.audit.internal.AuditPages",
                "com.flowtivo.tinfirm.company.internal.DeclaredCompaniesService",
                "com.flowtivo.tinfirm.governance.internal.RetentionPolicy",
                "com.flowtivo.tinfirm.mcp.internal.McpRequestFilter",
                "com.flowtivo.tinfirm.mcp.internal.McpFilesController",
                "com.flowtivo.tinfirm.runner.internal.RunnerWebSocketHandler",
                // The core's fallbacks: Community, 403 EDITION_REQUIRED, when ee/ is absent.
                "com.flowtivo.tinfirm.audit.internal.AuditService",
                "com.flowtivo.tinfirm.governance.internal.HoldService",
                "com.flowtivo.tinfirm.governance.internal.RetentionService",
                "com.flowtivo.tinfirm.mcp.internal.OutlookController",
                "com.flowtivo.tinfirm.mcp.internal.GmailController",
                "com.flowtivo.tinfirm.company.internal.ScimTokenService",
                "com.flowtivo.tinfirm.runner.internal.RunnerHostService");
        DescribedPredicate<JavaClass> outsideTheGates = DescribedPredicate.describe(
                "are outside ee/, shared.licence and the core's known gates",
                javaClass -> {
                    String name = javaClass.getName().split("\\$", 2)[0];
                    return !underEe.contains(name) && !gates.contains(name)
                            && !name.startsWith("com.flowtivo.tinfirm.shared.licence.");
                });
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.flowtivo.tinfirm");
        noClasses().that(outsideTheGates)
            .should().dependOnClassesThat().areAssignableTo(Feature.class)
            .because("a paid feature's code belongs under ee/ (ADR-0077, decision 1)")
            .check(classes);
    }

    /** The classes under {@code ee/src/main/java}, by their names, from the sources. */
    private static Set<String> classesUnderEe() throws IOException {
        Path sources = Path.of("..", "ee", "src", "main", "java");
        try (Stream<Path> files = Files.walk(sources)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                .map(file -> sources.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "."))
                .map(name -> name.substring(0, name.length() - ".java".length()))
                .collect(Collectors.toSet());
        }
    }
}
