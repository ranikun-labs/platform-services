package com.ranikunlabs.platform.identity;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.ranikunlabs.platform.identity")
class IdentityAuthArchitectureTest {

    @ArchTest
    static final ArchRule applicationOrchestrationDoesNotImportInfrastructure = noClasses()
            .that()
            .resideInAnyPackage("com.ranikunlabs.platform.identity.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "jakarta.persistence..",
                    "org.springframework.data.redis..",
                    "io.jsonwebtoken..",
                    "carelog.."
            );

    @ArchTest
    static final ArchRule applicationOrchestrationDoesNotImportHttpFramework = noClasses()
            .that()
            .resideInAnyPackage("com.ranikunlabs.platform.identity.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "jakarta.servlet..",
                    "org.springframework.http..",
                    "org.springframework.web..",
                    "org.springframework.security.."
            );

    @ArchTest
    static final ArchRule httpAdapterDoesNotBypassInfrastructureAdapters = noClasses()
            .that()
            .resideInAnyPackage("com.ranikunlabs.platform.identity.adapter.http..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.ranikunlabs.platform.identity.adapter.persistence..",
                    "com.ranikunlabs.platform.identity.adapter.redis..",
                    "com.ranikunlabs.platform.identity.adapter.oauth.kakao.."
            );

    @ArchTest
    static final ArchRule identityImplementationDoesNotImportCarelogTypes = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("carelog..", "carelog.carelog..", "carelog.gateway..");
}
