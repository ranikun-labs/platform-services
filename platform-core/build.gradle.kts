import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

plugins {
    base
}

group = "com.ranikunlabs.platform"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

private fun Project.declarableProjectDependencies(): Map<String, Set<String>> =
    configurations
            .filter { it.isCanBeDeclared }
            .mapNotNull { configuration ->
                val projectDependencies = configuration.dependencies
                        .withType(ProjectDependency::class.java)
                        .map { it.path }
                        .toSet()
                projectDependencies.takeIf { it.isNotEmpty() }?.let { configuration.name to it }
            }
            .toMap()

val verifyModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies the Platform Core host-to-feature dependency direction."

    doLast {
        val appProject = project(":app")
        val featureProjects = subprojects.filter { it != appProject }
        val featureProjectPaths = featureProjects.map { it.path }.toSet()
        val appProjectDependencies = appProject.declarableProjectDependencies()
                .values
                .flatten()
                .toSet()
        val platformCoreMainClasses = fileTree(rootDir) {
            include("*/src/main/**/*.java", "*/src/main/**/*.kt")
        }.filter { sourceFile ->
            val source = sourceFile.readText()
            source.contains("static void main(") || source.contains("fun main(")
        }

        check(appProject.plugins.hasPlugin("org.springframework.boot")) {
            "Platform Core app must own the executable Spring Boot plugin."
        }
        check(appProjectDependencies == featureProjectPaths) {
            "Platform Core app must compose every feature module; expected $featureProjectPaths, found $appProjectDependencies."
        }
        featureProjects.forEach { featureProject ->
            val featureDependencies = featureProject.declarableProjectDependencies()

            check(!featureProject.plugins.hasPlugin("org.springframework.boot")) {
                "${featureProject.path} must remain a library and must not apply the Spring Boot plugin."
            }
            check(featureProject.tasks.findByName("bootJar") == null) {
                "${featureProject.path} must not expose an executable bootJar deploy unit."
            }
            check(featureDependencies.isEmpty()) {
                "${featureProject.path} must not depend on another Platform Core project; found $featureDependencies."
            }
        }
        check(platformCoreMainClasses.map { it.relativeTo(rootDir).invariantSeparatorsPath } ==
                listOf("app/src/main/java/com/ranikunlabs/platform/core/PlatformCoreApplication.java")) {
            "Platform Core must have exactly one executable main in app; found $platformCoreMainClasses."
        }
    }
}

tasks.named("clean") {
    dependsOn(subprojects.map { "${it.path}:clean" })
}

tasks.named("assemble") {
    dependsOn(subprojects.map { "${it.path}:assemble" })
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn(verifyModuleBoundaries)
}

tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" })
}

tasks.register("test") {
    group = "verification"
    description = "Runs tests for all Platform Core modules."
    dependsOn(subprojects.map { "${it.path}:test" })
}
