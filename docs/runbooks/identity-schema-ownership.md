# Identity PostgreSQL Schema 소유권

Shared Identity는 단일 Application Database 안의 `identity` Schema와 그 Flyway
history를 소유한다. V1/V2 SQL과 checksum은 유지한다. Flyway는 명시된
`identity` Schema를 기본 대상으로 쓰며, 애플리케이션 시작 시 Schema를 만들지
않는다. 따라서 DB Schema provisioning은 애플리케이션 시작 전 별도 권한으로
완료되어야 한다.

Identity runtime datasource와 선택적 Flyway datasource를 분리할 수 있다. Flyway
URL/username/password가 모두 설정되면 migration은 해당 datasource를 사용하고,
셋 다 비어 있으면 local/dev/test에서는 기존처럼 Identity datasource를 사용한다.
`PLATFORM_ENVIRONMENT` 또는 `platform.metrics.environment`가 `prod`/`production`이거나
Spring `prod`/`production` profile이 활성화되면 세 값이 모두 필요하며, 누락 시 시작을
중단해 Identity runtime datasource로 fallback하지 않는다. 일부만 지정하면 환경과
관계없이 설정 오류로 시작을 중단한다. 이 연결점은 별도 자격증명을 지원할 뿐
Production role 이름, grant 또는 secret 전달 방식을 결정하지 않는다.

## Local Compose

새 PostgreSQL 데이터 볼륨은 `platform-core/identity/docker/10-identity-schema.sql`로
`identity`를 초기화한다. Docker 공식 Postgres entrypoint가 초기화하는 새 볼륨에만
적용된다. 기존 개발 볼륨은 이전 설정에서 Flyway가 이미 `identity`를 생성했으므로
재생성이 필요하지 않다. 시작 후 Flyway가 기존 이력을 검증하고 새 migration을
적용한다. 현재 V1/V2 migration 파일은 변경하지 않았다.

개발 DB 업그레이드는 데이터 삭제 없이 수행한다. `identity` Schema가 존재하는지,
`identity.flyway_schema_history`에 V1/V2가 기록되어 있는지 확인한 뒤 새 버전으로
시작한다. 누락되거나 다른 Schema에 위치한 history/table이 발견되면 자동 이동이나
baseline을 하지 말고 시작을 중단해 상태를 조사한다.

## Production Gate

ADR-0020은 Schema 및 Migration Ownership을 정했지만 실제 Production provisioning을
승인하지 않는다. ADR-0022 문서는 `in_review`이며 공통 PostgreSQL major version,
서비스별 role/grant와 migration/runtime 권한 분리, credential/secret 전달 방식,
운영 rollout/rollback 단계가
결정되지 않았다. 따라서 Production 실행은 `BLOCKED_OWNER_DECISION`이다.

Legacy Identity 보호 검사는 `pg_catalog`에서 `public`의 기존 Identity 객체명을
확인한다. `public.flyway_schema_history`가 발견됐지만 migration 연결이 읽지 못하면
migration을 중단한다. Production role 결정 시 이 fail-closed 검사가 유지되는 권한을
함께 확인해야 한다. Runtime role에는 `flyway_schema_history` 조회 권한을 부여하지 않는다.

결정 전에는 RDS, role, secret, production data, auth routing을 변경하지 않는다.
Production rollout sequence와 rollback command는 Owner가 version, role ownership,
credential rotation, preflight 및 recovery criteria를 확정한 뒤 작성해야 한다.

## Rollback

Local code rollback은 이전 Core image 또는 이전 commit으로 복귀한다. 이 변경은
기존 table/data를 이동하거나 삭제하지 않으며 V1/V2 history checksum을 바꾸지
않는다. Fresh Compose volume의 `identity` schema는 남겨 둔다. Schema drop이나
database reset은 rollback 절차에 포함하지 않는다.
