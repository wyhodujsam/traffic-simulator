package com.trafficsimulator.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.*;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.trafficsimulator",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule layered_architecture =
            layeredArchitecture()
                    .consideringAllDependencies()
                    .layer("Controller")
                    .definedBy("..controller..")
                    .layer("Engine")
                    .definedBy("..engine..")
                    .layer("Model")
                    .definedBy("..model..")
                    .layer("DTO")
                    .definedBy("..dto..")
                    .layer("Config")
                    .definedBy("..config..")
                    .layer("Scheduler")
                    .definedBy("..scheduler..")
                    .whereLayer("Controller")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Scheduler")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Model")
                    .mayOnlyBeAccessedByLayers(
                            "Engine", "Controller", "Config", "DTO", "Scheduler");

    @ArchTest
    static final ArchRule no_field_injection =
            noFields()
                    .should()
                    .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                    .because("Use constructor injection instead of field injection");

    @ArchTest
    static final ArchRule no_generic_exceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

    @ArchTest static final ArchRule use_slf4j = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest static final ArchRule no_stdout = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule controllers_annotated =
            classes()
                    .that()
                    .resideInAPackage("..controller..")
                    .and()
                    .haveSimpleNameEndingWith("Controller")
                    .should()
                    .beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .orShould()
                    .beAnnotatedWith("org.springframework.stereotype.Controller");

    @ArchTest
    static final ArchRule dtos_independent_from_model =
            noClasses()
                    .that()
                    .resideInAPackage("..dto..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..engine..")
                    .because("DTOs should not depend on engine internals");
}
