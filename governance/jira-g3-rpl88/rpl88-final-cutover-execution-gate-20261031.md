# Jira Migration G3/G4 — RPL-88 최종 Cutover 실행 Gate

**2026-10-31 KST · Owner Decision Packet · PREPARED / NOT_APPROVED / NO_CUTOVER**
**Scope:** Jira Migration Workstream에 한정한 기존 이슈 1건의 분류 권위 전환 검증. 이 문서 자체는 CutoverApprovalRef, Jira Write 또는 Source Merge 승인이 아니다.

## 1. 이번 F4에서 재사용하는 DONE

- Foundation PR #53 일반 Merge Commit `cfc3ac80be777b2783bd60c249f745f77d7cb8b8`; `docs/governance/jira-field-taxonomy.md` §8.2가 현재 main에 존재한다. PR #53 전담 Task RPL-133은 `완료`.
- G0~G2 승인·기존 118건, RPL-125 보정, RPL-128~132 신규 Target/Origin Canary 5/5, 확인 가능한 RPL Board #2 / Filter 10001 및 기한 초과 Automation의 분류 미사용 증거를 **다시 실행하지 않는다**.
- 2026-10-31 Work 보고: 비공개 G2 ZIP 무결성, RPL-88 Snapshot/Manifest 파일·기준·Legacy Hash, 현장 분류 Snapshot 정합성 및 분류 전용 복구 준비 PASS. 이 보고는 별도 Work 실행 증거이고 **본 GitHub 작성 세션이 ZIP을 재실행했다는 뜻이 아니다**.
- Jira live GET + Changelog `10934` 대조 (현재 실행 세션 직접 확인): Legacy Product `[carelog]`; Legacy Component `[documentation]`; **Legacy Labels `[adr, architecture, contract]`**; Target Product `carelog` / option `10020`; Target Component `documentation` / option `10031`; Target Concern `[architecture, contract]` / option `[10044,10045]`. Jira 기본 `labels`는 Legacy Labels 분류 필드가 아니다.
- `ReadAuthority=legacy`, `WriteAuthority=frozen`, `Phase=verified`, `CutoverApprovalRef=null` 유지. 전체 이슈 ADF 복원은 해당 분류 Snapshot의 보장 범위 밖.

## 2. 최종 제품·정책 결정 중 확정된 것과 미확정된 것

**확정:** Jira Native 사용자 화면을 우선한다. 외부 Origin/Authority Git 원장으로 Jira Native/JQL이 자동 Fail-closed 되지는 않는다. 별도 실사용 소비처가 발견되지 않은 범위에서는 새로운 Reader 서버를 만들지 않는다. 확인된 Board #2 / Filter 10001 JQL `project = RPL ORDER BY cf[10019] ASC`는 분류 필드를 쓰지 않는다. 확인된 활성 기한 초과 Automation도 분류 필드를 쓰지 않는다. 이들은 **증거 범위 한정** `NO_CLASSIFICATION_CONSUMER / NO_CHANGE_PASS`.

**아직 Owner의 정확한 별도 승인 Ref가 필요:**
1. 위 정확한 Board #2 / Filter 10001 / 확인된 기한 초과 Automation 각각의 소비처 전용 Manifest-aware Reader 연결 및 소비처 전용 Drill만 `NOT_APPLICABLE_BY_APPROVED_SCOPE`으로 처리하는 범위 한정 예외. 비공개 Filter·외부 소비처 `ACCESS_UNKNOWN`과 Feature/Task `TARGET_EXPOSURE`는 면제하지 않는다.
2. 기존 `RPL-88`의 **분류 Pilot 1건 전용** 최종 `CutoverApprovalRef`, 실행 시작·종료 기간, 승인된 복구·중단 조건.
3. Jira Migration **실제 실행 Primary Jira / Sole Writer Session / 승인된 Jira 인증 계정**, 그리고 Git 원장의 **정본 파일 경로·Revision·Writer 권한**. 현재 `RPL-88` 티켓 자체는 Carelog Architecture 작업이며 전체 Jira Migration 관리 Jira로 사용하거나 의미를 바꾸지 않는다. `RPL-133`은 PR #53 전용으로 DONE이므로 재오픈하지 않는다. 기존 Work 지정 의사는 있으나 다른 세션의 독점 잠금 상태는 UNKNOWN.

## 3. 실제 Cutover 실행 전 필수 기술 Gate (실패 시 STOP)

1. 승인된 **단일 정본 Manifest의 RPL-88 기존 원장 위치와 현재 Revision**이 정확히 식별되고, 해당 Write가 다른 Writer에 의해 변경되지 않았음을 확인한다. `governance/jira-g3-rpl88/readiness.json`은 **감사 Projection**으로서 정본/운영 Reader의 입력이라고 단정하지 않는다.
2. Owner 승인된 Cutover의 Before 증거: Jira GET(version / updated), 6개 분류 필드, Changelog `10934`, Legacy 단일 원본 Snapshot/Hash, 원장 Before Hash, 실제 조회 소비처와 허용 계정/권한.
3. Legacy Labels `adr`은 Target Concern에서 역산할 수 없다. **원본 Legacy 분류 필드를 그대로 보존**하고, 유효 분류 전환/복구 시 Legacy Hash 검증과 Target Drift 검증을 수행한다. 전체 Jira Issue/ADF 복구 PASS 주장 금지.
4. 실제 분류 소비처가 식별되지 않으면 합성 Reader·Rollback fixture를 운영 E2E/Drill PASS로 승격하지 않는다. §8.2 소비처별 승인 예외와 **이슈별 원장 Before/After·조건부 복구 증거**를 구분한다. 분류 소비처가 실제 존재한다면 그 경로의 실제 분류 조회와 복구 검증이 선행되어야 한다.
5. `authority_reader.py`는 배포되지 않은 오프라인 검증기다. 기존 MAJOR-01(legacy ReadAuthority인데 Target classification 반환), MAJOR-02(라벨 토글만 하는 rollback)는 **실사용 Consumer로 채택하는 경우 Merge/Deploy Blocker**. 실제 채택 전 임의 서버 구현 금지.
6. 요청·실패·연결 중단 시 원장 Revision 비교 후 **RPL-88만** 승인된 Before 상태로 복원하고 현 Jira 값·분류 권위 확인. Immutable G2 ZIP, 다른 이슈·기존 Changelog, Jira Legacy 분류는 삭제/변경 금지.

## 4. 전환 수행 계획 (모든 Gate 충족 + 최종 승인 후에만)

- Before: 승인 기간 내 Writer 1명만 RPL-88 정본 원장 Revision 잠금/조건부 검증. 현재 Jira GET/Hash와 ReadAuthority `legacy`·WriteAuthority `frozen` 재확인.
- Write: 승인된 **정본 원장 RPL-88 한 레코드만** `Phase=cutover`, `ReadAuthority=target`, `WriteAuthority=target` 및 **실제 Owner 발급** `CutoverApprovalRef`로 변경. Jira Legacy 필드·Target 저장값·설명·기본 `labels`는 변경하지 않는다.
- After: 변경된 원장 Revision을 재조회하고, Jira 6필드 불변/Changelog 불변/Target Option IDs 대조; 승인 소비처에서 필요한 실제 조회 검증. 승인 범위에 없는 소비처는 `NOT_APPLICABLE`이라고 쓸 수 없다.
- Fail: 승인된 Before Revision과 원본 Legacy Hash를 기준으로 중단/복구, 불일치·Writer 경합 시 fail closed. 실제 복구 테스트가 수행되지 않았으면 `NOT_RUN` 유지.
- Fan-in: Writer의 실제 Commit/GET 결과와 별도 READ-ONLY Reviewer의 고정 Revision 판정을 분리하고 Owner에 보고. 다른 활성 31건의 일괄 전환, 완료/폐기 이슈 변경 금지.

## 5. 별개 트랙 — Origin 예외와 G4

- `RPL-83/94/115/116/117/118`: 2026-10-23 사후 Target 보정 Changelog 실증; `target_create`로 승격 금지, 역사적 Legacy-less 보정 예외는 Owner 제품 결정.
- `RPL-122/124/126/127`: Target 존재하지만 생성 전 승인 Intent 미확인. `UNKNOWN_ORIGIN`으로 분류 권위 승격 금지, 실제 원본 근거가 없으면 UNKNOWN 유지.
- 선택형 Story의 Target 누락은 필수 누락 버그로 처리하지 않는다. `TARGET_EXPOSURE`는 유지하며 사용자가 저장된 Target을 공식 권위로 오해할 위험을 기록한다.
- G4는 식별된 소비처별 `NO_CHANGE_PASS`, 접근 권한 밖 소비처는 `ACCESS_UNKNOWN`. Legacy Hide/Delete는 별도 승인 없으며 이번 목표의 필수 구현에 포함하지 않는다.

## 6. F4 종료 Gate / 현재 Verdict

- **DONE:** Source PR #53, RPL-133, G0~G2, Canary 5/5, RPL-88 분류 Snapshot 정합성/복구 준비 (Work 보고 기준), 확인된 비분류 소비처 검증.
- **READY:** 정확한 정본 Manifest 위치·Revision 및 실제 Sole Writer 런타임 확인, 승인할 좁은 소비처 예외의 대상 식별·Decision Ref 준비.
- **WAITING_OWNER:** 소비처별 `NOT_APPLICABLE_BY_APPROVED_SCOPE` 한정 승인, RPL-88 전용 최종 `CutoverApprovalRef`/기간/복구 기준.
- **NOT_RUN:** 승인된 실제 Manifest Cutover/운영 소비처 E2E/Controlled Restore Drill, Cloud Work/Codex Agent 실행 실증.
- **BLOCKED:** 임의 authority write, 미확인 필터 전체 PASS, UNKNOWN_ORIGIN 소급 승격, Legacy Hide/Delete/Deploy.

**즉시 다음 행동:** 새로운 정책 PR·코드·Task 생성 대신, 기존 Jira Migration Work에서 정본 Manifest의 실제 경로와 Sole Writer를 확정하고 이 문서의 정확한 최종 Owner Gate만 한 번 제출한다. Reviewer는 원장 경계/복구 절차 고정 Revision에 대해 별도 검증; 승인이 나오면 RPL-88 한 건만 실제 실행.
