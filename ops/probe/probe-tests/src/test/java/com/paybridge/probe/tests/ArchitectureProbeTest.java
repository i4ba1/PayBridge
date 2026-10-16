package com.paybridge.probe.tests;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Shape of the module-boundary rule that ModuleBoundaryIT will enforce in T02. */
@AnalyzeClasses(packages = "com.paybridge.probe")
class ArchitectureProbeTest {

    @ArchTest
    static final ArchRule domainDoesNotDependOnSpring = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
            .allowEmptyShould(false);
}
