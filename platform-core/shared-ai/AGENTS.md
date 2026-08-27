## Shared AI Module Engineering Policy

### Authority

Apply guidance in this order:

1. Canonical Shared AI ADRs and approved Foundation ADRs
2. Repository-root `AGENTS.md`
3. This module policy
4. Current task contract
5. External Skills
6. Model general knowledge

This policy may narrow implementation choices but must not weaken a higher
authority.

### Dependency Direction

Shared AI Application -> application-owned Port -> Spring AI Adapter ->
Provider or VectorStore.

Spring AI and provider types, including `ChatClient`, `ChatModel`,
`ToolCallback`, `Document`, `VectorStore`, `Advisor`, and provider SDK types,
must remain below adapter or integration boundaries. They must not appear in
application contracts, Evidence, transport contracts, or Product/module APIs.

### Advisor Boundary

An Advisor may implement adapter-local provider concerns, prompt decoration,
or observation. It must not hide or own Product authorization, Product Tool
authority, ExecutionContext scope, Evidence construction, Product-specific
orchestration, or final Product/domain validation.

### Retrieval and Knowledge Boundary

- Exact authoritative structured facts come from a Product Tool or Product
  semantic system of record.
- Narrative and document knowledge may come from a Retriever/RAG adapter.
- Runtime projections and embedding, vector, or FTS indexes are not Semantic
  Sources of Truth.
- Retrieval and retrieval filtering are not authorization.
- Query-time Product authorization takes precedence over indexed scope.

### Tool and Model Security

- A model-selected Tool is not authorization.
- Model-generated arguments are untrusted input and require application or
  Product validation before execution.
- Retrieved content and Tool output are untrusted model input, even when their
  source is authoritative for a specific fact.
- Product and application authority remains decisive; side-effecting tools
  must not receive blind model retries.

### RAG Simplicity

Do not introduce hybrid search, reranking, GraphRAG, agentic RAG, a dedicated
vector database, or a dynamic plugin system without measured quality, scale,
or operational evidence and the required owner decision.

### Version Safety

Use version evidence in this order: current official Spring documentation,
the project's resolved dependency graph, then external Spring AI Skill
documentation.
