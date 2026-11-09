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


## PR 작성 및 본문 갱신
- PR 생성 또는 본문 수정 전에 대상 base 브랜치의 `.github/PULL_REQUEST_TEMPLATE.md` 실제 내용을 확인한다.
- 저장소에 작업 유형별 전용 PR 템플릿이 있으면 현재 작업과 맞는 형식을 우선한다.
- `/핵심정리` 형식은 한국어로 `목적`, `변경`, `검증`, `Finding`, `Source Merge`, `Production Gate`, `참조`를 구분해 작성한다.
- 실제 실행한 검증과 근거만 기록하고, 실행하지 않은 항목은 `PASS`가 아니라 `NOT_RUN`으로 표시한다.
- 작성자(Writer)의 검증 결과와 독립 Reviewer의 판정을 구분한다. 독립 `PASS`는 검수한 코드 HEAD가 명시되고 근거가 일치할 때만 기록한다.
- 코드 HEAD, base 브랜치, 테스트 명령·결과·링크가 실제 ref와 실행 근거에 맞는지 확인한다.
- Source Merge 승인과 Production 배포 승인을 별도 Gate로 기록한다. PR 본문 작성만으로 Merge 승인을 간주하지 않는다.
- Secret, PII, 민감한 SQL 파라미터를 본문에 기록하지 않는다. 필요한 설명에는 비식별·마스킹한 예시만 사용한다.
