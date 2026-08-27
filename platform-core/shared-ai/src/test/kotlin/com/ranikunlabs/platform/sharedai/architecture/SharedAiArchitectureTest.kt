package com.ranikunlabs.platform.sharedai.architecture

import com.ranikunlabs.platform.sharedai.application.ExecutionCommand
import com.ranikunlabs.platform.sharedai.application.SharedAiExecutionService
import com.ranikunlabs.platform.sharedai.application.SharedAiStreamingExecutionService
import com.ranikunlabs.platform.sharedai.application.ExecutionStreamObserver
import com.ranikunlabs.platform.sharedai.application.SkillHandler
import com.ranikunlabs.platform.sharedai.core.Evidence
import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ModelStreamObserver
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort
import com.ranikunlabs.platform.sharedai.ports.StreamingModelGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
            "software.amazon.awssdk",
            "reactor.core",
            "org.reactivestreams",
            "Flux",
            "Mono",
            "Publisher",
            "SseEmitter",
            "kotlinx.coroutines",
            "suspend fun",
        )

        neutralRoots.flatMap(::sourceFiles).forEach { source ->
            assertThat(source.readText()).describedAs(source.toString()).doesNotContain(*forbidden.toTypedArray())
        }
    }

    @Test
    fun `module dependencies contain neither Product persistence nor feature projects`() {
        val buildFile = sharedAiRoot.resolve("build.gradle.kts").readText()

        assertThat(buildFile).doesNotContain("project(\"", "jdbc", "jpa", "r2dbc", "postgresql", "pgvector")
    }

    @Test
    fun `public contracts are ordinary Java callable signatures`() {
        val contractTypes = listOf(
            ExecutionContext::class.java,
            Evidence::class.java,
            ExecutionCommand::class.java,
            SharedAiExecutionService::class.java,
            SkillHandler::class.java,
            ProductToolPort::class.java,
            RetrieverPort::class.java,
            ModelGateway::class.java,
            StreamingModelGateway::class.java,
            ModelStreamObserver::class.java,
            SharedAiStreamingExecutionService::class.java,
            ExecutionStreamObserver::class.java,
        )
        val signatures = contractTypes.flatMap { type ->
            type.methods.map { method ->
                listOf(method.returnType.name) + method.parameterTypes.map(Class<*>::getName)
            }.flatten()
        }

        assertThat(signatures).noneMatch {
            it.contains("kotlin.coroutines") ||
                it.contains("kotlin.jvm.functions") ||
                it.contains("kotlin.Result") ||
                it.contains("kotlin.Pair") ||
                it.contains("kotlin.Triple") ||
                it.contains("kotlinx.coroutines") ||
                it.contains("org.springframework.ai")
                || it.contains("software.amazon.awssdk")
                || it.contains("reactor.core")
                || it.contains("org.reactivestreams")
                || it.contains("org.springframework.web")
        }
    }

    private fun sourceFiles(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && (it.extension == "kt" || it.extension == "java") }.toList()
        }
    }
}
