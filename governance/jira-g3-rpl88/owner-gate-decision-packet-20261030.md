# Jira Migration G3/G4 — Owner Gate Decision Packet
Status: PROPOSED / NOT_APPROVED / NO_CUTOVER
Date: 2026-10-30 KST
Scope owner: jira-migration workstream ONLY

## F1 결론
G1/G2 118건 PASS; G3 5유형 Admin Target Required 및 RPL-128 Feature Canary 범위 PASS; G4 확인된 Board/Automation 및 Legacy Create 미노출 PASS. 전체 Native G3/G4 및 RPL-88 Cutover Closure는 미완료.

이 문서는 승인 제안·실행 체크리스트로, CutoverApprovalRef를 발급하거나 Jira Write를 허가하지 않는다. 기존 PASS 증거를 재사용하고 동일한 증거 조회를 반복하지 않는다.

## 2026-10-30 실제 연결 측정
- 정책: harness-foundation-docs/docs/governance/jira-field-taxonomy.md, Git blob 0c3bc9d7a7b71ff072696c33d21e372d20d76b29, §8.0/§8.1
- RPL-128: Feature Target-only 생성 PASS (기존 독립 evidence).
- RPL-88: Legacy/Target 분류 일치, Changelog 10934, Phase=verified, ReadAuthority=legacy, WriteAuthority=frozen, CutoverApprovalRef=null.
- Jira Task(10010) Create Metadata: 필수 start date customfield_10015, due date, target Product customfield_10108, target Component customfield_10109; assignee customfield_10045 required (default present), reporter default present, summary required. Legacy Product/Component/Labels not present.
- Epic/Feature/Task/Bug/Research: Target Product·Component required; Story/Subtask: Target optional; Tech Debt: Target optional, Concern hidden.
- 확인된 Board/Automation에서는 Legacy 참조가 관찰되지 않았으나 모든 공유 Filter 확인은 미완.
- private source: ranikun-labs/platform-services branch audit/jira-g3-rpl88-pilot-20261030, governance/jira-g3-rpl88; offline reader synthetic 12/12 (not production); G2 originals separately retained in private Library.
- 지원 없는 Reader/실제 복구 Drill을 PASS로 주장하지 않는다.

## Owner 결정 제안 — 일괄 결재하되 각 결정은 별개
A. 관리 이슈: Jira RPL에 **Task 1건 생성** 승인 제안.
   제목: Jira Migration — G3/G4 Native Closure & RPL-88 Cutover Governance.
   Target Product platform-foundation (option 10026), Component infra (10030), Concern migration (10039).
   기간 **제안**: start 2026-10-30, due 2026-11-06 (일정은 Owner 확정 전 변경 가능). Jira 담당자 및 Reporter는 현재 승인된 관리자 계정만 사용. 기존 RPL-88/RPL-122/RPL-128을 관리 이슈로 오인하지 않는다.
   신규 Task 키가 실제 생성·GET 검증된 뒤에만 Primary Jira로 지정.
B. Sole Writer: 기존 jira-migration Work **단일 실행 주체**와 실제 Jira 인증 관리자 ID를 함께 명시. 다른 Workstream·브랜치 Writer는 수정 금지. 사용자 승인 전 개인 Writer/승인권을 추정하지 않는다.
C. 정본: 기존 private Git audit 브랜치를 검증 작업 경로로 사용하고 G2 검증 원본은 변경하지 않는다. Canonical Manifest는 별도 승인된 **정확한 비공개 단일 파일 경로와 Revision/Reader 입력 경로**로 지정하되 현재 audit/readiness.json을 자동 승격하지 않는다.
D. G3 남은 유형: Epic/Task/Bug/Research 신규 시험 Create는 각각 개별 대상·필드·실행 범위와 기간 승인 후 직렬 실행. 동일 목적 Issue 남발 금지; 실패하면 격리. 기존 Feature PASS 재실행 금지.
E. Story/Subtask/Tech Debt: 이번 단계에서 임의 Target-required·권위 활성화하지 말고 **예외/HOLD 제안**. Subtask는 상위 항목 필요; Tech Debt는 Concern 미노출. 영구 미지원 판정은 별도 Owner 결정.
F. G4: 계정 범위 밖 공유 Filter/보드·화면 의존성을 담당 Admin이 범위 명시 검증. 확인되지 않은 범위는 UNKNOWN 유지. Legacy 숨김/삭제/복구를 서로 다른 승인 사건으로 취급; 물리 삭제 금지.
G. RPL-88: 정확한 Sole Writer 및 단일 Manifest·Origin/Reader 경로 확인, 혼합 Origin fail-closed 실제 소비처 E2E, 비파괴적 복구 Drill, Before/After 영향 범위 검증을 승인된 기간에 완료. **이 증거를 Owner가 검토한 다음에만 별도 CutoverApprovalRef·적용 기간·복구 조건을 발급**. 지금은 null 유지.

## F4 실행 순서
1. Owner가 A~G 중 승인/수정/보류를 명시하면, 승인된 READY만 직렬/독립 읽기 병렬로 수행.
2. A 승인 시 정확한 필수 날짜·옵션으로 관리 Task Create 1건 → GET → Primary Jira 고정. 실패 시 재시도 Create 중복 방지.
3. B/C 승인 시 충돌 없는 One Writer, 원본과 별도의 정본 revision, Reader 입력 경로 확정.
4. D/E/F 승인 범위만 검증. Legacy 필드 값·원본 보존 및 공유 Filter 영향 보호.
5. G 준비 검증 완료 → **최종 Cutover 별도 승인 요청** → 승인 시 RPL-88 한 건만 Manifest authority 전환 → 실제 후속 Read·복구 가능성 검증.
6. 기타 후보 이슈는 임의 Cutover하지 않으며 기존 Done 76, RPL-40 폐기, UNKNOWN_ORIGIN 112/113/114는 격리.

## Acceptance/Stop
- PASS: 실제 변경 식별자·시각·승인 Ref·GET/JQL·Archive/Manifest Revision·Reader/Drill 증거.
- BLOCKED: 없는 Reader/권한/소유권/승인. synthetic/fixture는 production PASS 대체 불가.
- NO autonomous Create/Merge/Deploy/Cutover/Legacy deletion until corresponding explicit Owner approval.
