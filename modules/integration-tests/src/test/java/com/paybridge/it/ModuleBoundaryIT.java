package com.paybridge.it;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Module boundary rules from TIS section 3. Fails the build when a layer reaches across its boundary.
 * Written as plain Jupiter tests because the ArchUnit JUnit engine ignores Jupiter tag filters.
 */
@Tag("integration")
class ModuleBoundaryIT {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.paybridge");
    }

    @Test
    void everyModuleWithCodeIsImported() {
        // Guards against the rules below passing vacuously because nothing was imported.
        assertThat(classes.containPackage("com.paybridge.domain.payment")).isTrue();
        assertThat(classes.containPackage("com.paybridge.contracts")).isTrue();
        assertThat(classes.containPackage("com.paybridge.core")).isTrue();
        assertThat(classes.containPackage("com.paybridge.console")).isTrue();
        assertThat(classes.containPackage("com.paybridge.simulator")).isTrue();
    }

    @Test
    void domainIsPlainJava() {
        noClasses().that().resideInAPackage("com.paybridge.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "com.fasterxml..")
                .check(classes);
    }

    @Test
    void contractsHaveNoFrameworkDependencies() {
        noClasses().that().resideInAPackage("com.paybridge.contracts..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "com.fasterxml..")
                .check(classes);
    }

    @Test
    void portsDoNotReferenceAdapters() {
        // No application classes exist yet; the rule starts to bite when T05 adds the ports.
        noClasses().that().resideInAPackage("com.paybridge.application..")
                .should().dependOnClassesThat().resideInAPackage("com.paybridge.adapter..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void consoleDoesNotReachCoreOrAdapters() {
        noClasses().that().resideInAPackage("com.paybridge.console..")
                .should().dependOnClassesThat().resideInAnyPackage("com.paybridge.core..", "com.paybridge.adapter..")
                .check(classes);
    }

    @Test
    void coreAndAdaptersNeverReachTheSimulatorInternals() {
        // The core talks to the simulator over HTTP only; its database is closed to the core by separate credentials.
        noClasses().that().resideInAnyPackage("com.paybridge.core..", "com.paybridge.adapter..", "com.paybridge.application..")
                .should().dependOnClassesThat().resideInAPackage("com.paybridge.simulator..")
                .check(classes);
    }

    @Test
    void theProviderAdapterPerformsNoSql() {
        assertThat(classes.containPackage("com.paybridge.adapter.provider")).isTrue();
        noClasses().that().resideInAPackage("com.paybridge.adapter.provider..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..",
                        "org.springframework.data..", "jakarta.persistence..", "org.hibernate..")
                .check(classes);
    }

    @Test
    void simulatorIsIndependent() {
        noClasses().that().resideInAPackage("com.paybridge.simulator..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.paybridge.core..", "com.paybridge.adapter..", "com.paybridge.application..",
                        "com.paybridge.console..")
                .check(classes);
    }
}
