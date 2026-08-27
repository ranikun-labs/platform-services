package com.ranikunlabs.platform.sharedai.application

import com.ranikunlabs.platform.sharedai.core.ExecutionContext
import com.ranikunlabs.platform.sharedai.core.ExecutionScope
import com.ranikunlabs.platform.sharedai.ports.ModelResult
import com.ranikunlabs.platform.sharedai.ports.ProductFact
import com.ranikunlabs.platform.sharedai.ports.RetrievedItem

class PortResultCorrelationValidator {
    fun validate(context: ExecutionContext, fact: ProductFact) {
        validate(context, fact.executionReference, fact.scope, fact.sourceReference, fact.producerReference)
    }

    fun validate(context: ExecutionContext, item: RetrievedItem) {
        validate(context, item.executionReference, item.scope, item.sourceReference, item.producerReference)
    }

    fun validate(context: ExecutionContext, result: ModelResult) {
        validate(context, result.executionReference, result.scope, result.sourceReference, result.producerReference)
    }

    private fun validate(
        context: ExecutionContext,
        executionReference: String,
        scope: ExecutionScope,
        sourceReference: String,
        producerReference: String,
    ) {
        if (executionReference != context.executionReference) {
            throw PortResultValidationException()
        }
        if (!context.scope.contains(scope)) {
            throw PortResultValidationException()
        }
        if (sourceReference.isBlank() || producerReference.isBlank()) {
            throw PortResultValidationException()
        }
    }
}

class PortResultValidationException : RuntimeException("Shared AI port result correlation validation failed")
