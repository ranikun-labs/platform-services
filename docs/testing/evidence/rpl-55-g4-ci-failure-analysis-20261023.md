# RPL-55 — CI 실패 원인·해결·재발 방지 기록

> 2026-10-23 검증 / Owner: RPL-55 / 범위: **비운영 격리 GitHub Actions**, Production 사고 보고서 아님  
> 검증 데이터: Actions 실행 로그·고정 Git Commit·Compose/Dockerfile. 이 문서는 실패 이력의 원인과 교정 근거를 보존한다.  
> **현재 기준선:** [G4 16/16 PASS, Run #39918713612](https://github.com/ranikun-labs/platform-services/actions/runs/39918713612) @ `ac5c6e8255a0274ddd227f3a06759cf909d6e7b3`. 원본 PR 병합·운영 전환 완료를 의미하지 않는다.

## 1. 기능/아키텍처 흐름과 어느 단계에서 실패했나?

```text
GitHub Actions Runner
  ├─ [A. Build] Docker BuildKit → Image 3개 생성
  ├─ [B. Dependencies] PostgreSQL(Identity/Carelog 각자), Valkey, Fake Kakao
  ├─ [C. Startup] Gateway(WebFlux), Platform Core Identity(MVC), Carelog(MVC)
  │      └─ Docker healthcheck → healthy 판단 이후에만 통합 테스트 진행
  └─ [D. Product E2E] ID/PW login → Product Claims → JWT → Customer API
                              → 테넌트 격리/신뢰 헤더 거부
```

이번 FAIL은 **A(BuildKit) 또는 C(Healthcheck)** 단계에 발생했다. D(Product HTTP assertion)에 진입하지 않은 실행을 제품 테스트 FAIL로 분류하면 안 된다. 반대로 **TCP 접속 성공만으로 제품 Ready를 주장해서도 안 된다**.

## 2. 장애/교정 이력 (정확한 실행 결과)

| 실행 | 실패/결과 | 직접 증거 | 확정된 원인 / 후속 교정 |
| --- | --- | --- | --- |
| [Platform #39909684823](https://github.com/ranikun-labs/platform-services/actions/runs/39909684823) @ `ab8829984` | **FAIL**, BuildKit 시작 단계 | 로그: `additional privileges requested: pass "--allow=fs.read=/dev/null"` | Compose `proxy_ca.file` 기본값이 `/dev/null`; GitHub Hosted BuildKit의 해당 접근 거부 |
| [Platform #39909837157](https://github.com/ranikun-labs/platform-services/actions/runs/39909837157) @ `47b1b7c79` | **FAIL**, 동일 단계 | 동일 BuildKit `fs.read=/dev/null` 거부 | 여전히 수정되지 않은 `/dev/null` 경로; 제품 애플리케이션 실행 전 실패 |
| [Platform #39910025905](https://github.com/ranikun-labs/platform-services/actions/runs/39910025905) @ `cd85693aa` | **PASS**, Gradle + Docker Gateway↔Identity | 2/2 Job Success, E2E 120/120 | **격리 Workflow**에서 `CODEX_PROXY_CERT=./docs/testing/evidence/.gitkeep` 설정하여 BuildKit에 안전한 Workspace 파일 경로 전달. 전역 Dockerfile/운영 Secret 변경 없음 |
| [G4 #39916789876](https://github.com/ranikun-labs/platform-services/actions/runs/39916789876) @ `2fbbb2801` | **FAIL**, Carelog Docker unhealthy | 로그: Carelog Flyway V1~V8 적용, `Tomcat started on port 8080`, `Started CarelogApplication`, 이후 `carelog-be ... (unhealthy)` | **실제 관리 Listener 및 검사 경로 불일치**. Carelog Dockerfile은 `http://127.0.0.1:8081/actuator/health/readiness`를 검사하지만, 해당 비운영 Profile에 별도 Management Listener가 준비되지 않았음 |
| [G4 #39917815264](https://github.com/ranikun-labs/platform-services/actions/runs/39917815264) @ `4f6f85e1d` | **PASS**, 실 3서비스 기본 회귀 13/13 | G4 `PARTIAL_PASS` JSON | 격리 `docker-compose.g4-e2e.yml`의 **Carelog healthcheck만** 실제 App Listener `8080` TCP 검사로 재정의. 제품 Dockerfile·운영 Profile은 미변경 |
| [G4 #39918713612](https://github.com/ranikun-labs/platform-services/actions/runs/39918713612) @ `ac5c6e825` | **PASS**, 강화된 실 3서비스 회귀 **16/16** | `G4_THREE_PROCESS_NONPROD_PARTIAL_PASS`, failed 0 | **해결 확인**: 기존 검사 + 실제 B Customer 존재 시 헤더 위조 방어, 잘못된 Claim 거부 시나리오 추가 성공 |
| [선택 CI #39923556931](https://github.com/ranikun-labs/platform-services/actions/runs/39923556931) @ `f2cc25f65` | **SUCCESS** (경량 검사), Full E2E **SKIPPED** | `targeted-check=success`, `g4-real-process=skipped` | 전체 E2E 반복 비용 최소화. **이 실행은 16/16 신규 E2E가 아님** |

## 3. Healthcheck 역할과 환경 계약

| 구분 | 현재 동작·의미 | 안전한 판정 |
| --- | --- | --- |
| Carelog 제품 앱 리스너 | 비운영 테스트에서 HTTP `8080`, context path `/api/v1` | 프로세스 HTTP 기동 여부 확인 |
| Dockerfile 기본 readiness 검사 | `8081/actuator/health/readiness` | **해당 관리 리스너와 Actuator가 활성화된 실행 Profile에서만** 유효 |
| G4 비운영 Compose Override | `bash -c 'echo > /dev/tcp/127.0.0.1/8080'` | **TCP liveness 수준**; 앱 의존성·인증·DB readiness 보장 불가 |
| G4 실제 기능 검증 | 별도 Python Runner에서 실제 로그인 및 Customer API·권한·헤더 assertions | 해당 고정 소스·합성 환경에서 16/16 PASS 근거 |
| Production 준비성 | Management 포트·Actuator readiness, DB/Valkey, 내부 Claims 의존성, Gateway Routing 설정 | **운영 Profile에서 별도 검증 필수**. 이번 임시 Override를 운영에 복사하지 않음 |

**불변식:** Docker Container `healthy` + 앱 포트 TCP OPEN ≠ Spring Actuator `readiness=UP` ≠ Gateway→Identity→Carelog E2E PASS ≠ Production Cutover 승인.

다음 운영 준비 검증에서는 `SERVER_PORT`, `CARELOG_MANAGEMENT_PORT`, `management.server.port`, Active Profile, Actuator readiness path와 각 컨테이너의 Network/Healthcheck 설정을 한 표로 대조해야 한다. 단지 포트를 `8080`으로 바꿔서 모든 환경에 적용하지 않는다.

## 4. 공통 CI 실패 분류·대응 계약

| 분류 | 판정 기준 | 다음 행동 |
| --- | --- | --- |
| `BUILD_ENV` | BuildKit 권한, Proxy CA 경로, Gradle 의존성 해석·Docker Image Build 실패 | 환경/Workflow/Docker Compose 수정 후 **실패한 단계만** 재검증 |
| `STARTUP_CONFIG` | App Boot 로그·Migration 성공, Healthcheck 또는 Profile/포트 불일치 | 앱 포트와 관리포트, Probe 경로, Liveness/Readiness 역할을 확인. 해당 환경 Overlay 수정 |
| `DEPENDENCY_UNAVAILABLE` | DB/Valkey/Claims API 불가, Timeout, 일시적 서비스 의존성 실패 | 원인별 Connectivity·Retry·Fail-closed 표적 테스트; 배포 시 readiness 별도 확인 |
| `PRODUCT_REGRESSION` | 앱/DB/네트워크 준비 완료 뒤 인증·권한·테넌트/HTTP Assertion 실패 | Owner/Principal/Token/Route 코드 Finding으로 분리·해당 테스트 수정 및 재검증 |
| `UNKNOWN` | 로그 누락/실패 이유 불분명 | 재현 전 PASS·제품 결함 주장 금지. 필요한 로그·환경 정보부터 수집 |

**변경별 테스트 선택:** 변경한 기능의 Unit/Component/Integration 테스트를 기본으로 실행한다. 서비스 간 JWT/Trust Header/Tenant 계약 변경·통합 Fan-in·출시/전환 Gate 때만 Full E2E를 다시 돌린다. [G4 기준선·테스트 선택 정책](./rpl-55-g4-e2e-baseline-and-test-selection-20261023.md) 참고.

**필수 Evidence:** Repository/Branch/검증 SHA, Run URL, Job/Step, 실행 Profile·Healthcheck 계약, 기대/관측 값, 원인 분류, 수정 파일·Commit, **후속 성공 Run** 및 아직 미검증된 환경·범위. 단순히 초기에 FAIL했다는 실행 목록만으로 현재 제품 결함이 남았다고 판단하지 않는다.

## 5. 다른 저장소 Actions 조회 범위 (2026-10-23)

접근 가능한 `ranikun-labs` **14개 저장소**의 GitHub Actions Run 목록을 확인했다.

- **총 15개 Run:** `platform-services` 7개(FAIL 3/성공 4), `carelog-be` 1개 성공, `finance-harness-be` 1개 성공, `oh-my-ai` 5개 성공, `shared-ai-fastapi-poc` 과거 1개 성공.
- **실패 3건은 모두 위 표의 Platform Build/Healthcheck 이력**이고, 후속 코드·CI로 대응됐다. 이를 운영 장애 3건으로 산정하지 않는다.
- `carelog-fe`, `finance-harness-fe`, `platform-infra`, `harness-foundation-docs`, `finance-harness-docs`, `react-product-foundation`, `finance-harness-architecture`, `carelog-architecture`, `shared-ai-architecture`는 **조회 시점 반환된 Actions Run 0건**. **미실행·자동화 미설정·다른 검증 경로 여부는 미확인**이지 테스트 PASS가 아니다.
- 로그의 `actions/checkout@v4` Node.js 20 호환 경고와 `actions/setup-java@v4` 버전 경고는 발견됐으나 **이번 실패의 직접 원인이 아니다**. 공통 Actions 버전 업그레이드는 기존 Writer·CI 정책의 별도 작업으로 관리한다.

## 6. 후속 Gate

- 본 Incident Note는 **테스트 인프라 Finding Closure 증거**이며 Production Healthcheck·Runtime Application 변경 승인 문서가 아니다.
- RPL-55 실제 Carelog Cutover, 운영 Actuator/DB·Role·Secret, Live Kakao 및 Rollback E2E, PR #30/#32/#33/#35/#52/#53 병합에는 별도 Owner Gate 유지.
- Git 기술 기준선·Confluence 최신 현황·Jira 이슈 기록은 상호 링크. Notion은 사용하지 않는다.
