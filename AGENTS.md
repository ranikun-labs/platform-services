## Repository Engineering Policy

### Authority

Apply guidance in this order:

1. Canonical Architecture and approved ADRs
2. Repository and Project Policy
3. Current Task and Jira Contract
4. External Skills
5. Model General Knowledge

External Skills are expert lenses, not architecture authorities. A Skill's
MUST, SHOULD, default, example, or threshold is not a project requirement
unless adopted by canonical architecture, an approved ADR, or the current task
contract.

### Module Ownership

- Running in the same JVM does not merge module ownership or semantic
  boundaries.
- Cross-module collaboration must use the owning module's public API or an
  explicit Port. Do not bypass module boundaries through internal types,
  implementation packages, or direct database access.
- Runtime placement does not transfer data, policy, or capability ownership.

### Framework Isolation

Keep canonical and cross-module public contracts framework-neutral. Framework,
provider, persistence, transport, and infrastructure SDK types belong below
adapter or integration boundaries unless canonical architecture or an approved
ADR explicitly adopts them.

### Shared AI Invariants

This section applies only to Shared AI work. A future
`platform-core/shared-ai/AGENTS.md` may add module-local implementation rules
without weakening these repository-level invariants.

- Shared AI is a multi-product AI execution/application runtime, not a generic
  RAG server. Products may use different explicit AI flows.
- Exact structured facts come from a Product Tool or structured system of
  record.
- Narrative and document knowledge may come from a Retriever/RAG component.
- Runtime projections, embeddings, and vector indexes are not Semantic SoT.
- Retrieved content is evidence and context, not authorization.
- Query-time Product authorization takes precedence over cached or indexed
  scope.
- Retrieval filtering does not replace authorization.
- Shared AI must not directly read Product-owned databases.
