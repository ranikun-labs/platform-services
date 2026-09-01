package com.ranikunlabs.platform.sharedai.architecture

import com.ranikunlabs.platform.sharedai.application.ExecutionCommand
import com.ranikunlabs.platform.sharedai.application.ExecutionResult
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService
import com.ranikunlabs.platform.sharedai.application.SkillHandler
import com.ranikunlabs.platform.sharedai.core.Evidence
import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.ports.ExecutionMeasurement
import com.ranikunlabs.platform.sharedai.ports.ExecutionStage
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ModelRequest
import com.ranikunlabs.platform.sharedai.ports.ModelResult
import com.ranikunlabs.platform.sharedai.ports.ModelUsage
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort
import com.ranikunlabs.platform.sharedai.ports.RetrievedItem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

class SharedAiArchitectureTest {
    private val platformCoreRoot: Path = Path.of(System.getProperty("platformCoreRoot"))
    private val sharedAiRoot = platformCoreRoot.resolve("shared-ai")

    @Test
    fun `shared AI remains a hosted non-executable library`() {
        val mainSources = sourceFiles(sharedAiRoot.resolve("src/main"))
        val buildFile = sharedAiRoot.resolve("build.gradle.kts").readText()

        assertThat(mainSources).noneMatch { it.readText().contains("fun main(") }
        assertThat(mainSources).noneMatch { it.readText().contains("@SpringBootApplication") }
        assertThat(buildFile).doesNotContain("libs.plugins.spring.boot", "bootJar")
        assertThat(Files.exists(sharedAiRoot.resolve("Dockerfile"))).isFalse()
    }

    @Test
    fun `framework types remain outside application core and ports`() {
        val neutralRoots = listOf("application", "core", "ports").map {
            sharedAiRoot.resolve("src/main/kotlin/com/ranikunlabs/platform/sharedai/$it")
        }
        val forbidden = listOf(
            "org.springframework.ai",
            "ChatClient",
            "ChatModel",
            "ToolCallback",
            "VectorStore",
            "Advisor",
            "com.openai",
            "reactor",
            "org.reactivestreams",
            "SseEmitter",
            "kotlinx.coroutines",
        )

        neutralRoots.flatMap(::sourceFiles).forEach { source ->
            assertThat(source.readText()).describedAs(source.toString()).doesNotContain(*forbidden.toTypedArray())
        }
    }

    @Test
    fun `provider-specific failure taxonomy remains below the application boundary`() {
        val neutralRoots = listOf("application", "core", "ports").map {
            sharedAiRoot.resolve("src/main/kotlin/com/ranikunlabs/platform/sharedai/$it")
        }
        val forbidden = listOf(
            "OpenAiFailureReason",
            "OpenAiProviderFailure",
            "ModelFailureCategory",
            "RATE_LIMITED",
            "PROVIDER_UNAVAILABLE",
            "AUTHENTICATION",
            "INVALID_REQUEST",
        )

        neutralRoots.flatMap(::sourceFiles).forEach { source ->
            assertThat(source.readText()).describedAs(source.toString()).doesNotContain(*forbidden.toTypedArray())
        }
    }

    @Test
    fun `module dependencies contain neither Product persistence nor feature projects`() {
        val buildFile = sharedAiRoot.resolve("build.gradle.kts").readText()

        assertThat(buildFile).doesNotContain(
            "project(\"",
            "jdbc",
            "jpa",
            "r2dbc",
            "postgresql",
            "pgvector",
            "spring-ai-starter-model-openai",
        )
        assertThat(buildFile).contains("libs.spring.ai.openai")
    }

    @Test
    fun `public neutral contract constructors and signatures remain framework-free`() {
        val contractTypes = listOf(
            ExecutionContext::class.java,
            ExecutionScope::class.java,
            Evidence::class.java,
            ExecutionCommand::class.java,
            ExecutionResult.Success::class.java,
            ExecutionResult.TechnicalFailure::class.java,
            SharedAiExecutionService::class.java,
            SkillHandler::class.java,
            ProductFact::class.java,
            ProductToolPort::class.java,
            RetrievedItem::class.java,
            RetrieverPort::class.java,
            ModelRequest::class.java,
            ModelGateway::class.java,
            ModelResult::class.java,
            ModelUsage::class.java,
            ExecutionStage::class.java,
            ExecutionMeasurement::class.java,
        )
        val forbidden = listOf(
            "org.springframework",
            "com.openai",
            "reactor",
            "org.reactivestreams",
            "kotlin.coroutines",
            "kotlinx.coroutines",
            "kotlin.jvm.functions",
            "kotlin.Result",
            "kotlin.Pair",
            "kotlin.Triple",
            "SseEmitter",
            "ServerSentEvent",
            "Flux",
            "Mono",
            "Publisher",
        )

        contractTypes.forEach { type ->
            assertThat(publicSignatureTypes(type))
                .describedAs(type.name)
                .noneMatch { signature -> forbidden.any(signature::contains) }
        }
    }

    private fun publicSignatureTypes(type: Class<*>): List<String> = buildList {
        type.genericSuperclass?.let { addAll(typeNames(it)) }
        type.genericInterfaces.forEach { addAll(typeNames(it)) }
        type.constructors
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .forEach { constructor ->
                constructor.genericParameterTypes.forEach { addAll(typeNames(it)) }
                constructor.genericExceptionTypes.forEach { addAll(typeNames(it)) }
            }
        type.methods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .forEach { method ->
                addAll(typeNames(method.genericReturnType))
                method.genericParameterTypes.forEach { addAll(typeNames(it)) }
                method.genericExceptionTypes.forEach { addAll(typeNames(it)) }
            }
        type.fields
            .filter { Modifier.isPublic(it.modifiers) }
            .forEach { field -> addAll(typeNames(field.genericType)) }
    }

    private fun typeNames(type: Type): List<String> = typeNames(type, mutableSetOf())

    private fun typeNames(type: Type, visited: MutableSet<Type>): List<String> {
        if (!visited.add(type)) return listOf(type.typeName)
        return when (type) {
            is Class<*> -> buildList {
                add(type.name)
                if (type.isArray) addAll(typeNames(type.componentType, visited))
            }
            is ParameterizedType -> buildList {
                addAll(typeNames(type.rawType, visited))
                type.actualTypeArguments.forEach { addAll(typeNames(it, visited)) }
                type.ownerType?.let { addAll(typeNames(it, visited)) }
            }
            is GenericArrayType -> typeNames(type.genericComponentType, visited)
            is WildcardType -> buildList {
                type.upperBounds.forEach { addAll(typeNames(it, visited)) }
                type.lowerBounds.forEach { addAll(typeNames(it, visited)) }
            }
            is TypeVariable<*> -> buildList {
                add(type.typeName)
                type.bounds.forEach { addAll(typeNames(it, visited)) }
            }
            else -> listOf(type.typeName)
        }
    }

    private fun sourceFiles(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && (it.extension == "kt" || it.extension == "java") }.toList()
        }
    }
}
