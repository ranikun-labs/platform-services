# RPL-55 — G4 비운영 E2E 기준선 및 테스트 선택 규칙

- 검증일: 2026-10-23
- 대상: Shared Gateway(WebFlux) → Platform Core Identity(Spring MVC) → Carelog Backend(Spring MVC)
- Owner: RPL-55 (실제 Carelog G4 Cutover); 코드는 `ranikun-labs/platform-services`, 제품 이슈 상태는 Jira
- 문서 성격: **실행 증거 + 후속 테스트 선택 지침**. RPL-55 완료 승인이나 Production 배포 승인이 아님
- 현재 파일은 **Draft [Platform PR #35](https://github.com/ranikun-labs/platform-services/pull/35)**에 있으며 원본 제품 PR·기본 브랜치에는 아직 병합되지 않음

## 1. 마지막으로 확인된 전체 기준선

| 항목 | 재현 증거 |
| --- | --- |
| 실제 검증한 소스 | Platform G4 합성 HEAD `ac5c6e8255a0274ddd227f3a06759cf909d6e7b3` |
| 원본 조합 | Platform PR #30/#33/#32의 비배포 합성 + Carelog PR #52/#53의 고정 비배포 합성 `e730e34db9b245ca4aafabe37b5ca71f459b70b8` |
| CI | [Run #39918713612](https://github.com/ranikun-labs/platform-services/actions/runs/39918713612), `completed/success`, 2026-10-23 |
| 실제 검사기 기록 | `G4_THREE_PROCESS_NONPROD_PARTIAL_PASS`, **16 PASS / 0 FAIL** |
| 소요 시간 | Actions Run `07:34:54Z → 07:43:37Z`, **약 8분 43초** (설치/빌드/테스트/정리 포함) |
| 환경 | 격리 Docker, 실제 Spring 3개 애플리케이션, 별도 일회성 PostgreSQL, Valkey, 가짜 Kakao, 합성 인증·고객 데이터 |
| 실행 코드 | `scripts/g4/run_three_services.sh`, `scripts/g4/verify_three_services.py`, `docker-compose.g4-e2e.yml` |
| 자세한 결과·Gate | [Jira RPL-55](https://ranikunlabs.atlassian.net/browse/RPL-55) · [PR #35](https://github.com/ranikun-labs/platform-services/pull/35) · [Confluence Shared Identity](https://ranikunlabs.atlassian.net/wiki/spaces/RLAB/pages/491538) |

**검증한 기능:** 일반 ID/PW 로그인 → Core Identity의 실제 Carelog Product Claims 조회 → Gateway → Carelog 고객 조회; 누락/형식 오류 JWT; 잘못된 조직 UUID·누락 Public ID Fail-closed; 내부 Projection 3필드·Service Secret 거부·없는 Account; 다른 조직 고객의 생성/소유 확인→A의 조회 404; B 고객이 실제 존재한 상태의 외부 신뢰 헤더 위조 방어; Public Internal API 차단.

**아직 미검증:** 실카카오 OAuth 실계정, B 계정의 실제 Identity 발급 세션(격리 시나리오 B는 합성 JWT), Product까지 관통하는 Refresh/Logout, Upstream 호출 0 보장·Projection 5xx/timeout, 매니저 가입·Orphan 복구, 실제 데이터 이관·Freeze·Cutover·Rollback/운영 보안 설정. **E2E 16 PASS ≠ RPL-55 DoD PASS ≠ Source Merge ≠ Production Go-Live.**

## 2. 다음 기능 개발 때 기본 선택

**원칙: 변경한 기능의 테스트만 실행. 전체 3서버 E2E는 기본 자동 실행하지 않는다.**

| 변경 범위 | 우선 실행할 Targeted Test | 전체 E2E |
| --- | --- | --- |
| Gateway JWT·허용 경로·헤더 | `./gradlew -p gateway-app test --tests 'com.ranikunlabs.platform.gateway.filter.JwtGlobalFilterCharacterizationTest' --tests 'com.ranikunlabs.platform.gateway.route.GatewayRoutingHttpIntegrationTest' --no-daemon` | 인증·신뢰 경계/라우팅 계약 변경이면 통합 후보 승인 시 실행 |
| Platform Identity 로그인·JWT | `./gradlew -p platform-core :identity:test --tests 'com.ranikunlabs.platform.identity.AuthServiceImplTest' --tests 'com.ranikunlabs.platform.identity.JwtTokenProviderTest' --no-daemon` | 토큰 Claim·인증 계약 변경이면 실행 |
| 카카오 OAuth | `./gradlew -p platform-core :identity:test --tests 'com.ranikunlabs.platform.identity.KakaoOAuthAdapterTest' --tests 'com.ranikunlabs.platform.identity.OAuthApplicationServiceTest' --no-daemon` | 실제 Gateway/OAuth 경계 변경이면 가짜 Provider 결합 검증 추가 |
| Carelog Customer·조직 격리 | Carelog 저장소에서 `./gradlew :carelog-be:test --tests 'carelog.carelog.customer.web.CustomerControllerTest' --tests 'carelog.carelog.customer.app.CustomerServiceImplTest' --no-daemon` | Product Claims/Principal의 계약이 변하지 않으면 생략 |
| G4 비운영 검사 도구만 변경 | `python3 -m py_compile scripts/g4/verify_three_services.py && bash -n scripts/g4/run_three_services.sh`; Carelog `python -m unittest discover -s scripts/tests -p 'test_rpl55_g4_nonprod_smoke.py'` | 검사 추가/수정이 진짜 서버 동작을 검증해야 할 때만 실행 |
| Finance Journal Owner·멱등성 | Finance BE에서 `./gradlew test --tests 'labs.ranikun.finance.journal.web.JournalIdempotencyOwnerIntegrationTests' --no-daemon` | G4와 무관하면 G4 전체 실행 금지 |
| README·Wiki·Jira 링크 등 문서만 수정 | 링크/정적 체크 | 원칙적으로 실행하지 않음 |

위 명령은 **기존 테스트 파일·태스크 경로를 확인한 선택 예시**다. 앞으로 수정한 기능에 알맞은 실제 테스트가 없는 경우에는 테스트를 추가해야 하며, 단순 문법 검사 PASS를 해당 기능의 Runtime PASS로 승격하면 안 된다.

## 3. Full E2E를 다시 실행해야 하는 경우

- Gateway↔Identity↔Carelog의 **JWT Claim, Trust Header, 내부 Service Auth, OAuth 콜백, 조직 격리, HTTP 계약·라우팅·스키마**가 변경됐을 때
- 두 개 이상의 서비스 변경을 **같은 통합 후보로 처음 결합**할 때
- G4 병합·Cutover·운영 배포/롤백 승인 전 필수 Gate를 준비할 때
- 여러 독립 기능 변경이 누적돼 최근 E2E 기준선에서 상당한 차이가 생겼을 때 (권장 2~4주 간격으로 변경이 있는 경우에만 점검; 자동 스케줄 아님)
- 인증 사고, 보안 회귀, 장애 재현 등 해당 경계 전체 검증이 필요한 때

**일반 개발 루프:** Feature Change → 관련 Unit/Component/Integration 테스트 PASS → 필요한 Review → 병합 후보 Fan-in에서 범위 검토 → 위 조건일 때만 Full E2E → 증거 등록. 테스트하지 않은 기능을 과거 E2E PASS에 포함시키지 않는다.

## 4. 비용 절약을 위한 CI 실행 계약

현재 [G4 PR #35](https://github.com/ranikun-labs/platform-services/pull/35)의 분리된 테스트 브랜치에서는:

- **매 Push:** `targeted-check`만 자동 실행 (Python/Bash 구문·Seed/증거 파일 확인). 이는 **검사기 정적 체크**이지 제품 Unit/Integration Test PASS가 아님.
- **전체 3서버 G4 E2E:** GH Actions `workflow_dispatch`로 명시 실행 (**워크플로를 기본 브랜치에 등록한 후**). 아직 Audit 브랜치에만 있다면, 승인된 검증 커밋의 메시지에 **`[full-g4-e2e]`**를 명시하여 해당 커밋에서만 실행하도록 한다. 실행하지 않으면 최종 E2E는 `NOT_RUN`이다.
- 실제 Carelog/Platform의 기능별 소스 변경에는 위 2절의 Unit/Component/Integration 테스트를 별도로 실행한다. `targeted-check` PASS를 보안 회귀 PASS라고 주장하지 않는다.
- 풀 테스트를 다시 실행하면 **테스트 대상 전체 SHA, Carelog 외부 SHA, Run ID, 시나리오 PASS/FAIL, 비운영/Production 경계, 미검증 목록**을 Jira RPL-55·PR·Confluence/Git Wiki에 반영한다. 문서만 바뀐 HEAD가 이전 코드 기준선을 소급 무효화하지는 않지만, **실행한 코드 SHA는 항상 명시**한다.

## 5. 데이터 및 Owner Gate

- 원본 제품 PR #30/#33/#32/#52/#53 및 운영 Secret/DB/권한/배포는 자동 수정하지 않는다
- 합성 비운영 자격 증명과 테스트 DB만 허용; 실카카오·실 사용자/운영 계정 금지
- G4의 Source Merge, 실제 Cutover/Go-Live, Rollback은 별도 Owner 승인
- Notion은 정본으로 사용하지 않는다. **Jira(상태·Gate) + Confluence(사람용 최신 기능 Wiki) + Git LLM Wiki(기술 Evidence/Contract) + GitHub CI(실행 원본)**로 구분한다
