# Jira Field Taxonomy §8.2 — Jira Native 운영 범위·권위·복구 예외 제안

**판정:** `DRAFT / NOT_APPROVED / NOT_EFFECTIVE / NO_CUTOVER`  
**작성일:** 2026-10-31 KST  
**Writer Scope:** Jira Migration audit branch only. Accepted `harness-foundation-docs/main`은 수정하지 않았으며, 본 문서는 정본 정책을 대체하지 않는다.  
**기존 계약:** `harness-foundation-docs/docs/governance/jira-field-taxonomy.md` v1.0, blob `0c3bc9d7a7b71ff072696c33d21e372d20d76b29` (§8.0/§8.1); Portfolio Governance v1.3 blob `bf2c67e36566557e3288554deeeb573a7e39b40a`; AI Session Governance v1.1 blob `d923ddcc77fc39348760d5934c88a971e7f72833`.  
**Owner R&R:** Confluence 2026-10-30 `07-1. Personal AI — 세션별 R&R·수정 권한·인계 규칙`. AI Jira 자동 생성 제품 코드/Origin Registry Runtime은 Developer AI Platform 소유, Jira Admin/Native G3/G4는 Jira Migration 소유.

## 1. 문제·선택지

- Jira Native Board/Issue UI/JQL은 저장된 Product/Component/Concern 값만 표시·검색하며 외부 Git의 `Origin`, `ReadAuthority`, `CutoverApprovalRef`를 해석하지 못한다. Native JQL GET PASS는 별도 SSOT 강제 PASS가 아니다.
- 확인된 RPL Board #2의 Filter `10001` JQL은 `project = RPL ORDER BY cf[10019] ASC` (Rank); 활성 RPL Automation JQL은 기한·상태만 사용. 둘 다 `NO_CLASSIFICATION_CONSUMER`. 현 계정의 저장/shared Filter는 조회 범위에서 0건. Feature/Task Screen은 Target 필드를 표시하므로 `TARGET_EXPOSURE`이며 `NO_CLASSIFICATION_CONSUMER`로 통합할 수 없다.
- 다른 소유자의 비공개 Filter/외부 소비처는 `ACCESS_UNKNOWN`. 전 범위 부재 확인 아님.
- 실제 Manifest-aware 운영 Reader는 확인되지 않았고 private `authority_reader.py`는 오프라인 감사 도구이며 MAJOR-01/02 미해결. 운영 서비스가 없다면 신규 Reader 서버 구축을 필수조건으로 삼는 것은 과설계다.
- 그러나 발효 Taxonomy §8.0/§8.1의 per-Issue CutoverApprovalRef, Origin·미확인 격리, 승인된 운영 조회와 복구 조건을 단순히 생략하는 것은 불가.

### Owner 제품 결정이 먼저 필요한 질문

`DECISION_NATIVE_CLASSIFICATION_AUTHORITY`:
- **추천 A:** Jira Native를 사용자가 보는 공식 운영 인터페이스로 채택. Target 필드가 보이는 이슈라도 Cutover 전에는 *기존 Legacy가 공식 분류*라는 운영 계약을 유지한다. Native UI가 이를 강제하지 못하는 제약과 사용자가 해당 필드를 미리보기로 이해할 수 있는 표시/문서 기준을 Owner가 수용해야 한다. 정본 §8.2에서 실존하는 소비처에만 Consumer/Restore 검증을 적용하는 bounded 예외를 별도 승인한다.
- **대안 B:** 이슈별 Origin/ReadAuthority를 일반 사용자 조회·검색에서도 **기술적으로 강제**해야 한다면 Jira Native만으로는 충족 불가. 구체적인 소비 서비스·읽기 UX·보안 범위·Writer·실행 환경을 확정한 뒤 실제 Manifest-aware Adapter를 별도 구현·배포·E2E 해야 한다.
- **미승인:** 본 문서는 A나 B 채택으로 간주되지 않는다.

## 2. 제안 정본 삽입 문구 (§8.2, 미승인)

> ### 8.2 Jira Native 소비처 범위와 운영 권위 검증 예외 (Owner 승인 시 발효)
>
> **적용 대상:** RPL 프로젝트에 한정한다. 대상 Consumer 종류, 실제 JQL/화면·Automation Revision, 조회 계정, 접근 가능한 범위, Owner 결정 Ref와 검증 일시를 영속 Evidence로 저장한다. `ACCESS_UNKNOWN`은 발견되지 않았다는 뜻이 아니다.
>
> **분류:** (1) `LEGACY_CLASSIFICATION_CONSUMER`: 실제 Legacy 필드를 업무 정렬·필터·집계·판단에 사용, (2) `TARGET_CLASSIFICATION_CONSUMER`: Target 필드를 사용, (3) `NO_CLASSIFICATION_CONSUMER`: 분류 필드를 사용하지 않음, (4) `TARGET_EXPOSURE`: Native UI가 Target 필드를 표시하거나 편집 대상으로 노출하지만 공식 권위 선택 기능은 없음, (5) `ACCESS_UNKNOWN`: 접근·실증 불가. 타입별로 구분하며 Board·Filter·Automation이 `NO_CLASSIFICATION_CONSUMER`여도 Issue Screen의 `TARGET_EXPOSURE`를 지우지 않는다.
>
> **Native UI의 기술 한계:** Jira Native JQL/Screen은 저장된 필드를 직접 표시하고 원장 `Origin`·`ReadAuthority`를 해석하거나 접근을 자동 차단하지 않는다. Native UI 조회 PASS는 이슈별 권위 강제 PASS가 아니다. 기존 `ReadAuthority=legacy`인 이슈의 Target 필드 값은 별도 승인된 계약이 없는 한 **비권위 참고값(preview)**이다. 해당 구분을 사용자에게 전달하는 UI/운영 절차와 잔여 위험은 Owner가 명시적으로 승인해야 한다.
>
> **한정 예외:** 실제 업무 분류를 읽지 않는 것으로 확인되고 Owner가 정확한 범위·기간·ReadConsumerRef·설정 증거를 승인한 `NO_CLASSIFICATION_CONSUMER`에 한해, 존재하지 않는 Manifest-aware Reader의 배포 및 해당 Consumer 전용 Legacy→Target→Restore 테스트는 `NOT_APPLICABLE_BY_APPROVED_SCOPE`으로 판정할 수 있다. Consumer가 존재하지 않는다는 증거를 가장하거나 synthetic approval/rollback을 운영 PASS로 승격하지 않는다. 다른 소비처 또는 `TARGET_EXPOSURE`의 권위/표시·복구 요구는 이 예외로 면제되지 않는다.
>
> **이슈별 권위 보존:** §8.0/§8.1의 `Origin`, 생성 전 `pending` Intent, Manifest 증거, 실제 조회 일치, 기존 Legacy Snapshot, `Phase`, `ReadAuthority`, `WriteAuthority`, `CutoverApprovalRef` 조건은 보존한다. 기존 Legacy 이슈는 별도 Owner의 이슈별 CutoverApprovalRef 이전 `verified/legacy/frozen`이며, Target Create는 검증된 원장 `create_ready/target/target`일 때만 정상 권위다. UNKNOWN/미등록은 추측·무단 fallback·dual-write·집계 포함 금지.
>
> **복구:** 승인된 실제 Consumer가 있다면 그 Consumer의 분류값으로 복구를 입증한다. 영향받는 Consumer가 없다면 사전에 기록한 원장 Revision·Legacy Snapshot·Native 설정의 이전/이후 조회·변경 전후 대조와 조건부 복구 절차를 준비하고, `CONSUMER_RESTORE_NOT_APPLICABLE` 사유를 Owner가 별도 승인한다. 실제 운영 복구 Drill이 없었다면 `NOT_RUN`으로 기록하며 `PASS`로 표시하지 않는다. 복구 시 Jira 원본 값·기존 이슈 ID를 삭제하거나 중복 생성하지 않는다.
>
> **전환:** Owner가 특정 이슈와 활성 범위·Writer·시간창·검증/복구 계획을 승인한 뒤에만 단일 Writer가 `Phase=cutover / ReadAuthority=target / WriteAuthority=target`을 원장에 기록하고 실제 조회·보존 Evidence를 남긴다. 원장 변경이 Jira Native 필드, Board, Filter 설정 변경을 뜻하지 않는다는 점을 명시한다. 본 정책 변경 승인과 특정 RPL-88 Cutover 실행 승인은 별개다.
>
> **G4:** Owner가 승인하지 않은 Legacy 필드/값/이력의 Hide·Delete 금지. 다른 사용자 비공개 Filter를 관찰할 수 없는 경우 `ACCESS_UNKNOWN` 경계를 보존하고 감사 범위 한정 PASS로만 보고한다.

## 3. 수용 기준 / FAIL-CLOSED

| 항목 | Gate |
|---|---|
| 화면에 Target 노출 | `TARGET_EXPOSURE`로 명시, legacy-authority 이전 이슈가 Target 노출로 인해 잘못 이해될 수 있는 잔여 위험 Owner 수용 필요 |
| RPL Board #2 Filter 10001 | 최신 Revision/JQL/공유/Owner 증거 조회. Rank-only이므로 분류 Consumer 검증은 승인된 범위에서 N/A 가능 |
| RPL Automation | 기한/상태 전용 Rule JQL 증거 보존. 분류 변경 없음 |
| 전역/비공개 Filter | 접근 확인 못 한 Scope = `ACCESS_UNKNOWN`, 전체 PASS 금지 |
| RPL-88 Before | Legacy·Target 6필드, Changelog 10934, 원본 Snapshot/Hash, 원장 `verified/legacy/frozen` |
| 실제 Cutover | Owner 별도 승인 Ref, 정확한 Pilot Issue, Sole Writer, 승인 시간창, pre/post 검사, 실패 시 restore/STOP |
| Cutover 후 | 승인된 원장·Jira GET/JQL 비교, 격리 대상 오염 없음, 실제 영향 Consumer 결과 확인 |
| 승인 없는 경우 | Jira 이슈·설정/권위·원장 최종 값 변경 0; 각 Decision은 `NOT_APPROVED` |

## 4. RPL-88 적용 경로 (미실행)

1. Owner가 A 또는 B 운영 모델을 선택한다. `A`라면 이 초안의 Scope 예외를 Foundation 정본 PR/고정 HEAD 독립 리뷰로 검증하고 별도 Owner 승인·Merge·발효 이후에만 적용한다.
2. Jira Migration 대표 Primary Jira와 RPL-88 Pilot의 **Sole Writer** 및 인증된 계정, Writer exclusive window를 명시한다. RPL-88의 실제 담당자와 Pilot 번호가 대표 Migration Primary를 자동 확정하지 않는다.
3. 미확인 비공개 Filter·외부 소비자는 접근 범위를 추가 확인하거나 UNKNOWN으로 보존하고 한정 Cutover Scope를 승인한다.
4. 기존 Legacy Snapshot·GET/JQL·Changelog 재사용; RPL-88 전용 **CutoverApprovalRef**와 적용 시간창·복구 조건을 별도 승인받는다.
5. 승인 후 한 이슈만 `legacy→target` 권위 전환·재조회·원장 Revision 증거를 남기고 STOP 조건 발생 시 승인된 복구. G4 삭제는 별도 Gate.

## 5. 독립 검수 질의

- 화면 `TARGET_EXPOSURE`가 사실상 공식 분류 소비자로 활용되는가? 사용자가 legacy 권위를 침해하지 않는지 어떻게 알 수 있는가?
- `NO_CLASSIFICATION_CONSUMER` 예외를 Owner가 승인한 정확한 범위의 Reader/Restore 조건만 제외하도록 썼는가?
- Native-only 운영으로 이슈별 Origin 격리·권위 강제를 '지원한다'고 오해하게 만들지는 않았는가?
- Writer 소유·정본 승인·범위 N/A·RPL-88 실제 Cutover 승인과 G4 Hide/Delete가 서로 다른 Gate로 보존되는가?
- rollback은 데이터 원본과 Jira Create/FieldConfiguration에 실제 영향을 주는가? 영향이 없다면 하지 않은 시험을 PASS라 하지 않는가?

**현재 상태:** `CHANGES_PROPOSED_ONLY`. Accepted policy/source PR/merge/approval/cutover 모두 `NOT_RUN`. 실제 사용 경로가 정해지기 전에는 기존 `authority_reader.py` MAJOR-01/02를 닫지 않는다.
