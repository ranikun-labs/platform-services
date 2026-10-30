# Jira Migration — G0~G4 Current Roadmap & Closure (2026-10-30 KST)

> **최신 G3/G4 F4 Audit Delta · 2026-10-30 22:34 KST 이후 — Original RPL-128 Intent Restored:** RPL-128 Feature의 생성 전 `pending` Git Commit `0b1f4526eb32ed7744f6a47d664b8faff22b59f2` / Blob `3ee9e93c7ea28de588cb18fa9a87850cd7a4ecb4`, 최종 `target_create/create_ready` Blob `07a6c7d42ddad01a00846b0113befa405c19ed06`를 다른 저장소 `ranikun-labs/harness-foundation-docs@audit/jira-g3-feature-canary-20261030:docs/governance/jira-g3-canary-feature-2026-10-30.json`에서 직접 검증. Jira RPL-128 GET/JQL/Option ID와 대조 PASS. 따라서 Target 필수 5유형 Canary의 **생성 전 pending→생성 후 Origin** 증거 연결 5/5 PASS (RPL-129~132는 별도 원장). **타 Target-only 10건 READ-ONLY Changelog 구분:** 6건(RPL-83/94/115/116/117/118) 2026-10-23 생성 후 Target 필드 갱신 증명(Change IDs 11063, 11066~11070); 4건(RPL-122/124/126/127)은 현재 Target 존재·Target 변경 이력 없음, 생성 전 Intent 미확인. 10건 모두 무단 `target_create` 승격 불가. `g3-g4-origin-coverage-reconciliation-20261030.json`, `g3-g4-noncanary-origin-evidence-20261030.json` 참고. **Reader 계약 추가 Finding:** `authority_reader.py`가 `ReadAuthority=legacy`라도 Target Option IDs를 `classification`에 반환하며, rollback simulation은 authority label만 검증함. 실제 소비처에 연결 전 의도·effective value를 확정하고 검증할 것 (`senior-reader-semantics-review-20261030.md`). **G3 Existing-Issue Cutover는 여전히 BLOCKED, G4 검증 범위 한정 PASS.** 이 최신 Delta가 아래 이전 Origin 미확인 기록보다 우선하며 역사 기록은 삭제하지 않는다.


> **F4 Origin Coverage 추가 점검 (2026-10-30):** 미완료 50건은 Legacy+Target 31, Target-only 15, Optional Story 무분류 4건. Target-only 15건 중 이번 4건(RPL-129~132)은 pending→Create→GET/JQL→Origin 원장 검증 완료; Feature RPL-128은 기존 Canary PASS이나 본 범위 영속 Origin 원장까지 재확인하지 않았고, 다른 10건은 Origin 증거 확인이 별도로 필요하다. 필드 존재만으로 ReadAuthority를 결정하지 않는다. 근거: `g3-g4-origin-coverage-reconciliation-20261030.json`.


> **2026-10-30 18:04 KST 실제 실행 최신 업데이트:** Owner 승인된 G3 Target-only Canary 4건(Epic RPL-129 / Task RPL-130 / Bug RPL-131 / Research RPL-132)이 모두 Native Jira Create→GET→JQL 4/4 PASS; 기존 Feature RPL-128과 합쳐 Target 필수 5유형 신규 Create 시험 완료. 인증된 외부 Manifest Reader·Mixed Origin·Rollback/Restore 및 RPL-88 Cutover는 여전히 NOT_RUN/BLOCKED. 현재 Jira 전체 132건 / 미완료 50건, 활성 필수 Target 누락 0건. `g3-target-create-four-canary-execution-20261030.md` 및 `g3-target-create-ledger-20261030.json`이 해당 신규 Create의 최신 증거다. 아래 기존 날짜별 수치와 미검증 4유형 문구는 **실행 전 역사 스냅샷**으로 보존한다. G4는 현재 접근 가능 범위만 PASS이며 전체 Closure 아님.


**F1 Verdict: G4 최종 완료 아님. G3·G4 모두 부분 완료.**

## 실제 연결 기준
- RPL Jira live GET/JQL: 128건, Done 82, Non-Done 46.
- Non-Done 분할: Legacy+Target 30, Target-only 11, Legacy-only 0(필수형 결측 보정 전 1), Target 없음 선택형 Story 4, partial Target 0. 합계 46.
- RPL-125 Research의 새 Product·Component 2필드 보정 실제 완료 2026-10-30 16:28:31 KST; Changelog 11099, Jira comment 10731, Legacy 불변. 현재 필수 유형 Target 누락 **0건**. `rpl125-preupdate-classification-20261030.json` 및 `rpl125-postupdate-verification-20261030.json` 참조.
- 신규 이슈의 Origin/Authority는 필드 존재만으로 추정하지 않는다.

## Goal별 Verdict / AC

| Goal | 판정 | 실증/남은 것 |
| --- | --- | --- |
| G0 분류 계약 | PASS (승인 범위) | Taxonomy §8.0/8.1 정본 |
| G1 Admin | PASS (현재 검증된 설정) | 8 Issue Type Create Metadata: 5 필수 Target, Story/Subtask/Tech Debt 선택형; 8유형 Create Legacy 미노출 |
| G2 Data | PASS (당시 118/118) + CURRENT_DRIFT_FIXED | 기존 원본 백업/검증 유지; RPL-125 Target 필수형 누락 1건 수정; 4 Story 선택형 예외 |
| G3 Native Create | PARTIAL | Feature RPL-128 Canary PASS. Epic/Task/Bug/Research 각 시험 Create·GET·지원 정책은 별도 승인/검증 전 NOT_RUN. Story/Subtask/Tech Debt는 Optional/예외 유지 |
| G3 Existing-Issue Authority | BLOCKED | RPL-88 `Phase=verified / ReadAuthority=legacy / WriteAuthority=frozen / CutoverApprovalRef=null`. Mixed-Origin/Reader/실제 restore drill/Owner 최종 승인 미확인. 기존 30건 병존은 Target 공식 권위의 증거가 아니다 |
| G4 Native Preservation | PARTIAL | 확인된 Board/Automation Legacy 참조 없음, 신규 Create Legacy 미노출, 원본 보존 PASS. 계정 밖 공유 Filter/화면·실제 권위 소비처 및 유형별 예외 완전성 미확인 |
| G4 Legacy Hide/Delete | DEFERRED | 숨김은 실제 소비처 및 복구 완료 후 별도 승인; 물리 삭제는 완료 정의에 포함하지 않고 장기 보존, 별도 Owner 승인 전 금지 |

## Senior 결정 (이 문서 자체는 변경 승인이나 CutoverApprovalRef가 아님)
1. **새 관리 Jira Task 생성 안 함.** 단일 이슈가 없어도 §8.0 감독형 직렬 Fast Track의 사전검증은 가능. Workstream Coordinator와 Jira 인증 Writer의 실제 식별/충돌 확인은 유지. 새로운 Task 생성은 목표 자체가 아님.
2. **새 Reader 서버 개발 안 함.** 실제 운영 소비처/Manifest 권위 검증에 필요한 통합만 확인. AI Jira 자동 생성 및 PR #83/#84는 Developer AI Platform 소유.
3. **RPL-125 수정은 완료·검증됨.** 재실행 금지. Changelog 11099/댓글 10731 재사용.
4. **나머지 4 Story는 Optional이므로 자동 보정 안 함.** 예외 정책 결정 전 누락 버그라고 주장하지 않는다.
5. **기존 30 병존 이슈를 자동 일괄 전환하지 않음.** G2 원본/Issue Origin/단일 권위 별도 승인 필요. UNKNOWN_ORIGIN 112/113/114, RPL-40 폐기, Done 역사 이슈 보호.
6. **RPL-88만 기존 이슈 Cutover Pilot.** 운영 Reader/복구 증거를 확보하고 그 후 정확한 `CutoverApprovalRef`를 발급받기 전 `ReadAuthority=legacy` 유지.
7. **Legacy 물리 삭제는 최종 Closure 필수 조건 아님.** 데이터 무결성·복구·감사를 위해 보존.

## F4 다음 READY/승인 분리
A. 승인 없는 READY: 현재 Jira/보드·필터·자동화 조회, 공유 Filter 접근범위/소유자 확인, 각 타입 Create Metadata 변경 탐지, 감사증거 정리.
B. Owner 한정 감독형 시험 Create: Epic/Task/Bug/Research 유형별 실제 Create를 승인된 대상/기간/필수 시작·기한값/Writer로 각각 수행 후 중복 Create 방지·GET/Changelog·오염 방지 검증. 승인 전 **NOT_RUN**.
C. G3 Cutover: RPL-88 실제 Manifest Authority 조회, Mixed Origin/UNKNOWN fail-closed, 실제 비파괴적 Restore Drill, Consumer/Reader 연결 증거 → **Owner 전용 CutoverApprovalRef 별도 승인** → 한 건만 Cutover → 실 재조회. 실패 시 즉시 stop/승인된 복구.
D. G4: 영향받는 기존 보드/필터/자동화/화면만 검증 근거에 따라 Target으로 교체; 정본/원본 보존. 숨김/삭제는 권위 전환·복구 검증 뒤 별도 승인.
E. 승인받은 Pilot이 PASS이면 활성 이슈 후보만 작은 배치로 확장. 완료 82건/폐기·UNKNOWN 대량 처리 금지.

## Stop Conditions
- No invented CutoverApprovalRef/Origin, no unconditional dual-read, no silent Legacy fallback.
- No Jira issue Create, status transition, mass migration, Legacy deletion, unsafe reader deployment without scoped approval.
- No PR #83/#84 or other Workstream code modifications.
- PASS only with actual Jira Get/JQL, native UI/consumer, history, snapshot, rollback evidence.

## Evidence
- `g3-g4-live-inventory-20261030.json` (pre RPL-125 correction), `rpl125-preupdate-classification-20261030.json`, `rpl125-postupdate-verification-20261030.json` (post)
- RPL-88 Jira Changelog 10934, current CutoverApprovalRef null; audit README/Reader tests
- Taxonomy canonical Git Blob 0c3bc9d7a7b71ff072696c33d21e372d20d76b29
