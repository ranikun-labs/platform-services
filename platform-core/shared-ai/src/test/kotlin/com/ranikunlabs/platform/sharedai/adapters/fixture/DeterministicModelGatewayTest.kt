package com.ranikunlabs.platform.sharedai.adapters.fixture

import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.ports.ModelRequest
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeterministicModelGatewayTest {
    private val scope = ExecutionScope("finance", "tenant:test", setOf("question:q1"))

    @Test
    fun `finance general analysis query gets a five field JSON interpretation`() {
        val result = DeterministicModelGateway().interpret(request("Finance General Analysis\nLens: general-analysis\n"))

        val text = result.interpretation
        assertThat(text).startsWith("{").endsWith("}")
        assertThat(text).doesNotContain("Reviewed")
        listOf("summary", "observations", "assumptions", "nextChecks", "evidenceNote").forEach { field ->
            assertThat(Regex("\"$field\":").findAll(text).count()).describedAs(field).isEqualTo(1)
        }
    }

    @Test
    fun `other skills keep the existing review interpretation`() {
        val result = DeterministicModelGateway().interpret(request("review knowledge"))

        assertThat(result.interpretation).isEqualTo("Reviewed 3 current items using 0 knowledge item(s).")
    }

    private fun request(query: String) = ModelRequest(
        "exec-1",
        scope,
        query,
        ProductFact("exec-1", scope, "fixture:product-current-state", "fixture:product-tool", "current-item-count", "3"),
        emptyList(),
    )
}
