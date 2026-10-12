package io.github.hemanthvk980.shortener;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Layering is enforced by the build, so agents (or humans) can't erode it unnoticed. */
@AnalyzeClasses(packages = "io.github.hemanthvk980.shortener", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layers = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Api").definedBy("..link.api..")
            .layer("Service").definedBy("..link.service..")
            .layer("Repository").definedBy("..link.repo..")
            .layer("Domain").definedBy("..link.domain..")
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Api")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service");

    @ArchTest
    static final ArchRule domainIsFrameworkLight = noClasses().that().resideInAPackage("..link.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");

    @ArchTest
    static final ArchRule controllersDontTouchRepositories = noClasses().that().resideInAPackage("..link.api..")
            .should().dependOnClassesThat().resideInAPackage("..link.repo..");
}
