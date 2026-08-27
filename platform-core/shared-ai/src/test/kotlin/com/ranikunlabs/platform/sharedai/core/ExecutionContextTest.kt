package com.ranikunlabs.platform.sharedai.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExecutionContextTest {
    @Test
    fun `requires explicit product reference`() {
        assertThatThrownBy { ExecutionScope(" ", "tenant-a", emptySet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("productReference")
    }

    @Test
    fun `requires explicit tenant reference`() {
        assertThatThrownBy { ExecutionScope("product-a", "", emptySet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tenantReference")
    }

    @Test
    fun `allows a child scope to narrow resources`() {
        val context = context(ExecutionScope("product-a", "tenant-a", setOf("one", "two")))

        val child = context.child(ExecutionScope("product-a", "tenant-a", setOf("one")))

        assertThat(child.scope.resourceReferences).containsExactly("one")
    }

    @Test
    fun `rejects a child scope that widens or changes tenant`() {
        val context = context(ExecutionScope("product-a", "tenant-a", setOf("one")))

        assertThatThrownBy {
            context.child(ExecutionScope("product-a", "tenant-a", setOf("one", "two")))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not widen")
        assertThatThrownBy {
            context.child(ExecutionScope("product-a", "tenant-b", setOf("one")))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `empty resource references mean no resource-specific references and never wildcard`() {
        val empty = ExecutionScope("product-a", "tenant-a", emptySet())

        assertThat(empty.contains(ExecutionScope("product-a", "tenant-a", emptySet()))).isTrue()
        assertThat(empty.contains(ExecutionScope("product-a", "tenant-a", setOf("resource-a")))).isFalse()
    }

    private fun context(scope: ExecutionScope) =
        ExecutionContext(scope, "principal-a", "request-a", "execution-a", "correlation-a")
}
