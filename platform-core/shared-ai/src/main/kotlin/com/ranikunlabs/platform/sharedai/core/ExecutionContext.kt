package com.ranikunlabs.platform.sharedai.core

class ExecutionScope(
    val productReference: String,
    val tenantReference: String,
    resourceReferences: Set<String>,
) {
    /**
     * An empty set means that no resource-specific references are carried. It is never a wildcard.
     */
    val resourceReferences: Set<String> = java.util.Set.copyOf(resourceReferences)

    init {
        require(productReference.isNotBlank()) { "productReference must not be blank" }
        require(tenantReference.isNotBlank()) { "tenantReference must not be blank" }
        require(this.resourceReferences.none(String::isBlank)) { "resourceReferences must not contain blank values" }
    }

    fun contains(child: ExecutionScope): Boolean =
        productReference == child.productReference &&
            tenantReference == child.tenantReference &&
            resourceReferences.containsAll(child.resourceReferences)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ExecutionScope &&
            productReference == other.productReference &&
            tenantReference == other.tenantReference &&
            resourceReferences == other.resourceReferences

    override fun hashCode(): Int {
        var result = productReference.hashCode()
        result = 31 * result + tenantReference.hashCode()
        result = 31 * result + resourceReferences.hashCode()
        return result
    }
}

data class ExecutionContext(
    val scope: ExecutionScope,
    val principalReference: String,
    val requestReference: String,
    val executionReference: String,
    val correlationReference: String?,
) {
    init {
        require(principalReference.isNotBlank()) { "principalReference must not be blank" }
        require(requestReference.isNotBlank()) { "requestReference must not be blank" }
        require(executionReference.isNotBlank()) { "executionReference must not be blank" }
        require(correlationReference == null || correlationReference.isNotBlank()) {
            "correlationReference must be null or non-blank"
        }
    }

    fun child(childScope: ExecutionScope): ExecutionContext {
        require(scope.contains(childScope)) { "child scope must not widen its parent scope" }
        return ExecutionContext(
            childScope,
            principalReference,
            requestReference,
            executionReference,
            correlationReference,
        )
    }
}
