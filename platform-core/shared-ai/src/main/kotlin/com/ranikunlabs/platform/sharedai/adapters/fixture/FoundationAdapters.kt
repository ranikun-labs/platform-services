package com.ranikunlabs.platform.sharedai.adapters.fixture

import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.ports.ModelGateway
import com.ranikunlabs.platform.sharedai.ports.ModelRequest
import com.ranikunlabs.platform.sharedai.ports.ModelResult
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.ProductToolPort
import com.ranikunlabs.platform.sharedai.ports.RetrievedItem
import com.ranikunlabs.platform.sharedai.ports.RetrieverPort

class InMemoryProductToolAdapter : ProductToolPort {
    override fun readFact(context: ExecutionContext): ProductFact =
        ProductFact(
            context.executionReference,
            context.scope,
            "fixture:product-current-state",
            "fixture:product-tool",
            "current-item-count",
            "3",
        )
}

class InMemoryRetrieverAdapter : RetrieverPort {
    override fun retrieve(context: ExecutionContext, query: String): List<RetrievedItem> =
        listOf(
            RetrievedItem(
                context.executionReference,
                context.scope,
                "fixture:knowledge-guidance",
                "fixture:retriever",
                "Review items against the currently approved guidance.",
            ),
        )
}

class DeterministicModelGateway : ModelGateway {
    override fun interpret(request: ModelRequest): ModelResult =
        ModelResult(
            request.executionReference,
            request.scope,
            "fixture:model-interpretation",
            "fixture:deterministic-model",
            "Reviewed ${request.productFact.value} current items using ${request.retrievedItems.size} knowledge item(s).",
        )
}
