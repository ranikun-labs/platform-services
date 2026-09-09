package com.ranikunlabs.platform.sharedaudit.architecture;

import com.ranikunlabs.platform.sharedaudit.core.AuditEvent;
import com.ranikunlabs.platform.sharedaudit.core.AuditEventId;
import com.ranikunlabs.platform.sharedaudit.core.AuditEventType;
import com.ranikunlabs.platform.sharedaudit.ports.AuditPublicationResult;
import com.ranikunlabs.platform.sharedaudit.ports.AuditPublisher;
import com.ranikunlabs.platform.sharedaudit.policy.AuditEventPolicy;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@AnalyzeClasses(packages = "com.ranikunlabs.platform.sharedaudit")
class SharedAuditArchitectureTest {

    @ArchTest
    static final ArchRule neutralPackagesRemainFrameworkAndTransportFree = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.ranikunlabs.platform.sharedaudit.core..",
                    "com.ranikunlabs.platform.sharedaudit.ports..",
                    "com.ranikunlabs.platform.sharedaudit.policy..",
                    "com.ranikunlabs.platform.sharedaudit.application..",
                    "com.ranikunlabs.platform.sharedaudit.internal..",
                    "com.ranikunlabs.platform.sharedaudit.adapters.capture..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "reactor..",
                    "org.reactivestreams..",
                    "jakarta.persistence..",
                    "org.springframework.data.redis..",
                    "io.nats..",
                    "com.ranikunlabs.platform.identity..",
                    "com.ranikunlabs.platform.sharedai..")
            .because("Shared Audit neutral contracts must not leak frameworks, transport, persistence, or features");

    @Test
    void moduleIsAHostedJavaLibraryAndNotAnExecutable() throws Exception {
        Path moduleRoot = Path.of(System.getProperty("platformCoreRoot")).resolve("shared-audit");
        String buildFile = Files.readString(moduleRoot.resolve("build.gradle.kts"));
        List<Path> mainSources = sourceFiles(moduleRoot.resolve("src/main"));

        assertThat(buildFile).contains("java-library")
                .doesNotContain("libs.plugins.spring.boot", "bootJar");
        assertThat(mainSources).allSatisfy(source -> assertThat(Files.readString(source))
                .doesNotContain("static void main(", "fun main(", "@SpringBootApplication"));
        assertThat(Files.exists(moduleRoot.resolve("Dockerfile"))).isFalse();
    }

    @Test
    void moduleHasNoFeatureProjectDependency() throws Exception {
        Path buildFile = Path.of(System.getProperty("platformCoreRoot"))
                .resolve("shared-audit/build.gradle.kts");

        assertThat(Files.readString(buildFile)).doesNotContain("implementation(project(", "api(project(");
    }

    @Test
    void publicContractsHaveFrameworkFreeSignatures() {
        List<Class<?>> contractTypes = List.of(
                AuditPublisher.class,
                AuditPublicationResult.class,
                AuditPublicationResult.Accepted.class,
                AuditPublicationResult.Rejected.class,
                AuditPublicationResult.Failed.class,
                AuditEvent.class,
                AuditEventId.class,
                AuditEventType.class,
                AuditEventPolicy.class);
        List<String> forbidden = List.of(
                "org.springframework",
                "reactor",
                "org.reactivestreams",
                "jakarta.persistence",
                "io.nats",
                "com.ranikunlabs.platform.identity",
                "com.ranikunlabs.platform.sharedai");

        contractTypes.forEach(type -> assertThat(publicSignatureTypes(type))
                .describedAs(type.getName())
                .noneMatch(signature -> forbidden.stream().anyMatch(signature::contains)));
    }

    private static List<Path> sourceFiles(Path root) throws Exception {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path)
                            && (path.toString().endsWith(".java") || path.toString().endsWith(".kt")))
                    .toList();
        }
    }

    private static List<String> publicSignatureTypes(Class<?> type) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        if (type.getGenericSuperclass() != null) {
            names.addAll(typeNames(type.getGenericSuperclass()));
        }
        for (Type genericInterface : type.getGenericInterfaces()) {
            names.addAll(typeNames(genericInterface));
        }
        for (var constructor : type.getConstructors()) {
            if (!Modifier.isPublic(constructor.getModifiers()) || constructor.isSynthetic()) {
                continue;
            }
            for (Type parameter : constructor.getGenericParameterTypes()) {
                names.addAll(typeNames(parameter));
            }
            for (Type exception : constructor.getGenericExceptionTypes()) {
                names.addAll(typeNames(exception));
            }
        }
        for (var method : type.getMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            names.addAll(typeNames(method.getGenericReturnType()));
            for (Type parameter : method.getGenericParameterTypes()) {
                names.addAll(typeNames(parameter));
            }
            for (Type exception : method.getGenericExceptionTypes()) {
                names.addAll(typeNames(exception));
            }
        }
        return names;
    }

    private static List<String> typeNames(Type type) {
        return typeNames(type, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static List<String> typeNames(Type type, java.util.Set<Type> visited) {
        if (!visited.add(type)) {
            return List.of(type.getTypeName());
        }
        if (type instanceof Class<?> clazz) {
            return clazz.isArray()
                    ? java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(clazz.getName()),
                            typeNames(clazz.getComponentType(), visited).stream()).toList()
                    : List.of(clazz.getName());
        }
        if (type instanceof ParameterizedType parameterized) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            names.addAll(typeNames(parameterized.getRawType(), visited));
            for (Type argument : parameterized.getActualTypeArguments()) {
                names.addAll(typeNames(argument, visited));
            }
            if (parameterized.getOwnerType() != null) {
                names.addAll(typeNames(parameterized.getOwnerType(), visited));
            }
            return names;
        }
        if (type instanceof GenericArrayType array) {
            return typeNames(array.getGenericComponentType(), visited);
        }
        if (type instanceof WildcardType wildcard) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            for (Type bound : wildcard.getUpperBounds()) {
                names.addAll(typeNames(bound, visited));
            }
            for (Type bound : wildcard.getLowerBounds()) {
                names.addAll(typeNames(bound, visited));
            }
            return names;
        }
        if (type instanceof TypeVariable<?> variable) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            names.add(variable.getTypeName());
            for (Type bound : variable.getBounds()) {
                names.addAll(typeNames(bound, visited));
            }
            return names;
        }
        return List.of(type.getTypeName());
    }
}
