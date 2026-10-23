# Ranikun Labs — Jira G2 Full 118 Migration / Execution Handoff
**As-of:** 2026-10-23 KST · **Status:** `G2_IN_PROGRESS / WRITE_TOOL_SAFETY_BLOCKED`  
**Owner approved:** expand migration scope to all 118 RPL issues. This scope approval does not by itself approve conflicting product ownership or Jira admin/global settings/cutover.

## 1. 30-second business summary

Old Jira Product and Component were free multi-value fields. The new taxonomy uses **one accountable Product, one primary technical Component**, with optional 0–3 standardized Concern tags. The existing historical values are retained for recovery and provenance. Previously completed work is now included in scope.

**Current:** 118 total; **58 stored Product+Component** (27 pre-existing, 31 edited on 2026-10-23), **60 missing**, zero partially filled.  
No drift was detected in the old Product/Component/Labels, issue summary, or workflow status across the full 118-issue pre/post comparison. **No G3 target read cutover**.

## 2. Remaining 60 / Work streams

| Cohort | Count | Next write strategy |
|---|---:|---|
| A. Single-value safe normalization | 24 | Resume only after the Jira write safety block is legitimately cleared; verify latest updated, old labels, empty Target; set the two Target option IDs only; reread |
| B. Clear ownership proposals (still require approval of these exact values) | 14 | Owner batch confirmation; then target-only write |
| C. Needs individual boundary/contract decision | 6 | 5 ownership decisions plus RPL-111 JetStream/SQS description conflict; decide classification separately from workflow/contract edits |
| D. Duplicate/discarded historical records | 4 | Choose classify-and-retain vs formally exempt; never delete, re-open, or change status merely for migration |
| E. Story 4 + Subtask 8 | 12 | Reuse existing Target fields on these work types through Jira team-managed settings after targeted admin approval and real metadata verification; no duplicate fields |
| **Total** | **60** | |

### Cohort A — 24 ready mappings

RPL-58, RPL-59, RPL-60, RPL-61, RPL-65, RPL-66, RPL-67, RPL-72, RPL-73, RPL-74, RPL-76, RPL-79, RPL-86, RPL-87, RPL-90, RPL-92, RPL-97, RPL-99, RPL-100, RPL-101, RPL-102, RPL-103, RPL-104, RPL-108

All 24 have exact option IDs, expected prewrite timestamps and legacy fields in `remaining-60-classification-analysis-20261023.json`; these are preflight input, not authorization to bypass a tool safety rejection.

### Cohort B — 14 canonical ownership candidates (not yet Owner-approved)

| Jira | Proposed Product / Component | Grounded rationale |
|---|---|---|
| [RPL-16](https://ranikunlabs.atlassian.net/browse/RPL-16) | `identity` / `backend` | 공용 Product Client Registry의 인증 책임; 코드는 Carelog에 있어도 기능 소유권은 Identity 후보 |
| [RPL-22](https://ranikunlabs.atlassian.net/browse/RPL-22) | `dev-harness` / `documentation` | Pending Handoff 구현 계약·문서 정본 |
| [RPL-24](https://ranikunlabs.atlassian.net/browse/RPL-24) | `identity` / `documentation` | 공통 Product Client Redirect 보안 계약 Research |
| [RPL-25](https://ranikunlabs.atlassian.net/browse/RPL-25) | `identity` / `backend` | 제품별 OAuth Provider Callback resolver |
| [RPL-28](https://ranikunlabs.atlassian.net/browse/RPL-28) | `identity` / `documentation` | OAuth State Client Binding 문서 계약 |
| [RPL-53](https://ranikunlabs.atlassian.net/browse/RPL-53) | `gateway` / `backend` | Shared Gateway 기능 추출; gateway는 Product, backend는 구현 영역 |
| [RPL-54](https://ranikunlabs.atlassian.net/browse/RPL-54) | `identity` / `backend` | Shared Identity 기능 추출; identity는 Product, backend는 구현 영역 |
| [RPL-71](https://ranikunlabs.atlassian.net/browse/RPL-71) | `platform-foundation` / `infra` | Gateway·Identity 공통 실행 인프라 기반 |
| [RPL-84](https://ranikunlabs.atlassian.net/browse/RPL-84) | `finance` / `backend` | Finance Journal Detail/List API |
| [RPL-96](https://ranikunlabs.atlassian.net/browse/RPL-96) | `finance` / `backend` | Finance Journal Create 멱등성 |
| [RPL-105](https://ranikunlabs.atlassian.net/browse/RPL-105) | `platform-foundation` / `backend` | Platform Core 실행 Host·Build 기반 |
| [RPL-106](https://ranikunlabs.atlassian.net/browse/RPL-106) | `ai` / `backend` | Shared AI Kotlin/Spring AI 실행 모듈 |
| [RPL-109](https://ranikunlabs.atlassian.net/browse/RPL-109) | `audit` / `documentation` | Audit Event Catalog 및 계약 정본 |
| [RPL-110](https://ranikunlabs.atlassian.net/browse/RPL-110) | `audit` / `backend` | Audit Publisher Boundary API/구현 |

### Cohorts C and D — specific decisions or exclusion

| Jira | Proposed Product / Component | Gate | Rationale |
|---|---|---|---|
| [RPL-111](https://ranikunlabs.atlassian.net/browse/RPL-111) | `audit` / `backend` | CONTRACT_CHECK | Target 분류는 audit/backend 후보이지만 기존 JetStream 본문과 SQS 정책 불일치. 분류 변경과 설명 변경 분리 |
| [RPL-1](https://ranikunlabs.atlassian.net/browse/RPL-1) | `carelog` / `backend` | OWNER_REVIEW | Carelog 로그인 Epic, Gateway·Identity 영향 책임 기록 필요 |
| [RPL-4](https://ranikunlabs.atlassian.net/browse/RPL-4) | `gateway` / `backend` | OWNER_REVIEW | Security permitAll + Gateway Route 두 경계 혼재: gateway/backend 후보 또는 작업 분리 |
| [RPL-7](https://ranikunlabs.atlassian.net/browse/RPL-7) | `carelog` / `backend` | OWNER_REVIEW | 회귀 테스트·계약 문서 동시 범위: 대표 Component 확인 |
| [RPL-10](https://ranikunlabs.atlassian.net/browse/RPL-10) | `finance` / `documentation` | OWNER_REVIEW | Finance FE를 통한 Shared Identity 공통 계약 연구. identity/documentation과 단일 책임 논의, RPL-11 중복 |
| [RPL-11](https://ranikunlabs.atlassian.net/browse/RPL-11) | `finance` / `documentation` | OWNER_REVIEW | 완료 RPL-10의 Duplicate 관계. finance vs identity 소유권 + 중복 종료 정책 분리 |
| [RPL-38](https://ranikunlabs.atlassian.net/browse/RPL-38) | `dev-harness` / `infra` | HISTORICAL_EXCEPTION | RPL-37 생성 오류로 발생한 폐기 티켓; 이력 유지하며 분류 포함할지 결정 |
| [RPL-39](https://ranikunlabs.atlassian.net/browse/RPL-39) | `dev-harness` / `infra` | HISTORICAL_EXCEPTION | RPL-37 생성 오류로 발생한 폐기 티켓; 이력 유지하며 분류 포함할지 결정 |
| [RPL-40](https://ranikunlabs.atlassian.net/browse/RPL-40) | `dev-harness` / `infra` | HISTORICAL_EXCEPTION | RPL-37 생성 오류로 발생한 폐기 티켓; 이력 유지하며 분류 포함할지 결정 |
| [RPL-45](https://ranikunlabs.atlassian.net/browse/RPL-45) | `finance` / `frontend` | HISTORICAL_EXCEPTION | RPL-44의 중복 생성 이슈; 이력 유지하며 분류 포함할지 결정 |

### Cohort E — Work type field setup

Team-managed Jira UI, according to Atlassian's current docs: **RPL → Space/Project settings → Work types → Story (then Subtask)**, select each work type and drag the already created `Product (Target)`, `Component (Target)`, `Concern (Target)` fields from the existing fields palette, then save. If fields are not available in the work-type palette, confirm inclusion in **Space settings → Fields** and administrative field eligibility. **Do not create another set of Target fields or make them required in this migration.**  
Re-query Story and Subtask `editmeta` and ensure that `customfield_10108/10109/10110` appear on both issue types before issue writes. If unsupported, keep 12 classified externally and record a formal exception rather than pretending to have stored them.

Official Atlassian guide: https://support.atlassian.com/jira-software-cloud/docs/customize-an-issues-fields-in-team-managed-projects/

Proposed derived ownership (not automatic inheritance): Subtasks of RPL-26 generally `dev-harness/infra`, RPL-94 `dev-harness/backend`; Story RPL-115..118 `dev-harness/ai`. **RPL-83 duplicates the completed RPL-82 name/work; preserve historical state until its relationship is resolved.**

## 3. Optional Concern normalization

Concern is optional, **not an empty-field blocker**. Currently 12 issues have at least one Target Concern; 106 have none. **40 issues** have legacy label(s) whose exact lowercased text matches the approved canonical 14 Concern options and are a candidate to add. These are proposals, not mandatory 118-issue backfill. Do not convert arbitrary labels, change legacy Labels, or set more than 3.

## 4. Safety and reversibility

- Private Git prewrite record: `prewrite-snapshot-20261023.json`. It stores selected migration fields and metadata, **not a full copy of every Jira Description/attachment**.
- Verified postwrite reconciliation: `full-118-readonly-reconciliation-20261023.json`.
- Reversible **Target-only** manifest: `rollback-31-target-only-manifest-20261023.json`. Each of the 31 entries records before/after IDs, last known Jira updated and guard. Any rollback must be separately approved, must re-read the issue first, and must abort on changed values/timestamps. Jira history, timestamp, workflows and external side effects cannot be erased by resetting a field.
- **Never reuse rollback for the 27 older migrated issues.**
- Continue a single Writer, bounded batches, paired preflight/post-read and write journal; if ambiguous write response, search by IssueKey before retry.
- The external Jira tool rejected additional writes during the prior session. **Do not bypass safety policy/tool decisions** using alternate connectors, scripts or endpoints.
- Legacy classification remains authoritative until separate G3 activation. `RPL-112/113/114` Origin remains unverified/quarantined; Target stored does not prove origin.
- Do not change Description, Issue Type, status, parent, linked issues, workflows, Board, Required/Default, or remove legacy fields as part of Target-only migration.
- AI Jira Create PR #83/#84 remains on hold.

## 5. Completion gates / What user must decide once

1. Approve B's 14 precise value pairs or mark exceptions.
2. Choose C's 6 conflicting/boundary cases and D's 4 historical records; classify historical records consistently or explicitly exempt from 118/118 *storage* while still retaining 118/118 *governance coverage*.
3. Approve a targeted **Jira Admin work type field association for Story/Subtask** (no field creation, no Required/Default changes) if allowed, or record the 12 as formally governed unsupported exceptions.
4. Legitimately resolve the previously rejected Jira edit path. Then update A/B/C/D/E in approved bounded batches and compare all 118 to backup; no duplicate writes.
5. Request G2 closure only once no unauthorized/no-rollback-evidence state exists. G3 and G4 are separate.

**Result:** Read-only audit, mapping and guarded rollback assets ready. G2 not completed and no Jira Admin or additional issue edits made in this handoff step.
