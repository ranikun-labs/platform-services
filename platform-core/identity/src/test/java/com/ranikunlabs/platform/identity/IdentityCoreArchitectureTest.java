package com.ranikunlabs.platform.identity;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.ranikunlabs.platform.identity")
class IdentityCoreArchitectureTest {

    @ArchTest
    static final ArchRule frameworkNeutralCoreAndPortsUseOnlyJdkAndIdentityContracts = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.ranikunlabs.platform.identity.core..",
                    "com.ranikunlabs.platform.identity.application.port.."
            )
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages(
                    "java..",
                    "com.ranikunlabs.platform.identity.core..",
                    "com.ranikunlabs.platform.identity.application.port.."
            );
}
